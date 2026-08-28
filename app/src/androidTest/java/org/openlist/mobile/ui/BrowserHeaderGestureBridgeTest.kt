package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class BrowserHeaderGestureBridgeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun staticViewportSwipeDrivesOfficialHeaderState() {
        lateinit var headerState: TopAppBarState
        compose.setContent {
            MaterialTheme {
                headerState = rememberTopAppBarState(initialHeightOffsetLimit = -120f)
                val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
                    state = headerState,
                    snapAnimationSpec = null,
                    flingAnimationSpec = null,
                )
                BrowserScrollGestureSurface(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .testTag(STATIC_VIEWPORT_TAG),
                ) {}
            }
        }

        compose.onNodeWithTag(STATIC_VIEWPORT_TAG).performTouchInput {
            swipe(
                start = Offset(center.x, height * 0.75f),
                end = Offset(center.x, height * 0.25f),
                durationMillis = 300L,
            )
        }

        compose.runOnIdle {
            assertEquals(-120f, headerState.heightOffset)
        }
    }

    @Test
    fun searchModeCanDisableStaticViewportHeaderScroll() {
        lateinit var headerState: TopAppBarState
        compose.setContent {
            MaterialTheme {
                headerState = rememberTopAppBarState(initialHeightOffsetLimit = -120f)
                val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
                    state = headerState,
                    canScroll = { false },
                    snapAnimationSpec = null,
                    flingAnimationSpec = null,
                )
                BrowserScrollGestureSurface(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .testTag(STATIC_VIEWPORT_TAG),
                ) {}
            }
        }

        compose.onNodeWithTag(STATIC_VIEWPORT_TAG).performTouchInput {
            swipe(
                start = Offset(center.x, height * 0.75f),
                end = Offset(center.x, height * 0.25f),
                durationMillis = 300L,
            )
        }

        compose.runOnIdle {
            assertEquals(0f, headerState.heightOffset)
        }
    }

    @Test
    fun realScaffoldBodySwipeCollapsesOnlyTheAppBarAndKeepsBreadcrumbVisible() {
        lateinit var headerState: TopAppBarState
        compose.setContent {
            MaterialTheme {
                headerState = rememberTopAppBarState()
                val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
                    state = headerState,
                    snapAnimationSpec = null,
                    flingAnimationSpec = null,
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                ) {
                    BrowserScrollGestureSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets(0, FAKE_TOP_INSET_PX, 0, 0)),
                    ) {
                        Column {
                            BrowserHeaderLayout(state = headerState) {
                                TopAppBar(
                                    title = { Text("文件") },
                                    windowInsets = WindowInsets(0, 0, 0, 0),
                                )
                            }
                            BreadcrumbBar(path = "/电影", onNavigate = {})
                        }
                    }
                    Scaffold(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    ) { contentPadding ->
                        LazyColumn(
                            Modifier
                                .fillMaxSize()
                                .padding(contentPadding)
                                .testTag(OpenListUiTags.FILE_COLLECTION),
                        ) {
                            items(40) {
                                Box(Modifier.fillMaxWidth().height(48.dp))
                            }
                        }
                    }
                }
            }
        }

        compose.waitForIdle()
        val expandedFrameHeight = compose
            .onNodeWithTag(OpenListUiTags.BROWSER_APP_BAR_FRAME)
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val expandedBreadcrumbBounds = compose
            .onNodeWithTag(OpenListUiTags.BREADCRUMB_BAR)
            .fetchSemanticsNode()
            .boundsInRoot
        val expandedCollectionBounds = compose
            .onNodeWithTag(OpenListUiTags.FILE_COLLECTION)
            .fetchSemanticsNode()
            .boundsInRoot
        assertEquals(expandedBreadcrumbBounds.bottom, expandedCollectionBounds.top, 1f)

        compose.onNodeWithTag(OpenListUiTags.FILE_COLLECTION).performTouchInput {
            swipe(
                start = Offset(center.x, height * 0.8f),
                end = Offset(center.x, height * 0.2f),
                durationMillis = 300L,
            )
        }

        compose.runOnIdle {
            assertEquals(headerState.heightOffsetLimit, headerState.heightOffset)
        }
        compose.onNodeWithTag(OpenListUiTags.BREADCRUMB_BAR).assertIsDisplayed()
        compose.onNodeWithText("根目录").assertIsDisplayed()
        compose.onNodeWithText("电影").assertIsDisplayed()
        val collapsedBreadcrumbBounds = compose
            .onNodeWithTag(OpenListUiTags.BREADCRUMB_BAR)
            .fetchSemanticsNode()
            .boundsInRoot
        val collapsedFrameHeight = compose
            .onNodeWithTag(OpenListUiTags.BROWSER_APP_BAR_FRAME)
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val collapsedCollectionBounds = compose
            .onNodeWithTag(OpenListUiTags.FILE_COLLECTION)
            .fetchSemanticsNode()
            .boundsInRoot
        assertEquals(0f, collapsedFrameHeight, 1f)
        assertEquals(expandedBreadcrumbBounds.height, collapsedBreadcrumbBounds.height, 1f)
        assertEquals(
            expandedBreadcrumbBounds.top - expandedFrameHeight,
            collapsedBreadcrumbBounds.top,
            1f,
        )
        assertEquals(FAKE_TOP_INSET_PX.toFloat(), collapsedBreadcrumbBounds.top, 1f)
        assertEquals(collapsedBreadcrumbBounds.bottom, collapsedCollectionBounds.top, 1f)

        compose.onNodeWithTag(OpenListUiTags.BREADCRUMB_BAR).performTouchInput {
            swipe(
                start = Offset(center.x, height * 0.2f),
                end = Offset(center.x, height * 2.2f),
                durationMillis = 300L,
            )
        }

        compose.runOnIdle {
            assertEquals(0f, headerState.heightOffset)
        }
        val restoredBreadcrumbBounds = compose
            .onNodeWithTag(OpenListUiTags.BREADCRUMB_BAR)
            .fetchSemanticsNode()
            .boundsInRoot
        assertEquals(expandedBreadcrumbBounds.top, restoredBreadcrumbBounds.top, 1f)
    }

    private companion object {
        const val STATIC_VIEWPORT_TAG = "browser_static_viewport"
        const val FAKE_TOP_INSET_PX = 24
    }
}
