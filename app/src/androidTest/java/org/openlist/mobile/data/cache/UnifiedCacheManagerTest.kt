@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.CachePolicy

@RunWith(AndroidJUnit4::class)
class UnifiedCacheManagerTest {
    private val directories = mutableListOf<File>()

    @After
    fun cleanDirectories() {
        directories.forEach(File::deleteRecursively)
    }

    @Test
    fun pureBlobCacheCanUseTheWholeByteAndEntryBudget() = withManager(
        CachePolicy(maxBytes = 80, maxAgeMillis = Long.MAX_VALUE, maxEntries = 3),
    ) { manager ->
        repeat(3) { index -> putBlob(manager, "blob-$index", ByteArray(20) { index.toByte() }) }

        val stats = manager.detailedStats()

        assertEquals(60L, stats.total.totalBytes)
        assertEquals(3, stats.total.entryCount)
        assertEquals(stats.total, stats.blobs)
        assertEquals(0, stats.media.entryCount)
    }

    @Test
    fun pureMediaCacheCanBorrowCapacityFormerlyReservedForBlobs() = withManager(
        CachePolicy(maxBytes = 100, maxAgeMillis = Long.MAX_VALUE, maxEntries = 2),
    ) { manager ->
        cacheMedia(manager, stableKey('a'), ByteArray(80) { it.toByte() })

        val stats = manager.detailedStats()

        assertEquals(80L, stats.total.totalBytes)
        assertEquals(1, stats.total.entryCount)
        assertEquals(stats.total, stats.media)
        assertEquals(0, stats.blobs.entryCount)
    }

    @Test
    fun mixedBackendsAutomaticallyEnforceOneAggregateByteLimit() = withManager(
        CachePolicy(maxBytes = 10, maxAgeMillis = Long.MAX_VALUE, maxEntries = 10),
        // Media3 owns its span timestamp source. Epoch zero makes the blob unambiguously older.
        clock = MutableClock(0),
    ) { manager ->
        val blobKey = putBlob(manager, "older-blob", ByteArray(6) { 1 })
        val mediaKey = stableKey('b')

        cacheMedia(manager, mediaKey, ByteArray(6) { 2 })

        val stats = manager.stats()
        assertTrue(stats.totalBytes <= 10L)
        assertTrue(stats.entryCount <= 10)
        assertNull(manager.blobCache.acquire(blobKey))
        assertTrue(manager.mediaCache.cache.getCachedSpans(mediaKey).isNotEmpty())
    }

    @Test
    fun lowEntryLimitsDoNotDisableBlobCaching() {
        (1..3).forEach { limit ->
            withManager(
                CachePolicy(maxBytes = 1_000, maxAgeMillis = Long.MAX_VALUE, maxEntries = limit),
            ) { manager ->
                repeat(limit) { index -> putBlob(manager, "limit-$limit-$index", byteArrayOf(1)) }

                assertEquals(limit, manager.stats().entryCount)
                assertEquals(limit, manager.blobCache.stats().entryCount)
            }
        }
    }

    @Test
    fun mixedBackendsAutomaticallyEnforceOneAggregateEntryLimit() = withManager(
        CachePolicy(maxBytes = 1_000, maxAgeMillis = Long.MAX_VALUE, maxEntries = 3),
        clock = MutableClock(0),
    ) { manager ->
        putBlob(manager, "blob-a", byteArrayOf(1))
        putBlob(manager, "blob-b", byteArrayOf(2))

        cacheMedia(manager, stableKey('c'), byteArrayOf(3))
        cacheMedia(manager, stableKey('d'), byteArrayOf(4))

        val stats = manager.detailedStats()
        assertEquals(3, stats.total.entryCount)
        assertEquals(2, stats.media.entryCount)
        assertEquals(1, stats.blobs.entryCount)
    }

    @Test
    fun leasedGlobalVictimDoesNotCauseNewerEntriesToBeOverEvicted() = withManager(
        CachePolicy(maxBytes = 10, maxAgeMillis = Long.MAX_VALUE, maxEntries = 10),
        clock = MutableClock(0),
    ) { manager ->
        val blobKey = putBlob(manager, "leased-oldest", ByteArray(6) { 1 })
        val lease = requireNotNull(manager.blobCache.acquire(blobKey))
        val mediaKey = stableKey('e')

        try {
            cacheMedia(manager, mediaKey, ByteArray(6) { 2 })

            // The old blob is logically removed but remains readable until this lease closes.
            assertTrue(manager.blobCache.entries().single().pendingRemoval)
            assertTrue(manager.mediaCache.cache.getCachedSpans(mediaKey).isNotEmpty())
            assertEquals(2, manager.stats().entryCount)
        } finally {
            lease.close()
        }

        assertEquals(6L, manager.stats().totalBytes)
        assertEquals(1, manager.stats().entryCount)
        assertTrue(manager.mediaCache.cache.getCachedSpans(mediaKey).isNotEmpty())
    }

    private fun withManager(
        policy: CachePolicy,
        clock: CacheClock = SystemCacheClock,
        block: (UnifiedCacheManager) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "unified-cache-${UUID.randomUUID()}")
        val blobs = File(root, "blobs")
        val media = File(root, "media")
        directories += root
        UnifiedCacheManager.create(
            blobDirectory = blobs,
            mediaDirectory = media,
            policy = policy,
            clock = clock,
        ).use(block)
    }

    private fun putBlob(
        manager: UnifiedCacheManager,
        logicalId: String,
        payload: ByteArray,
    ): CacheKey {
        val key = CacheKey.namespaced("unified-test", logicalId, revision = "v1")
        assertTrue(manager.blobCache.put(key, payload.size.toLong()) { it.write(payload) })
        return key
    }

    private fun cacheMedia(
        manager: UnifiedCacheManager,
        key: String,
        payload: ByteArray,
    ) {
        val source = manager.mediaCache.decorate(
            DataSource.Factory { MemoryDataSource(payload) },
        ).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("https://cache.invalid/$key"))
            .setKey(key)
            .setLength(payload.size.toLong())
            .build()
        val buffer = ByteArray(16)
        try {
            source.open(spec)
            while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {
                // Drain the source so CacheDataSource publishes the completed span on close.
            }
        } finally {
            source.close()
        }
    }

    private fun stableKey(character: Char): String =
        "openlist-content-v2:" + character.toString().repeat(64)

    private class MutableClock(var nowMillis: Long) : CacheClock {
        override fun nowMillis(): Long = nowMillis
    }

    private class MemoryDataSource(private val payload: ByteArray) : DataSource {
        private var uri: Uri? = null
        private var position = 0
        private var remaining = 0

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            val available = (payload.size - position).coerceAtLeast(0)
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                available
            } else {
                minOf(available.toLong(), dataSpec.length).toInt()
            }
            return remaining.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0) return C.RESULT_END_OF_INPUT
            val count = minOf(length, remaining)
            payload.copyInto(buffer, offset, position, position + count)
            position += count
            remaining -= count
            return count
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            uri = null
            remaining = 0
        }
    }
}
