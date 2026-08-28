package org.openlist.mobile.media.gallery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.openlist.mobile.data.cache.CacheCoordinator
import org.openlist.mobile.data.cache.CacheKey
import org.openlist.mobile.data.cache.CacheLease
import org.openlist.mobile.media.ContentKey
import org.openlist.mobile.media.MediaEntry
import org.openlist.mobile.media.MediaUrlResolver
import org.openlist.mobile.media.SensitiveMediaHeaders
import org.openlist.mobile.media.tokenSafeMediaClient
import org.openlist.mobile.media.validateHttpUrl
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Resolves a Gallery image from the managed complete-blob cache before asking OpenList for a raw
 * URL. A returned cached handle pins its file until [GalleryImageHandle.close].
 */
class GalleryImageRepository(
    private val urlResolver: MediaUrlResolver,
    private val managedDiskCache: CacheCoordinator? = null,
    downloadClient: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val safeDownloadClient = tokenSafeMediaClient(downloadClient)

    val usesManagedDiskCache: Boolean get() = managedDiskCache != null

    suspend fun acquire(entry: MediaEntry): GalleryImageHandle {
        // withContext has prompt cancellation on its return dispatch. Track a handle while it
        // crosses that boundary so a managed cache lease cannot be lost if the caller disappears
        // after the IO work completed but before the result reaches its coroutine.
        val pendingHandle = AtomicReference<GalleryImageHandle?>(null)
        var ownershipTransferred = false
        try {
            val handle = withContext(ioDispatcher) {
                acquireOnDispatcher(entry).also(pendingHandle::set)
            }
            pendingHandle.set(null)
            ownershipTransferred = true
            return handle
        } finally {
            if (!ownershipTransferred) pendingHandle.getAndSet(null)?.close()
        }
    }

    private suspend fun acquireOnDispatcher(entry: MediaEntry): GalleryImageHandle {
        val cacheKey = galleryImageCacheKey(entry.contentKey)
        managedDiskCache?.tryAcquire(cacheKey)?.let { lease ->
            return GalleryImageHandle(
                data = lease.file,
                contentKey = entry.contentKey,
                isManagedDiskCacheHit = true,
                cacheLease = lease,
            )
        }

        // raw_url is intentionally resolved only after the managed cache misses.
        val resolved = urlResolver.resolve(entry.remotePath)
        validateHttpUrl(resolved.url)
        val cache = managedDiskCache
            ?: return GalleryImageHandle.remote(resolved.url, entry.contentKey)
        val entryBytes = entry.size.takeIf { it > 0L }
        val policy = runCatching { cache.policy }.getOrNull()
            ?: return GalleryImageHandle.remote(resolved.url, entry.contentKey)
        if (
            policy.maxBytes == 0L || policy.maxAgeMillis == 0L || policy.maxEntries == 0 ||
            (entryBytes != null && entryBytes > policy.maxBytes)
        ) {
            return GalleryImageHandle.remote(resolved.url, entry.contentKey)
        }

        return try {
            val request = Request.Builder()
                .url(resolved.url)
                .apply {
                    SensitiveMediaHeaders.removeFrom(resolved.requestHeaders)
                        .forEach { (name, value) -> header(name, value) }
                }
                .build()
            safeDownloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return GalleryImageHandle.remote(resolved.url, entry.contentKey)
                }

                val responseBytes = response.body.contentLength().takeIf { it >= 0L }
                // Conflicting authoritative lengths mean the response cannot be proven to be the
                // complete object represented by this content key. Let Coil fetch the remote URL
                // instead of publishing ambiguous bytes.
                if (entryBytes != null && responseBytes != null && entryBytes != responseBytes) {
                    return GalleryImageHandle.remote(resolved.url, entry.contentKey)
                }
                val expectedBytes = entryBytes ?: responseBytes
                val writeSession = runCatching {
                    cache.beginWrite(cacheKey, expectedBytes)
                }.getOrNull() ?: return GalleryImageHandle.remote(
                    resolved.url,
                    entry.contentKey,
                )

                try {
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            writeSession.outputStream.write(buffer, 0, count)
                        }
                    }
                    if (writeSession.commit()) {
                        cache.tryAcquire(cacheKey)?.let { lease ->
                            return GalleryImageHandle(
                                data = lease.file,
                                contentKey = entry.contentKey,
                                isManagedDiskCacheHit = true,
                                cacheLease = lease,
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    writeSession.abort()
                    throw cancelled
                } catch (_: Exception) {
                    writeSession.abort()
                    // Cache failures must not prevent Coil from trying the resolved remote URL.
                }
            }
            GalleryImageHandle.remote(resolved.url, entry.contentKey)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cache failures must not prevent the image loader from trying the already-resolved URL.
            GalleryImageHandle.remote(resolved.url, entry.contentKey)
        }
    }
}

/** Data is either a pinned managed-cache File or a remote URL understood by Coil. */
class GalleryImageHandle internal constructor(
    val data: Any,
    val contentKey: ContentKey,
    val isManagedDiskCacheHit: Boolean,
    private val cacheLease: CacheLease?,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) cacheLease?.close()
    }

    internal companion object {
        fun remote(url: String, contentKey: ContentKey) = GalleryImageHandle(
            data = url,
            contentKey = contentKey,
            isManagedDiskCacheHit = false,
            cacheLease = null,
        )
    }
}

internal fun galleryImageCacheKey(contentKey: ContentKey): CacheKey =
    CacheKey.namespaced(
        namespace = "gallery-image-original",
        logicalId = contentKey.value,
        revision = "v1",
    )

private fun CacheCoordinator.tryAcquire(key: CacheKey): CacheLease? =
    runCatching { acquire(key) }.getOrNull()
