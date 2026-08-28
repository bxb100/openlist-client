package org.openlist.mobile.data.download

import androidx.work.Data

data class DownloadWorkInput(
    val remotePath: String,
    val targetUri: String,
    val sessionBinding: DownloadSessionBinding,
    val expectedBytes: Long? = null,
) {
    init {
        require(remotePath.isNotBlank()) { "remotePath must not be blank" }
        require(targetUri.isNotBlank()) { "targetUri must not be blank" }
        require(expectedBytes == null || expectedBytes >= 0) { "expectedBytes must not be negative" }
    }

    fun toData(): Data = Data.Builder()
        .putString(DownloadWorkKeys.REMOTE_PATH, remotePath)
        .putString(DownloadWorkKeys.TARGET_URI, targetUri)
        .putString(DownloadWorkKeys.SESSION_BINDING, sessionBinding.value)
        .apply { expectedBytes?.let { putLong(DownloadWorkKeys.EXPECTED_BYTES, it) } }
        .build()

    companion object {
        fun fromData(data: Data): DownloadWorkInput? = runCatching {
            DownloadWorkInput(
                remotePath = requireNotNull(data.getString(DownloadWorkKeys.REMOTE_PATH)),
                targetUri = requireNotNull(data.getString(DownloadWorkKeys.TARGET_URI)),
                sessionBinding = DownloadSessionBinding.parse(
                    requireNotNull(data.getString(DownloadWorkKeys.SESSION_BINDING)),
                ),
                expectedBytes = data.getLong(DownloadWorkKeys.EXPECTED_BYTES, -1L)
                    .takeIf { it >= 0 },
            )
        }.getOrNull()
    }
}

object DownloadWorkKeys {
    const val REMOTE_PATH = "remote_path"
    const val TARGET_URI = "target_uri"
    const val SESSION_BINDING = "session_binding"
    const val EXPECTED_BYTES = "expected_bytes"
    const val DOWNLOADED_BYTES = "downloaded_bytes"
    const val TOTAL_BYTES = "total_bytes"
    const val FAILURE_CODE = "failure_code"
    const val ERROR = "error"
}
