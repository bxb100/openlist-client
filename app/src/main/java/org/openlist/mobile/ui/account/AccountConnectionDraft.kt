package org.openlist.mobile.ui.account

import java.net.URI
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountDraft
import org.openlist.mobile.ui.LoginEndpointDraft
import org.openlist.mobile.ui.LoginProtocol

/**
 * Editing connection fields must not rewrite an unchanged saved account identity. The form may
 * encode Unicode paths or normalize a port for display, while session ownership uses the stored
 * URL representation. Compare both through the form, then retain the original representation for
 * the same destination; actual endpoint, username, and HTTP-policy changes still reach the store.
 */
internal fun accountConnectionDraft(
    displayName: String,
    endpoint: LoginEndpointDraft,
    username: String,
    allowInsecureHttp: Boolean,
    savedServer: ServerProfile? = null,
): AccountDraft {
    val candidate = endpoint.serverProfile(username, allowInsecureHttp)
    val sameEndpoint = savedServer != null && runCatching {
        endpoint.comparableUri() == LoginEndpointDraft.fromBaseUrl(savedServer.baseUrl).comparableUri()
    }.getOrDefault(false)
    val server = if (sameEndpoint && savedServer != null) {
        candidate.copy(
            baseUrl = savedServer.baseUrl,
            username = if (candidate.username == savedServer.username.trim()) savedServer.username else candidate.username,
            // This flag has no effect for HTTPS. Keep its stored value so a metadata edit also
            // leaves the complete ServerProfile equal for playback/session-change observers.
            allowInsecureHttp = if (endpoint.protocol == LoginProtocol.HTTPS) {
                savedServer.allowInsecureHttp
            } else {
                candidate.allowInsecureHttp
            },
        )
    } else candidate
    return AccountDraft(displayName.trim(), server)
}

private fun LoginEndpointDraft.comparableUri(): URI {
    val uri = URI(baseUrl())
    val effectivePort = uri.port.takeIf { it >= 0 } ?: if (protocol == LoginProtocol.HTTPS) 443 else 80
    val authority = URI(uri.scheme, null, uri.host, effectivePort, null, null, null)
    // URI equality ignores host/escape hex case without confusing encoded slashes with separators.
    return URI(authority.toASCIIString() + uri.rawPath.orEmpty())
}
