package org.openlist.mobile.data.download

import java.io.IOException

data class DownloadRequest(
    val remotePath: String,
    val expectedBytes: Long? = null,
) {
    init {
        require(remotePath.isNotBlank()) { "remotePath must not be blank" }
        require(expectedBytes == null || expectedBytes >= 0) { "expectedBytes must not be negative" }
    }
}

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

data class DownloadResult(
    val downloadedBytes: Long,
    val totalBytes: Long?,
)

enum class DownloadFailureCode {
    SESSION_CHANGED,
    AUTH_REQUIRED,
    NOT_FOUND,
    RATE_LIMITED,
    REMOTE_REJECTED,
    SERVER_ERROR,
    NETWORK,
    INVALID_RESPONSE,
    TARGET_UNAVAILABLE,
}

class DownloadHttpException(
    val statusCode: Int,
    val retryAfterMillis: Long? = null,
    message: String = "下载源返回 HTTP $statusCode",
) : IOException(message)

class DownloadNetworkException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class DownloadTargetException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class DownloadIntegrityException(
    message: String,
) : IOException(message)

class DownloadSessionChangedException(
    message: String = "登录服务器、账号或凭据已变化，下载已停止",
) : IOException(message)

data class DownloadFailure(
    val code: DownloadFailureCode,
    val retryable: Boolean,
    val message: String,
)
