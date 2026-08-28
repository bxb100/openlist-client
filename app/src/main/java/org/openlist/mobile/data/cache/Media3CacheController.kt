@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.data.cache

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.DatabaseProvider
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.openlist.mobile.core.model.CachePolicy
import org.openlist.mobile.media.OpenListMediaRequestRegistry
import org.openlist.mobile.media.OpenListMediaRequestUri
import org.openlist.mobile.media.withKnownRemainingLength

/** Accept only identities produced by ContentKeyFactory; URLs and HLS resource keys must bypass. */
internal object Media3StableCacheKey {
    private val FORMAT = Regex("openlist-content-v2:[0-9a-f]{64}")

    fun from(value: String?): String? = value?.takeIf(FORMAT::matches)
}

internal const val MEDIA3_CACHE_FRAGMENT_SIZE_BYTES = 2L * 1024L * 1024L
// Playback must never wait indefinitely for another in-flight writer. Two MiB fragments make
// recent bytes visible quickly, while a still-open hole is safely fetched from upstream.
internal const val MEDIA3_CACHE_DATA_SOURCE_FLAGS = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR

/**
 * Media3 adapter that applies the app's three cache constraints at logical content-key level.
 *
 * Media3 may store many spans for one media item. This controller deliberately counts all spans
 * with the same stable `DataSpec.key` as one entry, while their lengths still contribute to the
 * byte limit. [decorate] also leases that key for the lifetime of each open DataSource, so trim or
 * clear never unlinks files from an active player read.
 */
