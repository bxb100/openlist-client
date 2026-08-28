@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.LinkedHashMap
import java.util.UUID

/**
 * An opaque, process-local reference to an OpenList path.
 *
 * MediaItems cross process boundaries through MediaSession. The random identifier and URI can be
 * disclosed to a trusted system controller without disclosing the user's full remote path. The
 * actual path exists only in this app process and is resolved immediately before network I/O.
 */
internal data class RegisteredMediaRequest(
    val mediaId: String,
    val uri: String,
)

internal data class RegisteredMediaRequestDetails(
    val remotePath: String,
    val knownSize: Long?,
)

internal object OpenListMediaRequestRegistry {
    // A service timeline listener prunes old queue entries. This cap also bounds registrations
    // made for previews/items that never reach the player.
    private const val MAX_MAPPINGS = 8_192
    private val mappings = LinkedHashMap<String, RegisteredMediaRequestDetails>(128, 0.75f, true)

    @Synchronized
    fun register(remotePath: String, knownSize: Long? = null): RegisteredMediaRequest {
        require(knownSize == null || knownSize > 0L) { "knownSize must be positive when present" }
        val mediaId = generateSequence { UUID.randomUUID().toString() }
            .first { candidate -> candidate !in mappings }
        mappings[mediaId] = RegisteredMediaRequestDetails(
            remotePath = normalizeRemotePath(remotePath),
            knownSize = knownSize,
        )
        while (mappings.size > MAX_MAPPINGS) {
            val oldest = mappings.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        return RegisteredMediaRequest(
            mediaId = mediaId,
            uri = OpenListMediaRequestUri.create(mediaId),
        )
    }

    @Synchronized
    fun detailsOrNull(mediaId: String): RegisteredMediaRequestDetails? = mappings[mediaId]

    @Synchronized
    fun remotePathOrNull(mediaId: String): String? = mappings[mediaId]?.remotePath

    @Synchronized
    fun retainOnly(mediaIds: Set<String>) {
        mappings.keys.retainAll(mediaIds)
    }

    @Synchronized
    fun clear() = mappings.clear()

    @Synchronized
    internal fun clearForTest() = mappings.clear()
}

/** URI indirection used by MediaItems; it contains only an opaque, process-local identifier. */
internal object OpenListMediaRequestUri {
    private const val SCHEME = "openlist-media"
    private const val AUTHORITY = "resolve"
    private val MEDIA_ID_FORMAT = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )

    fun create(mediaId: String): String {
        require(MEDIA_ID_FORMAT.matches(mediaId)) { "mediaId must be an opaque UUID" }
        return "$SCHEME://$AUTHORITY/$mediaId"
    }

    fun mediaIdOrNull(uri: Uri): String? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || uri.authority != AUTHORITY) return null
        if (uri.query != null || uri.fragment != null || uri.pathSegments.size != 1) return null
        return uri.lastPathSegment?.takeIf(MEDIA_ID_FORMAT::matches)
    }
}

/**
 * Resolves OpenList paths lazily, then delegates network reads to a token-safe OkHttp client.
 *
 * The final network request is scrubbed twice: once at DataSpec resolution and again immediately
 * before the range-response guard. The network scrubber also protects redirects and clients with
 * authentication application interceptors, so an OpenList token cannot be forwarded to
 * object-storage hosts.
 */
class OpenListMediaDataSourceFactory(
    resolver: MediaUrlResolver,
    downloadClient: OkHttpClient = OkHttpClient(),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DataSource.Factory {
    private val delegate: DataSource.Factory

    init {
        val safeClient = tokenSafeMediaClient(downloadClient)
            .withOpenListStrictRangeResponses()
        val upstream = OpenListSegmentedRangeDataSource.Factory(
            OkHttpDataSource.Factory(safeClient)
                .setUserAgent("OpenList-Android/0.1"),
        )
        // ResolvingDataSource.Factory shares one Resolver between every DataSource it creates.
        // Use a fresh resolver instead so the reported URI can safely remain the opaque URI for
        // this one transfer. Otherwise CacheDataSource persists the expiring signed raw URL as
        // redirected-URI metadata.
        delegate = DataSource.Factory {
            ResolvingDataSource(
                upstream.createDataSource(),
                LazyResolver(resolver, ioDispatcher),
            )
        }
    }

    override fun createDataSource(): DataSource = delegate.createDataSource()

    private class LazyResolver(
        private val resolver: MediaUrlResolver,
        private val ioDispatcher: CoroutineDispatcher,
    ) : ResolvingDataSource.Resolver {
        private var reportedUri: Uri? = null

        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            reportedUri = dataSpec.uri
            val cleanOriginalHeaders = SensitiveMediaHeaders.removeFrom(dataSpec.httpRequestHeaders)
            val mediaId = OpenListMediaRequestUri.mediaIdOrNull(dataSpec.uri)
                ?: return dataSpec.withRequestHeaders(cleanOriginalHeaders)
            val registeredRequest = OpenListMediaRequestRegistry.detailsOrNull(mediaId)
                ?: throw MediaUrlResolutionException("Unknown or expired media request")

            val resolved = try {
                runBlocking(ioDispatcher) { resolver.resolve(registeredRequest.remotePath) }
            } catch (error: IOException) {
                throw error
            } catch (error: Exception) {
                throw MediaUrlResolutionException("Unable to resolve media request", error)
            }
            validateHttpUrl(resolved.url)
            val resolvedHeaders = SensitiveMediaHeaders.removeFrom(resolved.requestHeaders)
            return dataSpec.withKnownRemainingLength(registeredRequest.knownSize).buildUpon()
                .setUri(resolved.url.toUri())
                .setHttpRequestHeaders(cleanOriginalHeaders + resolvedHeaders)
                .build()
        }

        override fun resolveReportedUri(uri: Uri): Uri = reportedUri ?: uri
    }
}

/**
 * Supplies the upstream with the remaining object length when OpenList already reported it.
 *
 * Extractors use this value to find tail indexes (for example MP4 `mfra`) without first issuing an
 * open-ended request. Explicit subrange lengths always win. The ordered guards also make the
 * subtraction safe for every valid [Long] value.
 */
internal fun DataSpec.withKnownRemainingLength(knownSize: Long?): DataSpec {
    if (length != C.LENGTH_UNSET.toLong() || knownSize == null || knownSize <= 0L) return this
    if (position < 0L || position >= knownSize) return this
    val remainingLength = knownSize - position
    return buildUpon().setLength(remainingLength).build()
}

internal fun tokenSafeMediaClient(downloadClient: OkHttpClient): OkHttpClient =
    downloadClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val safeHeaders = request.headers.newBuilder().apply {
                request.headers.names()
                    .filter(SensitiveMediaHeaders::isSensitive)
                    .forEach(::removeAll)
            }.build()
            chain.proceed(request.newBuilder().headers(safeHeaders).build())
        }
        .build()
