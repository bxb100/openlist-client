package org.openlist.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.media.ContentKey
import org.openlist.mobile.media.MediaEntry
import org.openlist.mobile.media.gallery.GalleryState

@RunWith(AndroidJUnit4::class)
class GalleryViewerContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingMainImageTogglesViewerControls() {
        setGalleryContent(GalleryState(imageEntries()))

        compose.onNodeWithContentDescription("关闭图片浏览").assertIsDisplayed()
        compose.onNodeWithTag(TEST_GALLERY_IMAGE).performClick()
        compose.onNodeWithContentDescription("关闭图片浏览").assertDoesNotExist()

        compose.onNodeWithTag(TEST_GALLERY_IMAGE).performClick()
        compose.onNodeWithContentDescription("关闭图片浏览").assertIsDisplayed()
        compose.onNodeWithContentDescription("同目录图片").performClick()
        compose.onNodeWithTag(OpenListUiTags.GALLERY_IMAGE_LIST).assertIsDisplayed()
    }

    @Test
    fun selectingFarImageJumpsGalleryAndUpdatesSelectionSemantics() {
        val state = GalleryState(imageEntries())
        setGalleryContent(state)

        compose.onNodeWithContentDescription("同目录图片").performClick()
        compose.onNodeWithTag(OpenListUiTags.galleryImageItem(0)).assertIsSelected()

        compose.onNodeWithTag(OpenListUiTags.GALLERY_IMAGE_LIST).performScrollToIndex(FAR_INDEX)
        compose.onNodeWithTag(OpenListUiTags.galleryImageItem(FAR_INDEX))
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle { assertEquals(FAR_INDEX, state.currentIndex) }
        compose.onNodeWithTag(OpenListUiTags.GALLERY_IMAGE_LIST).assertIsDisplayed()
        compose.onNodeWithTag(OpenListUiTags.galleryImageItem(FAR_INDEX)).assertIsSelected()

        compose.onNodeWithTag(OpenListUiTags.GALLERY_IMAGE_LIST).performScrollToIndex(0)
        compose.onNodeWithTag(OpenListUiTags.galleryImageItem(0)).assertIsNotSelected()
    }

    @Test
    fun zoomStateShowsResetAndResetButtonClearsIt() {
        setGalleryContent(GalleryState(imageEntries()), exposeZoomControl = true)

        compose.onNodeWithTag(OpenListUiTags.GALLERY_RESET_ZOOM).assertDoesNotExist()
        compose.onNodeWithTag(TEST_ZOOM_CONTROL).performClick()
        compose.onNodeWithTag(OpenListUiTags.GALLERY_RESET_ZOOM).assertIsDisplayed().performClick()
        compose.onNodeWithTag(OpenListUiTags.GALLERY_RESET_ZOOM).assertDoesNotExist()
    }

    @Test
    fun switchingImagesClearsZoomState() {
        val state = GalleryState(imageEntries())
        setGalleryContent(state, exposeZoomControl = true)
        compose.onNodeWithTag(TEST_ZOOM_CONTROL).performClick()
        compose.onNodeWithTag(OpenListUiTags.GALLERY_RESET_ZOOM).assertIsDisplayed()

        compose.runOnIdle { state.next() }

        compose.onNodeWithTag(OpenListUiTags.GALLERY_RESET_ZOOM).assertDoesNotExist()
    }

    private fun setGalleryContent(
        state: GalleryState,
        exposeZoomControl: Boolean = false,
    ) {
        compose.setContent {
            MaterialTheme {
                GalleryViewerContent(
                    state = state,
                    onDismiss = {},
                    modifier = Modifier.fillMaxSize(),
                ) { onImageTap, _, onZoomChanged ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(TEST_GALLERY_IMAGE)
                            .clickable(onClick = onImageTap),
                    ) {
                        if (exposeZoomControl) {
                            Button(
                                onClick = { onZoomChanged(true) },
                                modifier = Modifier.testTag(TEST_ZOOM_CONTROL),
                            ) {
                                Text("模拟缩放")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun imageEntries(count: Int = 30): List<MediaEntry> = List(count) { index ->
        MediaEntry(
            remotePath = "/photos/image-$index.jpg",
            name = "image-$index.jpg",
            kind = MediaKind.IMAGE,
            size = 1_024L + index,
            modified = "2026-08-27T00:00:00Z",
            contentKey = ContentKey("gallery-test-image-$index"),
        )
    }

    private companion object {
        const val TEST_GALLERY_IMAGE = "test_gallery_image"
        const val TEST_ZOOM_CONTROL = "test_zoom_control"
        const val FAR_INDEX = 24
    }
}
