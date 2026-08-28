package org.openlist.mobile.media.gallery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale

internal const val GALLERY_MIN_SCALE = 1f
internal const val GALLERY_MAX_SCALE = 5f
private const val GALLERY_ZOOM_EPSILON = 0.01f

internal data class GalleryZoomTransform(
    val scale: Float = GALLERY_MIN_SCALE,
    val offset: Offset = Offset.Zero,
)

internal fun isGalleryZoomed(scale: Float): Boolean = scale > GALLERY_MIN_SCALE + GALLERY_ZOOM_EPSILON

internal fun displayedGalleryContentSize(
    sourceSize: Size,
    viewportSize: Size,
    contentScale: ContentScale,
): Size {
    val safeViewport = viewportSize.takeIf { it.width > 0f && it.height > 0f } ?: return Size.Zero
    val safeSource = sourceSize.takeIf { it.width > 0f && it.height > 0f } ?: return safeViewport
    val scaleFactor = contentScale.computeScaleFactor(safeSource, safeViewport)
    return Size(
        width = safeSource.width * scaleFactor.scaleX,
        height = safeSource.height * scaleFactor.scaleY,
    )
}

internal fun applyGalleryZoomTransform(
    current: GalleryZoomTransform,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    viewportSize: Size,
    contentSize: Size,
    minScale: Float = GALLERY_MIN_SCALE,
    maxScale: Float = GALLERY_MAX_SCALE,
): GalleryZoomTransform {
    val safeViewport = viewportSize.takeIf { it.width > 0f && it.height > 0f } ?: return current
    val currentScale = current.scale.coerceIn(minScale, maxScale)
    val nextScale = (currentScale * zoomChange)
        .takeIf(Float::isFinite)
        ?.coerceIn(minScale, maxScale)
        ?: currentScale
    if (!isGalleryZoomed(nextScale)) return GalleryZoomTransform()

    val zoomRatio = nextScale / currentScale
    val viewportCenter = Offset(safeViewport.width / 2f, safeViewport.height / 2f)
    val focalOffset = centroid - viewportCenter
    val translated = Offset(
        x = current.offset.x * zoomRatio + (1f - zoomRatio) * focalOffset.x + panChange.x,
        y = current.offset.y * zoomRatio + (1f - zoomRatio) * focalOffset.y + panChange.y,
    )
    return clampGalleryZoomTransform(
        current = GalleryZoomTransform(scale = nextScale, offset = translated),
        viewportSize = safeViewport,
        contentSize = contentSize,
        minScale = minScale,
        maxScale = maxScale,
    )
}

internal fun clampGalleryZoomTransform(
    current: GalleryZoomTransform,
    viewportSize: Size,
    contentSize: Size,
    minScale: Float = GALLERY_MIN_SCALE,
    maxScale: Float = GALLERY_MAX_SCALE,
): GalleryZoomTransform {
    val safeViewport = viewportSize.takeIf { it.width > 0f && it.height > 0f } ?: return GalleryZoomTransform()
    val scale = current.scale
        .takeIf(Float::isFinite)
        ?.coerceIn(minScale, maxScale)
        ?: GALLERY_MIN_SCALE
    if (!isGalleryZoomed(scale)) return GalleryZoomTransform()

    val safeContent = contentSize.takeIf { it.width > 0f && it.height > 0f } ?: safeViewport
    val horizontalBound = ((safeContent.width * scale) - safeViewport.width).coerceAtLeast(0f) / 2f
    val verticalBound = ((safeContent.height * scale) - safeViewport.height).coerceAtLeast(0f) / 2f
    return GalleryZoomTransform(
        scale = scale,
        offset = Offset(
            x = current.offset.x.coerceIn(-horizontalBound, horizontalBound),
            y = current.offset.y.coerceIn(-verticalBound, verticalBound),
        ),
    )
}
