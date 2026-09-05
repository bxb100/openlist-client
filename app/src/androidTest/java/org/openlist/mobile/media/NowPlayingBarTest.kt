package org.openlist.mobile.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.ui.theme.OpenListTheme
import org.openlist.mobile.ui.viewer.NowPlayingBar

@RunWith(AndroidJUnit4::class)
class NowPlayingBarTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longFilenameAtDoubleFontKeepsSeparateOpenAndPauseTargets() {
        var opened = 0
        var toggled = 0
        val title = "旅行记录_杭州西湖_完整版本_第一部分.mp4"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                OpenListTheme(darkTheme = false) {
                    Box(Modifier.width(320.dp)) {
                        NowPlayingBar(
                            title = title,
                            isPlaying = true,
                            isVideo = true,
                            onOpen = { opened += 1 },
                            onToggle = { toggled += 1 },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停播放")
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.runOnIdle {
            assertEquals(0, opened)
            assertEquals(1, toggled)
        }
        compose.onNodeWithText(title).performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
