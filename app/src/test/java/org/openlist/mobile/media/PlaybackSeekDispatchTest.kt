package org.openlist.mobile.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSeekDispatchTest {
    @Test
    fun `current item seek is preferred when both commands are available`() {
        assertThat(
            playbackSeekDispatch(
                canSeekInCurrentItem = true,
                canSeekToMediaItem = true,
                isCurrentItemSeekable = true,
                isCurrentItemLive = false,
                currentIndex = 1,
                mediaItemCount = 3,
            ),
        ).isEqualTo(PlaybackSeekDispatch.CURRENT_ITEM)
    }

    @Test
    fun `indexed seek is used for a valid non-live queue item`() {
        assertThat(
            playbackSeekDispatch(
                canSeekInCurrentItem = false,
                canSeekToMediaItem = true,
                isCurrentItemSeekable = true,
                isCurrentItemLive = false,
                currentIndex = 1,
                mediaItemCount = 3,
            ),
        ).isEqualTo(PlaybackSeekDispatch.INDEXED_ITEM)
    }

    @Test
    fun `indexed fallback is unavailable for live media`() {
        assertThat(
            playbackSeekDispatch(
                canSeekInCurrentItem = false,
                canSeekToMediaItem = true,
                isCurrentItemSeekable = true,
                isCurrentItemLive = true,
                currentIndex = 0,
                mediaItemCount = 1,
            ),
        ).isEqualTo(PlaybackSeekDispatch.NONE)
    }

    @Test
    fun `indexed fallback is unavailable for invalid queue indexes`() {
        assertThat(
            playbackSeekDispatch(
                canSeekInCurrentItem = false,
                canSeekToMediaItem = true,
                isCurrentItemSeekable = true,
                isCurrentItemLive = false,
                currentIndex = -1,
                mediaItemCount = 1,
            ),
        ).isEqualTo(PlaybackSeekDispatch.NONE)
        assertThat(
            playbackSeekDispatch(
                canSeekInCurrentItem = false,
                canSeekToMediaItem = true,
                isCurrentItemSeekable = true,
                isCurrentItemLive = false,
                currentIndex = 1,
                mediaItemCount = 1,
            ),
        ).isEqualTo(PlaybackSeekDispatch.NONE)
    }

    @Test
    fun `indexed fallback does not pretend an unseekable item can seek`() {
        assertThat(
            playbackSeekDispatch(
                canSeekInCurrentItem = false,
                canSeekToMediaItem = true,
                isCurrentItemSeekable = false,
                isCurrentItemLive = false,
                currentIndex = 0,
                mediaItemCount = 1,
            ),
        ).isEqualTo(PlaybackSeekDispatch.NONE)
    }
}
