package org.openlist.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserFloatingActionsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun playbackShortcutIsIndependentFromUploadAvailability() {
        var playbackRequests = 0
        compose.setContent {
            MaterialTheme {
                BrowserFloatingActions(
                    showPlaybackQueue = true,
                    showUpload = false,
                    onPlaybackQueueRequested = { playbackRequests += 1 },
                    onUploadRequested = {},
                )
            }
        }

        compose.onNodeWithTag(OpenListUiTags.PLAYBACK_QUEUE_FAB)
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag(OpenListUiTags.UPLOAD_FAB).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, playbackRequests) }
    }

    @Test
    fun playbackShortcutIsPlacedAboveUpload() {
        compose.setContent {
            MaterialTheme {
                BrowserFloatingActions(
                    showPlaybackQueue = true,
                    showUpload = true,
                    onPlaybackQueueRequested = {},
                    onUploadRequested = {},
                )
            }
        }

        val playbackTop = compose.onNodeWithTag(OpenListUiTags.PLAYBACK_QUEUE_FAB)
            .fetchSemanticsNode().boundsInRoot.top
        val uploadTop = compose.onNodeWithTag(OpenListUiTags.UPLOAD_FAB)
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(playbackTop < uploadTop)
    }
}
