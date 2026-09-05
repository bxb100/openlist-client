package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.DirectoryListing

@RunWith(AndroidJUnit4::class)
class DirectoryContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun retainedEmptyDirectoryShowsRefreshFailureAndCanRetry() {
        val refreshing = mutableStateOf(true)
        val failure = mutableStateOf<String?>(null)
        var retryCount = 0
        compose.setContent {
            MaterialTheme {
                DirectoryContent(
                    entries = emptyList(),
                    listing = DirectoryListing(),
                    layout = CollectionLayout.List,
                    loading = refreshing.value,
                    error = failure.value,
                    onRetry = {
                        retryCount++
                        refreshing.value = true
                        failure.value = null
                    },
                    onUpload = {},
                    onOpen = {},
                    onFileActions = {},
                )
            }
        }

        compose.onNodeWithText("此文件夹为空").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        compose.runOnIdle {
            refreshing.value = false
            failure.value = "服务器暂时不可用"
        }
        compose.onNodeWithText("服务器暂时不可用").assertIsDisplayed()
        compose.onNodeWithText("此文件夹为空").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertDoesNotExist()

        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retryCount) }
        compose.onNodeWithText("服务器暂时不可用").assertDoesNotExist()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
    }

    @Test
    fun shortWindowWithLargeTextCanScrollLongLoadErrorToRetry() {
        var retryCount = 0
        val longError = "无法连接到服务器。请确认设备已连接网络，服务器地址正确，并检查服务器是否正在运行。读取目录时连接被关闭。".repeat(4)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.size(width = 320.dp, height = 300.dp)) {
                        DirectoryContent(
                            entries = emptyList(),
                            listing = null,
                            layout = CollectionLayout.List,
                            loading = false,
                            error = longError,
                            onRetry = { retryCount++ },
                            onUpload = {},
                            onOpen = {},
                            onFileActions = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("重试").assertIsNotDisplayed()
        compose.onNodeWithText("重试").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, retryCount) }
    }

}
