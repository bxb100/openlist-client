package org.openlist.mobile.data.api.catalog

enum class TransportProtocol {
    HEALTH,
    DOWNLOAD,
    WEBDAV,
    S3,
    MCP,
}

enum class TransportAuthentication {
    NONE,
    SIGNED_URL,
    SHARE_ID,
    WEBDAV_BASIC_OR_BEARER,
    AWS_SIGNATURE,
    OPENLIST_ADMIN,
}

enum class TransportListener {
    MAIN_HTTP,
    DEDICATED_S3,
}

/**
 * A non-`/api` protocol entry point exposed by the same OpenList server.
 *
 * These are deliberately separate from [EndpointCatalog]: most do not return an OpenList JSON
 * envelope, and WebDAV/S3/MCP require protocol-specific request and response handling.
 */
data class TransportEndpoint(
    val id: String,
    val pathPattern: String,
    val methods: Set<ApiHttpMethod>,
    val protocol: TransportProtocol,
    val authentication: TransportAuthentication,
    val listener: TransportListener = TransportListener.MAIN_HTTP,
)

object NonApiTransportCatalog {
    private val ginAnyMethods = setOf(
        ApiHttpMethod.GET,
        ApiHttpMethod.POST,
        ApiHttpMethod.PUT,
        ApiHttpMethod.PATCH,
        ApiHttpMethod.DELETE,
        ApiHttpMethod.HEAD,
        ApiHttpMethod.OPTIONS,
        ApiHttpMethod.CONNECT,
        ApiHttpMethod.TRACE,
    )

    private val getAndHead = setOf(ApiHttpMethod.GET, ApiHttpMethod.HEAD)

    val endpoints: List<TransportEndpoint> = listOf(
        TransportEndpoint(
            "ping",
            "/ping",
            ginAnyMethods,
            TransportProtocol.HEALTH,
            TransportAuthentication.NONE,
        ),
        TransportEndpoint(
            "download.direct",
            "/d/*path",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SIGNED_URL,
        ),
        TransportEndpoint(
            "download.proxy",
            "/p/*path",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SIGNED_URL,
        ),
        TransportEndpoint(
            "archive.direct",
            "/ad/*path",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SIGNED_URL,
        ),
        TransportEndpoint(
            "archive.proxy",
            "/ap/*path",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SIGNED_URL,
        ),
        TransportEndpoint(
            "archive.extract",
            "/ae/*path",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SIGNED_URL,
        ),
        TransportEndpoint(
            "sharing.download.root",
            "/sd/:sid",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SHARE_ID,
        ),
        TransportEndpoint(
            "sharing.download.path",
            "/sd/:sid/*path",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SHARE_ID,
        ),
        TransportEndpoint(
            "sharing.archive.root",
            "/sad/:sid",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SHARE_ID,
        ),
        TransportEndpoint(
            "sharing.archive.path",
            "/sad/:sid/*path",
            getAndHead,
            TransportProtocol.DOWNLOAD,
            TransportAuthentication.SHARE_ID,
        ),
        TransportEndpoint(
            "webdav.root",
            "/dav",
            ginAnyMethods + ApiHttpMethod.PROPFIND,
            TransportProtocol.WEBDAV,
            TransportAuthentication.WEBDAV_BASIC_OR_BEARER,
        ),
        TransportEndpoint(
            "webdav.path",
            "/dav/*path",
            ginAnyMethods + setOf(
                ApiHttpMethod.PROPFIND,
                ApiHttpMethod.MKCOL,
                ApiHttpMethod.LOCK,
                ApiHttpMethod.UNLOCK,
                ApiHttpMethod.PROPPATCH,
                ApiHttpMethod.COPY,
                ApiHttpMethod.MOVE,
            ),
            TransportProtocol.WEBDAV,
            TransportAuthentication.WEBDAV_BASIC_OR_BEARER,
        ),
        TransportEndpoint(
            "s3",
            "/s3/*path",
            ginAnyMethods,
            TransportProtocol.S3,
            TransportAuthentication.AWS_SIGNATURE,
        ),
        TransportEndpoint(
            "s3.dedicated_listener",
            "/*path",
            ginAnyMethods,
            TransportProtocol.S3,
            TransportAuthentication.AWS_SIGNATURE,
            TransportListener.DEDICATED_S3,
        ),
        TransportEndpoint(
            "mcp",
            "/mcp",
            setOf(ApiHttpMethod.GET, ApiHttpMethod.POST, ApiHttpMethod.DELETE),
            TransportProtocol.MCP,
            TransportAuthentication.OPENLIST_ADMIN,
        ),
    )

    init {
        check(endpoints.map(TransportEndpoint::id).distinct().size == endpoints.size)
        check(endpoints.map(TransportEndpoint::pathPattern).distinct().size == endpoints.size)
        check(endpoints.none { it.pathPattern.startsWith("/api/") })
    }
}
