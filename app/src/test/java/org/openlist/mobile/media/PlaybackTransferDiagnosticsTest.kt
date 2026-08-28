package org.openlist.mobile.media

import android.net.TestUri
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackTransferDiagnosticsTest {
    @Test
    fun `stable key classifies network and cache bytes from every created data source`() {
        val clock = FakeClock()
        val store = PlaybackTransferDiagnosticsStore(clock)
        store.activate(MEDIA_ID, CACHE_KEY, knownSizeBytes = 10_000L)
        val networkFactory = FakeTransferDataSourceFactory(isNetwork = true)
        val cacheFactory = FakeTransferDataSourceFactory(isNetwork = false)
        val networkSource = store.decorate(networkFactory).createDataSource()
        val cacheSource = store.decorate(cacheFactory).createDataSource()
        val spec = dataSpec(CACHE_KEY)

        networkSource.open(spec)
        cacheSource.open(spec)
        networkFactory.sources.single().emit(600)
        cacheFactory.sources.single().emit(400)

        val snapshot = store.snapshot(MEDIA_ID)
        assertThat(networkFactory.sources.single().listenerCount).isEqualTo(1)
        assertThat(cacheFactory.sources.single().listenerCount).isEqualTo(1)
        assertThat(snapshot.networkBytesRead).isEqualTo(600L)
        assertThat(snapshot.cacheBytesRead).isEqualTo(400L)
        assertThat(snapshot.networkBytesPerSecond).isEqualTo(300L)
        assertThat(snapshot.cacheBytesPerSecond).isEqualTo(200L)
        assertThat(snapshot.hitRatio).isWithin(0.000_001).of(0.4)
        assertThat(snapshot.currentCacheStatus).isEqualTo(PlaybackCacheStatus.PARTIAL)
        assertThat(snapshot.sessionCacheStatus).isEqualTo(PlaybackCacheStatus.PARTIAL)
        assertThat(snapshot.knownSizeBytes).isEqualTo(10_000L)
        assertThat(snapshot.isCacheBypassed).isFalse()
    }

    @Test
    fun `wrong key media id and previous generation cannot pollute active item`() {
        val store = PlaybackTransferDiagnosticsStore(FakeClock())
        store.activate(MEDIA_ID, CACHE_KEY, knownSizeBytes = null)
        val validBinding = store.bindingFor(dataSpec(CACHE_KEY))

        store.record(validBinding.copy(customCacheKey = OTHER_CACHE_KEY), true, 100)
        store.record(validBinding.copy(mediaId = "subtitle-media-id"), true, 200)
        assertThat(store.snapshot(MEDIA_ID).networkBytesRead).isEqualTo(0L)

        store.record(validBinding, true, 300)
        store.activate(SECOND_MEDIA_ID, CACHE_KEY, knownSizeBytes = 20_000L)
        store.record(validBinding, true, 400)

        val snapshot = store.snapshot(SECOND_MEDIA_ID)
        assertThat(snapshot.networkBytesRead).isEqualTo(0L)
        assertThat(snapshot.cacheBytesRead).isEqualTo(0L)
        assertThat(snapshot.sessionCacheStatus).isEqualTo(PlaybackCacheStatus.WAITING)
        assertThat(snapshot.knownSizeBytes).isEqualTo(20_000L)
        assertThat(store.snapshot(MEDIA_ID).networkBytesRead).isEqualTo(0L)
    }

    @Test
    fun `HLS without cache key reports bypass while rejecting known subtitle media id`() {
        val store = PlaybackTransferDiagnosticsStore(FakeClock())
        store.activate(MEDIA_ID, customCacheKey = null, knownSizeBytes = null)
        // HLS chunk loaders may supply a segment-local key even though the parent MediaItem has no
        // customCacheKey. It remains bypassed network traffic belonging to this generation.
        val segmentBinding = store.bindingFor(dataSpec(cacheKey = "segment-local-key"))

        store.record(segmentBinding, isNetwork = true, bytesTransferred = 2_000)
        store.record(
            segmentBinding.copy(mediaId = "subtitle-media-id"),
            isNetwork = true,
            bytesTransferred = 5_000,
        )

        val snapshot = store.snapshot(MEDIA_ID)
        assertThat(snapshot.networkBytesRead).isEqualTo(2_000L)
        assertThat(snapshot.cacheBytesRead).isEqualTo(0L)
        assertThat(snapshot.hitRatio).isWithin(0.000_001).of(0.0)
        assertThat(snapshot.currentCacheStatus).isEqualTo(PlaybackCacheStatus.BYPASS)
        assertThat(snapshot.sessionCacheStatus).isEqualTo(PlaybackCacheStatus.BYPASS)
        assertThat(snapshot.isCacheBypassed).isTrue()
    }

    @Test
    fun `rolling speed becomes zero after idle while session miss remains`() {
        val clock = FakeClock()
        val store = PlaybackTransferDiagnosticsStore(clock)
        store.activate(MEDIA_ID, CACHE_KEY, knownSizeBytes = null)
        val binding = store.bindingFor(dataSpec(CACHE_KEY))

        store.record(binding, isNetwork = true, bytesTransferred = 1_000)
        assertThat(store.snapshot(MEDIA_ID).networkBytesPerSecond).isEqualTo(500L)

        clock.nowMs = 2_250L
        val idle = store.snapshot(MEDIA_ID)
        assertThat(idle.networkBytesPerSecond).isEqualTo(0L)
        assertThat(idle.currentCacheStatus).isEqualTo(PlaybackCacheStatus.WAITING)
        assertThat(idle.sessionCacheStatus).isEqualTo(PlaybackCacheStatus.MISS)
        assertThat(idle.networkBytesRead).isEqualTo(1_000L)
        assertThat(idle.hitRatio).isWithin(0.000_001).of(0.0)
    }

    @Test
    fun `waiting and cache hit ratios have explicit states`() {
        val store = PlaybackTransferDiagnosticsStore(FakeClock())
        store.activate(MEDIA_ID, CACHE_KEY, knownSizeBytes = null)

        val waiting = store.snapshot(MEDIA_ID)
        assertThat(waiting.hitRatio).isNull()
        assertThat(waiting.currentCacheStatus).isEqualTo(PlaybackCacheStatus.WAITING)
        assertThat(waiting.sessionCacheStatus).isEqualTo(PlaybackCacheStatus.WAITING)

        store.record(
            store.bindingFor(dataSpec(CACHE_KEY)),
            isNetwork = false,
            bytesTransferred = 512,
        )
        val hit = store.snapshot(MEDIA_ID)
        assertThat(hit.hitRatio).isWithin(0.000_001).of(1.0)
        assertThat(hit.currentCacheStatus).isEqualTo(PlaybackCacheStatus.HIT)
        assertThat(hit.sessionCacheStatus).isEqualTo(PlaybackCacheStatus.HIT)
    }

    @Test
    fun `playlist refresh retains session but transition and reset clear it`() {
        val store = PlaybackTransferDiagnosticsStore(FakeClock())
        store.activate(MEDIA_ID, CACHE_KEY, knownSizeBytes = null)
        store.record(
            store.bindingFor(dataSpec(CACHE_KEY)),
            isNetwork = true,
            bytesTransferred = 700,
        )

        store.activateIfChanged(MEDIA_ID, CACHE_KEY, knownSizeBytes = 9_000L)
        val retained = store.snapshot(MEDIA_ID)
        assertThat(retained.networkBytesRead).isEqualTo(700L)
        assertThat(retained.knownSizeBytes).isEqualTo(9_000L)

        store.activate(MEDIA_ID, CACHE_KEY, knownSizeBytes = 9_000L)
        assertThat(store.snapshot(MEDIA_ID).networkBytesRead).isEqualTo(0L)

        store.reset()
        val reset = store.snapshot(MEDIA_ID)
        assertThat(reset.networkBytesRead).isEqualTo(0L)
        assertThat(reset.knownSizeBytes).isNull()
        assertThat(reset.sessionCacheStatus).isEqualTo(PlaybackCacheStatus.WAITING)
    }

    private fun dataSpec(cacheKey: String?): DataSpec = DataSpec.Builder()
        .setUri(TestUri.INSTANCE)
        .apply { if (cacheKey != null) setKey(cacheKey) }
        .build()

    private class FakeClock(var nowMs: Long = 0L) : PlaybackElapsedRealtimeClock {
        override fun nowMs(): Long = nowMs
    }

    private class FakeTransferDataSourceFactory(
        private val isNetwork: Boolean,
    ) : DataSource.Factory {
        val sources = mutableListOf<FakeTransferDataSource>()

        override fun createDataSource(): DataSource = FakeTransferDataSource(isNetwork).also {
            sources += it
        }
    }

    private class FakeTransferDataSource(
        private val isNetwork: Boolean,
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var dataSpec: DataSpec? = null

        val listenerCount: Int
            get() = listeners.size

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            this.dataSpec = dataSpec
            listeners.forEach { it.onTransferInitializing(this, dataSpec, isNetwork) }
            listeners.forEach { it.onTransferStart(this, dataSpec, isNetwork) }
            return C.LENGTH_UNSET.toLong()
        }

        fun emit(bytesTransferred: Int) {
            val spec = checkNotNull(dataSpec)
            listeners.forEach {
                it.onBytesTransferred(this, spec, isNetwork, bytesTransferred)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = dataSpec?.uri

        override fun close() {
            val spec = dataSpec ?: return
            listeners.forEach { it.onTransferEnd(this, spec, isNetwork) }
            dataSpec = null
        }
    }

    private companion object {
        const val MEDIA_ID = "active-media-id"
        const val SECOND_MEDIA_ID = "next-media-id"
        const val CACHE_KEY = "openlist-content-v2:" +
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_CACHE_KEY = "openlist-content-v2:" +
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
