package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import org.openlist.mobile.ui.theme.OpenListTheme
import org.openlist.mobile.ui.viewer.NowPlayingBar
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveableAnimatedContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun preservesRememberSaveableStateAcrossAnimatedDestinationSwitches() {
        var destination by mutableStateOf("files")

        compose.setContent {
            MaterialTheme {
                Column {
                    Button(onClick = { destination = "files" }) { Text("文件") }
                    Button(onClick = { destination = "settings" }) { Text("设置") }
                    SaveableAnimatedContent(
                        targetState = destination,
                        label = "test-destination",
                    ) { target ->
                        RetainedPane(key = target)
                    }
                }
            }
        }

        compose.onNodeWithTag("files-value").assertTextContains("初始")
        compose.onNodeWithText("更新 files").performClick()
        compose.onNodeWithTag("files-value").assertTextContains("已更新")

        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("settings-value").assertTextContains("初始")
        compose.onNodeWithText("更新 settings").performClick()
        compose.onNodeWithTag("settings-value").assertTextContains("已更新")

        compose.onNodeWithText("文件").performClick()
        compose.onNodeWithTag("files-value").assertTextContains("已更新")
    }

    @Test
    fun sessionLoadFailureShowsAnExplicitWorkingRetryAction() {
        var retryCount = 0

        compose.setContent {
            MaterialTheme {
                SessionLoadingScreen(
                    errorMessage = "读取本机会话超时，请点击重试",
                    onRetry = { retryCount += 1 },
                )
            }
        }

        compose.onNodeWithText("读取本机会话超时，请点击重试").assertExists()
        compose.onNodeWithText("重试读取").performClick()
        compose.runOnIdle { assertEquals(1, retryCount) }
    }

    @Test
    fun responsiveNavigationChangeKeepsDestinationState() {
        var useNavigationRail by mutableStateOf(false)

        compose.setContent {
            MaterialTheme {
                ResponsiveDestinationHost(
                    useNavigationRail = useNavigationRail,
                    navigationRail = { Text("宽屏导航") },
                    bottomBar = { Text("窄屏导航") },
                ) {
                    RetainedPane(key = "responsive")
                }
            }
        }

        compose.onNodeWithText("窄屏导航").assertExists()
        compose.onNodeWithText("更新 responsive").performClick()
        compose.onNodeWithTag("responsive-value").assertTextContains("已更新")

        compose.runOnIdle { useNavigationRail = true }
        compose.onNodeWithText("宽屏导航").assertExists()
        compose.onNodeWithTag("responsive-value").assertTextContains("已更新")

        compose.runOnIdle { useNavigationRail = false }
        compose.onNodeWithText("窄屏导航").assertExists()
        compose.onNodeWithTag("responsive-value").assertTextContains("已更新")
    }

    @Test
    fun largeTextPlaybackBarReservesSpaceForTheLastScrollableFile() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OpenListTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        ResponsiveDestinationHost(
                            useNavigationRail = false,
                            navigationRail = {},
                            bottomBar = { Text("文件 · 传输 · 设置", Modifier.heightIn(min = 48.dp)) },
                            persistentBar = {
                                NowPlayingBar(
                                    title = "正在收听的长名称音频文件.flac",
                                    isPlaying = true,
                                    isVideo = false,
                                    onOpen = {},
                                    onToggle = {},
                                    modifier = Modifier.testTag("playback-bar"),
                                )
                            },
                        ) {
                            LazyColumn(Modifier.testTag("files-list")) {
                                items((1..30).toList()) { index ->
                                    Text(
                                        "文件 $index",
                                        Modifier.testTag("file-$index").padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithTag("files-list").performScrollToNode(hasTestTag("file-30"))
        val lastFile = compose.onNodeWithTag("file-30").assertIsDisplayed()
        val playbackBar = compose.onNodeWithTag("playback-bar").assertIsDisplayed()
        assertTrue(
            "The last file must finish above playback chrome",
            lastFile.fetchSemanticsNode().boundsInRoot.bottom <=
                playbackBar.fetchSemanticsNode().boundsInRoot.top,
        )
    }
}

@Composable
private fun RetainedPane(key: String) {
    var value by rememberSaveable { mutableStateOf("$key 初始") }
    Column {
        Text(value, Modifier.testTag("$key-value"))
        Button(onClick = { value = "$key 已更新" }) {
            Text("更新 $key")
        }
    }
}
