@file:androidx.annotation.OptIn(
    markerClass = [androidx.media3.common.util.UnstableApi::class],
)

package org.openlist.mobile.media

import androidx.media3.exoplayer.analytics.PlayerId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpenListMedia3RuntimeFactoryTest {
    @Test
    fun `load control retains a bounded back buffer for instant short rewinds`() {
        val loadControl = OpenListMedia3RuntimeFactory.createLoadControl()

        assertThat(loadControl.getBackBufferDurationUs(PlayerId.UNSET))
            .isEqualTo(PLAYBACK_BACK_BUFFER_MS * 1_000L)
        assertThat(loadControl.retainBackBufferFromKeyframe(PlayerId.UNSET)).isFalse()
    }
}
