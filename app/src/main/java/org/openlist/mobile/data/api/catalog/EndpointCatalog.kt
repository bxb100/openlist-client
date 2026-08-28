package org.openlist.mobile.data.api.catalog

data class EndpointResolution(
    val endpoint: Endpoint,
    val alias: EndpointAlias? = null,
) {
    val requestedPath: String
        get() = alias?.path ?: endpoint.path

    val access: ApiAccess
        get() = alias?.access ?: endpoint.access

    fun accessForRequestPath(requestPath: String?): ApiAccess =
        alias?.access ?: endpoint.accessForRequestPath(requestPath)
}

/**
 * Machine-checkable route inventory for OpenList v4.2.5.
 *
 * The upstream declarations are in `server/router.go` and `server/handles/task.go`. The server
 * registers 199 unique canonical `/api` paths. It additionally registers one legacy
 * `/api/admin/task/...` alias for each of the 84 task paths; aliases intentionally do not change
 * [CANONICAL_PATH_COUNT].
 */
object EndpointCatalog {
    const val OPENLIST_VERSION = "v4.2.5"
    const val OPENLIST_COMMIT = "cc87e88f038a5a27c8782afc7b66a3c1a3cdcb77"
    const val CROSS_CHECKED_MAIN_COMMIT = "1a6cabf45aecf66c6d2ff6c32aed39d50264f43c"
    const val CANONICAL_PATH_COUNT = 199
    const val TASK_KIND_COUNT = 7
    const val TASK_ACTION_COUNT = 12
    const val TASK_PATH_COUNT = TASK_KIND_COUNT * TASK_ACTION_COUNT
    const val LEGACY_ALIAS_COUNT = TASK_PATH_COUNT

