package org.openlist.mobile.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.OpenListObject

@RunWith(AndroidJUnit4::class)
class FileCollectionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun list_exposesFileNameAndPrimaryAction() {
        val entry = BrowserEntry(
            path = "/Music/song.mp3",
            parent = "/Music",
            item = OpenListObject(name = "song.mp3", size = 2_048, type = 3),
        )
        var openedPath: String? = null

        compose.setContent {
            MaterialTheme {
                FileCollection(
                    entries = listOf(entry),
                    layout = CollectionLayout.List,
                    contentPadding = PaddingValues(0.dp),
                    onOpen = { openedPath = it.path },
                    onDetails = {},
                    onFileActions = {},
                )
            }
        }

        compose.onNodeWithText("song.mp3").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("/Music/song.mp3", openedPath) }
        compose.onNodeWithContentDescription("song.mp3 的更多操作")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun grid_displaysParentForSearchResult() {
        val entry = BrowserEntry(
            path = "/Photos/Trips/sunrise.jpg",
            parent = "/Photos/Trips",
            item = OpenListObject(name = "sunrise.jpg", size = 4_096, type = 0),
        )

        compose.setContent {
            MaterialTheme {
                FileCollection(
                    entries = listOf(entry),
                    layout = CollectionLayout.Grid,
                    contentPadding = PaddingValues(0.dp),
                    onOpen = {},
                    onDetails = {},
                    onFileActions = {},
                    showParent = true,
                )
            }
        }

        compose.onNodeWithText("sunrise.jpg").assertIsDisplayed()
        compose.onNodeWithText("/Photos/Trips").assertIsDisplayed()
    }

    @Test
    fun listTitleIsConstrainedToOneLine() {
        assertTitleIsOneLine(CollectionLayout.List)
    }

    @Test
    fun gridTitleIsConstrainedToOneLine() {
        assertTitleIsOneLine(CollectionLayout.Grid)
    }

    private fun assertTitleIsOneLine(layout: CollectionLayout) {
        val longName = "a-very-long-file-name-that-cannot-fit-on-one-line-without-being-truncated.mp4"
        val entry = BrowserEntry(
            path = "/Videos/$longName",
            parent = "/Videos",
            item = OpenListObject(name = longName, size = 4_096, type = 2),
        )
        compose.setContent {
            MaterialTheme {
                FileCollection(
                    entries = listOf(entry),
                    layout = layout,
                    contentPadding = PaddingValues(0.dp),
                    onOpen = {},
                    onDetails = {},
                    onFileActions = {},
                    modifier = Modifier.width(180.dp),
                )
            }
        }

        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(longName).performSemanticsAction(
            SemanticsActions.GetTextLayoutResult,
        ) { action -> action(results) }
        assertEquals(1, results.single().lineCount)
    }
}