class Media3CacheController(
    val cacheDirectory: File,
    initialPolicy: CachePolicy,
    private val clock: CacheClock = SystemCacheClock,
    databaseProvider: DatabaseProvider? = null,
) : Closeable {
    @Volatile
    private var currentPolicy = initialPolicy
    @Volatile
    private var mutationGate: CacheMutationGate? = null
    private val released = AtomicBoolean(false)
    private val clearEpoch = AtomicLong(0)
    private val inProgressWrites = AtomicInteger(0)
    private val trimLock = ReentrantLock()
    private val activityLock = ReentrantLock()
    private val removalFinished = activityLock.newCondition()
    private val activeKeyCounts = mutableMapOf<String, Int>()
    /**
     * Bytes accepted by open CacheDataSinks but not yet visible as committed cache spans.
     *
     * CacheDataSource can reopen the sink for several holes/fragments of one resource. Keeping
     * this accounting at controller scope prevents every new sink from receiving a fresh
     * [CachePolicy.maxBytes] allowance while an earlier sink is still being published.
     */
    private val pendingWriteBytesByKey = mutableMapOf<String, Long>()
    /** Guards optimistic committed-span snapshots against reservation/commit transitions. */
    private var writeAccountingVersion = 0L
    private val pendingRemovalKeys = mutableSetOf<String>()
    private val removingKeys = mutableSetOf<String>()

    private val stableKeyFactory = CacheKeyFactory { dataSpec ->
        requireNotNull(Media3StableCacheKey.from(dataSpec.key)) {
            "Media cache requests require an app-owned content key; signed/raw URLs are not cache keys"
        }
    }
    private val evictor = TouchingPolicyCacheEvictor { cache ->
        if (!released.get()) tryTrimFromCacheCallback(cache)
    }

    /** Exactly one SimpleCache instance owns [cacheDirectory] for this controller. */
    val cache: SimpleCache = if (databaseProvider == null) {
        SimpleCache(cacheDirectory, evictor)
    } else {
        SimpleCache(cacheDirectory, evictor, databaseProvider)
    }

    init {
        trim()
    }

    val policy: CachePolicy
        get() = currentPolicy

    /**
     * Wraps an authenticated/resolving upstream factory. MediaItem.customCacheKey must contain the
     * stable content identity before the upstream turns the logical URI into an expiring URL.
     */
    fun decorate(upstream: DataSource.Factory): DataSource.Factory {
        ensureOpen()
        val lengthAwareUpstream = DataSource.Factory {
            KnownLengthUpstreamDataSource(upstream.createDataSource())
        }
        val sinkFactory = DataSink.Factory {
            PolicyDataSink(
                controller = this,
                delegateFactory = { publishingCache ->
                    CacheDataSink.Factory()
                        .setCache(publishingCache)
                        .setFragmentSize(MEDIA3_CACHE_FRAGMENT_SIZE_BYTES)
                        .createDataSink()
                },
            )
        }
        val writableCacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(lengthAwareUpstream)
            .setCacheWriteDataSinkFactory(sinkFactory)
            .setCacheKeyFactory(stableKeyFactory)
            .setFlags(MEDIA3_CACHE_DATA_SOURCE_FLAGS)
        val readOnlyCacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(lengthAwareUpstream)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory(stableKeyFactory)
            .setFlags(MEDIA3_CACHE_DATA_SOURCE_FLAGS)

        return DataSource.Factory {
            LeasingDataSource(
                writableCacheFactory = writableCacheFactory,
                readOnlyCacheFactory = readOnlyCacheFactory,
                upstreamFactory = lengthAwareUpstream,
                controller = this,
            )
        }
    }

    fun updatePolicy(policy: CachePolicy): CacheTrimResult {
        ensureOpen()
        currentPolicy = policy
        return trim()
    }

    internal fun attachMutationGate(gate: CacheMutationGate?) {
        mutationGate = gate
    }

    internal fun entrySnapshots(): List<Media3CacheEntry> {
        ensureOpen()
        return snapshot(cache)
    }

    internal fun pendingRemovalCount(): Int = activityLock.withLock {
        pendingRemovalKeys.size
    }

    internal fun pendingRemovalKeys(): Set<String> = activityLock.withLock {
        pendingRemovalKeys.toSet()
    }

    fun stats(): CacheStats {
        ensureOpen()
        val entries = snapshot(cache)
        val activeCount = activityLock.withLock { activeKeyCounts.values.sum() }
        return CacheStats(
            totalBytes = entries.fold(0L) { total, entry -> saturatingAdd(total, entry.sizeBytes) },
            entryCount = entries.size,
            activeLeaseCount = activeCount,
            inProgressWriteCount = inProgressWrites.get(),
            expiredEntryCount = entries.count { Media3CachePolicyPlanner.isExpired(it, currentPolicy, clock.nowMillis()) },
        )
    }

    fun trim(): CacheTrimResult {
        ensureOpen()
        return trimCache(cache)
    }

    /**
     * Clears current spans. Active reads are deferred, and sinks opened before this call stop
     * accepting bytes; their partial span is removed when the final DataSource lease closes.
     */
    fun clear(): CacheTrimResult {
        ensureOpen()
        clearEpoch.incrementAndGet()
        return trimLock.withLock {
            val before = snapshot(cache)
            val keys = buildSet {
                addAll(cache.keys)
                activityLock.withLock { addAll(activeKeyCounts.keys) }
            }
            activityLock.withLock { pendingRemovalKeys.addAll(keys) }
            keys.forEach { requestRemoval(cache, it) }
            trimResult(before)
        }
    }

    /** Removes one logical media resource; returns false when an active lease deferred removal. */
    fun remove(key: String): Boolean {
        require(key.isNotBlank()) { "key must not be blank" }
        ensureOpen()
        activityLock.withLock { pendingRemovalKeys.add(key) }
        requestRemoval(cache, key)
        return activityLock.withLock { key !in pendingRemovalKeys && key !in removingKeys }
    }

    /** Releases Media3's directory lock. Stop all players/DataSources before calling this. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        clearEpoch.incrementAndGet()
        activityLock.withLock {
            pendingRemovalKeys.clear()
            removingKeys.clear()
            removalFinished.signalAll()
        }
        cache.release()
    }

    override fun close() = release()

    /**
     * CacheEvictor callbacks run while SimpleCache owns its monitor. Never wait for [trimLock]
     * there: a concurrent explicit trim can own [trimLock] while waiting for that same monitor.
     * Skipping is safe because the lock owner is itself performing a complete cache snapshot/trim.
     */
    private fun tryTrimFromCacheCallback(targetCache: Cache) {
        if (!trimLock.tryLock()) return
        try {
            trimCacheLocked(targetCache)
        } finally {
            trimLock.unlock()
        }
    }

    private fun trimCache(targetCache: Cache): CacheTrimResult = trimLock.withLock {
        trimCacheLocked(targetCache)
    }

    /** Must be called while [trimLock] is held. */
    private fun trimCacheLocked(targetCache: Cache): CacheTrimResult {
        if (released.get()) return CacheTrimResult(0, 0, 0, 0, 0)
        val before = snapshot(targetCache)
        val knownKeys = before.mapTo(mutableSetOf(), Media3CacheEntry::key)
        val pending = activityLock.withLock {
            pendingRemovalKeys.retainAll(knownKeys + activeKeyCounts.keys)
            pendingRemovalKeys.toSet()
        }
        val selected = Media3CachePolicyPlanner.keysToRemove(
            entries = before,
            policy = currentPolicy,
            nowMillis = clock.nowMillis(),
            alreadyPending = pending,
        )
        selected.forEach { requestRemoval(targetCache, it) }
        return trimResult(before, targetCache)
    }

    private fun trimResult(
        before: List<Media3CacheEntry>,
        targetCache: Cache = cache,
    ): CacheTrimResult {
        val after = snapshot(targetCache)
        val beforeBytes = before.fold(0L) { total, entry -> saturatingAdd(total, entry.sizeBytes) }
        val afterBytes = after.fold(0L) { total, entry -> saturatingAdd(total, entry.sizeBytes) }
        val remainingKeys = after.mapTo(mutableSetOf(), Media3CacheEntry::key)
        val deferred = activityLock.withLock {
            pendingRemovalKeys.count { it in remainingKeys || activeKeyCounts.containsKey(it) }
        }
        return CacheTrimResult(
            removedBytes = (beforeBytes - afterBytes).coerceAtLeast(0),
            removedEntries = (before.size - after.size).coerceAtLeast(0),
            deferredEntries = deferred,
            bytesAfter = afterBytes,
            entriesAfter = after.size,
        )
    }

    private fun requestRemoval(targetCache: Cache, key: String) {
        val removeNow = activityLock.withLock {
            if ((activeKeyCounts[key] ?: 0) > 0) {
                pendingRemovalKeys.add(key)
                false
            } else if (!removingKeys.add(key)) {
                false
            } else {
                true
            }
        }
        if (!removeNow) return

        try {
            targetCache.removeResource(key)
        } finally {
            activityLock.withLock {
                removingKeys.remove(key)
                pendingRemovalKeys.remove(key)
                removalFinished.signalAll()
            }
        }
    }

    /** False means an older lease is pending deletion and this new read must bypass the cache. */
    private fun retainKey(key: String): Boolean = activityLock.withLock {
            while (key in removingKeys && !released.get()) removalFinished.awaitUninterruptibly()
            check(!released.get()) { "Media cache is released" }
            if (key in pendingRemovalKeys) return@withLock false
            activeKeyCounts[key] = (activeKeyCounts[key] ?: 0) + 1
            true
    }

    /**
     * Enforces TTL before SimpleCache can touch a span and refresh its last-access timestamp.
     * Expired active content is marked for deletion and this new reader bypasses it; inactive
     * content is removed synchronously before the upstream request starts.
     */
    private fun retainFreshKey(key: String): Boolean {
        val entry = Media3CachePolicyPlanner.aggregate(
            cache.getCachedSpans(key).map { span ->
                Media3SpanSnapshot(
                    key = key,
                    length = span.length,
                    lastTouchTimestamp = span.lastTouchTimestamp,
                )
            },
        ).singleOrNull()
        if (
            entry != null &&
            Media3CachePolicyPlanner.isExpired(entry, currentPolicy, clock.nowMillis())
        ) {
            activityLock.withLock { pendingRemovalKeys.add(key) }
            requestRemoval(cache, key)
            return false
        }
        return retainKey(key)
    }

    private fun releaseKey(key: String) {
        var shouldRemove = false
        activityLock.withLock {
            val count = activeKeyCounts[key] ?: return
            check(count > 0) { "Unbalanced Media3 cache lease" }
            if (count == 1) {
                activeKeyCounts.remove(key)
                if (key in pendingRemovalKeys && !released.get() && removingKeys.add(key)) {
                    shouldRemove = true
                }
            } else {
                activeKeyCounts[key] = count - 1
            }
        }

        if (shouldRemove) {
            try {
                cache.removeResource(key)
            } finally {
                activityLock.withLock {
                    removingKeys.remove(key)
                    pendingRemovalKeys.remove(key)
                    removalFinished.signalAll()
                }
            }
        }
        if (!released.get()) trimCache(cache)
    }

    private fun writeEpoch(): Long = clearEpoch.get()

    private fun acceptsRequestWrite(): Boolean = currentPolicy.acceptsWrites

    /**
     * Repairs metadata written by the former open-ended-206 path before CacheDataSource trusts it.
     *
     * The old source could persist the first capped segment length as the whole resource length,
     * even when PolicyDataSink declined every byte. Known OpenList sizes replace that value. For
     * an unknown-size item, a pre-migration length is removed once and learned again from the
     * strict segmented source. Persisted redirected URIs are also removed because they may contain
     * an expired signed raw URL.
     */
    private fun migrateContentMetadata(key: String, knownSize: Long?) {
        val metadata = cache.getContentMetadata(key)
        val storedVersion = metadata.get(RANGE_METADATA_VERSION_KEY, 0L)
        val storedLength = ContentMetadata.getContentLength(metadata)
        val redirectedUri = ContentMetadata.getRedirectedUri(metadata)
        val requiresMigration = storedVersion < RANGE_METADATA_VERSION
        val requiresKnownLengthCorrection = knownSize != null && storedLength != knownSize
        if (!requiresMigration && !requiresKnownLengthCorrection && redirectedUri == null) return

        val mutations = ContentMetadataMutations()
            .set(RANGE_METADATA_VERSION_KEY, RANGE_METADATA_VERSION)
        when {
            knownSize != null -> ContentMetadataMutations.setContentLength(mutations, knownSize)
            requiresMigration -> ContentMetadataMutations.setContentLength(mutations, C.LENGTH_UNSET.toLong())
        }
        if (redirectedUri != null) {
            ContentMetadataMutations.setRedirectedUri(mutations, null)
        }
        cache.applyContentMetadataMutations(key, mutations)
    }

    private fun mayWrite(epoch: Long, key: String): Boolean =
        !released.get() && epoch == clearEpoch.get() && currentPolicy.acceptsWrites &&
            activityLock.withLock { key !in pendingRemovalKeys && key !in removingKeys }

    /**
     * Reserves bytes against this logical content key before forwarding them to CacheDataSink.
     *
     * A media object's declared size is not a write prerequisite: a multi-gigabyte file may keep
     * the sparse ranges that were actually read. The retained committed spans plus unpublished
     * writes are nevertheless capped at the current byte policy, so repeated ranges/sink opens
     * cannot grow one object without bound. CacheDataSource only opens a sink for a cache hole,
     * therefore every accepted byte is new cached coverage rather than an overwrite.
     */
    private fun reserveWriteBytes(epoch: Long, key: String, requestedBytes: Int): Int {
        if (requestedBytes <= 0 || released.get() || epoch != clearEpoch.get()) return 0
        while (true) {
            val observedVersion = activityLock.withLock { writeAccountingVersion }
            val committedBytes = cache.getCachedSpans(key).fold(0L) { total, span ->
                saturatingAdd(total, span.length)
            }
            val result = activityLock.withLock {
                if (writeAccountingVersion != observedVersion) return@withLock null
                val policy = currentPolicy
                if (
                    released.get() || epoch != clearEpoch.get() || !policy.acceptsWrites ||
                    key in pendingRemovalKeys || key in removingKeys
                ) {
                    return@withLock 0
                }
                val pendingBytes = pendingWriteBytesByKey[key] ?: 0L
                val remaining = (policy.maxBytes - saturatingAdd(committedBytes, pendingBytes))
                    .coerceAtLeast(0L)
                val accepted = minOf(requestedBytes.toLong(), remaining).toInt()
                if (accepted > 0) {
                    pendingWriteBytesByKey[key] = saturatingAdd(pendingBytes, accepted.toLong())
                    writeAccountingVersion++
                }
                accepted
            }
            if (result != null) return result
        }
    }

    private fun releaseWriteBytes(key: String, releasedBytes: Long) {
        if (releasedBytes <= 0) return
        activityLock.withLock {
            val pending = pendingWriteBytesByKey[key] ?: return@withLock
            check(pending >= releasedBytes) { "Unbalanced Media3 cache write reservation" }
            val remaining = pending - releasedBytes
            if (remaining == 0L) pendingWriteBytesByKey.remove(key)
            else pendingWriteBytesByKey[key] = remaining
            writeAccountingVersion++
        }
    }

    private fun onWriteStarted() {
        inProgressWrites.incrementAndGet()
    }

    private fun onWriteFinished() {
        inProgressWrites.decrementAndGet()
    }

    private fun publishCacheWrite(mutation: () -> Unit) {
        mutationGate?.mutateAndTrim(mutation) ?: mutation()
    }

    private fun ensureOpen() {
        check(!released.get()) { "Media cache is released" }
    }

    private fun snapshot(targetCache: Cache): List<Media3CacheEntry> =
        Media3CachePolicyPlanner.aggregate(
            targetCache.keys.flatMap { key ->
                targetCache.getCachedSpans(key).map { span ->
                    Media3SpanSnapshot(
                        key = key,
                        length = span.length,
                        lastTouchTimestamp = span.lastTouchTimestamp,
                    )
                }
            },
        )

    private class LeasingDataSource(
        writableCacheFactory: DataSource.Factory,
        readOnlyCacheFactory: DataSource.Factory,
        upstreamFactory: DataSource.Factory,
        private val controller: Media3CacheController,
    ) : DataSource {
        // CacheDataSource keeps FLAG_IGNORE_CACHE_ON_ERROR state on the instance. Reusing these
        // delegates across open/close retries prevents a broken cache from being retried forever.
        private val writableCacheDataSource = writableCacheFactory.createDataSource()
        private val readOnlyCacheDataSource = readOnlyCacheFactory.createDataSource()
        private val upstreamDataSource = upstreamFactory.createDataSource()
        private var leasedKey: String? = null
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            writableCacheDataSource.addTransferListener(transferListener)
            readOnlyCacheDataSource.addTransferListener(transferListener)
            upstreamDataSource.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            check(delegate == null) { "DataSource is already open" }
            // HLS manifests/segments intentionally have no app-owned content key. Bypass rather
            // than letting CacheDataSource derive a persistent key from a possibly signed URL.
            val key = Media3StableCacheKey.from(dataSpec.key)
            val cacheAllowed = key != null && controller.retainFreshKey(key)
            if (cacheAllowed) leasedKey = key
            return try {
                val knownSize = OpenListMediaRequestUri.mediaIdOrNull(dataSpec.uri)
                    ?.let(OpenListMediaRequestRegistry::detailsOrNull)
                    ?.knownSize
                if (cacheAllowed) {
                    controller.migrateContentMetadata(requireNotNull(key), knownSize)
                }
                val selected = when {
                    !cacheAllowed -> upstreamDataSource
                    controller.acceptsRequestWrite() -> writableCacheDataSource
                    else -> readOnlyCacheDataSource
                }
                delegate = selected
                val selectedDataSpec = if (cacheAllowed) {
                    dataSpec.withKnownRemainingLength(knownSize)
                } else {
                    dataSpec
                }
                selected.open(selectedDataSpec)
            } catch (error: Throwable) {
                val selected = delegate
                delegate = null
                runCatching { selected?.close() }
                val retainedKey = leasedKey
                leasedKey = null
                if (retainedKey != null) controller.releaseKey(retainedKey)
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            checkNotNull(delegate) { "DataSource is not open" }.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders.orEmpty()

        override fun close() {
            val selected = delegate
            delegate = null
            val key = leasedKey
            leasedKey = null
            try {
                selected?.close()
            } finally {
                if (key != null) controller.releaseKey(key)
            }
        }
    }

    /**
     * CacheDataSource may create its own miss subrequests and drop the caller's explicit length.
     * Reapplying the known remainder here keeps tail reads seek-friendly for large indexed media.
     */
    private class KnownLengthUpstreamDataSource(
        private val delegate: DataSource,
    ) : DataSource by delegate {
        override fun open(dataSpec: DataSpec): Long {
            val knownSize = OpenListMediaRequestUri.mediaIdOrNull(dataSpec.uri)
                ?.let(OpenListMediaRequestRegistry::detailsOrNull)
                ?.knownSize
            return delegate.open(dataSpec.withKnownRemainingLength(knownSize))
        }
    }

    private class PolicyDataSink(
        private val controller: Media3CacheController,
        private val delegateFactory: (Cache) -> DataSink,
    ) : DataSink {
        private var delegate: DataSink? = null
        private var epoch = Long.MIN_VALUE
        private var key = ""
        private var reservedBytes = 0L
        private var counted = false
        private var isOpen = false

        override fun open(dataSpec: DataSpec) {
            check(!isOpen) { "DataSink is already open" }
            isOpen = true
            epoch = controller.writeEpoch()
            key = requireNotNull(dataSpec.key?.takeIf(String::isNotBlank)) {
                "Media cache writes require a stable DataSpec.key"
            }
            reservedBytes = 0
            if (!controller.mayWrite(epoch, key)) return

            val created = delegateFactory(
                PublishingCache(
                    delegate = controller.cache,
                    controller = controller,
                    onCommitted = ::onCacheFileCommitted,
                ),
            )
            delegate = created
            counted = true
            controller.onWriteStarted()
            try {
                created.open(dataSpec)
            } catch (error: Throwable) {
                finishDelegate()
                isOpen = false
                throw error
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            val target = delegate ?: return
            val accepted = controller.reserveWriteBytes(epoch, key, length)
            if (accepted <= 0) {
                finishDelegate()
                return
            }

            reservedBytes += accepted
            try {
                target.write(buffer, offset, accepted)
            } catch (error: Throwable) {
                finishDelegate()
                throw error
            }
            if (accepted < length) finishDelegate()
        }

        override fun close() {
            if (!isOpen) return
            isOpen = false
            finishDelegate()
        }

        private fun finishDelegate() {
            val target = delegate
            delegate = null
            try {
                target?.close()
            } finally {
                if (reservedBytes > 0) {
                    controller.releaseWriteBytes(key, reservedBytes)
                    reservedBytes = 0
                }
                if (counted) {
                    counted = false
                    controller.onWriteFinished()
                }
            }
        }

        private fun onCacheFileCommitted(length: Long) {
            if (length <= 0) return
            check(reservedBytes >= length) { "Committed more Media3 cache bytes than reserved" }
            reservedBytes -= length
            controller.releaseWriteBytes(key, length)
        }
    }

    /**
     * CacheDataSink commits a full fragment from inside write(), before its DataSink is closed.
     * Intercept that actual publication boundary so every span is committed through the aggregate
     * mutation gate and stops counting as an unpublished reservation as soon as it is durable.
     */
    private class PublishingCache(
        private val delegate: Cache,
        private val controller: Media3CacheController,
        private val onCommitted: (Long) -> Unit,
    ) : Cache by delegate {
        override fun commitFile(file: File, length: Long) {
            controller.publishCacheWrite {
                delegate.commitFile(file, length)
                onCommitted(length)
            }
        }
    }

    /** No span eviction of its own; touches are enabled so lastTouchTimestamp is sliding. */
    private class TouchingPolicyCacheEvictor(
        private val onMutation: (Cache) -> Unit,
    ) : CacheEvictor {
        override fun requiresCacheSpanTouches(): Boolean = true

        override fun onCacheInitialized() = Unit

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) =
            onMutation(cache)

        override fun onSpanAdded(cache: Cache, span: CacheSpan) = onMutation(cache)

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) =
            onMutation(cache)
    }

    private companion object {
        const val RANGE_METADATA_VERSION_KEY = "custom_openlist_range_metadata_version"
        const val RANGE_METADATA_VERSION = 1L

        fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}
