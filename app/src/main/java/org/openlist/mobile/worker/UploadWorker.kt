package org.openlist.mobile.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
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
import org.openlist.mobile.data.api.OpenListApi
import org.openlist.mobile.data.logging.LogSanitizer
import org.openlist.mobile.data.upload.FileRandomAccessUploadSource
import org.openlist.mobile.data.upload.JobBoundUploadSession
import org.openlist.mobile.data.upload.JsonUploadCheckpointStore
import org.openlist.mobile.data.upload.OpenListMultipartUploadApi
import org.openlist.mobile.data.upload.ResumableUploader
import org.openlist.mobile.data.upload.UploadCommand
import org.openlist.mobile.data.upload.UploadFileHashes
import org.openlist.mobile.data.upload.UploadGrantLifecycle
import org.openlist.mobile.data.upload.UploadIdentity
import org.openlist.mobile.data.upload.UploadPermanentException
import org.openlist.mobile.data.upload.UploadProgress
import org.openlist.mobile.data.upload.UploadStagingStore
import org.openlist.mobile.data.upload.UploadSessionBinding
import org.openlist.mobile.data.upload.UploadSourceGrant
import org.openlist.mobile.data.upload.UploadTargetWork
import org.openlist.mobile.data.upload.UploadUniqueEnqueueGate
import org.openlist.mobile.data.upload.UploadUniqueEnqueueResult
import org.openlist.mobile.data.upload.UploadWorkCleanup
import org.openlist.mobile.data.upload.UploadWorkDisposition
import org.openlist.mobile.data.upload.UploadWorkPolicy
import org.openlist.mobile.data.upload.UPLOAD_CHECKPOINT_DIRECTORY
import org.openlist.mobile.data.upload.UPLOAD_STAGING_DIRECTORY
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

class UploadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo(progress = null)

    override suspend fun doWork(): Result {
        UploadGrantLifecycle.initialize(applicationContext)
        UploadGrantLifecycle.onWorkerStarted(applicationContext, id)
        val stagingKey = id.toString()
        val stagingStore = UploadStagingStore(
            File(applicationContext.filesDir, UPLOAD_STAGING_DIRECTORY),
        )
        val checkpointStore = JsonUploadCheckpointStore(
            File(applicationContext.filesDir, UPLOAD_CHECKPOINT_DIRECTORY),
        )
        val workCleanup = UploadWorkCleanup(stagingStore, checkpointStore)
        var checkpointKey: String? = null
        var sourceGrant: UploadSourceGrant? = null
        var sourceUri: Uri? = null

        return try {
            val uri = inputData.getString(KEY_SOURCE_URI)?.let(Uri::parse)
                ?: throw UploadPermanentException("缺少源文件 URI")
            sourceUri = uri
            if (uri.scheme.equals(CONTENT_SCHEME, ignoreCase = true)) {
                sourceGrant = UploadSourceGrant {
                    UploadGrantLifecycle.onSourceNoLongerNeeded(
                        context = applicationContext,
                        workId = id,
                        sourceUri = uri,
                    )
                }
            }
            val remotePath = inputData.getString(KEY_REMOTE_PATH)?.takeIf(String::isNotBlank)
                ?.let(UploadTargetWork::canonicalRemotePath)
                ?: throw UploadPermanentException("缺少远程文件路径")
            val expectedBaseUrl = inputData.getString(KEY_SERVER_BASE_URL)
                ?: throw UploadPermanentException("上传任务缺少服务器身份")
            val expectedUsername = inputData.getString(KEY_SERVER_USERNAME)
                ?: throw UploadPermanentException("上传任务缺少账号身份")
            if (!inputData.keyValueMap.containsKey(KEY_SERVER_ALLOW_INSECURE_HTTP)) {
                throw UploadPermanentException("上传任务缺少服务器安全配置")
            }
            val expectedAllowInsecureHttp = inputData.getBoolean(
                KEY_SERVER_ALLOW_INSECURE_HTTP,
                false,
            )
            val expectedSessionBinding = inputData.getString(KEY_SESSION_BINDING)
                ?.let { value ->
                    runCatching { UploadSessionBinding.parse(value) }.getOrNull()
                }
                ?: throw UploadPermanentException("上传任务缺少有效的登录会话绑定")

            setForeground(createForegroundInfo(progress = null))
            val application = applicationContext as? OpenListApplication
                ?: throw UploadPermanentException("OpenListApplication 尚未初始化")

            // StateFlow starts with an in-memory placeholder. Waiting for DataStore here is
            // essential after WorkManager cold-starts the process.
            val persisted = application.container.sessionStore.awaitLoaded()
            val jobSession = JobBoundUploadSession.capture(
                expectedBaseUrl = expectedBaseUrl,
                expectedUsername = expectedUsername,
                expectedAllowInsecureHttp = expectedAllowInsecureHttp,
                expectedSessionBinding = expectedSessionBinding,
                currentBaseUrl = persisted.server.baseUrl,
                currentUsername = persisted.server.username,
                currentAllowInsecureHttp = persisted.server.allowInsecureHttp,
                currentToken = persisted.token,
                currentSessionBindingKey = persisted.sessionBindingKey.ifBlank { persisted.token },
            )
            val jobHttpClient = jobSession.newHttpClient(
                okHttpClient = application.container.httpClient.okHttpClient,
                gson = application.container.httpClient.gson,
                currentSnapshot = application.container.sessionAuthenticator::snapshot,
                refreshSession = application.container.sessionAuthenticator::refresh,
                isSessionCurrent = {
                    val current = application.container.sessionStore.snapshot()
                    jobSession.matchesCurrent(
                        baseUrl = current.server.baseUrl,
                        username = current.server.username,
                        allowInsecureHttp = current.server.allowInsecureHttp,
                        token = current.token,
                        sessionBindingKey = current.sessionBindingKey.ifBlank { current.token },
                    )
                },
            )

            val configuredSize = inputData.getLong(KEY_FILE_SIZE, -1L).takeIf { it >= 0 }
            val restored = stagingStore.restore(stagingKey, configuredSize)
            val metadata = if (restored != null) {
                SourceMetadata(
                    expectedSize = restored.size,
                    modifiedAtMillis = inputData.getLong(KEY_LAST_MODIFIED, -1L)
                        .takeIf { it >= 0 },
                    mimeType = inputData.getString(KEY_MIME_TYPE)
                        ?.takeIf(String::isNotBlank)
                        ?: "application/octet-stream",
                )
            } else {
                resolveMetadata(uri)
            }
            val staged = restored ?: stagingStore.stage(stagingKey, metadata.expectedSize) {
                applicationContext.contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("无法打开所选文件：$uri")
            }
            sourceGrant?.onStagingSucceeded()
            val requestedSha256 = inputData.getString(KEY_SHA256)?.takeIf(String::isNotBlank)
            if (requestedSha256 != null &&
                !requestedSha256.equals(staged.sha256, ignoreCase = true)
            ) {
                throw UploadPermanentException("所选文件的 SHA-256 与上传任务不一致")
            }

            val sourceIdentity = staged.sourceIdentity
            checkpointKey = UploadIdentity.checkpoint(
                jobSession.serverScope,
                remotePath,
                sourceIdentity,
            )
            UploadGrantLifecycle.registerCheckpoint(
                context = applicationContext,
                workId = id,
                checkpointKey = checkpointKey,
            )
            val command = UploadCommand(
                checkpointKey = checkpointKey,
                sourceIdentity = sourceIdentity,
                remotePath = remotePath,
                fileSize = staged.size,
                mimeType = metadata.mimeType,
                modifiedAtMillis = metadata.modifiedAtMillis,
                overwrite = inputData.getBoolean(KEY_OVERWRITE, true),
                preferredChunkSize = inputData.getLong(KEY_PREFERRED_CHUNK_SIZE, -1L)
                    .takeIf { it > 0 },
                hashes = UploadFileHashes(
                    md5 = inputData.getString(KEY_MD5),
                    sha1 = inputData.getString(KEY_SHA1),
                    sha256 = staged.sha256,
                ),
            )
            val source = FileRandomAccessUploadSource(staged.file, staged.size)
            val multipartEnabled = try {
                OpenListApi(jobHttpClient).serverCapabilities().multipartEnabled
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            val uploader = ResumableUploader(
                transport = OpenListMultipartUploadApi(jobHttpClient),
                checkpoints = checkpointStore,
                multipartEnabled = multipartEnabled,
            )
            val result = uploader.upload(command, source, ::publishProgress)
            sourceGrant?.onWorkDisposition(UploadWorkDisposition.SUCCESS)
            workCleanup.apply(
                disposition = UploadWorkDisposition.SUCCESS,
                stagingKey = stagingKey,
                checkpointKey = checkpointKey,
            )
            UploadGrantLifecycle.onWorkDisposition(
                context = applicationContext,
                workId = id,
                sourceUri = sourceUri,
                disposition = UploadWorkDisposition.SUCCESS,
            )
            Result.success(
                workDataOf(
                    KEY_RESULT_MODE to result.mode.name,
                    KEY_UPLOADED_BYTES to result.uploadedBytes,
                    KEY_REMOTE_NAME to remoteName(),
                ),
            )
        } catch (cancelled: CancellationException) {
            if (isCancelledByApp()) {
                withContext(NonCancellable) {
                    // Do not perform blocking network I/O while WorkManager is stopping us. The
                    // server expires abandoned in-memory sessions; unlink local retained data now.
                    sourceGrant?.onWorkDisposition(UploadWorkDisposition.CANCELLED)
                    workCleanup.apply(
                        disposition = UploadWorkDisposition.PERMANENT_FAILURE,
                        stagingKey = stagingKey,
                        checkpointKey = checkpointKey,
                    )
                    UploadGrantLifecycle.onWorkDisposition(
                        context = applicationContext,
                        workId = id,
                        sourceUri = sourceUri,
                        disposition = UploadWorkDisposition.CANCELLED,
                    )
                }
            }
            // Constraint/process stops remain resumable. API 31+ exposes explicit app cancellation;
            // older releases cannot distinguish it safely, so they retain local resumable state.
            throw cancelled
        } catch (error: Exception) {
            val disposition = UploadWorkPolicy.classifyFailure(error)
            sourceGrant?.onWorkDisposition(disposition)
            workCleanup.apply(
                disposition = disposition,
                stagingKey = stagingKey,
                checkpointKey = checkpointKey,
            )
            UploadGrantLifecycle.onWorkDisposition(
                context = applicationContext,
                workId = id,
                sourceUri = sourceUri,
                disposition = disposition,
            )
            if (disposition == UploadWorkDisposition.RETRY) {
                Result.retry()
            } else {
                val message = error.message?.takeIf(String::isNotBlank) ?: "上传无法继续"
                failure(message)
            }
        } finally {
            UploadGrantLifecycle.onWorkerFinished(applicationContext, id)
        }
    }

    private fun isCancelledByApp(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    private suspend fun publishProgress(progress: UploadProgress) {
        setForeground(createForegroundInfo(progress))
        setProgress(
            workDataOf(
                KEY_UPLOADED_BYTES to progress.uploadedBytes,
                KEY_TOTAL_BYTES to progress.totalBytes,
                KEY_CHUNK_INDEX to (progress.chunkIndex ?: -1),
                KEY_TOTAL_CHUNKS to (progress.totalChunks ?: -1),
                KEY_REMOTE_NAME to remoteName(),
            ),
        )
    }

    private fun createForegroundInfo(progress: UploadProgress?): ForegroundInfo {
        ensureNotificationChannel()
        val total = progress?.totalBytes ?: inputData.getLong(KEY_FILE_SIZE, -1L)
        val uploaded = progress?.uploadedBytes ?: 0L
        val percent = if (total > 0) {
            ((uploaded.coerceIn(0L, total).toDouble() / total.toDouble()) * 100)
                .toInt()
                .coerceIn(0, 100)
        } else {
            0
        }
        val remoteName = remoteName()
        val status = if (total > 0) "$remoteName · $percent%" else "$remoteName · 正在准备"
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("正在上传到 OpenList")
            .setContentText(status)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0)
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
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "文件上传",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示 OpenList 后台上传进度"
                setShowBadge(false)
            },
        )
    }

    private suspend fun resolveMetadata(uri: Uri): SourceMetadata = withContext(Dispatchers.IO) {
        val configuredSize = inputData.getLong(KEY_FILE_SIZE, -1L)
        val configuredModified = inputData.getLong(KEY_LAST_MODIFIED, -1L)
        var queriedSize = -1L
        var queriedModified = -1L
        applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                queriedSize = cursor.longOrDefault(OpenableColumns.SIZE, -1L)
                queriedModified = cursor.longOrDefault(DocumentsContract.Document.COLUMN_LAST_MODIFIED, -1L)
            }
        }
        if (queriedSize < 0) {
            queriedSize = runCatching {
                applicationContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
        }
        require(configuredSize < 0 || queriedSize < 0 || configuredSize == queriedSize) {
            "源文件大小已变化，请重新选择文件"
        }
        require(configuredModified < 0 || queriedModified < 0 || configuredModified == queriedModified) {
            "源文件修改时间已变化，请重新选择文件"
        }
        val size = configuredSize.takeIf { it >= 0 }
            ?: queriedSize.takeIf { it >= 0 }
        val modified = configuredModified.takeIf { it >= 0 }
            ?: queriedModified.takeIf { it >= 0 }
        val mimeType = inputData.getString(KEY_MIME_TYPE)?.takeIf(String::isNotBlank)
            ?: applicationContext.contentResolver.getType(uri)
            ?: "application/octet-stream"
        SourceMetadata(size, modified, mimeType)
    }

    private fun Cursor.longOrDefault(columnName: String, default: Long): Long {
        val index = getColumnIndex(columnName)
        return if (index >= 0 && !isNull(index)) getLong(index) else default
    }

    private fun failure(message: String): Result = Result.failure(
        workDataOf(
            KEY_ERROR to LogSanitizer.redact(message).take(MAX_ERROR_LENGTH),
            KEY_REMOTE_NAME to remoteName(),
        ),
    )

    private fun remoteName(): String = inputData.getString(KEY_REMOTE_PATH)
        ?.substringAfterLast('/')
        ?.takeIf(String::isNotBlank)
        ?: "文件"

    private data class SourceMetadata(
        val expectedSize: Long?,
        val modifiedAtMillis: Long?,
        val mimeType: String,
    )

    companion object {
        const val KEY_SOURCE_URI = "source_uri"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_FILE_SIZE = "file_size"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_LAST_MODIFIED = "last_modified"
        const val KEY_OVERWRITE = "overwrite"
        const val KEY_PREFERRED_CHUNK_SIZE = "preferred_chunk_size"
        const val KEY_SERVER_BASE_URL = "server_base_url"
        const val KEY_SERVER_USERNAME = "server_username"
        const val KEY_SERVER_ALLOW_INSECURE_HTTP = "server_allow_insecure_http"
        const val KEY_SESSION_BINDING = "session_binding"
        const val KEY_MD5 = "file_md5"
        const val KEY_SHA1 = "file_sha1"
        const val KEY_SHA256 = "file_sha256"

        const val KEY_UPLOADED_BYTES = "uploaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_CHUNK_INDEX = "chunk_index"
        const val KEY_TOTAL_CHUNKS = "total_chunks"
        const val KEY_RESULT_MODE = "result_mode"
        const val KEY_ERROR = "error"
        const val KEY_REMOTE_NAME = "remote_name"
        const val WORK_TAG = "openlist-upload"

        private val uniqueEnqueueGate = UploadUniqueEnqueueGate()

        private const val CONTENT_SCHEME = "content"
        private const val MAX_ERROR_LENGTH = 2_000
        private const val NOTIFICATION_CHANNEL_ID = "openlist_uploads"
        private const val FALLBACK_NOTIFICATION_ID = 0x4f4c

        fun request(
            sourceUri: Uri,
            remotePath: String,
            fileSize: Long? = null,
            mimeType: String? = null,
            modifiedAtMillis: Long? = null,
            overwrite: Boolean = true,
            preferredChunkSize: Long? = null,
            hashes: UploadFileHashes = UploadFileHashes(),
            serverBaseUrl: String,
            serverUsername: String,
            serverAllowInsecureHttp: Boolean = false,
            sessionBinding: UploadSessionBinding,
        ): OneTimeWorkRequest {
            require(serverBaseUrl.isNotBlank()) { "serverBaseUrl must not be blank" }
            val canonicalRemotePath = UploadTargetWork.canonicalRemotePath(remotePath)
            val input = Data.Builder()
                .putString(KEY_SOURCE_URI, sourceUri.toString())
                .putString(KEY_REMOTE_PATH, canonicalRemotePath)
                .putString(KEY_SERVER_BASE_URL, serverBaseUrl)
                .putString(KEY_SERVER_USERNAME, serverUsername)
                .putBoolean(KEY_SERVER_ALLOW_INSECURE_HTTP, serverAllowInsecureHttp)
                .putString(KEY_SESSION_BINDING, sessionBinding.value)
                .putBoolean(KEY_OVERWRITE, overwrite)
                .apply {
                    fileSize?.let { putLong(KEY_FILE_SIZE, it) }
                    mimeType?.let { putString(KEY_MIME_TYPE, it) }
                    modifiedAtMillis?.let { putLong(KEY_LAST_MODIFIED, it) }
                    preferredChunkSize?.let { putLong(KEY_PREFERRED_CHUNK_SIZE, it) }
                    hashes.md5?.let { putString(KEY_MD5, it) }
                    hashes.sha1?.let { putString(KEY_SHA1, it) }
                    hashes.sha256?.let { putString(KEY_SHA256, it) }
                }
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(WORK_TAG)
                .apply {
                    TransferWorkMetadata.tags(
                        binding = sessionBinding.value,
                        remotePath = canonicalRemotePath,
                        createdAtMillis = System.currentTimeMillis(),
                    ).forEach { addTag(it) }
                }
                .build()
        }

        fun uniqueWorkName(
            remotePath: String,
            serverAllowInsecureHttp: Boolean,
            sessionBinding: UploadSessionBinding,
        ): String = UploadTargetWork.uniqueName(
            sessionBinding = sessionBinding,
            serverAllowInsecureHttp = serverAllowInsecureHttp,
            remotePath = remotePath,
        )

        /**
         * KEEP prevents concurrent multipart/checkpoint writers for one session destination.
         *
         * A successful enqueue Operation does not reveal whether KEEP inserted [request]. The
         * request id is queried after the transaction and the source grant is transferred only
         * when that id exists. A rejected request is synchronously reconciled, while a possible
         * legacy/shared URI owner keeps a durable lease until its unique work becomes terminal.
         */
        internal suspend fun enqueueUnique(
            context: Context,
            sourceUri: Uri,
            remotePath: String,
            serverAllowInsecureHttp: Boolean,
            sessionBinding: UploadSessionBinding,
            request: OneTimeWorkRequest,
        ): UploadUniqueEnqueueResult {
            require(sourceUri.scheme.equals(CONTENT_SCHEME, ignoreCase = true)) {
                "sourceUri must be a SAF content URI"
            }
            val appContext = context.applicationContext
            UploadGrantLifecycle.initialize(appContext)
            val workManager = WorkManager.getInstance(appContext)
            val workName = uniqueWorkName(
                remotePath = remotePath,
                serverAllowInsecureHttp = serverAllowInsecureHttp,
                sessionBinding = sessionBinding,
            )
            return uniqueEnqueueGate.enqueue(
                hasActiveWork = {
                    workManager.getWorkInfosForUniqueWorkFlow(workName).first().any { info ->
                        info.state == WorkInfo.State.ENQUEUED ||
                            info.state == WorkInfo.State.RUNNING
                    }
                },
                acquireSourceGrant = {
                    UploadGrantLifecycle.acquire(
                        context = appContext,
                        workId = request.id,
                        sourceUri = sourceUri,
                        uniqueWorkName = workName,
                    )
                },
                enqueueAndConfirm = {
                    workManager.enqueueUniqueWork(
                        workName,
                        UploadTargetWork.existingWorkPolicy,
                        request,
                    ).awaitOperation()
                    val accepted = workManager.getWorkInfoByIdFlow(request.id).first() != null
                    if (accepted) UploadGrantLifecycle.onEnqueued(appContext, request.id)
                    accepted
                },
                releaseSourceGrant = {
                    UploadGrantLifecycle.resolveAfterUnacceptedEnqueue(appContext, request.id)
                },
            )
        }

        /** Cancels an upload while its durable source/local cleanup observer remains authoritative. */
        fun cancel(context: Context, workId: java.util.UUID): Operation =
            UploadGrantLifecycle.cancel(context, workId)
    }
}
