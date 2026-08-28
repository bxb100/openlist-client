package org.openlist.mobile.media.gallery

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openlist.mobile.core.model.CachePolicy
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.data.cache.ManagedDiskCache
import org.openlist.mobile.media.ContentKey
import org.openlist.mobile.media.MediaEntry
import org.openlist.mobile.media.MediaUrlResolver
import org.openlist.mobile.media.ResolvedMediaUrl
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryImageRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cache hit does not resolve url and lease pins file through clear`() = runTest {
        val cache = cache()
        val entry = imageEntry()
        val bytes = "complete-image".toByteArray()
        assertThat(cache.put(galleryImageCacheKey(entry.contentKey), bytes.size.toLong()) { it.write(bytes) })
            .isTrue()
        var resolveCount = 0
        val repository = GalleryImageRepository(
            urlResolver = MediaUrlResolver {
                resolveCount += 1
                ResolvedMediaUrl("https://should-not-resolve.test/image")
            },
            managedDiskCache = cache,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val handle = repository.acquire(entry)
        val cachedFile = handle.data as File

        assertThat(resolveCount).isEqualTo(0)
        assertThat(handle.isManagedDiskCacheHit).isTrue()
        assertThat(cachedFile.readBytes()).isEqualTo(bytes)
        assertThat(cache.stats().activeLeaseCount).isEqualTo(1)

        cache.clear()
        assertThat(cachedFile.isFile).isTrue()
        handle.close()
        assertThat(cache.stats().activeLeaseCount).isEqualTo(0)
        assertThat(cache.stats().entryCount).isEqualTo(0)
        cache.close()
    }

    @Test
    fun `cancellation while cache handle returns from io releases its lease`() = runTest {
        val cache = cache()
        val entry = imageEntry()
        assertThat(cache.put(galleryImageCacheKey(entry.contentKey), 1L) { it.write(byteArrayOf(1)) })
            .isTrue()
        val ioDispatcher = ManuallyQueuedDispatcher()
        val repository = GalleryImageRepository(
            urlResolver = MediaUrlResolver {
                error("A cache hit must not resolve the URL")
            },
            managedDiskCache = cache,
            ioDispatcher = ioDispatcher,
        )

        val acquisition = async { repository.acquire(entry) }
        runCurrent()
        ioDispatcher.runNext()
        assertThat(cache.stats().activeLeaseCount).isEqualTo(1)

        // The IO block has produced the handle, but the test dispatcher has not resumed the
        // caller yet. Cancelling here exercises withContext's prompt-cancellation handoff.
        acquisition.cancelAndJoin()

        assertThat(cache.stats().activeLeaseCount).isEqualTo(0)
        cache.close()
    }

    @Test
    fun `cache miss downloads complete blob securely and subsequent hit avoids network`() = runTest {
        MockWebServer().use { server ->
            val bytes = ByteArray(4_096) { index -> (index % 251).toByte() }
            server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))
            val cache = cache()
            val entry = imageEntry(size = bytes.size.toLong())
            var resolveCount = 0
            val repository = GalleryImageRepository(
                urlResolver = MediaUrlResolver {
                    resolveCount += 1
                    ResolvedMediaUrl(
                        url = server.url("/original").toString(),
                        requestHeaders = mapOf(
                            "Authorization" to "openlist-secret",
                            "Cookie" to "session=secret",
                            "X-Image-Request" to "allowed",
                        ),
                    )
                },
                managedDiskCache = cache,
                downloadClient = OkHttpClient(),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val first = repository.acquire(entry)

            assertThat(first.isManagedDiskCacheHit).isTrue()
            assertThat((first.data as File).readBytes()).isEqualTo(bytes)
            first.close()
            val request = server.takeRequest()
            assertThat(request.headers["Authorization"]).isNull()
            assertThat(request.headers["Cookie"]).isNull()
            assertThat(request.headers["X-Image-Request"]).isEqualTo("allowed")

            val second = repository.acquire(entry)
            assertThat(second.isManagedDiskCacheHit).isTrue()
            second.close()
            assertThat(resolveCount).isEqualTo(1)
            assertThat(server.requestCount).isEqualTo(1)
            assertThat(cache.stats().entryCount).isEqualTo(1)
            cache.close()
        }
    }

    @Test
    fun `disabled managed cache returns remote model without duplicate download`() = runTest {
        val cache = ManagedDiskCache(
            directory = temporaryFolder.newFolder("disabled"),
            initialPolicy = CachePolicy(maxBytes = 0, maxAgeMillis = 0, maxEntries = 0),
        )
        val remote = "https://storage.test/image.jpg?temporary=one"
        val repository = GalleryImageRepository(
            urlResolver = MediaUrlResolver { ResolvedMediaUrl(remote) },
            managedDiskCache = cache,
            ioDispatcher = Dispatchers.Unconfined,
        )

        val handle = repository.acquire(imageEntry())

        assertThat(handle.isManagedDiskCacheHit).isFalse()
        assertThat(handle.data).isEqualTo(remote)
        assertThat(cache.stats().entryCount).isEqualTo(0)
        handle.close()
        cache.close()
    }

    @Test
    fun `conflicting object and response lengths never publish an image`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("four"))
            val cache = cache()
            val remote = server.url("/mismatched").toString()
            val repository = GalleryImageRepository(
                urlResolver = MediaUrlResolver { ResolvedMediaUrl(remote) },
                managedDiskCache = cache,
                downloadClient = OkHttpClient(),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val handle = repository.acquire(imageEntry(size = 3))

            assertThat(handle.isManagedDiskCacheHit).isFalse()
            assertThat(handle.data).isEqualTo(remote)
            assertThat(cache.stats().entryCount).isEqualTo(0)
            assertThat(cache.directory.listFiles().orEmpty().none { it.name.endsWith(".part") }).isTrue()
            handle.close()
            cache.close()
        }
    }

    @Test
    fun `unknown chunked image cannot leave a part larger than cache capacity`() = runTest {
        MockWebServer().use { server ->
            val bytes = ByteArray(32) { it.toByte() }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setChunkedBody(okio.Buffer().write(bytes), 3),
            )
            val cache = cache(maxBytes = 8)
            val remote = server.url("/chunked").toString()
            val repository = GalleryImageRepository(
                urlResolver = MediaUrlResolver { ResolvedMediaUrl(remote) },
                managedDiskCache = cache,
                downloadClient = OkHttpClient(),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val handle = repository.acquire(imageEntry(size = -1))

            assertThat(handle.isManagedDiskCacheHit).isFalse()
            assertThat(handle.data).isEqualTo(remote)
            assertThat(cache.stats().entryCount).isEqualTo(0)
            assertThat(cache.directory.listFiles().orEmpty().none { it.name.endsWith(".part") }).isTrue()
            handle.close()
            cache.close()
        }
    }

    @Test
    fun `known oversized image bypasses repository download before opening a response`() = runTest {
        MockWebServer().use { server ->
            val cache = cache(maxBytes = 8)
            val remote = server.url("/too-large").toString()
            val repository = GalleryImageRepository(
                urlResolver = MediaUrlResolver { ResolvedMediaUrl(remote) },
                managedDiskCache = cache,
                downloadClient = OkHttpClient(),
                ioDispatcher = Dispatchers.Unconfined,
            )

            val handle = repository.acquire(imageEntry(size = 9))

            assertThat(handle.isManagedDiskCacheHit).isFalse()
            assertThat(handle.data).isEqualTo(remote)
            assertThat(server.requestCount).isEqualTo(0)
            handle.close()
            cache.close()
        }
    }

    private fun cache(maxBytes: Long = 10L * 1024 * 1024) = ManagedDiskCache(
        directory = temporaryFolder.newFolder(),
        initialPolicy = CachePolicy(
            maxBytes = maxBytes,
            maxAgeMillis = 60_000,
            maxEntries = 10,
        ),
    )

    private fun imageEntry(size: Long = 100) = MediaEntry(
        remotePath = "/photos/image.jpg",
        name = "image.jpg",
        kind = MediaKind.IMAGE,
        size = size,
        modified = "revision",
        contentKey = ContentKey("openlist-content-v2:test-image"),
    )

    private class ManuallyQueuedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.addLast(block)
        }

        fun runNext() {
            check(tasks.isNotEmpty()) { "No queued IO task" }
            tasks.removeFirst().run()
        }
    }
}
