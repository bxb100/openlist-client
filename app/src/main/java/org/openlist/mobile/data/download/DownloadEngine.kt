package org.openlist.mobile.data.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.openlist.mobile.media.MediaUrlResolver
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DownloadEngine(
    private val resolver: MediaUrlResolver,
    downloadClient: OkHttpClient,
    private val requireSessionCurrent: () -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val client = downloadClient.newBuilder()
        .addNetworkInterceptor { chain ->
            requireSessionCurrent()
            val safe = chain.request().newBuilder()
                .headers(DownloadHeaders.sanitize(chain.request().headers))
                .build()
            chain.proceed(safe)
        }
        .build()

    suspend fun download(
        request: DownloadRequest,
        target: DownloadTarget,
        onProgress: suspend (DownloadProgress) -> Unit = {},
    ): DownloadResult {
        var refreshAttempted = false
        while (true) {
            currentCoroutineContext().ensureActive()
            requireSessionCurrent()
            val resolved = resolver.resolve(request.remotePath)
            requireSessionCurrent()
            val response = try {
                client.newCall(DownloadHeaders.request(resolved)).awaitResponse()
            } catch (error: IOException) {
                error.sessionChangeOrNull()?.let { throw it }
                throw DownloadNetworkException("无法连接下载源", error)
            }
            if (response.code in REFRESHABLE_LINK_STATUSES && !refreshAttempted) {
                response.close()
                refreshAttempted = true
                continue
            }
            return consumeResponse(response, request, target, onProgress)
        }
    }

    private suspend fun consumeResponse(
        response: Response,
        request: DownloadRequest,
        target: DownloadTarget,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = response.use {
        if (!response.isSuccessful) {
            throw DownloadHttpException(
                statusCode = response.code,
                retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
            )
        }

        val responseLength = response.body.contentLength().takeIf { it >= 0 }
        val expected = request.expectedBytes
        if (expected != null && responseLength != null && expected != responseLength) {
            throw DownloadIntegrityException(
                "下载大小不一致（预期 $expected，服务器 $responseLength）",
            )
        }
        val total = expected ?: responseLength
        var targetOpened = false
        try {
            withContext(Dispatchers.IO) {
                requireSessionCurrent()
                val output = try {
                    target.openTruncated()
                } catch (error: SecurityException) {
                    throw DownloadTargetException("没有目标文件写入权限", error)
                } catch (error: IOException) {
                    if (error is DownloadTargetException) throw error
                    throw DownloadTargetException("无法打开目标文件", error)
                }
                targetOpened = true
                var downloaded = 0L
                try {
                    output.use { sink ->
                        response.body.byteStream().use { source ->
                            onProgress(DownloadProgress(0, total))
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                requireSessionCurrent()
                                val read = try {
                                    source.read(buffer)
                                } catch (error: IOException) {
                                    throw DownloadNetworkException("下载连接在传输中断开", error)
                                }
                                if (read < 0) break
                                if (read == 0) continue
                                requireSessionCurrent()
                                if (expected != null && downloaded + read > expected) {
                                    throw DownloadIntegrityException("下载内容超过预期大小 $expected")
                                }
                                try {
                                    sink.write(buffer, 0, read)
                                } catch (error: IOException) {
                                    throw DownloadTargetException("写入目标文件失败", error)
                                }
                                downloaded += read
                                onProgress(DownloadProgress(downloaded, total))
                            }
                            requireSessionCurrent()
                            try {
                                sink.flush()
                            } catch (error: IOException) {
                                throw DownloadTargetException("刷新目标文件失败", error)
                            }
                        }
                    }
                } catch (error: DownloadNetworkException) {
                    throw error
                } catch (error: DownloadTargetException) {
                    throw error
                } catch (error: DownloadIntegrityException) {
                    throw error
                } catch (error: IOException) {
                    throw DownloadTargetException("关闭目标文件失败", error)
                }
                if (expected != null && downloaded != expected) {
                    throw DownloadIntegrityException("下载不完整（预期 $expected，实际 $downloaded）")
                }
                if (responseLength != null && downloaded != responseLength) {
                    throw DownloadIntegrityException("响应提前结束（预期 $responseLength，实际 $downloaded）")
                }
                requireSessionCurrent()
                DownloadResult(downloadedBytes = downloaded, totalBytes = total)
            }
        } catch (error: Exception) {
            if (targetOpened) {
                runCatching(target::clear).exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }

    private fun parseRetryAfterMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0L) * 1_000L }
        return runCatching {
            val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            (requireNotNull(formatter.parse(value)).time - nowMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private val REFRESHABLE_LINK_STATUSES = setOf(401, 403, 404, 410)
    }
}

private fun IOException.sessionChangeOrNull(): DownloadSessionChangedException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is DownloadSessionChangedException) return current
        current = current.cause
    }
    return null
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, cancelledResponse, _ ->
                    cancelledResponse.close()
                }
            }
        },
    )
}
