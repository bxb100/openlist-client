package org.openlist.mobile.media

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackControlsPolicyTest {
    @Test
    fun `playback chrome follows both light and dark material color schemes`() {
        val light = lightColorScheme(
            surface = Color(0xFFFDF8FF),
            onSurface = Color(0xFF1D1B20),
            surfaceContainer = Color(0xFFF3EDF7),
            secondaryContainer = Color(0xFFE8DEF8),
            onSecondaryContainer = Color(0xFF1D192B),
        )
        val dark = darkColorScheme(
            surface = Color(0xFF141218),
            onSurface = Color(0xFFE6E0E9),
            surfaceContainer = Color(0xFF211F26),
            secondaryContainer = Color(0xFF4A4458),
            onSecondaryContainer = Color(0xFFE8DEF8),
        )

        listOf(light, dark).forEach { scheme ->
            val chrome = playbackChromePalette(scheme)

            assertThat(chrome.pageContainer).isEqualTo(scheme.surface)
            assertThat(chrome.pageContent).isEqualTo(scheme.onSurface)
            assertThat(chrome.secondaryContent).isEqualTo(scheme.onSurfaceVariant)
            assertThat(chrome.controlsContainer).isEqualTo(scheme.surfaceContainer)
            assertThat(chrome.divider).isEqualTo(scheme.outlineVariant)
            assertThat(chrome.selectedQueueContainer).isEqualTo(scheme.secondaryContainer)
            assertThat(chrome.selectedQueueContent).isEqualTo(scheme.onSecondaryContainer)
        }

        assertThat(playbackChromePalette(light).pageContainer)
            .isNotEqualTo(playbackChromePalette(dark).pageContainer)
        assertThat(playbackChromePalette(light).controlsContainer)
            .isNotEqualTo(playbackChromePalette(dark).controlsContainer)
    }

    @Test
    fun `ten second seek clamps to media boundaries`() {
        assertThat(
            playbackSeekTarget(
                currentPositionMs = 4_000L,
                offsetMs = -SEEK_INTERVAL_MS,
                durationMs = 60_000L,
            ),
        ).isEqualTo(0L)
        assertThat(
            playbackSeekTarget(
                currentPositionMs = 55_000L,
                offsetMs = SEEK_INTERVAL_MS,
                durationMs = 60_000L,
            ),
        ).isEqualTo(60_000L)
        assertThat(
            playbackSeekTarget(
                currentPositionMs = 20_000L,
                offsetMs = SEEK_INTERVAL_MS,
                durationMs = C.TIME_UNSET,
            ),
        ).isEqualTo(30_000L)
    }

    @Test
    fun `seek target saturates instead of overflowing long positions`() {
        assertThat(
            playbackSeekTarget(
                currentPositionMs = Long.MAX_VALUE - 1L,
                offsetMs = SEEK_INTERVAL_MS,
                durationMs = C.TIME_UNSET,
            ),
        ).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `scrubbing keeps precision for long videos and clamps fractions`() {
        val twelveHoursMs = 12L * 60L * 60L * 1_000L

        assertThat(playbackPositionFromFraction(0.5f, twelveHoursMs))
            .isEqualTo(twelveHoursMs / 2L)
        assertThat(playbackPositionFromFraction(-1f, twelveHoursMs)).isEqualTo(0L)
        assertThat(playbackPositionFromFraction(2f, twelveHoursMs)).isEqualTo(twelveHoursMs)
        assertThat(playbackPositionFraction(twelveHoursMs / 4L, twelveHoursMs))
            .isWithin(0.0001f)
            .of(0.25f)
    }

    @Test
    fun `duration selects first finite positive Media3 value`() {
        assertThat(playbackDurationMs(C.TIME_UNSET, -1L, 0L, 90_000L, 100_000L))
            .isEqualTo(90_000L)
        assertThat(playbackDurationMs(C.TIME_UNSET, -1L, 0L)).isNull()
    }

    @Test
    fun `speed button walks supported speeds and wraps`() {
        assertThat(nextPlaybackSpeed(1f)).isEqualTo(1.25f)
        assertThat(nextPlaybackSpeed(1.99f)).isEqualTo(0.5f)
        assertThat(nextPlaybackSpeed(2f)).isEqualTo(0.5f)
    }

    @Test
    fun `horizontal video seek advances one second per fixed physical distance`() {
        val durationMs = 6L * 60L * 60L * 1_000L
        val startPositionMs = 7_250L
        val pixelsPerSecondAt2xDensity = VIDEO_HORIZONTAL_SEEK_DP_PER_SECOND * 2f

        assertThat(horizontalSeekTarget(startPositionMs, 0f, pixelsPerSecondAt2xDensity, durationMs))
            .isEqualTo(7_250L)
        assertThat(horizontalSeekTarget(startPositionMs, 48f, pixelsPerSecondAt2xDensity, durationMs))
            .isEqualTo(10_250L)
        assertThat(horizontalSeekTarget(startPositionMs, -48f, pixelsPerSecondAt2xDensity, durationMs))
            .isEqualTo(4_250L)
        assertThat(horizontalSeekTarget(startPositionMs, 15.9f, pixelsPerSecondAt2xDensity, durationMs))
            .isEqualTo(7_250L)
        assertThat(horizontalSeekTarget(startPositionMs, -15.9f, pixelsPerSecondAt2xDensity, durationMs))
            .isEqualTo(7_250L)
    }

    @Test
    fun `horizontal video seek sensitivity is independent of display density`() {
        val durationMs = 60_000L
        val startPositionMs = 20_000L

        assertThat(horizontalSeekTarget(startPositionMs, 24f, 8f, durationMs))
            .isEqualTo(23_000L)
        assertThat(horizontalSeekTarget(startPositionMs, 72f, 24f, durationMs))
            .isEqualTo(23_000L)
        assertThat(horizontalSeekTarget(startPositionMs, -120f, 24f, durationMs))
            .isEqualTo(15_000L)
        assertThat(horizontalSeekTarget(1_250L, -24f, 8f, durationMs))
            .isEqualTo(0L)
        assertThat(horizontalSeekTarget(59_250L, 24f, 8f, durationMs))
            .isEqualTo(durationMs)
    }

    @Test
    fun `horizontal video seek rejects invalid geometry and clamps extreme positions`() {
        val durationMs = 60_000L

        assertThat(horizontalSeekTarget(-1L, 0f, 1_000f, durationMs)).isEqualTo(0L)
        assertThat(horizontalSeekTarget(Long.MAX_VALUE, 0f, 1_000f, durationMs))
            .isEqualTo(durationMs)
        assertThat(horizontalSeekTarget(30_000L, 500f, 0f, durationMs)).isNull()
        assertThat(horizontalSeekTarget(30_000L, Float.NaN, 8f, durationMs)).isNull()
        assertThat(horizontalSeekTarget(30_000L, Float.POSITIVE_INFINITY, 8f, durationMs))
            .isNull()
        assertThat(horizontalSeekTarget(30_000L, 500f, Float.NaN, durationMs)).isNull()
        assertThat(horizontalSeekTarget(30_000L, 500f, 8f, null)).isNull()
        assertThat(horizontalSeekTarget(30_000L, 500f, 8f, C.TIME_UNSET)).isNull()
        assertThat(horizontalSeekTarget(Long.MAX_VALUE - 1L, 8f, 8f, Long.MAX_VALUE))
            .isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `long press boost follows playback intent through buffering`() {
        assertThat(
            canStartLongPressSpeedBoost(
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                canChangeSpeed = true,
            ),
        ).isTrue()
        assertThat(
            canStartLongPressSpeedBoost(
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
                canChangeSpeed = true,
            ),
        ).isTrue()

        listOf(Player.STATE_IDLE, Player.STATE_ENDED).forEach { stoppedState ->
            assertThat(
                canStartLongPressSpeedBoost(
                    playWhenReady = true,
                    playbackState = stoppedState,
                    canChangeSpeed = true,
                ),
            ).isFalse()
        }
        assertThat(
            canStartLongPressSpeedBoost(
                playWhenReady = false,
                playbackState = Player.STATE_READY,
                canChangeSpeed = true,
            ),
        ).isFalse()
        assertThat(
            canStartLongPressSpeedBoost(
                playWhenReady = true,
                playbackState = Player.STATE_READY,
                canChangeSpeed = false,
            ),
        ).isFalse()
    }

    @Test
    fun `video zoom is a uniform enlargement of the fitted surface`() {
        val source = Size(width = 1_920f, height = 1_080f)
        val viewport = Size(width = 1_080f, height = 1_920f)
        val fitted = videoScaleFactor(source, viewport, VideoScaleMode.FIT)
        val zoomed = videoScaleFactor(source, viewport, VideoScaleMode.ZOOMED)

        assertThat(fitted.scaleX).isEqualTo(fitted.scaleY)
        assertThat(zoomed.scaleX).isEqualTo(zoomed.scaleY)
        assertThat(zoomed.scaleX).isWithin(0.0001f)
            .of(fitted.scaleX * VIDEO_ZOOM_FACTOR)
    }

    @Test
    fun `entering video zoom hides controls while restoring does not`() {
        val zoom = toggleVideoScale(VideoScaleMode.FIT)
        val restore = toggleVideoScale(VideoScaleMode.ZOOMED)

        assertThat(zoom.scaleMode).isEqualTo(VideoScaleMode.ZOOMED)
        assertThat(zoom.hideControls).isTrue()
        assertThat(restore.scaleMode).isEqualTo(VideoScaleMode.FIT)
        assertThat(restore.hideControls).isFalse()
    }

    @Test
    fun `fullscreen controls only hide for the unchanged idle ready epoch`() {
        fun shouldHide(
            controlsVisible: Boolean = true,
            interactionActive: Boolean = false,
            interactionEpoch: Int = 7,
            scheduledEpoch: Int = 7,
            isPlaying: Boolean = true,
            playbackState: Int = Player.STATE_READY,
        ) = shouldHideFullscreenControlsAfterTimeout(
            controlsVisible = controlsVisible,
            interactionActive = interactionActive,
            interactionEpoch = interactionEpoch,
            scheduledEpoch = scheduledEpoch,
            isPlaying = isPlaying,
            playbackState = playbackState,
        )

        assertThat(shouldHide()).isTrue()
        assertThat(shouldHide(controlsVisible = false)).isFalse()
        assertThat(shouldHide(interactionActive = true)).isFalse()
        assertThat(shouldHide(interactionEpoch = 8)).isFalse()
        assertThat(shouldHide(isPlaying = false)).isFalse()
        assertThat(shouldHide(playbackState = Player.STATE_BUFFERING)).isFalse()
    }
}
