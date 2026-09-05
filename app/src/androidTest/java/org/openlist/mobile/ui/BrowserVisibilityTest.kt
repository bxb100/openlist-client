package org.openlist.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.util.FileVisibilityMatcher
import org.openlist.mobile.ui.browser.SearchUiState
import org.openlist.mobile.ui.theme.OpenListTheme

@RunWith(AndroidJUnit4::class)
class BrowserVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fullyHiddenSearchPageStillLoadsTheNextServerPage() {
        val hidden = BrowserEntry("/report.tmp", "/", OpenListObject(name = "report.tmp"))
        val visible = BrowserEntry("/report.pdf", "/", OpenListObject(name = "report.pdf"))
        val state = mutableStateOf(SearchUiState(
            results = listOf(hidden), searched = true, total = 2, hasMore = true,
        ))
        val matcher = FileVisibilityMatcher.compile(listOf(FileVisibilityRule("tmp", "*.tmp")))
        var requests = 0
        compose.setContent {
            OpenListTheme(darkTheme = false) {
                BrowserSearchResults(
                    state = state.value,
                    query = "report",
                    layout = CollectionLayout.List,
                    onRetry = {},
                    onLoadMore = {
                        requests++
                        state.value = state.value.copy(results = listOf(hidden, visible), hasMore = false)
                    },
                    onOpen = {},
                    onFileActions = {},
                    visibilityMatcher = matcher,
                )
            }
        }
        compose.onNodeWithText("report.tmp").assertDoesNotExist()
        compose.onNodeWithText("当前结果已被隐藏").assertIsDisplayed()
        compose.onNodeWithText("加载更多结果").performClick()
        compose.onNodeWithText("report.pdf").assertIsDisplayed()
        compose.onNodeWithText("report.tmp").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test
    fun filteredDirectoryOffersRuleRecoveryInsteadOfClaimingItIsEmpty() {
        var edits = 0
        compose.setContent {
            OpenListTheme(darkTheme = false) {
                DirectoryContent(
                    entries = emptyList(),
                    listing = DirectoryListing(),
                    layout = CollectionLayout.List,
                    loading = false,
                    error = null,
                    onRetry = {}, onUpload = {}, onOpen = {}, onFileActions = {},
                    hiddenCount = 3,
                    onFilterRequested = { edits++ },
                )
            }
        }
        compose.onNodeWithText("此文件夹为空").assertDoesNotExist()
        compose.onNodeWithText("调整规则").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }
}
