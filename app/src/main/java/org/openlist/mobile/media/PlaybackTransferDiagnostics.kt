@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import android.net.Uri
import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.ArrayDeque
import kotlin.math.roundToLong

/** User-facing interpretation of cache activity for the active media item. */
internal enum class PlaybackCacheStatus {
    WAITING,
    BYPASS,
    HIT,
    PARTIAL,
    MISS,
}

/**
 * Immutable diagnostics intended to be polled by the player UI.
 *
 * Speeds cover the preceding two seconds. Cumulative byte counters and [hitRatio] cover only the
 * current media-item session and are reset on every item transition.
 */
internal data class PlaybackTransferSnapshot(
    val mediaId: String?,
    val networkBytesPerSecond: Long,
    val cacheBytesPerSecond: Long,
    val networkBytesRead: Long,
    val cacheBytesRead: Long,
    val hitRatio: Double?,
    val currentCacheStatus: PlaybackCacheStatus,
    val sessionCacheStatus: PlaybackCacheStatus,
    val knownSizeBytes: Long?,
    val isCacheBypassed: Boolean,
)

internal fun interface PlaybackElapsedRealtimeClock {
    fun nowMs(): Long
}

private object SystemPlaybackElapsedRealtimeClock : PlaybackElapsedRealtimeClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * Process-local playback I/O telemetry.
 *
 * The UI deliberately polls [snapshot] instead of collecting a flow: Media3 can report many byte
 * callbacks per second and no callback should schedule Compose work. Each byte callback takes one
 * small monitor, updates two counters and at most one 250 ms bucket.
 */
internal object PlaybackTransferDiagnostics {
    private val store = PlaybackTransferDiagnosticsStore(SystemPlaybackElapsedRealtimeClock)

    fun decorate(delegate: DataSource.Factory): DataSource.Factory =
        store.decorate(delegate)

    fun activate(
        mediaId: String?,
        customCacheKey: String?,
        knownSizeBytes: Long?,
    ) {
        store.activate(mediaId, customCacheKey, knownSizeBytes)
    }

    fun activateIfChanged(
        mediaId: String?,
        customCacheKey: String?,
        knownSizeBytes: Long?,
    ) {
        store.activateIfChanged(mediaId, customCacheKey, knownSizeBytes)
    }

    fun snapshot(mediaId: String?): PlaybackTransferSnapshot = store.snapshot(mediaId)

    fun reset() = store.reset()
}

