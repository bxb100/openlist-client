package org.openlist.mobile.media

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackToggleTest {
    @Test
    fun `finished playback seeks to start before replay even when play intent is still true`() {
        val events = toggle(playWhenReady = true, playbackState = Player.STATE_ENDED)

        assertThat(events).containsExactly("seek to start", "prepare and play").inOrder()
        assertThat(playbackShowsPause(true, Player.STATE_ENDED)).isFalse()
    }

    @Test
    fun `buffering with play intent pauses immediately rather than starting again`() {
        val events = toggle(playWhenReady = true, playbackState = Player.STATE_BUFFERING)

        assertThat(events).containsExactly("pause")
        assertThat(playbackShowsPause(true, Player.STATE_BUFFERING)).isTrue()
    }

    @Test
    fun `idle after an error retries directly even when old play intent remains true`() {
        val events = toggle(playWhenReady = true, playbackState = Player.STATE_IDLE)

        assertThat(events).containsExactly("prepare and play")
        assertThat(playbackShowsPause(true, Player.STATE_IDLE)).isFalse()
    }

    @Test
    fun `paused media resumes at its existing position`() {
        assertThat(toggle(playWhenReady = false, playbackState = Player.STATE_READY))
            .containsExactly("prepare and play")
    }

    private fun toggle(playWhenReady: Boolean, @Player.State playbackState: Int): List<String> {
        val events = mutableListOf<String>()
        performPlaybackToggle(
            playWhenReady = playWhenReady,
            playbackState = playbackState,
            pause = { events += "pause" },
            seekToStart = { events += "seek to start" },
            play = { events += "prepare and play" },
        )
        return events
    }
}
