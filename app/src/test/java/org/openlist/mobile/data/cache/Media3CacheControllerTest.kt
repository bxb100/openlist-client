package org.openlist.mobile.data.cache

import androidx.media3.datasource.cache.CacheDataSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Media3CacheControllerTest {
    @Test
    fun `media playback never blocks on an in-flight cache span`() {
        assertThat(MEDIA3_CACHE_DATA_SOURCE_FLAGS and CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .isEqualTo(0)
    }

    @Test
    fun `media cache still ignores cache after a cache-layer fault`() {
        assertThat(MEDIA3_CACHE_DATA_SOURCE_FLAGS and CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .isEqualTo(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    @Test
    fun `media cache fragments playback spans at two mib`() {
        assertThat(MEDIA3_CACHE_FRAGMENT_SIZE_BYTES).isEqualTo(2L * 1024L * 1024L)
    }
}
