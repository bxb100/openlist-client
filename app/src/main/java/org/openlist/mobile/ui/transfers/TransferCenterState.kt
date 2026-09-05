package org.openlist.mobile.ui.transfers

import androidx.work.WorkInfo
import org.openlist.mobile.data.download.DownloadFailureCode
import org.openlist.mobile.data.download.DownloadSessionBinding
import org.openlist.mobile.data.download.DownloadWorkKeys
import org.openlist.mobile.data.preferences.AppSettings
import org.openlist.mobile.data.upload.UploadSessionBinding
import org.openlist.mobile.worker.DownloadWorker
import org.openlist.mobile.worker.TransferWorkMetadata
import org.openlist.mobile.worker.UploadWorker
import java.util.UUID

enum class TransferDirection(val label: String) { UPLOAD("上传"), DOWNLOAD("下载") }

enum class TransferStatus {
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ;

    val isActive: Boolean get() = this == RUNNING || this == WAITING
}

data class TransferEntry(
    val id: UUID,
    val name: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val transferredBytes: Long? = null,
    val totalBytes: Long? = null,
    val createdAtMillis: Long? = null,
    val isRetrying: Boolean = false,
    val failureCode: DownloadFailureCode? = null,
) {
    val progress: Float?
        get() = transferredBytes?.let { transferProgress(it, totalBytes) }
}

data class TransferCenterState(
    val entries: List<TransferEntry> = emptyList(),
    val isLoading: Boolean = false,
    val loadFailed: Boolean = false,
) {
    val activeCount: Int get() = entries.count { it.status.isActive }
    val failedCount: Int get() = entries.count { it.status == TransferStatus.FAILED }
}

internal data class TransferIdentity(val uploadTag: String, val downloadTag: String) {
    companion object {
        fun from(settings: AppSettings): TransferIdentity {
            val bindingKey = settings.sessionBindingKey.ifBlank { settings.token }
            return TransferIdentity(
                uploadTag = TransferWorkMetadata.sessionTag(
                    UploadSessionBinding.create(settings.server, bindingKey).value,
                ),
                downloadTag = TransferWorkMetadata.sessionTag(
                    DownloadSessionBinding.create(settings.server, bindingKey).value,
                ),
            )
        }
    }
}

/**
 * Filter again at the presentation boundary: a remembered Flow can still hold the prior account's
 * last emission for one composition after a switch. Untagged legacy work has no provable owner.
 */
internal fun transferEntries(
    workInfos: List<WorkInfo>,
    identity: TransferIdentity,
): List<TransferEntry> = workInfos.mapNotNull { info ->
    val direction = when {
        UploadWorker.WORK_TAG in info.tags && identity.uploadTag in info.tags -> TransferDirection.UPLOAD
        DownloadWorker.WORK_TAG in info.tags && identity.downloadTag in info.tags -> TransferDirection.DOWNLOAD
        else -> return@mapNotNull null
    }
    val status = when (info.state) {
        WorkInfo.State.RUNNING -> TransferStatus.RUNNING
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> TransferStatus.WAITING
        WorkInfo.State.SUCCEEDED -> TransferStatus.SUCCEEDED
        WorkInfo.State.FAILED -> TransferStatus.FAILED
        WorkInfo.State.CANCELLED -> TransferStatus.CANCELLED
    }
    // WorkManager clears progress when work finishes. Terminal counts must come from outputData.
    val data = if (info.state.isFinished) info.outputData else info.progress
    val bytesKey = when (direction) {
        TransferDirection.UPLOAD -> UploadWorker.KEY_UPLOADED_BYTES
        TransferDirection.DOWNLOAD -> DownloadWorkKeys.DOWNLOADED_BYTES
    }
    val bytes = data.getLong(bytesKey, -1L).takeIf { it >= 0L }
    val totalKey = when (direction) {
        TransferDirection.UPLOAD -> UploadWorker.KEY_TOTAL_BYTES
        TransferDirection.DOWNLOAD -> DownloadWorkKeys.TOTAL_BYTES
    }
    val total = data.getLong(totalKey, -1L).takeIf { it >= 0L }
        ?: bytes.takeIf { status == TransferStatus.SUCCEEDED }
    TransferEntry(
        id = info.id,
        name = TransferWorkMetadata.name(info.tags) ?: "文件",
        direction = direction,
        status = status,
        transferredBytes = bytes,
        totalBytes = total,
        createdAtMillis = TransferWorkMetadata.createdAtMillis(info.tags),
        isRetrying = status == TransferStatus.WAITING && info.runAttemptCount > 0,
        failureCode = if (direction == TransferDirection.DOWNLOAD && status == TransferStatus.FAILED) {
            runCatching {
                DownloadFailureCode.valueOf(info.outputData.getString(DownloadWorkKeys.FAILURE_CODE).orEmpty())
            }.getOrNull()
        } else {
            null
        },
    )
}.distinctBy(TransferEntry::id).sortedWith(
    compareBy<TransferEntry> { !it.status.isActive }
        .thenByDescending { it.createdAtMillis ?: 0L }
        .thenBy { it.id.toString() },
)

internal fun transferProgress(transferredBytes: Long, totalBytes: Long?): Float? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return (transferredBytes.coerceIn(0L, total).toDouble() / total.toDouble()).toFloat()
}

internal fun TransferEntry.recoveryMessage(): String = when {
    status == TransferStatus.CANCELLED && direction == TransferDirection.DOWNLOAD ->
        "如需再次下载，请返回文件页重新选择保存位置。"
    status == TransferStatus.CANCELLED -> "如需再次上传，请返回目标文件夹重新选择本地文件。"
    direction == TransferDirection.UPLOAD -> "请返回目标文件夹，重新选择本地文件上传。"
    failureCode == DownloadFailureCode.SESSION_CHANGED || failureCode == DownloadFailureCode.AUTH_REQUIRED ->
        "请确认当前账户已登录，再从文件页重新发起下载。"
    failureCode == DownloadFailureCode.TARGET_UNAVAILABLE ->
        "保存位置不可用。请从文件页重新下载，并选择可写入的位置。"
    failureCode == DownloadFailureCode.NOT_FOUND -> "文件已不可用。请返回文件夹刷新列表。"
    failureCode == DownloadFailureCode.INVALID_RESPONSE ->
        "未能获得完整文件。请从文件页重新下载并选择保存位置。"
    failureCode == DownloadFailureCode.REMOTE_REJECTED ->
        "服务器拒绝了下载。请确认文件访问权限后，从文件页重新下载。"
    else -> "请检查连接，然后从文件页重新下载并选择保存位置。"
}
