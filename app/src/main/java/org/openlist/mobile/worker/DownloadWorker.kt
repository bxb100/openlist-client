package org.openlist.mobile.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await as awaitOperation
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.openlist.mobile.OpenListApplication
import org.openlist.mobile.data.download.DownloadEngine
import org.openlist.mobile.data.download.DownloadFailure
import org.openlist.mobile.data.download.DownloadFailureCode
import org.openlist.mobile.data.download.DownloadGrantLifecycle
import org.openlist.mobile.data.download.DownloadProgress
import org.openlist.mobile.data.download.DownloadRequest
import org.openlist.mobile.data.download.DownloadRetryClassifier
import org.openlist.mobile.data.download.DownloadSessionChangedException
import org.openlist.mobile.data.download.DownloadSessionBinding
import org.openlist.mobile.data.download.DownloadTarget
import org.openlist.mobile.data.download.DownloadTargetException
import org.openlist.mobile.data.download.DownloadTargetWork
import org.openlist.mobile.data.download.DownloadUniqueEnqueueGate
import org.openlist.mobile.data.download.DownloadUniqueEnqueueResult
import org.openlist.mobile.data.download.DownloadWorkDisposition
import org.openlist.mobile.data.download.DownloadWorkInput
import org.openlist.mobile.data.download.DownloadWorkKeys
import org.openlist.mobile.data.download.DownloadWorkPolicy
import org.openlist.mobile.data.download.JobBoundDownloadSession
import org.openlist.mobile.data.download.SafDownloadTarget
import org.openlist.mobile.data.preferences.AppSettings
import org.openlist.mobile.media.MediaUrlResolutionException
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class DownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private var lastProgressBytes = -1L
    private var lastProgressAtMillis = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(progress = null)

    override suspend fun doWork(): Result {
        DownloadGrantLifecycle.initialize(applicationContext)
        val input = DownloadWorkInput.fromData(inputData)
            ?: return failure(
                DownloadFailure(
                    DownloadFailureCode.INVALID_RESPONSE,
                    retryable = false,
                    message = "下载任务参数无效",
                ),
            )
        val targetUri = input.targetUri.toUri()
        if (!targetUri.scheme.equals(ContentScheme, ignoreCase = true)) {
            return failure(
                DownloadFailure(
                    DownloadFailureCode.TARGET_UNAVAILABLE,
                    retryable = false,
                    message = "下载目标必须是 SAF content URI",
                ),
            )
        }
        DownloadGrantLifecycle.adoptLegacy(applicationContext, id, targetUri)
        DownloadGrantLifecycle.onWorkerStarted(applicationContext, id)

        return try {
            setForeground(createForegroundInfo(progress = null))
            val application = applicationContext as? OpenListApplication
                ?: return failure(invalidState("OpenListApplication 尚未初始化"), targetUri)
            val settings = application.container.sessionStore.awaitLoaded()
            val jobSession = try {
                JobBoundDownloadSession.capture(input.sessionBinding, settings)
            } catch (error: DownloadSessionChangedException) {
                return failure(
                    DownloadFailure(
                        DownloadFailureCode.SESSION_CHANGED,
                        retryable = false,
                        message = error.message ?: "登录会话已变化，请重新发起下载",
                    ),
                    targetUri,
                )
            }
            val fixedPassword = application.container.pathCredentials.passwordFor(
                settings.server,
                input.remotePath,
            )
            val requireSessionCurrent: () -> Unit = {
                if (!jobSession.matchesCurrent(application.container.sessionStore.snapshot())) {
                    throw DownloadSessionChangedException()
                }
            }
            requireSessionCurrent()
            val jobHttpClient = jobSession.newHttpClient(
                okHttpClient = application.container.httpClient.okHttpClient,
                gson = application.container.httpClient.gson,
                currentSnapshot = application.container.sessionAuthenticator::snapshot,
                refreshSession = application.container.sessionAuthenticator::refresh,
                isSessionCurrent = {
                    jobSession.matchesCurrent(application.container.sessionStore.snapshot())
                },
            )
            val resolver = jobSession.newResolver(
                okHttpClient = application.container.httpClient.okHttpClient,
                gson = application.container.httpClient.gson,
                remotePath = input.remotePath,
                pathPassword = fixedPassword,
                currentSnapshot = application.container.sessionAuthenticator::snapshot,
                refreshSession = application.container.sessionAuthenticator::refresh,
                isSessionCurrent = {
                    jobSession.matchesCurrent(application.container.sessionStore.snapshot())
                },
            )
            val safTarget = SafDownloadTarget(applicationContext.contentResolver, targetUri)
            val target = object : DownloadTarget {
                override fun openTruncated(): OutputStream {
                    val output = safTarget.openTruncated()
                    try {
                        DownloadGrantLifecycle.onTargetOpened(applicationContext, id)
                    } catch (error: Exception) {
                        runCatching(output::close)
                        runCatching(safTarget::clear)
                        throw DownloadTargetException("无法记录下载目标状态", error)
                    }
                    return output
                }

                override fun clear() = safTarget.clear()
            }
            val result = DownloadEngine(
                resolver = resolver,
                downloadClient = jobHttpClient.okHttpClient,
                requireSessionCurrent = requireSessionCurrent,
            ).download(
                request = DownloadRequest(input.remotePath, input.expectedBytes),
                target = target,
                onProgress = ::publishProgress,
            )
            requireSessionCurrent()
            DownloadGrantLifecycle.onWorkDisposition(
                context = applicationContext,
                workId = id,
                targetUri = targetUri,
                disposition = DownloadWorkDisposition.SUCCESS,
            )
            Result.success(
                workDataOf(
                    DownloadWorkKeys.REMOTE_PATH to input.remotePath,
                    DownloadWorkKeys.TARGET_URI to input.targetUri,
                    DownloadWorkKeys.DOWNLOADED_BYTES to result.downloadedBytes,
                    DownloadWorkKeys.TOTAL_BYTES to (result.totalBytes ?: -1L),
                ),
            )
        } catch (cancelled: CancellationException) {
            if (isCancelledByApp()) {
                withContext(NonCancellable) {
                    DownloadGrantLifecycle.onCancelledByApp(
                        context = applicationContext,
                        workId = id,
                        targetUri = targetUri,
                    )
                }
            }
            throw cancelled
        } catch (error: MediaUrlResolutionException) {
            failure(
                DownloadFailure(
                    DownloadFailureCode.INVALID_RESPONSE,
                    retryable = false,
                    message = error.message ?: "服务器未返回有效下载地址",
                ),
                targetUri,
            )
        } catch (error: DownloadSessionChangedException) {
            failure(
                DownloadFailure(
                    DownloadFailureCode.SESSION_CHANGED,
                    retryable = false,
                    message = error.message ?: "登录会话已变化，请重新发起下载",
                ),
                targetUri,
            )
        } catch (error: Exception) {
            val failure = DownloadRetryClassifier.classify(error)
            when (DownloadWorkPolicy.forFailure(failure)) {
                DownloadWorkDisposition.RETRY -> Result.retry()
                DownloadWorkDisposition.PERMANENT_FAILURE -> failure(failure, targetUri)
                DownloadWorkDisposition.SUCCESS,
                DownloadWorkDisposition.CANCELLED,
                -> error("Failure cannot map to a non-failure disposition")
            }
        } finally {
            DownloadGrantLifecycle.onWorkerFinished(applicationContext, id)
        }
    }

    private suspend fun publishProgress(progress: DownloadProgress) {
        val now = SystemClock.elapsedRealtime()
        val complete = progress.totalBytes != null && progress.downloadedBytes >= progress.totalBytes
        val enoughBytes = progress.downloadedBytes - lastProgressBytes >= PROGRESS_BYTE_STEP
        val enoughTime = now - lastProgressAtMillis >= PROGRESS_TIME_STEP_MILLIS
        if (lastProgressBytes >= 0 && !complete && !enoughBytes && !enoughTime) return

        lastProgressBytes = progress.downloadedBytes
        lastProgressAtMillis = now
        setForeground(createForegroundInfo(progress))
        setProgress(
            workDataOf(
                DownloadWorkKeys.DOWNLOADED_BYTES to progress.downloadedBytes,
                DownloadWorkKeys.TOTAL_BYTES to (progress.totalBytes ?: -1L),
            ),
        )
    }

    private fun createForegroundInfo(progress: DownloadProgress?): ForegroundInfo {
        ensureNotificationChannel()
        val total = progress?.totalBytes
            ?: inputData.getLong(DownloadWorkKeys.EXPECTED_BYTES, -1L).takeIf { it >= 0 }
        val downloaded = progress?.downloadedBytes ?: 0L
        val percent = if (total != null && total > 0) {
            ((downloaded.coerceIn(0L, total).toDouble() / total.toDouble()) * 100)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }
        val name = inputData.getString(DownloadWorkKeys.REMOTE_PATH)
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: "文件"
        val status = if (total != null && total > 0) "$name · $percent%" else "$name · 正在下载"
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在从 OpenList 下载")
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total == null || total <= 0)
            .addAction(android.R.drawable.ic_delete, "取消", cancelIntent)
            .build()
        val notificationId = (id.hashCode() and Int.MAX_VALUE).takeIf { it != 0 }
            ?: FALLBACK_NOTIFICATION_ID
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "文件下载",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示 OpenList 后台下载进度"
                setShowBadge(false)
            },
        )
    }

    private fun failure(failure: DownloadFailure, targetUri: Uri? = null): Result {
        DownloadGrantLifecycle.onWorkDisposition(
            context = applicationContext,
            workId = id,
            targetUri = targetUri,
            disposition = DownloadWorkPolicy.forFailure(failure),
        )
        return Result.failure(
            workDataOf(
                DownloadWorkKeys.FAILURE_CODE to failure.code.name,
                DownloadWorkKeys.ERROR to failure.message.take(MAX_ERROR_LENGTH),
            ),
        )
    }

    private fun invalidState(message: String) = DownloadFailure(
        DownloadFailureCode.INVALID_RESPONSE,
        retryable = false,
        message = message,
    )

    private fun isCancelledByApp(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    companion object {
        const val WORK_TAG = "openlist-download"
        private const val ContentScheme = "content"
        private const val NOTIFICATION_CHANNEL_ID = "openlist_downloads"
        private const val FALLBACK_NOTIFICATION_ID = 0x4f4d
        private const val MAX_ERROR_LENGTH = 2_000
        private const val PROGRESS_BYTE_STEP = 256L * 1024
        private const val PROGRESS_TIME_STEP_MILLIS = 500L
        private val uniqueEnqueueGate = DownloadUniqueEnqueueGate()

        /**
         * Builds a request while the ACTION_CREATE_DOCUMENT grant is still active. The grant is
         * acquired by [enqueueUnique], immediately before WorkManager owns the request. Only a
         * one-way session binding enters Work Data; token, password, and raw_url never do.
         */
        fun request(
            context: Context,
            remotePath: String,
            targetUri: Uri,
            settings: AppSettings,
            expectedBytes: Long? = null,
        ): OneTimeWorkRequest = request(
            context = context,
            remotePath = remotePath,
            targetUri = targetUri,
            sessionBinding = DownloadSessionBinding.create(
                settings.server,
                settings.sessionBindingKey.ifBlank { settings.token },
            ),
            expectedBytes = expectedBytes,
        )

        /** Preferred overload for a binding captured before launching the document picker. */
        fun request(
            context: Context,
            remotePath: String,
            targetUri: Uri,
            sessionBinding: DownloadSessionBinding,
            expectedBytes: Long? = null,
        ): OneTimeWorkRequest {
            require(targetUri.scheme.equals(ContentScheme, ignoreCase = true)) {
                "targetUri must be a SAF content URI"
            }
            val input = DownloadWorkInput(
                remotePath = remotePath,
                targetUri = targetUri.toString(),
                sessionBinding = sessionBinding,
                expectedBytes = expectedBytes,
            )
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(input.toData())
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .apply {
                    TransferWorkMetadata.tags(
                        binding = sessionBinding.value,
                        remotePath = remotePath,
                        createdAtMillis = System.currentTimeMillis(),
                    ).forEach { addTag(it) }
                }
                .build()
            return request
        }

        fun uniqueWorkName(targetUri: Uri): String =
            DownloadTargetWork.uniqueName(targetUri.toString())

        /** KEEP serializes all writes to one SAF document without exposing its URI as a work name. */
        internal suspend fun enqueueUnique(
            context: Context,
            targetUri: Uri,
            request: OneTimeWorkRequest,
        ): DownloadUniqueEnqueueResult = withContext(Dispatchers.IO) {
            DownloadGrantLifecycle.initialize(context)
            val appContext = context.applicationContext
            val workManager = WorkManager.getInstance(appContext)
            val workName = uniqueWorkName(targetUri)
            uniqueEnqueueGate.enqueue(
                hasActiveWork = {
                    workManager.getWorkInfosForUniqueWorkFlow(workName).first().any { info ->
                        !info.state.isFinished
                    }
                },
                acquireTargetGrant = {
                    DownloadGrantLifecycle.acquire(appContext, request.id, targetUri)
                },
                enqueueAndConfirm = {
                    workManager.enqueueUniqueWork(
                        workName,
                        ExistingWorkPolicy.KEEP,
                        request,
                    ).awaitOperation()
                    val accepted = workManager.getWorkInfoByIdFlow(request.id).first() != null
                    if (accepted) DownloadGrantLifecycle.onEnqueued(appContext, request.id)
                    accepted
                },
                reconcileUnacceptedGrant = {
                    DownloadGrantLifecycle.resolveAfterUnacceptedEnqueue(
                        appContext,
                        request.id,
                    )
                },
            )
        }

        /** Preferred cancellation entry point for callers that expose download task controls. */
        fun cancel(context: Context, workId: java.util.UUID): Operation =
            DownloadGrantLifecycle.cancel(context, workId)
    }
}
