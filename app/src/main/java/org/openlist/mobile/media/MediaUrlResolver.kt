package org.openlist.mobile.media

import org.openlist.mobile.data.api.OpenListApi
import java.io.IOException
import java.net.URI
import java.util.Locale

data class ResolvedMediaUrl(
    val url: String,
    val requestHeaders: Map<String, String> = emptyMap(),
)

fun interface MediaUrlResolver {
    /** Resolve immediately before I/O so an expiring OpenList raw URL is never persisted in a queue. */
    suspend fun resolve(remotePath: String): ResolvedMediaUrl
}

/** Resolves ordinary user-accessible files through `/api/fs/get` and its `raw_url` result. */
class OpenListMediaUrlResolver(
    private val api: OpenListApi,
    private val passwordForPath: (String) -> String = { "" },
) : MediaUrlResolver {
    override suspend fun resolve(remotePath: String): ResolvedMediaUrl {
        val normalizedPath = normalizeRemotePath(remotePath)
        val details = api.get(normalizedPath, passwordForPath(normalizedPath))
        val rawUrl = details.rawUrl.trim()
        if (rawUrl.isBlank()) throw MediaUrlResolutionException("fs/get did not return raw_url for $normalizedPath")
        validateHttpUrl(rawUrl)
        return ResolvedMediaUrl(rawUrl)
    }
}

class MediaUrlResolutionException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal fun validateHttpUrl(url: String) {
    val uri = runCatching { URI(url) }
        .getOrElse { throw MediaUrlResolutionException("raw_url is invalid", it) }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (!uri.isAbsolute || (scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
        throw MediaUrlResolutionException("raw_url must be an absolute HTTP(S) URL")
    }
}

internal object SensitiveMediaHeaders {
    private val names = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "cookie2",
        "x-alist-token",
        "x-openlist-token",
    )

    fun removeFrom(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { key -> !isSensitive(key) }

    fun isSensitive(name: String): Boolean = name.lowercase(Locale.ROOT) in names
}
