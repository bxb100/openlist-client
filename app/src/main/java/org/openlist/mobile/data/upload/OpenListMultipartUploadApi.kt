package org.openlist.mobile.data.upload

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.openlist.mobile.data.api.OpenListApiException
import org.openlist.mobile.data.api.OpenListHttpClient
import org.openlist.mobile.data.api.dto.MultipartSession
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class OpenListMultipartUploadApi(
    private val http: OpenListHttpClient,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MultipartUploadTransport {
    override suspend fun init(request: MultipartInitRequest): MultipartSession {
        val httpRequest = http.requestBuilder(INIT_PATH, headers = UploadHeaders.init(request))
            .post(EMPTY_BODY)
            .build()
        return executeSession(httpRequest)
    }

    override suspend fun status(uploadId: String): MultipartSession {
        val request = http.requestBuilder(
            STATUS_PATH,
            query = mapOf("upload_id" to uploadId),
        ).get().build()
        return executeSession(request)
    }

    override suspend fun uploadChunk(
        uploadId: String,
        index: Int,
        body: RequestBody,
    ): MultipartSession {
        val request = http.requestBuilder(CHUNK_PATH, headers = UploadHeaders.chunk(uploadId, index))
            .put(body)
            .build()
        return executeSession(request)
    }

    override suspend fun complete(uploadId: String): MultipartSession {
        val request = http.requestBuilder(COMPLETE_PATH, headers = UploadHeaders.session(uploadId))
            .post(EMPTY_BODY)
            .build()
        return executeSession(request)
    }

    override suspend fun abort(uploadId: String) {
        val request = http.requestBuilder(ABORT_PATH, headers = UploadHeaders.session(uploadId))
            .post(EMPTY_BODY)
            .build()
        executeUnit(request)
    }

    override suspend fun legacyPut(request: MultipartInitRequest, body: RequestBody) {
        val httpRequest = http.requestBuilder(LEGACY_PUT_PATH, headers = UploadHeaders.legacyPut(request))
            .put(body)
            .build()
        executeUnit(httpRequest)
    }

    private suspend fun executeSession(request: Request): MultipartSession =
        execute(request) { data ->
            if (data == null || data.isJsonNull) null else http.gson.fromJson(data, MultipartSession::class.java)
        }

    private suspend fun executeUnit(request: Request) {
        execute<Unit>(request) { Unit }
    }

    private suspend fun <T> execute(request: Request, decode: (JsonElement?) -> T?): T {
        return try {
            executeAttempt(request, decode)
        } catch (error: UploadProtocolException) {
            if (error.httpStatus != 401 && error.apiCode != 401) throw error
            val renewedRequest = try {
                http.refreshRequestAfterUnauthorized(request)
            } catch (refreshError: OpenListApiException) {
                throw UploadProtocolException(
                    httpStatus = refreshError.httpStatus ?: 200,
                    apiCode = refreshError.apiCode,
                    message = refreshError.message,
                    cause = refreshError,
                )
            } ?: throw error
            executeAttempt(renewedRequest, decode)
        }
    }

    private suspend fun <T> executeAttempt(request: Request, decode: (JsonElement?) -> T?): T {
        http.raw(request).use { response ->
            val text = response.body.string()
            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { error ->
                throw UploadProtocolException(
                    httpStatus = response.code,
                    apiCode = response.code,
                    message = "服务器返回了无法解析的响应（HTTP ${response.code}）",
                    retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                    malformedResponse = true,
                    cause = error,
                )
            }
            val apiCode = root.get("code")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: response.code
            val message = root.get("message")?.takeUnless(JsonElement::isJsonNull)?.asString.orEmpty()
            val data = root.get("data")
            val decoded = runCatching { decode(data) }.getOrElse { error ->
                throw UploadProtocolException(
                    httpStatus = response.code,
                    apiCode = apiCode,
                    message = "服务器返回了无法解析的上传状态",
                    retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                    malformedResponse = true,
                    cause = error,
                )
            }
            if (!response.isSuccessful || apiCode != SUCCESS_CODE) {
                throw UploadProtocolException(
                    httpStatus = response.code,
                    apiCode = apiCode,
                    message = message.ifBlank { "上传请求失败（$apiCode）" },
                    session = decoded as? MultipartSession,
                    retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After")),
                )
            }
            return decoded ?: throw UploadProtocolException(
                httpStatus = response.code,
                apiCode = apiCode,
                message = "上传响应缺少 data",
            )
        }
    }

    private fun parseRetryAfterMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        value.trim().toLongOrNull()?.let { seconds ->
            return seconds.coerceAtLeast(0L) * 1_000L
        }
        return runCatching {
            val formatter = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            (requireNotNull(formatter.parse(value)).time - nowMillis()).coerceAtLeast(0L)
        }.getOrNull()
    }

    companion object {
        const val INIT_PATH = "/api/fs/multipart/init"
        const val CHUNK_PATH = "/api/fs/multipart/chunk"
        const val STATUS_PATH = "/api/fs/multipart/status"
        const val COMPLETE_PATH = "/api/fs/multipart/complete"
        const val ABORT_PATH = "/api/fs/multipart/abort"
        const val LEGACY_PUT_PATH = "/api/fs/put"
        private const val SUCCESS_CODE = 200
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
