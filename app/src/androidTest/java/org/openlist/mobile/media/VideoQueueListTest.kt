package org.openlist.mobile.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoQueueListTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun queueBackgroundRecomposesWithLightAndDarkMaterialThemes() {
        val light = lightColorScheme(surface = Color(0xFFFF00FF))
        val dark = darkColorScheme(surface = Color(0xFF00FFFF))
        var colorScheme by mutableStateOf(light)

        compose.setContent {
            MaterialTheme(colorScheme = colorScheme) {
                VideoQueueList(
                    queue = emptyList(),
                    currentIndex = -1,
                    isPlaying = false,
                    onSelect = {},
                    modifier = Modifier.height(160.dp),
                )
            }
        }

        fun sampledBackground(): Color =
            compose.onNodeWithTag(PlaybackUiTags.VIDEO_QUEUE_CONTAINER)
                .captureToImage()
                .toPixelMap()[1, 1]

        assertEquals(light.surface, sampledBackground())
        compose.runOnIdle { colorScheme = dark }
        compose.waitForIdle()
        assertEquals(dark.surface, sampledBackground())
    }

    @Test
    fun longQueueCanScrollAndSelectAnotherVideo() {
        val queue = (1..30).map { index ->
            MediaItem.Builder()
                .setMediaId("video-$index")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("第 $index 集").build())
                .build()
        }
        var selectedIndex = -1

        compose.setContent {
            MaterialTheme {
                VideoQueueList(
                    queue = queue,
                    currentIndex = 0,
                    isPlaying = true,
                    onSelect = { selectedIndex = it },
                    modifier = Modifier.height(240.dp),
                )
            }
        }

        compose.onNodeWithText("播放队列 · 30").assertIsDisplayed()
        compose.onNodeWithText("正在播放").assertIsDisplayed()
        compose.onNodeWithTag(PlaybackUiTags.VIDEO_QUEUE_LIST).performScrollToIndex(29)
        compose.onNodeWithText("第 30 集").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(29, selectedIndex) }
    }

    @Test
    fun fullscreenButtonHasAccessibleStateAndCanToggle() {
        var fullscreen by mutableStateOf(false)
        compose.setContent {
            FullscreenToggleButton(
                isFullscreen = fullscreen,
                onToggle = { fullscreen = !fullscreen },
            )
        }

        compose.onNodeWithContentDescription("进入全屏").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("退出全屏").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("进入全屏").assertIsDisplayed()
    }
}
