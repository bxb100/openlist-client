package org.openlist.mobile.data.api.catalog

/** HTTP verbs understood by OpenList's Gin router. */
enum class ApiHttpMethod {
    /** Gin `Any`; catalog-only and never sent as an HTTP request method. */
    ANY,
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
    HEAD,
    OPTIONS,
    CONNECT,
    TRACE,
    PROPFIND,
    MKCOL,
    LOCK,
    UNLOCK,
    PROPPATCH,
    COPY,
    MOVE,
}

/** The server-side identity needed to use an endpoint for its default request shape. */
enum class ApiAccess {
    /** No OpenList identity middleware is installed for this route. */
    PUBLIC,

    /** `Auth(false)`: guest access works only while the guest account is enabled. */
    GUEST_IF_ENABLED,

    /** `Auth(true)`: even a disabled guest identity is accepted. */
    GUEST_ALWAYS,

    /** OpenList's dedicated WebAuthn identity middleware. */
    AUTHN_CONTEXT,

    /** Guest accounts are rejected. */
    NON_GUEST,

    /** Only an administrator account or the server admin token is accepted. */
    ADMIN,
}

/**
 * A request-body path prefix that changes an endpoint's effective authorization behavior.
 *
 * OpenList's split file-system readers dispatch sharing paths before applying the regular guest
 * check, so route middleware alone is not enough to describe their caller-visible behavior.
 */
data class PathAccessOverride(
    val prefix: String,
    val access: ApiAccess,
) {
    init {
        require(prefix.startsWith('/')) { "Path access prefix must be absolute: $prefix" }
    }
}

enum class EndpointAliasKind {
    /** Compatibility route retained by OpenList for older automation clients. */
    LEGACY_ADMIN_TASK,
}

data class EndpointAlias(
    val path: String,
    val kind: EndpointAliasKind,
    val access: ApiAccess? = null,
) {
    init {
        require(path.startsWith("/api/")) { "API alias must start with /api/: $path" }
    }
}

/**
 * One canonical OpenList API path.
 *
 * A Gin `Any` route remains one endpoint here and exposes all supported [methods], so path
 * coverage and callable HTTP behavior can both be checked without inflating the path count.
 */
data class Endpoint(
    val id: String,
    val path: String,
    val methods: Set<ApiHttpMethod>,
    val preferredMethod: ApiHttpMethod,
    val access: ApiAccess,
    val aliases: List<EndpointAlias> = emptyList(),
    val pathAccessOverrides: List<PathAccessOverride> = emptyList(),
    /** Method written in the upstream router; [ApiHttpMethod.ANY] is kept unexpanded. */
    val declaredMethod: ApiHttpMethod = preferredMethod,
) {
    init {
        require(id.isNotBlank()) { "Endpoint id must not be blank" }
        require(path.startsWith("/api/")) { "Canonical API path must start with /api/: $path" }
        require(methods.isNotEmpty()) { "Endpoint $path must support at least one method" }
        require(preferredMethod in methods) {
            "Preferred method $preferredMethod is not supported by $path"
        }
        require(declaredMethod == ApiHttpMethod.ANY || declaredMethod == preferredMethod) {
            "Declared method $declaredMethod does not match preferred method $preferredMethod for $path"
        }
        require(ApiHttpMethod.ANY !in methods) { "ANY is a route declaration, not a wire method" }
        require(aliases.map(EndpointAlias::path).distinct().size == aliases.size) {
            "Endpoint $path contains duplicate aliases"
        }
        require(pathAccessOverrides.map(PathAccessOverride::prefix).distinct().size == pathAccessOverrides.size) {
            "Endpoint $path contains duplicate path access overrides"
        }
    }

    val method: ApiHttpMethod
        get() = declaredMethod

    fun supports(method: ApiHttpMethod): Boolean = method in methods

    /** Returns effective access when this route carries a request-body `path` field. */
    fun accessForRequestPath(requestPath: String?): ApiAccess =
        requestPath
            ?.let { value -> pathAccessOverrides.firstOrNull { value.startsWith(it.prefix) } }
            ?.access
            ?: access

    fun path(aliasKind: EndpointAliasKind?): String = when (aliasKind) {
        null -> path
        else -> aliases.firstOrNull { it.kind == aliasKind }?.path
            ?: throw IllegalArgumentException("Endpoint $path has no $aliasKind alias")
    }
}