    /** Methods registered by Gin's RouterGroup.Any. */
    private val anyMethods = setOf(
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

    private val sharingPathAccess = listOf(
        PathAccessOverride(prefix = "/@s", access = ApiAccess.GUEST_ALWAYS),
    )

    private fun endpoint(
        id: String,
        path: String,
        method: ApiHttpMethod,
        access: ApiAccess,
    ) = Endpoint(id, path, setOf(method), method, access)

    private fun any(
        id: String,
        path: String,
        preferredMethod: ApiHttpMethod,
        access: ApiAccess,
        pathAccessOverrides: List<PathAccessOverride> = emptyList(),
    ) = Endpoint(
        id = id,
        path = path,
        methods = anyMethods,
        preferredMethod = preferredMethod,
        access = access,
        pathAccessOverrides = pathAccessOverrides,
        declaredMethod = ApiHttpMethod.ANY,
    )

    private val authEndpoints = listOf(
        endpoint("auth.login", "/api/auth/login", ApiHttpMethod.POST, ApiAccess.PUBLIC),
        endpoint("auth.login_hash", "/api/auth/login/hash", ApiHttpMethod.POST, ApiAccess.PUBLIC),
        endpoint("auth.login_ldap", "/api/auth/login/ldap", ApiHttpMethod.POST, ApiAccess.PUBLIC),
        endpoint("me.get", "/api/me", ApiHttpMethod.GET, ApiAccess.GUEST_IF_ENABLED),
        endpoint("me.update", "/api/me/update", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("me.sshkey.list", "/api/me/sshkey/list", ApiHttpMethod.GET, ApiAccess.NON_GUEST),
        endpoint("me.sshkey.add", "/api/me/sshkey/add", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("me.sshkey.delete", "/api/me/sshkey/delete", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("auth.2fa.generate", "/api/auth/2fa/generate", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("auth.2fa.verify", "/api/auth/2fa/verify", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("auth.logout", "/api/auth/logout", ApiHttpMethod.GET, ApiAccess.GUEST_IF_ENABLED),
        endpoint("auth.sso", "/api/auth/sso", ApiHttpMethod.GET, ApiAccess.PUBLIC),
        endpoint("auth.sso_callback", "/api/auth/sso_callback", ApiHttpMethod.GET, ApiAccess.PUBLIC),
        endpoint("auth.get_sso_id", "/api/auth/get_sso_id", ApiHttpMethod.GET, ApiAccess.PUBLIC),
        endpoint("auth.sso_get_token", "/api/auth/sso_get_token", ApiHttpMethod.GET, ApiAccess.PUBLIC),
        endpoint(
            "authn.webauthn_begin_login",
            "/api/authn/webauthn_begin_login",
            ApiHttpMethod.GET,
            ApiAccess.PUBLIC,
        ),
        endpoint(
            "authn.webauthn_finish_login",
            "/api/authn/webauthn_finish_login",
            ApiHttpMethod.POST,
            ApiAccess.PUBLIC,
        ),
        endpoint(
            "authn.webauthn_begin_registration",
            "/api/authn/webauthn_begin_registration",
            ApiHttpMethod.GET,
            ApiAccess.AUTHN_CONTEXT,
        ),
        endpoint(
            "authn.webauthn_finish_registration",
            "/api/authn/webauthn_finish_registration",
            ApiHttpMethod.POST,
            ApiAccess.AUTHN_CONTEXT,
        ),
        endpoint("authn.delete", "/api/authn/delete_authn", ApiHttpMethod.POST, ApiAccess.AUTHN_CONTEXT),
        endpoint(
            "authn.credentials",
            "/api/authn/getcredentials",
            ApiHttpMethod.GET,
            ApiAccess.AUTHN_CONTEXT,
        ),
        any("public.settings", "/api/public/settings", ApiHttpMethod.GET, ApiAccess.PUBLIC),
        any(
            "public.offline_download_tools",
            "/api/public/offline_download_tools",
            ApiHttpMethod.GET,
            ApiAccess.PUBLIC,
        ),
        any(
            "public.archive_extensions",
            "/api/public/archive_extensions",
            ApiHttpMethod.GET,
            ApiAccess.PUBLIC,
        ),
    )

    private val fileSystemEndpoints = listOf(
        // Split readers dispatch /@s sharing paths before the regular disabled-guest check.
        any(
            "fs.list",
            "/api/fs/list",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
            pathAccessOverrides = sharingPathAccess,
        ),
        any(
            "fs.get",
            "/api/fs/get",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
            pathAccessOverrides = sharingPathAccess,
        ),
        any(
            "fs.archive.meta",
            "/api/fs/archive/meta",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
            pathAccessOverrides = sharingPathAccess,
        ),
        any(
            "fs.archive.list",
            "/api/fs/archive/list",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
            pathAccessOverrides = sharingPathAccess,
        ),
        any("fs.search", "/api/fs/search", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        any("fs.other", "/api/fs/other", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        any("fs.dirs", "/api/fs/dirs", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint("fs.mkdir", "/api/fs/mkdir", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint("fs.rename", "/api/fs/rename", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint("fs.batch_rename", "/api/fs/batch_rename", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint("fs.regex_rename", "/api/fs/regex_rename", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint("fs.move", "/api/fs/move", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint(
            "fs.recursive_move",
            "/api/fs/recursive_move",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint("fs.copy", "/api/fs/copy", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint("fs.remove", "/api/fs/remove", ApiHttpMethod.POST, ApiAccess.GUEST_IF_ENABLED),
        endpoint(
            "fs.remove_empty_directory",
            "/api/fs/remove_empty_directory",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint("fs.put", "/api/fs/put", ApiHttpMethod.PUT, ApiAccess.GUEST_IF_ENABLED),
        endpoint("fs.form", "/api/fs/form", ApiHttpMethod.PUT, ApiAccess.GUEST_IF_ENABLED),
        endpoint(
            "fs.multipart.init",
            "/api/fs/multipart/init",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.multipart.chunk",
            "/api/fs/multipart/chunk",
            ApiHttpMethod.PUT,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.multipart.complete",
            "/api/fs/multipart/complete",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.multipart.status",
            "/api/fs/multipart/status",
            ApiHttpMethod.GET,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.multipart.abort",
            "/api/fs/multipart/abort",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint("fs.link", "/api/fs/link", ApiHttpMethod.POST, ApiAccess.ADMIN),
        endpoint(
            "fs.add_offline_download",
            "/api/fs/add_offline_download",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.archive.decompress",
            "/api/fs/archive/decompress",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.torrent.parse",
            "/api/fs/torrent/parse",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.torrent.upload_parse",
            "/api/fs/torrent/upload_parse",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.torrent.rapid_upload",
            "/api/fs/torrent/rapid_upload",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.torrent.generate",
            "/api/fs/torrent/generate",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
        endpoint(
            "fs.direct_upload_info",
            "/api/fs/get_direct_upload_info",
            ApiHttpMethod.POST,
            ApiAccess.GUEST_IF_ENABLED,
        ),
    )

    private val sharingEndpoints = listOf(
        any("share.list", "/api/share/list", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("share.get", "/api/share/get", ApiHttpMethod.GET, ApiAccess.NON_GUEST),
        endpoint("share.create", "/api/share/create", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("share.update", "/api/share/update", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("share.delete", "/api/share/delete", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("share.enable", "/api/share/enable", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
        endpoint("share.disable", "/api/share/disable", ApiHttpMethod.POST, ApiAccess.NON_GUEST),
    )

    private fun adminGet(id: String, relativePath: String) = endpoint(
        "admin.$id",
        "/api/admin/$relativePath",
        ApiHttpMethod.GET,
        ApiAccess.ADMIN,
    )

    private fun adminPost(id: String, relativePath: String) = endpoint(
        "admin.$id",
        "/api/admin/$relativePath",
        ApiHttpMethod.POST,
        ApiAccess.ADMIN,
    )

    private val adminEndpoints = listOf(
        adminGet("meta.list", "meta/list"),
        adminGet("meta.get", "meta/get"),
        adminPost("meta.create", "meta/create"),
        adminPost("meta.update", "meta/update"),
        adminPost("meta.delete", "meta/delete"),
        adminGet("user.list", "user/list"),
        adminGet("user.get", "user/get"),
        adminPost("user.create", "user/create"),
        adminPost("user.update", "user/update"),
        adminPost("user.cancel_2fa", "user/cancel_2fa"),
        adminPost("user.delete", "user/delete"),
        adminPost("user.del_cache", "user/del_cache"),
        adminGet("user.sshkey.list", "user/sshkey/list"),
        adminPost("user.sshkey.delete", "user/sshkey/delete"),
        adminGet("storage.list", "storage/list"),
        adminGet("storage.get", "storage/get"),
        adminPost("storage.create", "storage/create"),
        adminPost("storage.update", "storage/update"),
        adminPost("storage.delete", "storage/delete"),
        adminPost("storage.enable", "storage/enable"),
        adminPost("storage.disable", "storage/disable"),
        adminPost("storage.load_all", "storage/load_all"),
        adminGet("driver.list", "driver/list"),
        adminGet("driver.names", "driver/names"),
        adminGet("driver.info", "driver/info"),
        adminGet("setting.get", "setting/get"),
        adminGet("setting.list", "setting/list"),
        adminPost("setting.save", "setting/save"),
        adminPost("setting.delete", "setting/delete"),
        adminPost("setting.default", "setting/default"),
        adminPost("setting.reset_token", "setting/reset_token"),
        adminPost("setting.set_aria2", "setting/set_aria2"),
        adminPost("setting.set_qbit", "setting/set_qbit"),
        adminPost("setting.set_transmission", "setting/set_transmission"),
        adminPost("setting.set_115", "setting/set_115"),
        adminPost("setting.set_115_open", "setting/set_115_open"),
        adminPost("setting.set_123_pan", "setting/set_123_pan"),
        adminPost("setting.set_123_open", "setting/set_123_open"),
        adminPost("setting.set_pikpak", "setting/set_pikpak"),
        adminPost("setting.set_thunder", "setting/set_thunder"),
        adminPost("setting.set_thunderx", "setting/set_thunderx"),
        adminPost("setting.set_thunder_browser", "setting/set_thunder_browser"),
        adminPost("setting.set_guangyapan", "setting/set_guangyapan"),
        adminPost("message.get", "message/get"),
        adminPost("message.send", "message/send"),
        adminPost("index.build", "index/build"),
        adminPost("index.update", "index/update"),
        adminPost("index.stop", "index/stop"),
        adminPost("index.clear", "index/clear"),
        adminGet("index.progress", "index/progress"),
        adminPost("scan.start", "scan/start"),
        adminPost("scan.stop", "scan/stop"),
        adminGet("scan.progress", "scan/progress"),
    )

    private val taskEndpoints = TaskKind.entries.flatMap { kind ->
        TaskAction.entries.map { action ->
            val suffix = "${kind.segment}/${action.segment}"
            Endpoint(
                id = "task.${kind.segment}.${action.segment}",
                path = "/api/task/$suffix",
                methods = setOf(action.method),
                preferredMethod = action.method,
                access = ApiAccess.NON_GUEST,
                aliases = listOf(
                    EndpointAlias(
                        path = "/api/admin/task/$suffix",
                        kind = EndpointAliasKind.LEGACY_ADMIN_TASK,
                        access = ApiAccess.ADMIN,
                    ),
                ),
            )
        }
    }

    /** All 199 canonical paths, in stable declaration order. */
    val endpoints: List<Endpoint> =
        authEndpoints + fileSystemEndpoints + sharingEndpoints + adminEndpoints + taskEndpoints

    val canonicalPaths: Set<String> = endpoints.mapTo(linkedSetOf(), Endpoint::path)

    val aliases: List<EndpointAlias> = endpoints.flatMap(Endpoint::aliases)

    private val endpointsByPath: Map<String, Endpoint> = endpoints.associateBy(Endpoint::path)
    private val endpointsById: Map<String, Endpoint> = endpoints.associateBy(Endpoint::id)
    private val aliasesByPath: Map<String, Pair<Endpoint, EndpointAlias>> = buildMap {
        endpoints.forEach { endpoint ->
            endpoint.aliases.forEach { alias -> put(alias.path, endpoint to alias) }
        }
    }

    init {
        check(endpoints.size == CANONICAL_PATH_COUNT) {
            "Expected $CANONICAL_PATH_COUNT canonical routes, found ${endpoints.size}"
        }
        check(canonicalPaths.size == endpoints.size) { "Canonical API paths must be unique" }
        check(endpointsById.size == endpoints.size) { "Endpoint ids must be unique" }
        check(aliases.size == LEGACY_ALIAS_COUNT) {
            "Expected $LEGACY_ALIAS_COUNT legacy aliases, found ${aliases.size}"
        }
        check(aliases.map(EndpointAlias::path).distinct().size == aliases.size) {
            "Legacy API aliases must be unique"
        }
        check(aliases.none { it.path in canonicalPaths }) {
            "An alias collides with a canonical API path"
        }
    }

    fun findById(id: String): Endpoint? = endpointsById[id]

    fun findCanonical(path: String): Endpoint? = endpointsByPath[path]

    fun resolve(path: String): EndpointResolution? =
        endpointsByPath[path]?.let(::EndpointResolution)
            ?: aliasesByPath[path]?.let { (endpoint, alias) -> EndpointResolution(endpoint, alias) }

    fun requireCanonical(path: String): Endpoint =
        findCanonical(path) ?: throw IllegalArgumentException("Unknown canonical OpenList API path: $path")

    fun task(kind: TaskKind, action: TaskAction): Endpoint =
        requireNotNull(findById("task.${kind.segment}.${action.segment}"))

    fun admin(relativePath: String): Endpoint =
        requireCanonical("/api/admin/${relativePath.trimStart('/')}")
}
