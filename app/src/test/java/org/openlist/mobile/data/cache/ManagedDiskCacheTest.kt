package org.openlist.mobile.data.cache

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openlist.mobile.core.model.CachePolicy

class ManagedDiskCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val generousPolicy = CachePolicy(
        maxBytes = 10_000,
        maxAgeMillis = 10_000,
        maxEntries = 100,
    )

    @Test
    fun commitPublishesAtomicallyAndSurvivesRestart() {
        val root = temporaryFolder.newFolder("cache")
        val clock = FakeClock(1_000)
        val key = key("song")
        val cache = ManagedDiskCache(root, generousPolicy, clock)
        val write = cache.beginWrite(key, expectedBytes = 5)!!

        write.outputStream.write("hello".toByteArray())
        assertThat(root.listFiles().orEmpty().map(File::getName))
            .containsExactly("${key.diskId}.part")
        assertThat(write.commit()).isTrue()

        assertThat(root.listFiles().orEmpty().none { it.name.endsWith(".part") }).isTrue()
        assertThat(cache.stats().totalBytes).isEqualTo(5)
        assertThat(read(cache, key)).isEqualTo("hello")
        cache.close()

        val reopened = ManagedDiskCache(root, generousPolicy, clock)
        assertThat(read(reopened, key)).isEqualTo("hello")
        assertThat(reopened.stats().entryCount).isEqualTo(1)
        reopened.close()
    }

    @Test
    fun abortedAndEmptyWritesNeverBecomeEntries() {
        val cache = newCache()
        val abortedKey = key("aborted")
        val emptyKey = key("empty")

        cache.beginWrite(abortedKey)!!.apply {
            outputStream.write(byteArrayOf(1, 2, 3))
            abort()
        }
        val empty = cache.beginWrite(emptyKey)!!

        assertThat(empty.commit()).isFalse()
        assertThat(cache.stats().entryCount).isEqualTo(0)
        assertThat(cache.directory.listFiles().orEmpty().none { it.name.endsWith(".part") }).isTrue()
    }

    @Test
    fun callerMayCloseOutputStreamBeforeCommit() {
        val cache = newCache()
        val key = key("closed-output")
        val write = cache.beginWrite(key)!!
        write.outputStream.use { it.write("complete".toByteArray()) }

        assertThat(write.commit()).isTrue()
        assertThat(read(cache, key)).isEqualTo("complete")
    }

    @Test
    fun expectedObjectLargerThanCapacityBypassesCache() {
        val cache = newCache(CachePolicy(maxBytes = 4, maxAgeMillis = 1_000, maxEntries = 2))

        assertThat(cache.beginWrite(key("large"), expectedBytes = 5)).isNull()
        assertThat(cache.put(key("unknown-size")) { it.write(ByteArray(5)) }).isFalse()
        assertThat(cache.stats().entryCount).isEqualTo(0)
    }

    @Test
    fun declaredLengthMustMatchTheCompleteBlobAtCommit() {
        val cache = newCache()
        val key = key("short-response")
        val write = cache.beginWrite(key, expectedBytes = 5)!!

        write.outputStream.write(byteArrayOf(1, 2, 3, 4))

        assertThat(write.commit()).isFalse()
        assertThat(cache.acquire(key)).isNull()
        assertThat(cache.directory.listFiles().orEmpty().none { it.name.endsWith(".part") }).isTrue()
    }

    @Test
    fun unknownLengthWriterCannotGrowPartPastCurrentByteLimit() {
        val cache = newCache(CachePolicy(maxBytes = 4, maxAgeMillis = 1_000, maxEntries = 2))
        val key = key("chunked-response")
        val write = cache.beginWrite(key)!!

        write.outputStream.write(byteArrayOf(1, 2, 3, 4))
        write.outputStream.flush()
        val part = File(cache.directory, "${key.diskId}.part")
        assertThat(part.length()).isEqualTo(4L)

        assertThrows(IOException::class.java) { write.outputStream.write(5) }
        assertThat(part.length()).isAtMost(4L)
        assertThat(write.commit()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(cache.stats().entryCount).isEqualTo(0)
    }

    @Test
    fun declaredLengthIsAlsoAStreamingHardLimit() {
        val cache = newCache()
        val key = key("lying-length")
        val write = cache.beginWrite(key, expectedBytes = 3)!!

        assertThrows(IOException::class.java) {
            write.outputStream.write(byteArrayOf(1, 2, 3, 4))
        }

        val part = File(cache.directory, "${key.diskId}.part")
        assertThat(part.length()).isAtMost(3L)
        assertThat(write.commit()).isFalse()
        assertThat(part.exists()).isFalse()
    }

    @Test
    fun reducingByteLimitInvalidatesAnOpenWriterThatAlreadyExceedsIt() {
        val cache = newCache(CachePolicy(maxBytes = 10, maxAgeMillis = 1_000, maxEntries = 2))
        val key = key("policy-reduction")
        val write = cache.beginWrite(key)!!
        write.outputStream.write(ByteArray(6))
        write.outputStream.flush()
        val part = File(cache.directory, "${key.diskId}.part")
        assertThat(part.length()).isEqualTo(6L)

        cache.updatePolicy(CachePolicy(maxBytes = 4, maxAgeMillis = 1_000, maxEntries = 2))

        assertThat(part.exists()).isFalse()
        assertThrows(IOException::class.java) { write.outputStream.write(1) }
        assertThat(write.commit()).isFalse()
        assertThat(cache.stats().entryCount).isEqualTo(0)
    }

    @Test
    fun slidingTtlExpiresAtBoundary() {
        val clock = FakeClock(1_000)
        val cache = newCache(
            policy = CachePolicy(maxBytes = 100, maxAgeMillis = 1_000, maxEntries = 10),
            clock = clock,
        )
        val key = key("ttl")
        write(cache, key, "value")

        clock.advance(999)
        assertThat(read(cache, key)).isEqualTo("value")
        clock.advance(999)
        assertThat(read(cache, key)).isEqualTo("value")
        clock.advance(1_000)

        assertThat(cache.acquire(key)).isNull()
        assertThat(cache.stats().entryCount).isEqualTo(0)
    }

    @Test
    fun maxEntriesUsesGlobalLru() {
        val clock = FakeClock(1_000)
        val cache = newCache(
            policy = CachePolicy(maxBytes = 100, maxAgeMillis = 10_000, maxEntries = 2),
            clock = clock,
        )
        val a = key("a")
        val b = key("b")
        val c = key("c")
        write(cache, a, "a")
        clock.advance(1)
        write(cache, b, "b")
        clock.advance(1)
        assertThat(read(cache, a)).isEqualTo("a")
        clock.advance(1)
        write(cache, c, "c")

        assertThat(cache.acquire(b)).isNull()
        assertThat(read(cache, a)).isEqualTo("a")
        assertThat(read(cache, c)).isEqualTo("c")
        assertThat(cache.stats().entryCount).isEqualTo(2)
    }

    @Test
    fun maxBytesEvictsEvenWhenEntryCountIsBelowLimit() {
        val clock = FakeClock(1_000)
        val cache = newCache(
            policy = CachePolicy(maxBytes = 5, maxAgeMillis = 10_000, maxEntries = 10),
            clock = clock,
        )
        val old = key("old")
        val recent = key("recent")
        write(cache, old, "1234")
        clock.advance(1)
        write(cache, recent, "5678")

        assertThat(cache.acquire(old)).isNull()
        assertThat(read(cache, recent)).isEqualTo("5678")
        assertThat(cache.stats().totalBytes).isEqualTo(4)
    }

    @Test
    fun everyZeroLimitMeansZeroCapacity() {
        val zeroPolicies = listOf(
            CachePolicy(maxBytes = 0, maxAgeMillis = 1_000, maxEntries = 10),
            CachePolicy(maxBytes = 100, maxAgeMillis = 0, maxEntries = 10),
            CachePolicy(maxBytes = 100, maxAgeMillis = 1_000, maxEntries = 0),
        )

        zeroPolicies.forEachIndexed { index, zeroPolicy ->
            val cache = newCache(folderName = "zero-$index")
            write(cache, key("existing-$index"), "data")

            cache.updatePolicy(zeroPolicy)

            assertThat(cache.stats().entryCount).isEqualTo(0)
            assertThat(cache.beginWrite(key("new-$index"))).isNull()
            cache.close()
        }
    }

    @Test
    fun activeLeaseIsReadableUntilReleaseThenDeferredLruRemovalRuns() {
        val clock = FakeClock(1_000)
        val cache = newCache(clock = clock)
        val leasedKey = key("leased")
        val otherKey = key("other")
        write(cache, leasedKey, "leased")
        val lease = cache.acquire(leasedKey)!!
        clock.advance(1)
        write(cache, otherKey, "other")

        val result = cache.updatePolicy(generousPolicy.copy(maxEntries = 1))

        assertThat(result.deferredEntries).isEqualTo(1)
        assertThat(cache.stats().entryCount).isEqualTo(2)
        assertThat(cache.acquire(leasedKey)).isNull()
        assertThat(lease.openInputStream().bufferedReader().use { it.readText() }).isEqualTo("leased")

        lease.close()

        assertThat(cache.stats().entryCount).isEqualTo(1)
        assertThat(read(cache, otherKey)).isEqualTo("other")
    }

    @Test
    fun clearDefersLeasedEntryAndInvalidatesInProgressWrite() {
        val cache = newCache()
        val existing = key("existing")
        val staged = key("staged")
        write(cache, existing, "existing")
        val lease = cache.acquire(existing)!!
        val write = cache.beginWrite(staged)!!
        write.outputStream.write("staged".toByteArray())

        val result = cache.clear()

        assertThat(result.deferredEntries).isEqualTo(1)
        assertThat(cache.stats().activeLeaseCount).isEqualTo(1)
        assertThat(readFromLease(lease)).isEqualTo("existing")
        assertThat(write.commit()).isFalse()
        assertThat(cache.stats().inProgressWriteCount).isEqualTo(0)

        lease.close()
        assertThat(cache.stats().entryCount).isEqualTo(0)
    }

    @Test
    fun duplicateWriteForSameKeyIsRejected() {
        val cache = newCache()
        val key = key("same")
        val first = cache.beginWrite(key)!!

        assertThat(cache.beginWrite(key)).isNull()

        first.abort()
        cache.beginWrite(key)!!.abort()
    }

    @Test
    fun startupRemovesPartsUnindexedBlobsAndCorruptMetadata() {
        val root = temporaryFolder.newFolder("orphans")
        val orphanId = "a".repeat(64)
        val corruptId = "b".repeat(64)
        File(root, "$orphanId.blob").writeText("orphan")
        File(root, "$orphanId.part").writeText("partial")
        File(root, "$corruptId.blob").writeText("blob")
        File(root, "$corruptId.meta").writeText("not metadata")
        File(root, "$corruptId.meta.part").writeText("partial metadata")
        val unrelated = File(root, "keep-me.txt").apply { writeText("owned elsewhere") }

        val cache = ManagedDiskCache(root, generousPolicy, FakeClock(1_000))

        assertThat(cache.startupOrphansRemoved).isAtLeast(5)
        assertThat(root.listFiles().orEmpty().map(File::getName)).containsExactly(unrelated.name)
        assertThat(cache.stats().entryCount).isEqualTo(0)
    }

    @Test
    fun startupRejectsBlobWhoseLengthNoLongerMatchesMetadata() {
        val root = temporaryFolder.newFolder("corrupt-length")
        val key = key("value")
        ManagedDiskCache(root, generousPolicy, FakeClock(1_000)).use { cache ->
            write(cache, key, "valid")
        }
        File(root, "${key.diskId}.blob").appendText("corruption")

        val reopened = ManagedDiskCache(root, generousPolicy, FakeClock(1_000))

        assertThat(reopened.acquire(key)).isNull()
        assertThat(reopened.stats().entryCount).isEqualTo(0)
    }

    private fun newCache(
        policy: CachePolicy = generousPolicy,
        clock: FakeClock = FakeClock(1_000),
        folderName: String = "cache-${System.nanoTime()}",
    ): ManagedDiskCache = ManagedDiskCache(temporaryFolder.newFolder(folderName), policy, clock)

    private fun key(id: String): CacheKey = CacheKey.namespaced("test", id, revision = "v1")

    private fun write(cache: ManagedDiskCache, key: CacheKey, value: String) {
        assertThat(cache.put(key, value.toByteArray().size.toLong()) { output ->
            output.write(value.toByteArray())
        }).isTrue()
    }

    private fun read(cache: ManagedDiskCache, key: CacheKey): String? =
        cache.acquire(key)?.use(::readFromLease)

    private fun readFromLease(lease: CacheLease): String =
        lease.openInputStream().bufferedReader().use { it.readText() }

    private class FakeClock(var nowMillis: Long) : CacheClock {
        override fun nowMillis(): Long = nowMillis

        fun advance(millis: Long) {
            nowMillis += millis
        }
    }
}
