package org.openlist.mobile.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackDismissalTest {
    @Test
    fun `video pauses and preserves its queue before overlay is dismissed`() {
        val calls = mutableListOf<String>()

        performPlaybackDismissal(
            isVideo = true,
            pauseVideo = { calls += "pause" },
            dismiss = { calls += "dismiss" },
        )

        assertThat(calls).containsExactly("pause", "dismiss").inOrder()
    }

    @Test
    fun `audio dismissal preserves background playback`() {
        val calls = mutableListOf<String>()

        performPlaybackDismissal(
            isVideo = false,
            pauseVideo = { calls += "pause" },
            dismiss = { calls += "dismiss" },
        )

        assertThat(calls).containsExactly("dismiss")
    }

    @Test
    fun `only unexpected video disposal clears the playback queue`() {
        assertThat(
            shouldStopVideoOnOverlayDispose(
                isVideo = true,
                dismissedExplicitly = false,
            ),
        ).isTrue()
        assertThat(
            shouldStopVideoOnOverlayDispose(
                isVideo = true,
                dismissedExplicitly = true,
            ),
        ).isFalse()
        assertThat(
            shouldStopVideoOnOverlayDispose(
                isVideo = false,
                dismissedExplicitly = false,
            ),
        ).isFalse()
    }

    @Test
    fun `back exits fullscreen before dismissing playback`() {
        val fullscreenCalls = mutableListOf<String>()
        performPlaybackBack(
            isFullscreen = true,
            exitFullscreen = { fullscreenCalls += "exit-fullscreen" },
            dismissPlayback = { fullscreenCalls += "dismiss" },
        )
        assertThat(fullscreenCalls).containsExactly("exit-fullscreen")

        val normalCalls = mutableListOf<String>()
        performPlaybackBack(
            isFullscreen = false,
            exitFullscreen = { normalCalls += "exit-fullscreen" },
            dismissPlayback = { normalCalls += "dismiss" },
        )
        assertThat(normalCalls).containsExactly("dismiss")
    }
}
