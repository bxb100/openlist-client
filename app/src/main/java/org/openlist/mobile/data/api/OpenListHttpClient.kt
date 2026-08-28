package org.openlist.mobile.data.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.openlist.mobile.data.api.dto.ApiEnvelope
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One atomic view of the connection identity used to build a request. */
data class HttpSessionSnapshot(
    val baseUrl: String,
    val token: String?,
    val allowInsecureHttp: Boolean,
)

class OpenListHttpClient(
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val allowInsecureHttp: () -> Boolean = { false },
    val okHttpClient: OkHttpClient = defaultOkHttpClient(),
    val gson: Gson = Gson(),
    private val sessionSnapshot: (() -> HttpSessionSnapshot)? = null,
) {
    suspend inline fun <reified T> get(
        path: String,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T = executeForType("GET", path, query, headers, null, object : TypeToken<T>() {}.type) as T

    suspend inline fun <reified T> post(
        path: String,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T = executeForType(
        "POST",
        path,
        query,
        headers,
        if (body == null) null else jsonBody(body),
        object : TypeToken<T>() {}.type,
    ) as T

    suspend inline fun <reified T> put(
        path: String,
        body: RequestBody,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T = executeForType("PUT", path, query, headers, body, object : TypeToken<T>() {}.type) as T

    suspend fun raw(request: Request): Response = okHttpClient.newCall(request).awaitResponse()

    fun requestBuilder(
        path: String,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Request.Builder {
        val session = sessionSnapshot?.invoke()
        val builder = Request.Builder().url(
            resolveUrl(
                path = path,
                query = query,
                serverBaseUrl = session?.baseUrl ?: baseUrl(),
                insecureHttpAllowed = session?.allowInsecureHttp ?: allowInsecureHttp(),
            ),
        )
        (session?.token ?: token())?.takeIf(String::isNotBlank)
            ?.let { builder.header("Authorization", it) }
        // Explicit caller headers win. This is required for protocol transports such as MCP,
        // WebDAV, and S3 that may intentionally authenticate independently of the app session.
        headers.forEach(builder::header)
        builder.header("User-Agent", USER_AGENT)
        return builder
    }

    fun resolveUrl(path: String, query: Map<String, String?> = emptyMap()): HttpUrl {
        val session = sessionSnapshot?.invoke()
        return resolveUrl(
            path = path,
            query = query,
            serverBaseUrl = session?.baseUrl ?: baseUrl(),
            insecureHttpAllowed = session?.allowInsecureHttp ?: allowInsecureHttp(),
        )
    }

    private fun resolveUrl(
        path: String,
        query: Map<String, String?>,
        serverBaseUrl: String,
        insecureHttpAllowed: Boolean,
    ): HttpUrl {
        val root = serverBaseUrl.trimEnd('/').toHttpUrl()
        if (root.scheme == "http") {
            require(insecureHttpAllowed) { "HTTP 连接需要显式允许" }
        }
        val cleanPath = path.removePrefix("/")
        val url = root.newBuilder()
        val rootPath = root.encodedPath.trimEnd('/')
        url.encodedPath(if (rootPath.isBlank() || rootPath == "/") "/$cleanPath" else "$rootPath/$cleanPath")
        query.forEach { (key, value) -> if (value != null) url.addQueryParameter(key, value) }
        return url.build()
    }

    fun jsonBody(value: Any?): RequestBody =
        gson.toJson(value ?: emptyMap<String, Any>()).toRequestBody(JSON_MEDIA_TYPE)

    suspend fun executeForType(
        method: String,
        path: String,
        query: Map<String, String?>,
        headers: Map<String, String>,
        body: RequestBody?,
        resultType: Type,
    ): Any {
        val request = requestBuilder(path, query, headers)
            .method(method, when {
                method == "GET" || method == "HEAD" -> null
                body != null -> body
                else -> EMPTY_BODY
            })
            .build()
        return okHttpClient.newCall(request).awaitMapped { response ->
            val text = response.body.string()
            val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrElse { error ->
                throw OpenListApiException(
                    apiCode = response.code,
                    message = "服务器返回了无法解析的响应（HTTP ${response.code}）",
                    httpStatus = response.code,
                    cause = error,
                )
            }
            val apiCode = root.get("code")?.takeUnless(JsonElement::isJsonNull)?.asInt ?: response.code
            val message = root.get("message")?.takeUnless(JsonElement::isJsonNull)?.asString.orEmpty()
            val data = root.get("data")
            if (!response.isSuccessful || apiCode != 200) {
                throw OpenListApiException(
                    apiCode = apiCode,
                    message = message.ifBlank { "请求失败（$apiCode）" },
                    httpStatus = response.code,
                    responseData = data,
                )
            }
            if (resultType == Unit::class.java) return@awaitMapped Unit
            if ((data == null || data.isJsonNull) && resultType == JsonElement::class.java) {
                return@awaitMapped JsonNull.INSTANCE
            }
            if (data == null || data.isJsonNull) {
                emptyContainerFor(resultType)?.let { return@awaitMapped it }
                throw OpenListApiException(apiCode, "响应缺少 data", response.code)
            }
            gson.fromJson<Any>(data, resultType)
        }
    }

    suspend inline fun <reified T> envelope(request: Request): ApiEnvelope<T> =
        okHttpClient.newCall(request).awaitMapped { response ->
            val type = TypeToken.getParameterized(ApiEnvelope::class.java, T::class.java).type
            gson.fromJson(response.body.charStream(), type)
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        const val USER_AGENT = "OpenList-Android/0.1"

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

/**
 * Bridges OkHttp into structured cancellation. Parsing stays on OkHttp's dispatcher thread, and a
 * cancelled coroutine cancels the physical call so timeouts do not wait for the socket timeout.
 */
@PublishedApi
internal suspend fun <T> Call.awaitMapped(transform: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use(transform) }
                    result.fold(
                        onSuccess = { value ->
                            continuation.resume(value) { _, _, _ -> }
                        },
                        onFailure = { error ->
                            continuation.resumeWithException(error)
                        },
                    )
                }
            },
        )
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

private fun emptyContainerFor(type: Type): Any? {
    val rawType = when (type) {
        is Class<*> -> type
        is ParameterizedType -> type.rawType as? Class<*>
        else -> null
    } ?: return null
    return when {
        Map::class.java.isAssignableFrom(rawType) -> emptyMap<Any?, Any?>()
        Set::class.java.isAssignableFrom(rawType) -> emptySet<Any?>()
        Collection::class.java.isAssignableFrom(rawType) -> emptyList<Any?>()
        else -> null
    }
}
