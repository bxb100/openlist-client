package org.openlist.mobile.data.download

import okhttp3.Headers
import okhttp3.Request
import org.openlist.mobile.data.api.OpenListHttpClient
import org.openlist.mobile.media.ResolvedMediaUrl
import java.util.Locale

object DownloadHeaders {
    private val sensitiveNames = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "cookie2",
        "password",
        "x-alist-token",
        "x-openlist-token",
        "x-auth-token",
        "x-api-key",
    )
    private val transportOwnedNames = setOf(
        "host",
        "connection",
        "content-length",
        "transfer-encoding",
    )

    fun isSensitive(name: String): Boolean = name.lowercase(Locale.ROOT) in sensitiveNames

    fun sanitize(headers: Map<String, String>): Map<String, String> = headers.filterKeys { name ->
        val normalized = name.lowercase(Locale.ROOT)
        normalized !in sensitiveNames && normalized !in transportOwnedNames
    }

    /** Network-stage scrubber: preserve Host/Connection headers synthesized by OkHttp. */
    fun sanitize(headers: Headers): Headers = headers.newBuilder().apply {
        headers.names()
            .filter(::isSensitive)
            .forEach(::removeAll)
    }.build()

    fun request(resolved: ResolvedMediaUrl): Request {
        val builder = Request.Builder().url(resolved.url).get()
        sanitize(resolved.requestHeaders).forEach(builder::header)
        builder.header("Accept-Encoding", "identity")
        builder.header("User-Agent", OpenListHttpClient.USER_AGENT)
        return builder.build()
    }
}
