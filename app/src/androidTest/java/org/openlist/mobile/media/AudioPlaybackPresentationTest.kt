@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package org.openlist.mobile.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.ui.theme.OpenListTheme

@RunWith(AndroidJUnit4::class)
class AudioPlaybackPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun audioPageAndOpenQueueFollowChangesToTheAppColorScheme() {
        val light = lightColorScheme(
            surface = Color(0xFFF1F7F1),
            secondaryContainer = Color(0xFFD7EBD8),
            onSecondaryContainer = Color(0xFF103725),
        )
        val dark = darkColorScheme(
            surface = Color(0xFF131B15),
            secondaryContainer = Color(0xFF234631),
            onSecondaryContainer = Color(0xFFD1F8D9),
        )
        val colors = mutableStateOf(light)
        val title = "主题验证.flac"
        val state = PlaybackControllerState(
            queue = listOf(
                MediaItem.Builder().setMediaId("theme-test")
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build(),
            ),
            currentIndex = 0,
            isPlaying = false,
            playWhenReady = false,
            playbackState = Player.STATE_READY,
            repeatMode = Player.REPEAT_MODE_OFF,
            shuffleEnabled = false,
            playbackSpeed = 1f,
            canSeek = true,
            canChangeSpeed = true,
        )
        compose.setContent {
            MaterialTheme(colorScheme = colors.value) {
                AudioPlaybackPresentation(
                    state = state,
                    positionMs = 0L,
                    durationMs = 60_000L,
                    errorMessage = null,
                    onScrub = {},
                    onSeek = {},
                    onToggle = {},
                    onPrevious = {},
                    onNext = {},
                    onSelect = {},
                    modifier = Modifier.fillMaxSize().testTag("audio_theme_page"),
                )
            }
        }

        fun pageBackground(): Color = compose.onNodeWithTag("audio_theme_page")
            .captureToImage().toPixelMap()[1, 1]
        fun selectedQueueBackground(): Color = compose.onNode(hasText(title) and hasClickAction())
            .captureToImage().toPixelMap()[1, 1]

        assertEquals(light.surface, pageBackground())
        compose.runOnIdle { colors.value = dark }
        assertEquals(dark.surface, pageBackground())
        compose.onNodeWithText("播放队列 · 1").performScrollTo().performClick()
        assertEquals(dark.secondaryContainer, selectedQueueBackground())
        compose.runOnIdle { colors.value = light }
        assertEquals(light.secondaryContainer, selectedQueueBackground())
    }

    @Test
    fun bufferingCanBePausedAndQueueRemainsReachableWithLargeText() {
        var toggles = 0
        val selected = mutableListOf<Int>()
        val state = mutableStateOf(
            PlaybackControllerState(
                queue = listOf("旅行记录_杭州西湖_完整版本_第一部分.flac", "第二首.flac")
                    .mapIndexed { index, title ->
                        MediaItem.Builder().setMediaId("test-$index")
                            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build()).build()
                    },
                currentIndex = 0,
                isPlaying = false,
                playWhenReady = true,
                playbackState = Player.STATE_BUFFERING,
                repeatMode = Player.REPEAT_MODE_OFF,
                shuffleEnabled = false,
                playbackSpeed = 1f,
                canSeek = true,
                canChangeSpeed = true,
            ),
        )
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                OpenListTheme(darkTheme = false) {
                    Box(Modifier.width(320.dp).fillMaxSize()) {
                        AudioPlaybackPresentation(
                            state = state.value,
                            positionMs = 5_000L,
                            durationMs = 60_000L,
                            errorMessage = null,
                            onScrub = {},
                            onSeek = {},
                            onToggle = { toggles += 1 },
                            onPrevious = {},
                            onNext = {},
                            onSelect = selected::add,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("暂停")
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNodeWithText("播放队列 · 2").performScrollTo().performClick()
        compose.onNodeWithText("第二首.flac").performClick()
        compose.runOnIdle {
            assertEquals(listOf(1), selected)
            state.value = state.value.copy(playbackState = Player.STATE_IDLE)
        }
        compose.onNodeWithContentDescription("播放").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停").assertDoesNotExist()
    }
}
