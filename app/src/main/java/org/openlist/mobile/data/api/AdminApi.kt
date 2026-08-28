package org.openlist.mobile.data.api

import com.google.gson.JsonElement
import okhttp3.RequestBody
import okhttp3.Response
import org.openlist.mobile.data.api.catalog.ApiHttpMethod
import org.openlist.mobile.data.api.catalog.Endpoint
import org.openlist.mobile.data.api.catalog.EndpointAliasKind
import org.openlist.mobile.data.api.catalog.EndpointCatalog
import org.openlist.mobile.data.api.catalog.EndpointResolution
import org.openlist.mobile.data.api.catalog.GenericOpenListService

/** The 53 canonical non-task routes mounted below `/api/admin`. */
enum class AdminRoute(val relativePath: String) {
    META_LIST("meta/list"),
    META_GET("meta/get"),
    META_CREATE("meta/create"),
    META_UPDATE("meta/update"),
    META_DELETE("meta/delete"),
    USER_LIST("user/list"),
    USER_GET("user/get"),
    USER_CREATE("user/create"),
    USER_UPDATE("user/update"),
    USER_CANCEL_2FA("user/cancel_2fa"),
    USER_DELETE("user/delete"),
    USER_DELETE_CACHE("user/del_cache"),
    USER_SSH_KEY_LIST("user/sshkey/list"),
    USER_SSH_KEY_DELETE("user/sshkey/delete"),
    STORAGE_LIST("storage/list"),
    STORAGE_GET("storage/get"),
    STORAGE_CREATE("storage/create"),
    STORAGE_UPDATE("storage/update"),
    STORAGE_DELETE("storage/delete"),
    STORAGE_ENABLE("storage/enable"),
    STORAGE_DISABLE("storage/disable"),
    STORAGE_LOAD_ALL("storage/load_all"),
    DRIVER_LIST("driver/list"),
    DRIVER_NAMES("driver/names"),
    DRIVER_INFO("driver/info"),
    SETTING_GET("setting/get"),
    SETTING_LIST("setting/list"),
    SETTING_SAVE("setting/save"),
    SETTING_DELETE("setting/delete"),
    SETTING_DEFAULT("setting/default"),
    SETTING_RESET_TOKEN("setting/reset_token"),
    SETTING_SET_ARIA2("setting/set_aria2"),
    SETTING_SET_QBITTORRENT("setting/set_qbit"),
    SETTING_SET_TRANSMISSION("setting/set_transmission"),
    SETTING_SET_115("setting/set_115"),
    SETTING_SET_115_OPEN("setting/set_115_open"),
    SETTING_SET_123_PAN("setting/set_123_pan"),
    SETTING_SET_123_OPEN("setting/set_123_open"),
    SETTING_SET_PIKPAK("setting/set_pikpak"),
    SETTING_SET_THUNDER("setting/set_thunder"),
    SETTING_SET_THUNDER_X("setting/set_thunderx"),
    SETTING_SET_THUNDER_BROWSER("setting/set_thunder_browser"),
    SETTING_SET_GUANGYA_PAN("setting/set_guangyapan"),
    MESSAGE_GET("message/get"),
    MESSAGE_SEND("message/send"),
    INDEX_BUILD("index/build"),
    INDEX_UPDATE("index/update"),
    INDEX_STOP("index/stop"),
    INDEX_CLEAR("index/clear"),
    INDEX_PROGRESS("index/progress"),
    SCAN_START("scan/start"),
    SCAN_STOP("scan/stop"),
    SCAN_PROGRESS("scan/progress"),
    ;

    val endpoint: Endpoint
        get() = EndpointCatalog.admin(relativePath)
}

/** Typed and dynamic access to OpenList administrator APIs. */
class AdminApi(val service: GenericOpenListService) {
    constructor(http: OpenListHttpClient) : this(GenericOpenListService(http))

    suspend inline fun <reified T> execute(
        route: AdminRoute,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T = service.call(
        endpoint = route.endpoint,
        body = body,
        query = query,
        headers = headers,
    )

    suspend inline fun <reified T> get(
        route: AdminRoute,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        require(route.endpoint.supports(ApiHttpMethod.GET)) { "${route.endpoint.path} is not GET" }
        return service.call(route.endpoint, ApiHttpMethod.GET, query = query, headers = headers)
    }

    suspend inline fun <reified T> post(
        route: AdminRoute,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        require(route.endpoint.supports(ApiHttpMethod.POST)) { "${route.endpoint.path} is not POST" }
        return service.call(route.endpoint, ApiHttpMethod.POST, body, query = query, headers = headers)
    }

    suspend fun executeJson(
        route: AdminRoute,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): JsonElement = service.callJson(route.endpoint, body = body, query = query, headers = headers)

    suspend fun executeUnit(
        route: AdminRoute,
        body: Any? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ) = service.callUnit(route.endpoint, body = body, query = query, headers = headers)

    /**
     * Calls any catalogued `/api/admin` route, including a legacy admin-task alias, and parses the
     * OpenList response envelope as [T].
     */
    suspend inline fun <reified T> dynamic(
        relativeOrAbsolutePath: String,
        method: ApiHttpMethod? = null,
        body: Any? = null,
        rawBody: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        val resolution = resolveAdmin(relativeOrAbsolutePath)
        return service.call(
            endpoint = resolution.endpoint,
            method = method ?: resolution.endpoint.preferredMethod,
            body = body,
            rawBody = rawBody,
            query = query,
            headers = headers,
            aliasKind = resolution.alias?.kind,
        )
    }

    /** Raw variant for redirects, non-envelope responses, and forward-compatible payloads. */
    suspend fun raw(
        relativeOrAbsolutePath: String,
        method: ApiHttpMethod? = null,
        body: RequestBody? = null,
        query: Map<String, String?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val resolution = resolveAdmin(relativeOrAbsolutePath)
        return service.raw(
            endpoint = resolution.endpoint,
            method = method ?: resolution.endpoint.preferredMethod,
            body = body,
            query = query,
            headers = headers,
            aliasKind = resolution.alias?.kind,
        )
    }

    companion object {
        fun resolveAdmin(relativeOrAbsolutePath: String): EndpointResolution {
            val path = if (relativeOrAbsolutePath.startsWith("/api/admin/")) {
                relativeOrAbsolutePath
            } else {
                "/api/admin/${relativeOrAbsolutePath.trimStart('/')}"
            }
            return EndpointCatalog.resolve(path)
                ?: throw IllegalArgumentException("Unknown OpenList admin API path: $path")
        }
    }
}