internal class PlaybackTransferDiagnosticsStore(
    private val clock: PlaybackElapsedRealtimeClock,
) {
    private val lock = Any()
    private val buckets = ArrayDeque<TransferBucket>(MAX_BUCKET_COUNT)
    private var nextGeneration = 0L
    private var session: TransferSession? = null

    fun activate(
        mediaId: String?,
        customCacheKey: String?,
        knownSizeBytes: Long?,
    ) = synchronized(lock) {
        startSession(mediaId, customCacheKey, knownSizeBytes)
    }

    fun activateIfChanged(
        mediaId: String?,
        customCacheKey: String?,
        knownSizeBytes: Long?,
    ) = synchronized(lock) {
        val active = session
        if (active == null ||
            active.mediaId != mediaId ||
            active.customCacheKey != customCacheKey
        ) {
            startSession(mediaId, customCacheKey, knownSizeBytes)
        } else if (knownSizeBytes != null && active.knownSizeBytes == null) {
            active.knownSizeBytes = knownSizeBytes.takeIf { it > 0L }
        }
    }

    fun reset() = synchronized(lock) {
        nextGeneration++
        session = null
        buckets.clear()
    }

    internal fun decorate(delegate: DataSource.Factory): DataSource.Factory =
        PlaybackDiagnosticsDataSourceFactory(delegate, this)

    fun snapshot(requestedMediaId: String?): PlaybackTransferSnapshot = synchronized(lock) {
        val active = session
        if (active == null || active.mediaId != requestedMediaId) {
            return@synchronized emptySnapshot(requestedMediaId)
        }

        val nowMs = clock.nowMs()
        pruneBuckets(nowMs)
        var rollingNetworkBytes = 0L
        var rollingCacheBytes = 0L
        buckets.forEach { bucket ->
            rollingNetworkBytes = saturatingAdd(rollingNetworkBytes, bucket.networkBytes)
            rollingCacheBytes = saturatingAdd(rollingCacheBytes, bucket.cacheBytes)
        }
        val bypass = active.customCacheKey == null
        PlaybackTransferSnapshot(
            mediaId = active.mediaId,
            networkBytesPerSecond = bytesPerSecond(rollingNetworkBytes),
            cacheBytesPerSecond = bytesPerSecond(rollingCacheBytes),
            networkBytesRead = active.networkBytes,
            cacheBytesRead = active.cacheBytes,
            hitRatio = cacheHitRatio(active.networkBytes, active.cacheBytes),
            currentCacheStatus = cacheStatus(
                networkBytes = rollingNetworkBytes,
                cacheBytes = rollingCacheBytes,
                bypass = bypass,
            ),
            sessionCacheStatus = cacheStatus(
                networkBytes = active.networkBytes,
                cacheBytes = active.cacheBytes,
                bypass = bypass,
            ),
            knownSizeBytes = active.knownSizeBytes,
            isCacheBypassed = bypass,
        )
    }

    internal fun newTransferListener(): BoundPlaybackTransferListener =
        BoundPlaybackTransferListener(this)

    internal fun bindingFor(dataSpec: DataSpec): TransferBinding = synchronized(lock) {
        TransferBinding(
            generation = session?.generation ?: NO_GENERATION,
            mediaId = OpenListMediaRequestUri.mediaIdOrNull(dataSpec.uri),
            customCacheKey = dataSpec.key,
        )
    }

    internal fun record(
        binding: TransferBinding,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (bytesTransferred <= 0) return
        synchronized(lock) {
            val active = session ?: return@synchronized
            if (!binding.belongsTo(active)) return@synchronized

            val byteCount = bytesTransferred.toLong()
            if (isNetwork) {
                active.networkBytes = saturatingAdd(active.networkBytes, byteCount)
            } else {
                active.cacheBytes = saturatingAdd(active.cacheBytes, byteCount)
            }
            addToCurrentBucket(clock.nowMs(), isNetwork, byteCount)
        }
    }

    private fun startSession(
        mediaId: String?,
        customCacheKey: String?,
        knownSizeBytes: Long?,
    ) {
        nextGeneration++
        buckets.clear()
        session = mediaId?.takeIf(String::isNotBlank)?.let { activeMediaId ->
            TransferSession(
                generation = nextGeneration,
                mediaId = activeMediaId,
                customCacheKey = customCacheKey?.takeIf(String::isNotBlank),
                knownSizeBytes = knownSizeBytes?.takeIf { it > 0L },
            )
        }
    }

    private fun addToCurrentBucket(nowMs: Long, isNetwork: Boolean, byteCount: Long) {
        pruneBuckets(nowMs)
        val bucketStartMs = nowMs - Math.floorMod(nowMs, BUCKET_DURATION_MS)
        val last = buckets.peekLast()
        val bucket = when {
            last == null || last.startMs < bucketStartMs -> {
                TransferBucket(startMs = bucketStartMs).also(buckets::addLast)
            }
            last.startMs == bucketStartMs -> last
            // elapsedRealtime is monotonic. If a test/faulty clock goes backwards, discarding this
            // one sample is safer than corrupting the bounded, time-ordered window.
            else -> return
        }
        if (isNetwork) {
            bucket.networkBytes = saturatingAdd(bucket.networkBytes, byteCount)
        } else {
            bucket.cacheBytes = saturatingAdd(bucket.cacheBytes, byteCount)
        }
        while (buckets.size > MAX_BUCKET_COUNT) buckets.removeFirst()
    }

    private fun pruneBuckets(nowMs: Long) {
        val cutoffMs = nowMs - ROLLING_WINDOW_MS
        while (buckets.peekFirst()?.let { it.startMs + BUCKET_DURATION_MS <= cutoffMs } == true) {
            buckets.removeFirst()
        }
    }

    private data class TransferSession(
        val generation: Long,
        val mediaId: String,
        val customCacheKey: String?,
        var knownSizeBytes: Long?,
        var networkBytes: Long = 0L,
        var cacheBytes: Long = 0L,
    )

    private data class TransferBucket(
        val startMs: Long,
        var networkBytes: Long = 0L,
        var cacheBytes: Long = 0L,
    )

    private fun TransferBinding.belongsTo(active: TransferSession): Boolean {
        if (generation != active.generation) return false
        if (mediaId != null && mediaId != active.mediaId) return false
        return if (active.customCacheKey != null) {
            customCacheKey == active.customCacheKey
        } else {
            // HLS has no parent customCacheKey. Segment loaders may still assign their own URI
            // key, so generation + any opaque mediaId are the only identifiers available here.
            true
        }
    }

    private companion object {
        const val NO_GENERATION = -1L
        const val BUCKET_DURATION_MS = 250L
        const val ROLLING_WINDOW_MS = 2_000L
        const val MAX_BUCKET_COUNT = (ROLLING_WINDOW_MS / BUCKET_DURATION_MS).toInt() + 1

        fun bytesPerSecond(bytes: Long): Long =
            (bytes.toDouble() * 1_000.0 / ROLLING_WINDOW_MS.toDouble()).roundToLong()

        fun cacheHitRatio(networkBytes: Long, cacheBytes: Long): Double? {
            val totalBytes = saturatingAdd(networkBytes, cacheBytes)
            return totalBytes.takeIf { it > 0L }?.let { cacheBytes.toDouble() / it.toDouble() }
        }

        fun cacheStatus(
            networkBytes: Long,
            cacheBytes: Long,
            bypass: Boolean,
        ): PlaybackCacheStatus = when {
            bypass -> PlaybackCacheStatus.BYPASS
            cacheBytes > 0L && networkBytes > 0L -> PlaybackCacheStatus.PARTIAL
            cacheBytes > 0L -> PlaybackCacheStatus.HIT
            networkBytes > 0L -> PlaybackCacheStatus.MISS
            else -> PlaybackCacheStatus.WAITING
        }

        fun emptySnapshot(mediaId: String?) = PlaybackTransferSnapshot(
            mediaId = mediaId,
            networkBytesPerSecond = 0L,
            cacheBytesPerSecond = 0L,
            networkBytesRead = 0L,
            cacheBytesRead = 0L,
            hitRatio = null,
            currentCacheStatus = PlaybackCacheStatus.WAITING,
            sessionCacheStatus = PlaybackCacheStatus.WAITING,
            knownSizeBytes = null,
            isCacheBypassed = false,
        )

        fun saturatingAdd(left: Long, right: Long): Long =
            if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    }
}

internal data class TransferBinding(
    val generation: Long,
    val mediaId: String?,
    val customCacheKey: String?,
)

internal class BoundPlaybackTransferListener(
    private val store: PlaybackTransferDiagnosticsStore,
) : TransferListener {
    @Volatile
    private var binding = TransferBinding(
        generation = -1L,
        mediaId = null,
        customCacheKey = null,
    )

    fun bind(dataSpec: DataSpec) {
        binding = store.bindingFor(dataSpec)
    }

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        store.record(binding, isNetwork, bytesTransferred)
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit
}

private class PlaybackDiagnosticsDataSourceFactory(
    private val delegate: DataSource.Factory,
    private val store: PlaybackTransferDiagnosticsStore,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = PlaybackDiagnosticsDataSource(
        delegate = delegate.createDataSource(),
        listener = store.newTransferListener(),
    )
}

private class PlaybackDiagnosticsDataSource(
    private val delegate: DataSource,
    private val listener: BoundPlaybackTransferListener,
) : DataSource {
    init {
        delegate.addTransferListener(listener)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        listener.bind(dataSpec)
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders

    override fun close() = delegate.close()
}
