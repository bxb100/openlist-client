package org.openlist.mobile.data.download

import org.openlist.mobile.data.api.OpenListApiException
import java.io.IOException

object DownloadRetryClassifier {
    fun classify(error: Throwable): DownloadFailure = when (error) {
        is DownloadSessionChangedException -> DownloadFailure(
            DownloadFailureCode.SESSION_CHANGED,
            retryable = false,
            message = error.message ?: "登录会话已变化，请重新发起下载",
        )
        is DownloadTargetException, is SecurityException -> DownloadFailure(
            DownloadFailureCode.TARGET_UNAVAILABLE,
            retryable = false,
            message = error.message ?: "无法写入目标文件",
        )
        is DownloadIntegrityException -> DownloadFailure(
            DownloadFailureCode.INVALID_RESPONSE,
            retryable = false,
            message = error.message ?: "下载内容不完整",
        )
        is DownloadHttpException -> classifyHttp(error.statusCode, error.message)
        is OpenListApiException -> classifyHttp(
            error.httpStatus?.takeUnless { it in 200..299 } ?: error.apiCode,
            error.message,
        )
        is DownloadNetworkException, is IOException -> DownloadFailure(
            DownloadFailureCode.NETWORK,
            retryable = true,
            message = error.message ?: "网络连接失败",
        )
        else -> DownloadFailure(
            DownloadFailureCode.INVALID_RESPONSE,
            retryable = false,
            message = error.message ?: "下载失败",
        )
    }

    fun classifyHttp(statusCode: Int, message: String? = null): DownloadFailure = when (statusCode) {
        401, 403 -> DownloadFailure(
            DownloadFailureCode.AUTH_REQUIRED,
            retryable = false,
            message = message ?: "登录或受保护路径凭据已失效",
        )
        404, 410 -> DownloadFailure(
            DownloadFailureCode.NOT_FOUND,
            retryable = false,
            message = message ?: "远程文件不存在",
        )
        408, 425 -> DownloadFailure(
            DownloadFailureCode.NETWORK,
            retryable = true,
            message = message ?: "下载请求超时",
        )
        429 -> DownloadFailure(
            DownloadFailureCode.RATE_LIMITED,
            retryable = true,
            message = message ?: "服务器暂时限流",
        )
        in 500..599 -> DownloadFailure(
            DownloadFailureCode.SERVER_ERROR,
            retryable = true,
            message = message ?: "服务器暂时不可用",
        )
        else -> DownloadFailure(
            DownloadFailureCode.REMOTE_REJECTED,
            retryable = false,
            message = message ?: "下载请求被拒绝（HTTP $statusCode）",
        )
    }
}
