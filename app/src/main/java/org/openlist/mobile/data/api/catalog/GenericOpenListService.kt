package org.openlist.mobile.data.api.catalog

import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.openlist.mobile.data.api.OpenListHttpClient
import java.lang.reflect.Type

/**
 * Catalog-aware access to every OpenList JSON endpoint plus a raw transport escape hatch.
 *
 * [call] parses OpenList's `{code,message,data}` envelope. Use [raw] for redirects, WebAuthn
 * protocol payloads, uploads/downloads, WebDAV, S3, MCP, or endpoints whose response is not an
 * OpenList JSON envelope. The caller owns and must close the returned [Response].
 */
class GenericOpenListService(val http: OpenListHttpClient) {
    /** Resolve a canonical path or registered alias, then parse its OpenList response envelope. */
    suspend inline fun <reified T> call(
        path: String,
        method: ApiHttpMethod? = null,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        val resolution = EndpointCatalog.resolve(path)
            ?: throw IllegalArgumentException("Unknown OpenList API path: $path")
        return call(
            endpoint = resolution.endpoint,
            method = method ?: resolution.endpoint.preferredMethod,
            body = body,
            rawBody = rawBody,
            query = query,
            headers = headers,
            aliasKind = resolution.alias?.kind,
        )
    }

    suspend inline fun <reified T> call(
        endpoint: Endpoint,
        method: ApiHttpMethod = endpoint.preferredMethod,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        aliasKind: EndpointAliasKind? = null,
    ): T = callForType(
        endpoint = endpoint,
        method = method,
        body = body,
        rawBody = rawBody,
        query = query,
        headers = headers,
        aliasKind = aliasKind,
        resultType = object : TypeToken<T>() {}.type,
    ) as T

    suspend fun callForType(
        endpoint: Endpoint,
        method: ApiHttpMethod = endpoint.preferredMethod,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        aliasKind: EndpointAliasKind? = null,
        resultType: Type,
    ): Any {
        require(endpoint.supports(method)) { "${endpoint.path} does not support $method" }
        require(body == null || rawBody == null) { "Specify body or rawBody, not both" }
        val requestBody = rawBody ?: when {
            method == ApiHttpMethod.GET || method == ApiHttpMethod.HEAD -> null
            body == null -> null
            else -> http.jsonBody(body)
        }
        return http.executeForType(
            method = method.name,
            path = endpoint.path(aliasKind),
            query = query,
            headers = headers,
            body = requestBody,
            resultType = resultType,
        )
    }

    suspend fun callJson(
        path: String,
        method: ApiHttpMethod? = null,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = call(path, method, body, rawBody, query, headers)

    suspend fun callJson(
        endpoint: Endpoint,
        method: ApiHttpMethod = endpoint.preferredMethod,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        aliasKind: EndpointAliasKind? = null,
    ): JsonElement = call(
        endpoint,
        method,
        body,
        rawBody,
        query,
        headers,
        aliasKind,
    )

    suspend fun callUnit(
        path: String,
        method: ApiHttpMethod? = null,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ) {
        call<Unit>(path, method, body, rawBody, query, headers)
    }

    suspend fun callUnit(
        endpoint: Endpoint,
        method: ApiHttpMethod = endpoint.preferredMethod,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        aliasKind: EndpointAliasKind? = null,
    ) {
        call<Unit>(endpoint, method, body, rawBody, query, headers, aliasKind)
    }

    /** Raw request for a catalogued JSON endpoint. The returned response must be closed. */
    suspend fun raw(
        endpoint: Endpoint,
        method: ApiHttpMethod = endpoint.preferredMethod,
        body: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        aliasKind: EndpointAliasKind? = null,
    ): Response {
        require(endpoint.supports(method)) { "${endpoint.path} does not support $method" }
        return raw(endpoint.path(aliasKind), method.name, body, query, headers)
    }

    /**
     * Unrestricted protocol escape hatch for [NonApiTransportCatalog] and forward-compatible
     * routes. Unlike [call], this does not parse an OpenList envelope.
     */
    suspend fun raw(
        path: String,
        method: String,
        body: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        includeOpenListToken: Boolean = true,
    ): Response {
        val normalizedMethod = method.uppercase()
        val requestBody = body ?: when (normalizedMethod) {
            "POST", "PUT", "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val requestBuilder = if (includeOpenListToken) {
            http.requestBuilder(path, query, headers)
        } else {
            Request.Builder()
                .url(http.resolveUrl(path, query))
                .apply { headers.forEach(::header) }
                .header("User-Agent", OpenListHttpClient.USER_AGENT)
        }
        val request = requestBuilder
            .method(normalizedMethod, requestBody)
            .build()
        return http.raw(request)
    }

    /**
     * Catalog-aware overload for non-JSON transports. [concretePath] replaces route parameters
     * such as `:sid` and `*path`. WebDAV and S3 keep their caller-supplied Authorization header;
     * MCP uses the configured OpenList administrator token.
     */
    suspend fun raw(
        transport: TransportEndpoint,
        concretePath: String,
        method: ApiHttpMethod,
        body: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Response {
        require(method != ApiHttpMethod.ANY && method in transport.methods) {
            "${transport.pathPattern} does not support $method"
        }
        require(
            concretePath.startsWith("/") &&
                !concretePath.contains(":sid") &&
                !concretePath.contains("*path"),
        ) {
            "concretePath must replace all route parameters: $concretePath"
        }
        return raw(
            path = concretePath,
            method = method.name,
            body = body,
            query = query,
            headers = headers,
            includeOpenListToken = transport.authentication == TransportAuthentication.OPENLIST_ADMIN,
        )
    }
}
