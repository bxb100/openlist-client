@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.data.cache

import androidx.media3.database.DatabaseProvider
import java.io.Closeable
import java.io.File
import org.openlist.mobile.core.model.CachePolicy

data class DetailedCacheStats(
    val total: CacheStats,
    val media: CacheStats,
    val blobs: CacheStats,
    /** The single user-visible policy shared by both backends and enforced on their total. */
    val policy: CachePolicy,
)

/**
 * Owns the complete-blob and seekable-media backends under one user-visible [CachePolicy].
 *
 * Each backend receives the full policy so an otherwise idle backend can use all available cache
 * capacity. Publications are serialized through [mutationGate], then aggregate LRU eviction runs
 * across both backends before the publication returns. Consequently the configured byte and entry
 * limits describe their combined contents, rather than two fixed non-borrowable partitions.
 *
 * As with either backend on its own, an entry held by an active reader is only marked for removal.
 * Aggregate trimming continues with other entries; if every remaining victim is leased, physical
 * usage can remain temporarily above the new policy until those leases close.
 */
class UnifiedCacheManager(
    val blobCache: ManagedDiskCache,
    val mediaCache: Media3CacheController,
    initialPolicy: CachePolicy,
) : Closeable {
    private val monitor = Any()
    private var closed = false
    private var currentPolicy = initialPolicy

    private val mutationGate = CacheMutationGate { mutation ->
        synchronized(monitor) {
            ensureOpenLocked()
            mutation()
            trimAggregateLocked()
        }
    }

    init {
        require(blobCache.directory.canonicalFile != mediaCache.cacheDirectory.canonicalFile) {
            "ManagedDiskCache and Media3 SimpleCache require different directories"
        }
        // Startup may load content produced under an older fixed partition. Apply the complete
        // policy first, then reconcile the two existing stores as one aggregate before exposure.
        blobCache.updatePolicy(initialPolicy)
        mediaCache.updatePolicy(initialPolicy)
        blobCache.attachMutationGate(mutationGate)
        mediaCache.attachMutationGate(mutationGate)
        synchronized(monitor) { trimAggregateLocked() }
    }

    val policy: CachePolicy
        get() = synchronized(monitor) { currentPolicy }

    fun updatePolicy(policy: CachePolicy): CacheTrimResult = synchronized(monitor) {
        ensureOpenLocked()
        val before = combinedStatsLocked()
        currentPolicy = policy
        blobCache.updatePolicy(policy)
        mediaCache.updatePolicy(policy)
        trimAggregateLocked()
        trimResultLocked(before)
    }

    fun stats(): CacheStats = detailedStats().total

    fun detailedStats(): DetailedCacheStats = synchronized(monitor) {
        ensureOpenLocked()
        val blobStats = blobCache.stats()
        val mediaStats = mediaCache.stats()
        DetailedCacheStats(
            total = combine(blobStats, mediaStats),
            media = mediaStats,
            blobs = blobStats,
            policy = currentPolicy,
        )
    }

    fun trim(): CacheTrimResult = synchronized(monitor) {
        ensureOpenLocked()
        val before = combinedStatsLocked()
        // Backend trims apply the shared TTL and independently remove any content that alone
        // exceeds the global limit. The aggregate pass handles mixed-backend overage.
        blobCache.trim()
        mediaCache.trim()
        trimAggregateLocked()
        trimResultLocked(before)
    }

    fun clear(): CacheTrimResult = synchronized(monitor) {
        ensureOpenLocked()
        val before = combinedStatsLocked()
        blobCache.clear()
        mediaCache.clear()
        trimResultLocked(before)
    }

    override fun close() = synchronized(monitor) {
        if (closed) return@synchronized
        closed = true
        blobCache.attachMutationGate(null)
        mediaCache.attachMutationGate(null)
        // Media3 owns a directory lock, so release it first during application shutdown.
        mediaCache.close()
        blobCache.close()
    }

    /** Must be called while [monitor] is held. */
    private fun trimAggregateLocked() {
        val entries = globalEntriesLocked().sortedWith(GLOBAL_LRU_ORDER)
        // Pending entries are already absent from the logical cache even though an active lease
        // may keep their physical files readable for a little longer. Counting them again would
        // over-evict newer content whenever the oldest victim is leased.
        var projectedBytes = entries
            .asSequence()
            .filterNot(GlobalEntry::pendingRemoval)
            .fold(0L) { total, entry -> saturatingAdd(total, entry.sizeBytes) }
        var projectedEntries = entries.count { !it.pendingRemoval }

        for (victim in entries) {
            if (
                projectedBytes <= currentPolicy.maxBytes &&
                projectedEntries <= currentPolicy.maxEntries
            ) {
                break
            }
            if (victim.pendingRemoval) continue

            projectedBytes = (projectedBytes - victim.sizeBytes).coerceAtLeast(0L)
            projectedEntries = (projectedEntries - 1).coerceAtLeast(0)
            when (victim.id.backend) {
                CacheBackend.BLOB -> blobCache.remove(requireNotNull(victim.blobKey))
                CacheBackend.MEDIA -> mediaCache.remove(victim.id.key)
            }
        }
    }

    /** Must be called while [monitor] is held. */
    private fun globalEntriesLocked(): List<GlobalEntry> {
        val pendingMediaKeys = mediaCache.pendingRemovalKeys()
        return buildList {
            blobCache.entries().forEach { entry ->
                add(
                    GlobalEntry(
                        id = GlobalEntryId(CacheBackend.BLOB, entry.key.diskId),
                        blobKey = entry.key,
                        sizeBytes = entry.sizeBytes,
                        lastAccessAtMillis = entry.lastAccessAtMillis,
                        createdAtMillis = entry.createdAtMillis,
                        pendingRemoval = entry.pendingRemoval,
                    ),
                )
            }
            mediaCache.entrySnapshots().forEach { entry ->
                add(
                    GlobalEntry(
                        id = GlobalEntryId(CacheBackend.MEDIA, entry.key),
                        blobKey = null,
                        sizeBytes = entry.sizeBytes,
                        lastAccessAtMillis = entry.lastAccessAtMillis,
                        // SimpleCache exposes the last touch but not a separate creation timestamp.
                        createdAtMillis = entry.lastAccessAtMillis,
                        pendingRemoval = entry.key in pendingMediaKeys,
                    ),
                )
            }
        }
    }

    /** Must be called while [monitor] is held. */
    private fun combinedStatsLocked(): CacheStats = combine(blobCache.stats(), mediaCache.stats())

    /** Must be called while [monitor] is held. */
    private fun trimResultLocked(before: CacheStats): CacheTrimResult {
        val after = combinedStatsLocked()
        return CacheTrimResult(
            removedBytes = (before.totalBytes - after.totalBytes).coerceAtLeast(0L),
            removedEntries = (before.entryCount - after.entryCount).coerceAtLeast(0),
            deferredEntries = saturatingAdd(
                blobCache.entries().count(CacheEntrySnapshot::pendingRemoval),
                mediaCache.pendingRemovalCount(),
            ),
            bytesAfter = after.totalBytes,
            entriesAfter = after.entryCount,
        )
    }

    private fun ensureOpenLocked() {
        check(!closed) { "Unified cache manager is closed" }
    }

    companion object {
        /** Both stores start with the full policy; the manager enforces it on their aggregate. */
        fun create(
            blobDirectory: File,
            mediaDirectory: File,
            policy: CachePolicy,
            clock: CacheClock = SystemCacheClock,
            databaseProvider: DatabaseProvider? = null,
        ): UnifiedCacheManager = UnifiedCacheManager(
            blobCache = ManagedDiskCache(blobDirectory, policy, clock),
            mediaCache = Media3CacheController(
                cacheDirectory = mediaDirectory,
                initialPolicy = policy,
                clock = clock,
                databaseProvider = databaseProvider,
            ),
            initialPolicy = policy,
        )

        private fun combine(first: CacheStats, second: CacheStats): CacheStats = CacheStats(
            totalBytes = saturatingAdd(first.totalBytes, second.totalBytes),
            entryCount = saturatingAdd(first.entryCount, second.entryCount),
            activeLeaseCount = saturatingAdd(first.activeLeaseCount, second.activeLeaseCount),
            inProgressWriteCount = saturatingAdd(first.inProgressWriteCount, second.inProgressWriteCount),
            expiredEntryCount = saturatingAdd(first.expiredEntryCount, second.expiredEntryCount),
        )

        private fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

        private fun saturatingAdd(left: Int, right: Int): Int =
            if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right

        private val GLOBAL_LRU_ORDER = compareBy<GlobalEntry> { it.lastAccessAtMillis }
            .thenBy { it.createdAtMillis }
            .thenBy { it.id.backend.ordinal }
            .thenBy { it.id.key }
    }
}

private enum class CacheBackend { BLOB, MEDIA }

private data class GlobalEntryId(
    val backend: CacheBackend,
    val key: String,
)

private data class GlobalEntry(
    val id: GlobalEntryId,
    val blobKey: CacheKey?,
    val sizeBytes: Long,
    val lastAccessAtMillis: Long,
    val createdAtMillis: Long,
    val pendingRemoval: Boolean,
)
