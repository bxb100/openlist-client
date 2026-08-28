package org.openlist.mobile.media.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GalleryZoomMathTest {
    @Test
    fun displayedContentSizeRespectsFitAspectRatio() {
        val displayed = displayedGalleryContentSize(
            sourceSize = Size(1_000f, 2_000f),
            viewportSize = Size(1_000f, 1_000f),
            contentScale = ContentScale.Fit,
        )

        assertThat(displayed.width).isEqualTo(500f)
        assertThat(displayed.height).isEqualTo(1_000f)
    }

    @Test
    fun applyTransformClampsScaleAndTranslationWithinBounds() {
        val transformed = applyGalleryZoomTransform(
            current = GalleryZoomTransform(scale = 4.8f, offset = Offset(1_000f, -1_000f)),
            zoomChange = 1.2f,
            panChange = Offset(5_000f, -5_000f),
            centroid = Offset(500f, 500f),
            viewportSize = Size(1_000f, 1_000f),
            contentSize = Size(1_000f, 1_000f),
        )

        assertThat(transformed.scale).isEqualTo(5f)
        assertThat(transformed.offset.x).isEqualTo(2_000f)
        assertThat(transformed.offset.y).isEqualTo(-2_000f)
    }

    @Test
    fun portraitFitImageDoesNotAllowHorizontalPanWhileStillNarrowerThanViewport() {
        val clamped = clampGalleryZoomTransform(
            current = GalleryZoomTransform(scale = 2f, offset = Offset(400f, 400f)),
            viewportSize = Size(1_000f, 1_000f),
            contentSize = Size(500f, 1_000f),
        )

        assertThat(clamped.offset.x).isEqualTo(0f)
        assertThat(clamped.offset.y).isEqualTo(400f)
    }

    @Test
    fun zoomingBackToMinimumResetsOffset() {
        val transformed = applyGalleryZoomTransform(
            current = GalleryZoomTransform(scale = 1.4f, offset = Offset(120f, -80f)),
            zoomChange = 0.5f,
            panChange = Offset.Zero,
            centroid = Offset(500f, 500f),
            viewportSize = Size(1_000f, 1_000f),
            contentSize = Size(1_000f, 1_000f),
        )

        assertThat(transformed).isEqualTo(GalleryZoomTransform())
    }

    @Test
    fun zoomAndPanPreserveThePreviousCentroidBeforeApplyingPan() {
        val transformed = applyGalleryZoomTransform(
            current = GalleryZoomTransform(),
            zoomChange = 2f,
            panChange = Offset(20f, 0f),
            centroid = Offset(250f, 500f),
            viewportSize = Size(1_000f, 1_000f),
            contentSize = Size(1_000f, 1_000f),
        )

        assertThat(transformed.scale).isEqualTo(2f)
        assertThat(transformed.offset).isEqualTo(Offset(270f, 0f))
    }
}
