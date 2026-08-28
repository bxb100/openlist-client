@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.CachePolicy
import org.openlist.mobile.media.OpenListMediaRequestRegistry

@RunWith(AndroidJUnit4::class)
class Media3CacheControllerDataSourceTest {
    @After
    fun clearMediaRequestRegistry() {
        OpenListMediaRequestRegistry.clearForTest()
    }

    @Test
    fun knownFileLargerThanPolicyCachesOnlyTheReadPrefixWithinCapacity() =
        withController(maxBytes = 8L) { controller ->
            val key = stableKey('a')
            val payload = ByteArray(16) { it.toByte() }
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/large.mp4",
                knownSize = payload.size.toLong(),
            )
            val upstream = RecordingDataSourceFactory(payload)
            val source = controller.decorate(upstream).createDataSource()

            val actual = readToEnd(source, mediaDataSpec(request.uri, key))

            assertArrayEquals(payload, actual)
            assertEquals(payload.size.toLong(), upstream.openedSpecs.single().length)
            assertEquals(8L, controller.cache.getCachedBytes(key, 0L, payload.size.toLong()))
            assertEquals(8L, controller.stats().totalBytes)
            assertEquals(0, controller.stats().inProgressWriteCount)
        }

    @Test
    fun rereadingCachedPrefixDoesNotOpenUpstream() = withController(maxBytes = 8L) { controller ->
        val key = stableKey('b')
        val payload = ByteArray(16) { it.toByte() }
        val request = OpenListMediaRequestRegistry.register(
            remotePath = "/large-prefix.mp4",
            knownSize = payload.size.toLong(),
        )
        val initialUpstream = RecordingDataSourceFactory(payload)
        readToEnd(
            controller.decorate(initialUpstream).createDataSource(),
            mediaDataSpec(request.uri, key),
        )
        assertEquals(8L, controller.stats().totalBytes)

        val replayUpstream = RecordingDataSourceFactory(payload)
        val cachedPrefix = readToEnd(
            controller.decorate(replayUpstream).createDataSource(),
            mediaDataSpec(request.uri, key).buildUpon()
                .setLength(8L)
                .build(),
        )

        assertArrayEquals(payload.copyOfRange(0, 8), cachedPrefix)
        assertTrue(
            "cached bytes must not fall through to upstream",
            replayUpstream.openedSpecs.isEmpty(),
        )
        assertEquals(8L, controller.stats().totalBytes)
    }

    @Test
    fun nonZeroRangeOfLargerFileIsCached() = withController(maxBytes = 8L) { controller ->
        val key = stableKey('c')
        val payload = ByteArray(16) { it.toByte() }
        val request = OpenListMediaRequestRegistry.register(
            remotePath = "/large-range.mp4",
            knownSize = payload.size.toLong(),
        )
        val upstream = RecordingDataSourceFactory(payload)
        val range = mediaDataSpec(request.uri, key).buildUpon()
            .setPosition(4L)
            .setLength(8L)
            .build()

        val actual = readToEnd(controller.decorate(upstream).createDataSource(), range)

        assertArrayEquals(payload.copyOfRange(4, 12), actual)
        assertEquals(4L, upstream.openedSpecs.single().position)
        assertEquals(8L, controller.cache.getCachedBytes(key, 4L, 8L))
        assertEquals(8L, controller.stats().totalBytes)
    }

    @Test
    fun uncachedRangeAfterCapacityStillUsesUpstreamWithoutGrowingCache() =
        withController(maxBytes = 8L) { controller ->
            val key = stableKey('d')
            val payload = ByteArray(16) { it.toByte() }
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/large-tail.mp4",
                knownSize = payload.size.toLong(),
            )
            readToEnd(
                controller.decorate(RecordingDataSourceFactory(payload)).createDataSource(),
                mediaDataSpec(request.uri, key).buildUpon()
                    .setLength(8L)
                    .build(),
            )
            val tailUpstream = RecordingDataSourceFactory(payload)
            val tail = readToEnd(
                controller.decorate(tailUpstream).createDataSource(),
                mediaDataSpec(request.uri, key).buildUpon()
                    .setPosition(8L)
                    .setLength(8L)
                    .build(),
            )

            assertArrayEquals(payload.copyOfRange(8, 16), tail)
            assertEquals(8L, tailUpstream.openedSpecs.single().position)
            assertEquals(8L, controller.stats().totalBytes)
            assertEquals(0L, controller.cache.getCachedBytes(key, 8L, 8L))
        }

    @Test
    fun fragmentedWritePublishesEverySpanAndUsesTheWholeByteBudgetBeforeSourceClose() {
        val fragmentBytes = MEDIA3_CACHE_FRAGMENT_SIZE_BYTES.toInt()
        val partialFragmentBytes = 64 * 1_024
        val maxBytes = 2L * fragmentBytes + partialFragmentBytes
        withController(maxBytes = maxBytes) { controller ->
            val key = stableKey('f')
            val payload = ByteArray(maxBytes.toInt() + 256 * 1_024) { index -> index.toByte() }
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/fragmented-large.mp4",
                knownSize = payload.size.toLong(),
            )
            val publishedSizes = mutableListOf<Long>()
            controller.attachMutationGate(
                CacheMutationGate { mutation ->
                    mutation()
                    publishedSizes += controller.stats().totalBytes
                },
            )
            val source = controller.decorate(RecordingDataSourceFactory(payload)).createDataSource()
            val spec = mediaDataSpec(request.uri, key).buildUpon()
                .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                .build()
            val actual = ByteArrayOutputStream(payload.size)
            val buffer = ByteArray(256 * 1_024)

            try {
                source.open(spec)
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    actual.write(buffer, 0, count)
                }

                assertArrayEquals(payload, actual.toByteArray())
                assertEquals(maxBytes, controller.stats().totalBytes)
                assertEquals(maxBytes, controller.cache.getCachedBytes(key, 0L, payload.size.toLong()))
                assertEquals(
                    listOf(
                        fragmentBytes.toLong(),
                        2L * fragmentBytes,
                        maxBytes,
                    ),
                    publishedSizes,
                )
                assertEquals(0, controller.stats().inProgressWriteCount)
            } finally {
                source.close()
                controller.attachMutationGate(null)
            }
        }
    }

    @Test
    fun cacheErrorIsRememberedAcrossRetriesOfTheSameLeasingDataSource() =
        withController(maxBytes = 1_024L) { controller ->
            val key = stableKey('9')
            val payload = ByteArray(16) { it.toByte() }
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/cache-error-retry.mp4",
                knownSize = payload.size.toLong(),
            )
            var rejectCachePublication = true
            var successfulCachePublications = 0
            controller.attachMutationGate(
                CacheMutationGate { mutation ->
                    if (rejectCachePublication) throw Cache.CacheException("synthetic cache failure")
                    mutation()
                    successfulCachePublications++
                },
            )
            val upstream = RecordingDataSourceFactory(payload)
            val source = controller.decorate(upstream).createDataSource()
            val spec = mediaDataSpec(request.uri, key)

            try {
                val firstFailure = runCatching { readToEnd(source, spec) }.exceptionOrNull()
                assertTrue(firstFailure is Cache.CacheException)

                rejectCachePublication = false
                val replay = readToEnd(source, spec)

                assertArrayEquals(payload, replay)
                assertEquals(2, upstream.openedSpecs.size)
                assertEquals(0, successfulCachePublications)
                assertTrue(controller.cache.getCachedSpans(key).isEmpty())
            } finally {
                controller.attachMutationGate(null)
            }
        }

    @Test
    fun disabledCacheStreamsWithoutWriting() = withController(maxBytes = 0L) { controller ->
        val key = stableKey('e')
        val payload = ByteArray(16) { it.toByte() }
        val request = OpenListMediaRequestRegistry.register(
            remotePath = "/cache-disabled.mp4",
            knownSize = payload.size.toLong(),
        )
        val upstream = RecordingDataSourceFactory(payload)

        val actual = readToEnd(
            controller.decorate(upstream).createDataSource(),
            mediaDataSpec(request.uri, key),
        )

        assertArrayEquals(payload, actual)
        assertEquals(1, upstream.openedSpecs.size)
        assertTrue(controller.cache.getCachedSpans(key).isEmpty())
        assertEquals(0L, controller.stats().totalBytes)
    }

    @Test
    fun partialCachePathStillServesAnExistingCachedSpan() =
        withController(maxBytes = 8L) { controller ->
            val key = stableKey('b')
            val payload = byteArrayOf(1, 2, 3, 4)
            putSpan(controller, key, payload)
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/larger-than-policy.mp4",
                knownSize = 16L,
            )
            val upstream = RecordingDataSourceFactory(ByteArray(16))
            val source = controller.decorate(upstream).createDataSource()
            val spec = mediaDataSpec(request.uri, key).buildUpon()
                .setLength(payload.size.toLong())
                .build()

            val actual = readToEnd(source, spec)

            assertArrayEquals(payload, actual)
            assertTrue(
                "cached bytes must not fall through to upstream",
                upstream.openedSpecs.isEmpty(),
            )
        }

    @Test
    fun expiredSpanIsRemovedBeforeAReadCanTouchAndReviveIt() {
        val now = System.currentTimeMillis()
        val clock = MutableClock(now)
        withController(
            maxBytes = 1_024L,
            maxAgeMillis = 1_000L,
            clock = clock,
        ) { controller ->
            val key = stableKey('e')
            val cached = byteArrayOf(1, 2, 3, 4)
            val fresh = byteArrayOf(9, 8, 7, 6)
            putSpan(controller, key, cached)
            val lastTouch = controller.cache.getCachedSpans(key)
                .maxOf(CacheSpan::lastTouchTimestamp)
            clock.nowMillis = lastTouch + 1_000L
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/expired.mp4",
                knownSize = fresh.size.toLong(),
            )
            val upstream = RecordingDataSourceFactory(fresh)
            val source = controller.decorate(upstream).createDataSource()

            val actual = readToEnd(source, mediaDataSpec(request.uri, key))

            assertArrayEquals(fresh, actual)
            assertEquals(1, upstream.openedSpecs.size)
        }
    }

    @Test
    fun knownLongSizeRepairsOldMetadataBeforeOpeningAtNonZeroPosition() =
        withController(maxBytes = 1_024L) { controller ->
            val key = stableKey('c')
            val gib = 1_024L * 1_024L * 1_024L
            val knownSize = 5L * gib + 37L
            val position = 4L * gib + 11L
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/large-indexed.mp4",
                knownSize = knownSize,
            )
            val staleMetadata = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(staleMetadata, 128L)
            controller.cache.applyContentMetadataMutations(key, staleMetadata)
            val upstream = RecordingDataSourceFactory(payload = null)
            val source = controller.decorate(upstream).createDataSource()

            var metadataLengthAfterOpen = C.LENGTH_UNSET.toLong()
            val openedLength = try {
                source.open(
                    mediaDataSpec(request.uri, key).buildUpon()
                        .setPosition(position)
                        .build(),
                ).also {
                    metadataLengthAfterOpen = ContentMetadata.getContentLength(
                        controller.cache.getContentMetadata(key),
                    )
                }
            } finally {
                source.close()
            }

            assertEquals(knownSize - position, openedLength)
            assertEquals(position, upstream.openedSpecs.single().position)
            assertEquals(knownSize - position, upstream.openedSpecs.single().length)
            // SimpleCache may discard metadata for a resource that still has no spans. Either the
            // corrected length or LENGTH_UNSET is safe; the stale short value must not survive.
            assertNotEquals(128L, metadataLengthAfterOpen)
            assertTrue(controller.cache.getCachedSpans(key).isEmpty())
        }

    @Test
    fun unknownSizeReadOnlyPathRemovesLegacyLengthAndRedirectMetadata() =
        withController(maxBytes = 0L) { controller ->
            val key = stableKey('d')
            val staleMetadata = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(staleMetadata, 128L)
            ContentMetadataMutations.setRedirectedUri(
                staleMetadata,
                Uri.parse("https://cdn.example/expired-signed-media"),
            )
            controller.cache.applyContentMetadataMutations(key, staleMetadata)
            val request = OpenListMediaRequestRegistry.register(
                remotePath = "/unknown-size.mp4",
                knownSize = null,
            )
            val payload = ByteArray(16) { it.toByte() }
            val upstream = RecordingDataSourceFactory(payload)
            val source = controller.decorate(upstream).createDataSource()

            val actual = readToEnd(source, mediaDataSpec(request.uri, key))

            assertArrayEquals(payload, actual)
            val migrated = controller.cache.getContentMetadata(key)
            assertNotEquals(128L, ContentMetadata.getContentLength(migrated))
            assertEquals(null, ContentMetadata.getRedirectedUri(migrated))
            assertTrue(controller.cache.getCachedSpans(key).isEmpty())
        }

    private fun withController(
        maxBytes: Long,
        maxAgeMillis: Long = Long.MAX_VALUE,
        clock: CacheClock = SystemCacheClock,
        block: (Media3CacheController) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "media-cache-data-source-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val policy = CachePolicy(
            maxBytes = maxBytes,
            maxAgeMillis = maxAgeMillis,
            maxEntries = 10,
        )
        try {
            Media3CacheController(directory, policy, clock = clock).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun putSpan(controller: Media3CacheController, key: String, payload: ByteArray) {
        val hole = controller.cache.startReadWrite(key, 0L, payload.size.toLong())
        try {
            val cacheFile = controller.cache.startFile(key, 0L, payload.size.toLong())
            cacheFile.outputStream().use { it.write(payload) }
            controller.cache.commitFile(cacheFile, payload.size.toLong())
        } finally {
            controller.cache.releaseHoleSpan(hole)
        }
    }

    private fun mediaDataSpec(uri: String, key: String): DataSpec = DataSpec.Builder()
        .setUri(Uri.parse(uri))
        .setKey(key)
        .build()

    private fun readToEnd(source: DataSource, dataSpec: DataSpec): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(7)
        try {
            source.open(dataSpec)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
        } finally {
            source.close()
        }
        return output.toByteArray()
    }

    private fun stableKey(character: Char): String =
        "openlist-content-v2:" + character.toString().repeat(64)

    private class MutableClock(var nowMillis: Long) : CacheClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class RecordingDataSourceFactory(
        private val payload: ByteArray?,
    ) : DataSource.Factory {
        val openedSpecs = mutableListOf<DataSpec>()

        override fun createDataSource(): DataSource = object : DataSource {
            private var openedUri: Uri? = null
            private var readPosition = 0
            private var bytesRemaining = 0

            override fun addTransferListener(transferListener: TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                openedSpecs += dataSpec
                openedUri = dataSpec.uri
                if (payload == null) return dataSpec.length

                readPosition = dataSpec.position.toInt()
                val available = (payload.size - readPosition).coerceAtLeast(0)
                bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                    available
                } else {
                    minOf(available.toLong(), dataSpec.length).toInt()
                }
                return bytesRemaining.toLong()
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val source = payload ?: return C.RESULT_END_OF_INPUT
                if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT
                val count = minOf(length, bytesRemaining)
                source.copyInto(buffer, offset, readPosition, readPosition + count)
                readPosition += count
                bytesRemaining -= count
                return count
            }

            override fun getUri(): Uri? = openedUri

            override fun close() {
                openedUri = null
                bytesRemaining = 0
            }
        }
    }
}
