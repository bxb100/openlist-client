package org.openlist.mobile.media

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackScreenAwakePolicyTest {
    @Test
    fun `playing video keeps the screen awake`() {
        assertTrue(
            shouldKeepVideoScreenAwake(
                isVideo = true,
                isPlaying = true,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
            ),
        )
    }

    @Test
    fun `video buffering with playback intent keeps the screen awake`() {
        assertTrue(
            shouldKeepVideoScreenAwake(
                isVideo = true,
                isPlaying = false,
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
            ),
        )
    }

    @Test
    fun `paused buffering video allows the screen to sleep`() {
        assertFalse(
            shouldKeepVideoScreenAwake(
                isVideo = true,
                isPlaying = false,
                playWhenReady = false,
                playbackState = Player.STATE_BUFFERING,
            ),
        )
    }

    @Test
    fun `suppressed or ended video allows the screen to sleep`() {
        assertFalse(
            shouldKeepVideoScreenAwake(
                isVideo = true,
                isPlaying = false,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
            ),
        )
        assertFalse(
            shouldKeepVideoScreenAwake(
                isVideo = true,
                isPlaying = false,
                playWhenReady = true,
                playbackState = Player.STATE_ENDED,
            ),
        )
    }

    @Test
    fun `audio playback does not keep the screen awake`() {
        assertFalse(
            shouldKeepVideoScreenAwake(
                isVideo = false,
                isPlaying = true,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
            ),
        )
    }
}
