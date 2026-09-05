package org.openlist.mobile.ui

import java.net.IDN
import java.net.URI
import org.openlist.mobile.core.model.ServerProfile

internal const val DEFAULT_LOGIN_PORT = "5244"

internal enum class LoginProtocol(val scheme: String) {
    HTTP("http"),
    HTTPS("https"),
}

/**
 * Shared editable endpoint for signing in and managing saved connections.
 *
 * Accounts continue to persist a complete base URL. Keeping this split at the UI boundary lets a
 * new connection default to OpenList's conventional HTTP/5244 endpoint without changing the
 * normalization rules for existing accounts, downloads, uploads, or media playback.
 */
internal data class LoginEndpointDraft(
    val host: String = "",
    val protocol: LoginProtocol = LoginProtocol.HTTP,
    val port: String = DEFAULT_LOGIN_PORT,
    val basePath: String = "",
) {
    fun baseUrl(): String {
        val normalizedHost = host.trim().removeSurrounding("[", "]")
        require(normalizedHost.isNotBlank()) { "请输入服务器地址或 IP" }
        require("://" !in normalizedHost) { "网址无效，请检查协议、端口和路径" }
        require(normalizedHost.none(Char::isWhitespace)) { "服务器地址不能包含空格" }
        require(normalizedHost.none { it == '/' || it == '?' || it == '#' || it == '@' }) {
            "服务器地址只需填写 IP 或域名"
        }

        val portNumber = port.trim().takeIf(String::isNotEmpty)?.let { value ->
            value.toIntOrNull()?.also {
                require(it in 1..65_535) { "端口必须在 1 到 65535 之间" }
            } ?: throw IllegalArgumentException("端口必须是数字")
        } ?: -1

        val path = basePath.trim()
        require('?' !in path && '#' !in path) { "服务器路径不能包含查询参数或片段" }
        val normalizedPath = path
            .trimEnd('/')
            .takeIf(String::isNotEmpty)
            ?.let { if (it.startsWith('/')) it else "/$it" }
        val uriHost = if (':' in normalizedHost) normalizedHost else IDN.toASCII(normalizedHost)
        val authority = URI(protocol.scheme, null, uriHost, portNumber, null, null, null)
        // Preserve percent-encoded path separators from a pasted reverse-proxy URL. Using URI.path
        // decodes %2F and can silently change the server's route; new spaces/unicode still encode.
        val encodedPath = normalizedPath?.let {
            // Supplying the authority keeps a path beginning with // a path, rather than letting
            // URI interpret its first segment as a new authority.
            URI(protocol.scheme, null, uriHost, portNumber, it, null, null).rawPath
                .replace(Regex("%25([0-9a-fA-F]{2})"), "%$1")
        }.orEmpty()
        val uri = URI(authority.toASCIIString() + encodedPath)
        require(!uri.host.isNullOrBlank()) { "服务器地址无效" }
        return uri.toASCIIString().trimEnd('/')
    }

    fun serverProfile(
        username: String,
        allowInsecureHttp: Boolean = protocol == LoginProtocol.HTTP,
    ): ServerProfile = ServerProfile(
        baseUrl = baseUrl(),
        username = username.trim(),
        allowInsecureHttp = protocol == LoginProtocol.HTTP && allowInsecureHttp,
    )

    /** Full URLs and host:port/path shorthand update all connection fields in a single edit. */
    fun withAddressInput(value: String): LoginEndpointDraft {
        val trimmed = value.trim()
        val hasEndpointParts = "://" in trimmed || '/' in trimmed ||
            trimmed.startsWith("[") || trimmed.count { it == ':' } == 1
        if (!hasEndpointParts) return copy(host = value)
        val parsed = fromBaseUrl(trimmed)
        return if (runCatching { parsed.baseUrl() }.isSuccess) parsed else copy(host = value)
    }

    companion object {
        fun fromBaseUrl(baseUrl: String): LoginEndpointDraft {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) return LoginEndpointDraft()
            return runCatching {
                val hasExplicitScheme = "://" in trimmed
                val withScheme = if (hasExplicitScheme) trimmed else "http://$trimmed"
                val uri = URI(withScheme)
                require(uri.userInfo == null && uri.query == null && uri.fragment == null)
                val parsedProtocol = when (uri.scheme?.lowercase()) {
                    LoginProtocol.HTTP.scheme -> LoginProtocol.HTTP
                    LoginProtocol.HTTPS.scheme -> LoginProtocol.HTTPS
                    else -> error("Unsupported login protocol")
                }
                val authority = requireNotNull(uri.rawAuthority)
                val parsedHost = uri.host?.removeSurrounding("[", "]") ?: run {
                    require(!authority.startsWith("["))
                    IDN.toASCII(authority.substringBefore(':'))
                }
                val explicitPort = if (uri.port >= 0) uri.port.toString() else {
                    authority.takeIf { !it.startsWith("[") && ':' in it }
                        ?.substringAfter(':')
                }
                require(explicitPort == null || explicitPort.toIntOrNull()?.let { it in 1..65_535 } == true)
                LoginEndpointDraft(
                    host = parsedHost,
                    protocol = parsedProtocol,
                    // Explicit URLs keep an omitted port omitted so an existing https://host
                    // account retains its identity. Scheme-less input is a new-connection
                    // shorthand and therefore receives OpenList's conventional port.
                    port = explicitPort
                        ?: DEFAULT_LOGIN_PORT.takeUnless { hasExplicitScheme }.orEmpty(),
                    basePath = uri.rawPath.orEmpty().takeUnless { it == "/" }.orEmpty(),
                )
            }.getOrElse {
                LoginEndpointDraft(host = trimmed)
            }
        }
    }
}
