package org.openlist.mobile.media

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playbackInfoButtonOpensAndClosesPrivacySafeDialog() {
        val dialogVisible = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                PlaybackInfoButton(
                    onClick = { dialogVisible.value = true },
                    onVideoSurface = false,
                )
                if (dialogVisible.value) {
                    PlaybackInfoDialogContent(
                        snapshot = PlaybackInfoSnapshot(
                            title = "https://example.invalid/private/movie.mp4?token=secret",
                            networkBytesPerSecond = 1_048_576L,
                            cacheBytesRead = 2_097_152L,
                            sessionCacheStatus = PlaybackCacheStatus.HIT,
                        ),
                        onDismiss = { dialogVisible.value = false },
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("播放信息").assertExists().performClick()
        compose.onNodeWithTag(PlaybackUiTags.PLAYBACK_INFO_DIALOG).assertExists()
        compose.onNodeWithText("movie.mp4").assertExists()
        compose.onNodeWithText("1.00 MiB/s").assertExists()
        compose.onNodeWithText("命中").assertExists()
        compose.onNodeWithText("https://example.invalid/private/movie.mp4?token=secret")
            .assertDoesNotExist()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()

        compose.onNodeWithTag(PlaybackUiTags.PLAYBACK_INFO_CLOSE).performClick()
        compose.onNodeWithTag(PlaybackUiTags.PLAYBACK_INFO_DIALOG).assertDoesNotExist()
    }

    @Test
    fun videoGestureLayerRoutesTapDoubleTapAndLongPress() {
        var tapCount = 0
        var seekBackCount = 0
        var seekForwardCount = 0
        val speedEvents = mutableListOf<String>()
        compose.setContent {
            VideoGestureSurface(
                onTap = { tapCount += 1 },
                canSeek = { true },
                currentPositionMs = { 0L },
                durationMs = { 120_000L },
                onSeekBack = { seekBackCount += 1 },
                onSeekForward = { seekForwardCount += 1 },
                onHorizontalSeekPreview = {},
                onHorizontalSeek = {},
                onSpeedBoostStart = {
                    speedEvents += "start"
                    true
                },
                onSpeedBoostEnd = { speedEvents += "end" },
                modifier = Modifier.width(300.dp).height(180.dp),
            )
        }

        val gestureLayer = compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE)
        gestureLayer.performTouchInput {
            val gestureGap = viewConfiguration.doubleTapTimeoutMillis + 100L
            click(center)
            advanceEventTime(gestureGap)
            doubleClick(Offset(width * 0.25f, center.y))
            advanceEventTime(gestureGap)
            doubleClick(Offset(width * 0.75f, center.y))
            advanceEventTime(gestureGap)
            longClick(center)
        }

        compose.runOnIdle {
            assertEquals(1, tapCount)
            assertEquals(1, seekBackCount)
            assertEquals(1, seekForwardCount)
            assertEquals(listOf("start", "end"), speedEvents)
        }
    }

    @Test
    fun horizontalVideoDragUsesFrozenPlaybackPositionAndCommitsExactlyOnce() {
        var tapCount = 0
        var currentPositionMs = 10_000L
        val previews = mutableListOf<Long?>()
        val committedPositions = mutableListOf<Long>()
        compose.setContent {
            VideoGestureSurface(
                onTap = { tapCount += 1 },
                canSeek = { true },
                currentPositionMs = { currentPositionMs },
                durationMs = { 100_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = { positionMs ->
                    previews += positionMs
                    if (positionMs != null) currentPositionMs = 80_000L
                },
                onHorizontalSeek = committedPositions::add,
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                modifier = Modifier.width(300.dp).height(180.dp),
            )
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            swipe(
                start = Offset(width * 0.65f, center.y),
                end = Offset(width * 0.8f, center.y),
                durationMillis = 600L,
            )
        }

        compose.runOnIdle {
            assertEquals(0, tapCount)
            assertTrue(previews.filterNotNull().isNotEmpty())
            assertTrue(previews.filterNotNull().all { it % 1_000L == 0L })
            assertEquals(null, previews.last())
            assertEquals(1, committedPositions.size)
            assertTrue(committedPositions.single() in 13_000L..17_000L)
            assertEquals(0L, committedPositions.single() % 1_000L)
        }
    }

    @Test
    fun cancelledHorizontalVideoDragClearsPreviewWithoutCommittingSeek() {
        val previews = mutableListOf<Long?>()
        val committedPositions = mutableListOf<Long>()
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { true },
                currentPositionMs = { 30_000L },
                durationMs = { 100_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = previews::add,
                onHorizontalSeek = committedPositions::add,
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                modifier = Modifier.width(300.dp).height(180.dp),
            )
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            down(Offset(width * 0.4f, center.y))
            moveTo(Offset(width * 0.7f, center.y), 300L)
            cancel()
        }

        compose.runOnIdle {
            assertTrue(previews.filterNotNull().isNotEmpty())
            assertEquals(null, previews.last())
            assertTrue(committedPositions.isEmpty())
        }
    }

    @Test
    fun horizontalDragCancelsPendingLongPressAndDoesNotBecomeATap() {
        var tapCount = 0
        val speedEvents = mutableListOf<String>()
        val committedPositions = mutableListOf<Long>()
        compose.setContent {
            VideoGestureSurface(
                onTap = { tapCount += 1 },
                canSeek = { true },
                currentPositionMs = { 0L },
                durationMs = { 90_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = {},
                onHorizontalSeek = committedPositions::add,
                onSpeedBoostStart = {
                    speedEvents += "start"
                    true
                },
                onSpeedBoostEnd = { speedEvents += "end" },
                modifier = Modifier.width(300.dp).height(180.dp),
            )
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis / 2L)
            moveTo(Offset(width * 0.8f, center.y), 200L)
            up()
        }

        compose.runOnIdle {
            assertEquals(0, tapCount)
            assertTrue(speedEvents.isEmpty())
            assertEquals(1, committedPositions.size)
        }
    }

    @Test
    fun verticalVideoDragsAdjustBrightnessOnLeftAndVolumeOnRightInBothDirections() {
        val starts = mutableListOf<VideoAdjustment>()
        val adjustments = mutableListOf<Pair<VideoAdjustment, Float>>()
        var endCount = 0
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { true },
                currentPositionMs = { 0L },
                durationMs = { 90_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = {},
                onHorizontalSeek = {},
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                onVerticalAdjustmentStart = starts::add,
                onVerticalAdjustment = { target, delta -> adjustments += target to delta },
                onVerticalAdjustmentEnd = { endCount += 1 },
                modifier = Modifier.width(300.dp).height(240.dp),
            )
        }

        val gestureLayer = compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE)
        listOf(VideoAdjustment.BRIGHTNESS, VideoAdjustment.VOLUME).forEach { target ->
            listOf(true, false).forEach { upwards ->
                gestureLayer.performTouchInput {
                    advanceEventTime(viewConfiguration.doubleTapTimeoutMillis + 100L)
                    val x = width * if (target == VideoAdjustment.BRIGHTNESS) 0.25f else 0.75f
                    swipe(
                        start = Offset(x, height * if (upwards) 0.8f else 0.2f),
                        end = Offset(x, height * if (upwards) 0.2f else 0.8f),
                        durationMillis = 300L,
                    )
                }

                compose.runOnIdle {
                    assertEquals(listOf(target), starts)
                    assertTrue(adjustments.isNotEmpty())
                    assertTrue(adjustments.all { it.first == target })
                    val finalDelta = adjustments.last().second
                    assertTrue(if (upwards) finalDelta > 0.2f else finalDelta < -0.2f)
                    assertTrue(kotlin.math.abs(finalDelta) <= 1f)
                    assertEquals(1, endCount)
                    starts.clear()
                    adjustments.clear()
                    endCount = 0
                }
            }
        }
    }

    @Test
    fun verticalVideoDragDoesNotSeekTapOrStartLongPressSpeedBoost() {
        var tapCount = 0
        var seekCount = 0
        val seekPreviews = mutableListOf<Long?>()
        val speedEvents = mutableListOf<String>()
        val adjustments = mutableListOf<Float>()
        compose.setContent {
            VideoGestureSurface(
                onTap = { tapCount += 1 },
                canSeek = { true },
                currentPositionMs = { 30_000L },
                durationMs = { 90_000L },
                onSeekBack = { seekCount += 1 },
                onSeekForward = { seekCount += 1 },
                onHorizontalSeekPreview = seekPreviews::add,
                onHorizontalSeek = { seekCount += 1 },
                onSpeedBoostStart = {
                    speedEvents += "start"
                    true
                },
                onSpeedBoostEnd = { speedEvents += "end" },
                onVerticalAdjustment = { _, delta -> adjustments += delta },
                modifier = Modifier.width(300.dp).height(240.dp),
            )
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            swipe(
                start = Offset(width * 0.25f, height * 0.8f),
                end = Offset(width * 0.25f, height * 0.2f),
                durationMillis = viewConfiguration.longPressTimeoutMillis + 200L,
            )
        }

        compose.runOnIdle {
            assertTrue(adjustments.isNotEmpty())
            assertEquals(0, tapCount)
            assertEquals(0, seekCount)
            assertTrue(seekPreviews.filterNotNull().isEmpty())
            assertTrue(speedEvents.isEmpty())
        }
    }

    @Test
    fun verticalDragKeepsItsInitialSideWhenTheFingerCrossesTheCenter() {
        val starts = mutableListOf<VideoAdjustment>()
        val adjustments = mutableListOf<Pair<VideoAdjustment, Float>>()
        val seeks = mutableListOf<Long>()
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { true },
                currentPositionMs = { 30_000L },
                durationMs = { 90_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = {},
                onHorizontalSeek = seeks::add,
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                onVerticalAdjustmentStart = starts::add,
                onVerticalAdjustment = { target, delta -> adjustments += target to delta },
                modifier = Modifier.width(300.dp).height(240.dp),
            )
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            down(Offset(width * 0.25f, height * 0.8f))
            moveTo(Offset(width * 0.25f, height * 0.55f), 100L)
            moveTo(Offset(width * 0.85f, height * 0.25f), 100L)
            up()
        }

        compose.runOnIdle {
            assertEquals(listOf(VideoAdjustment.BRIGHTNESS), starts)
            assertTrue(adjustments.isNotEmpty())
            assertTrue(adjustments.all { it.first == VideoAdjustment.BRIGHTNESS })
            assertTrue(adjustments.last().second > 0.2f)
            assertTrue(seeks.isEmpty())
        }
    }

    @Test
    fun disablingVideoGesturesPreventsAdjustmentAndEndsAnActiveAdjustment() {
        val enabled = mutableStateOf(false)
        val starts = mutableListOf<VideoAdjustment>()
        val adjustments = mutableListOf<Float>()
        var endCount = 0
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { true },
                currentPositionMs = { 0L },
                durationMs = { 90_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = {},
                onHorizontalSeek = {},
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                gesturesEnabled = enabled.value,
                onVerticalAdjustmentStart = starts::add,
                onVerticalAdjustment = { _, delta -> adjustments += delta },
                onVerticalAdjustmentEnd = { endCount += 1 },
                modifier = Modifier.width(300.dp).height(240.dp),
            )
        }

        val gestureLayer = compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE)
        gestureLayer.performTouchInput {
            swipe(Offset(width * 0.75f, height * 0.8f), Offset(width * 0.75f, height * 0.2f))
        }
        compose.runOnIdle {
            assertTrue(starts.isEmpty())
            assertTrue(adjustments.isEmpty())
            assertEquals(0, endCount)
            enabled.value = true
        }
        gestureLayer.performTouchInput {
            down(Offset(width * 0.75f, height * 0.8f))
            moveTo(Offset(width * 0.75f, height * 0.4f), 100L)
        }
        compose.runOnIdle {
            assertEquals(listOf(VideoAdjustment.VOLUME), starts)
            assertTrue(adjustments.isNotEmpty())
            enabled.value = false
        }
        compose.runOnIdle {
            assertEquals(1, endCount)
            adjustments.clear()
        }
        gestureLayer.performTouchInput {
            moveTo(Offset(width * 0.75f, height * 0.2f), 100L)
            up()
        }
        compose.runOnIdle {
            assertTrue(adjustments.isEmpty())
            assertEquals(1, endCount)
        }
    }

    @Test
    fun nonSeekableVideoStillAllowsVerticalVolumeAdjustment() {
        val starts = mutableListOf<VideoAdjustment>()
        val adjustments = mutableListOf<Float>()
        var endCount = 0
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { false },
                currentPositionMs = { 0L },
                durationMs = { null },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = {},
                onHorizontalSeek = {},
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                onVerticalAdjustmentStart = starts::add,
                onVerticalAdjustment = { _, delta -> adjustments += delta },
                onVerticalAdjustmentEnd = { endCount += 1 },
                modifier = Modifier.width(300.dp).height(240.dp),
            )
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            swipe(Offset(width * 0.75f, height * 0.8f), Offset(width * 0.75f, height * 0.2f))
        }

        compose.runOnIdle {
            assertEquals(listOf(VideoAdjustment.VOLUME), starts)
            assertTrue(adjustments.isNotEmpty())
            assertTrue(adjustments.last() > 0.2f)
            assertEquals(1, endCount)
        }
    }

    @Test
    fun verticalAdjustmentTakesPriorityOverTheSurroundingPageScroll() {
        val scrollState = ScrollState(0)
        val adjustments = mutableListOf<Float>()
        compose.setContent {
            Column(Modifier.width(300.dp).height(240.dp).verticalScroll(scrollState)) {
                VideoGestureSurface(
                    onTap = {},
                    canSeek = { true },
                    currentPositionMs = { 0L },
                    durationMs = { 90_000L },
                    onSeekBack = {},
                    onSeekForward = {},
                    onHorizontalSeekPreview = {},
                    onHorizontalSeek = {},
                    onSpeedBoostStart = { false },
                    onSpeedBoostEnd = {},
                    onVerticalAdjustment = { _, delta -> adjustments += delta },
                    modifier = Modifier.width(300.dp).height(240.dp),
                )
                Spacer(Modifier.height(800.dp))
            }
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            swipe(Offset(width * 0.25f, height * 0.8f), Offset(width * 0.25f, height * 0.2f))
        }

        compose.runOnIdle {
            assertTrue(scrollState.maxValue > 0)
            assertTrue(adjustments.isNotEmpty())
            assertTrue(adjustments.last() > 0.2f)
            assertEquals(0, scrollState.value)
        }
    }

    @Test
    fun cancelledVerticalVideoDragEndsAdjustmentAndReleasesInteractionExactlyOnce() {
        val adjustments = mutableListOf<Float>()
        val interactionStates = mutableListOf<Boolean>()
        var endCount = 0
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { true },
                currentPositionMs = { 0L },
                durationMs = { 90_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = {},
                onHorizontalSeek = {},
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                onVerticalAdjustment = { _, delta -> adjustments += delta },
                onVerticalAdjustmentEnd = { endCount += 1 },
                onInteractionActiveChange = interactionStates::add,
                modifier = Modifier.width(300.dp).height(240.dp),
            )
        }

        compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE).performTouchInput {
            down(Offset(width * 0.25f, height * 0.8f))
            moveTo(Offset(width * 0.25f, height * 0.4f), 100L)
            cancel()
        }

        compose.runOnIdle {
            assertTrue(adjustments.isNotEmpty())
            assertEquals(1, endCount)
            assertEquals(listOf(true, false), interactionStates)
        }
    }

    @Test
    fun videoGesturesReportInteractionUntilEveryPointerIsReleased() {
        val interactionStates = mutableListOf<Boolean>()
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { true },
                currentPositionMs = { 0L },
                durationMs = { 90_000L },
                onSeekBack = {},
                onSeekForward = {},
                onHorizontalSeekPreview = {},
                onHorizontalSeek = {},
                onSpeedBoostStart = { true },
                onSpeedBoostEnd = {},
                onInteractionActiveChange = interactionStates::add,
                modifier = Modifier.width(300.dp).height(180.dp),
            )
        }

        val gestureLayer = compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE)

        gestureLayer.performTouchInput {
            doubleClick(Offset(width * 0.25f, center.y))
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false, true, false), interactionStates)
            interactionStates.clear()
        }

        gestureLayer.performTouchInput {
            advanceEventTime(viewConfiguration.doubleTapTimeoutMillis + 100L)
            longClick(center)
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), interactionStates)
            interactionStates.clear()
        }

        gestureLayer.performTouchInput {
            advanceEventTime(viewConfiguration.doubleTapTimeoutMillis + 100L)
            swipe(
                start = Offset(width * 0.1f, center.y),
                end = Offset(width * 0.8f, center.y),
                durationMillis = 600L,
            )
        }
        compose.runOnIdle {
            assertEquals(listOf(true, false), interactionStates)
        }
    }

    @Test
    fun videoScaleToggleProvidesZoomAndRestoreActions() {
        val zoomed = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                VideoScaleToggleButton(
                    isZoomed = zoomed.value,
                    onToggle = { zoomed.value = !zoomed.value },
                )
            }
        }

        val zoomToggle = compose.onNodeWithTag(PlaybackUiTags.VIDEO_ZOOM_TOGGLE)
        compose.onNodeWithContentDescription("放大画面").assertExists()
        zoomToggle.performClick()
        compose.runOnIdle { assertTrue(zoomed.value) }
        compose.onNodeWithContentDescription("恢复完整画面").assertExists()

        zoomToggle.performClick()
        compose.runOnIdle { assertTrue(!zoomed.value) }
    }

    @Test
    fun doubleTapReadsSeekAvailabilityWhenTheGestureActuallyRuns() {
        var seekAllowed = false
        var seekForwardCount = 0
        compose.setContent {
            VideoGestureSurface(
                onTap = {},
                canSeek = { seekAllowed },
                currentPositionMs = { 0L },
                durationMs = { 60_000L },
                onSeekBack = {},
                onSeekForward = { seekForwardCount += 1 },
                onHorizontalSeekPreview = {},
                onHorizontalSeek = {},
                onSpeedBoostStart = { false },
                onSpeedBoostEnd = {},
                modifier = Modifier.width(300.dp).height(180.dp),
            )
        }

        val gestureLayer = compose.onNodeWithTag(PlaybackUiTags.VIDEO_GESTURE_SURFACE)
        gestureLayer.performTouchInput { doubleClick(Offset(width * 0.75f, center.y)) }
        compose.runOnIdle {
            assertEquals(0, seekForwardCount)
            seekAllowed = true
        }
        gestureLayer.performTouchInput {
            advanceEventTime(viewConfiguration.doubleTapTimeoutMillis + 100L)
            doubleClick(Offset(width * 0.75f, center.y))
        }

        compose.runOnIdle { assertEquals(1, seekForwardCount) }
    }

    @Test
    fun sliderPreviewsContinuouslyButCommitsOneSeekWhenDragFinishes() {
        val scrubbedPositions = mutableListOf<Long>()
        val committedPositions = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                SeekablePlaybackSlider(
                    currentPositionMs = 0L,
                    durationMs = 120_000L,
                    onScrub = scrubbedPositions::add,
                    onSeek = committedPositions::add,
                    onVideoSurface = false,
                    modifier = Modifier.width(320.dp),
                )
            }
        }

        compose.onNodeWithTag(PlaybackUiTags.PROGRESS_SLIDER).performTouchInput {
            swipe(
                start = Offset(width * 0.1f, center.y),
                end = Offset(width * 0.8f, center.y),
                durationMillis = 600L,
            )
        }

        compose.runOnIdle {
            assertTrue(scrubbedPositions.isNotEmpty())
            assertEquals(1, committedPositions.size)
            assertTrue(committedPositions.single() in 60_000L..120_000L)
        }
    }

    @Test
    fun sliderKeepsTheDragSnapshotWhenDurationChangesMidGesture() {
        val durationMs = mutableStateOf(120_000L)
        val committedPositions = mutableListOf<Long>()
        var durationUpdated = false
        compose.setContent {
            MaterialTheme {
                SeekablePlaybackSlider(
                    currentPositionMs = 0L,
                    durationMs = durationMs.value,
                    onScrub = {
                        if (!durationUpdated) {
                            durationUpdated = true
                            durationMs.value = 240_000L
                        }
                    },
                    onSeek = committedPositions::add,
                    onVideoSurface = false,
                    modifier = Modifier.width(320.dp),
                )
            }
        }

        compose.onNodeWithTag(PlaybackUiTags.PROGRESS_SLIDER).performTouchInput {
            swipe(
                start = Offset(width * 0.1f, center.y),
                end = Offset(width * 0.8f, center.y),
                durationMillis = 600L,
            )
        }

        compose.runOnIdle {
            assertTrue(durationUpdated)
            assertEquals(1, committedPositions.size)
            assertTrue(committedPositions.single() in 60_000L..120_000L)
        }
    }

    @Test
    fun unknownDurationDisablesScrubbingWithoutSubmittingASeek() {
        val committedPositions = mutableListOf<Long>()
        compose.setContent {
            MaterialTheme {
                SeekablePlaybackSlider(
                    currentPositionMs = 20_000L,
                    durationMs = null,
                    onScrub = {},
                    onSeek = committedPositions::add,
                    onVideoSurface = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        compose.onNodeWithTag(PlaybackUiTags.PROGRESS_SLIDER).performTouchInput {
            swipe(centerLeft, centerRight, durationMillis = 300L)
        }

        compose.runOnIdle { assertTrue(committedPositions.isEmpty()) }
    }
}
