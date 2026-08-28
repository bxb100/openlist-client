package org.openlist.mobile.media.gallery

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.media.MediaEntry
import org.openlist.mobile.media.MediaSequence
import org.openlist.mobile.media.MediaUrlResolver

@Stable
class GalleryState(
    items: List<MediaEntry>,
    initialIndex: Int = 0,
) {
    val items: List<MediaEntry> = items.toList()

    init {
        require(this.items.isNotEmpty()) { "A gallery must not be empty" }
        require(this.items.all { it.kind == MediaKind.IMAGE }) { "A gallery may contain only images" }
        require(initialIndex in this.items.indices) { "initialIndex is outside the gallery" }
    }

    var currentIndex: Int by mutableIntStateOf(initialIndex)
        private set

    val current: MediaEntry get() = items[currentIndex]
    val canGoPrevious: Boolean get() = currentIndex > 0
    val canGoNext: Boolean get() = currentIndex < items.lastIndex

    fun show(index: Int) {
        require(index in items.indices) { "Gallery index is out of bounds" }
        currentIndex = index
    }

    fun previous(): Boolean {
        if (!canGoPrevious) return false
        currentIndex -= 1
        return true
    }

    fun next(): Boolean {
        if (!canGoNext) return false
        currentIndex += 1
        return true
    }
}

@Composable
fun rememberGalleryState(
    sequence: MediaSequence,
    initialIndex: Int = sequence.currentIndex,
): GalleryState {
    require(sequence.kind == MediaKind.IMAGE) { "Only an image sequence can create a gallery" }
    return remember(sequence, initialIndex) { GalleryState(sequence.items, initialIndex) }
}

/** Reusable pager shell for callers that want to supply their own image renderer or zoom surface. */
@Composable
fun GalleryPager(
    state: GalleryState,
    modifier: Modifier = Modifier,
    userScrollEnabled: Boolean = true,
    onImageTap: (() -> Unit)? = null,
    pageContent: @Composable BoxScope.(MediaEntry) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = state.currentIndex,
        pageCount = { state.items.size },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(state::show)
    }
    LaunchedEffect(state.currentIndex) {
        if (pagerState.settledPage != state.currentIndex) {
            // List selections can jump across many pages. An immediate jump avoids intermediate
            // pager pages being observed as new external selections.
            pagerState.scrollToPage(state.currentIndex)
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = userScrollEnabled,
        key = { index -> state.items[index].contentKey.value },
    ) { index ->
        val interactionSource = remember { MutableInteractionSource() }
        val pageModifier = if (onImageTap == null) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClickLabel = "显示或隐藏图片列表",
                    onClick = onImageTap,
                )
        }
        Box(pageModifier) {
            pageContent(state.items[index])
        }
    }
}

/**
 * Ready-to-use gallery that resolves each visible image through fs/get and keys Coil's memory/disk
 * caches by stable [org.openlist.mobile.media.ContentKey], never by an expiring raw URL.
 */
@Composable
fun OpenListGallery(
    state: GalleryState,
    urlResolver: MediaUrlResolver,
    modifier: Modifier = Modifier,
    onImageTap: (() -> Unit)? = null,
    resetZoomKey: Any? = null,
    onZoomChanged: (Boolean) -> Unit = {},
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: (MediaEntry) -> String? = { it.name },
    loading: @Composable BoxScope.() -> Unit = {
        CircularProgressIndicator(Modifier.align(Alignment.Center))
    },
    error: @Composable BoxScope.(MediaEntry) -> Unit = {
        DefaultGalleryError()
    },
) {
    val imageRepository = remember(urlResolver) { GalleryImageRepository(urlResolver) }
    OpenListGallery(
        state = state,
        imageRepository = imageRepository,
        modifier = modifier,
        onImageTap = onImageTap,
        resetZoomKey = resetZoomKey,
        onZoomChanged = onZoomChanged,
        contentScale = contentScale,
        contentDescription = contentDescription,
        loading = loading,
        error = error,
    )
}

/** Managed-cache overload. Prefer constructing [imageRepository] once in the application graph. */
@Composable
fun OpenListGallery(
    state: GalleryState,
    imageRepository: GalleryImageRepository,
    modifier: Modifier = Modifier,
    onImageTap: (() -> Unit)? = null,
    resetZoomKey: Any? = null,
    onZoomChanged: (Boolean) -> Unit = {},
    contentScale: ContentScale = ContentScale.Fit,
    contentDescription: (MediaEntry) -> String? = { it.name },
    loading: @Composable BoxScope.() -> Unit = {
        CircularProgressIndicator(Modifier.align(Alignment.Center))
    },
    error: @Composable BoxScope.(MediaEntry) -> Unit = {
        DefaultGalleryError()
    },
) {
    var currentPageZoomed by remember(state) { mutableStateOf(false) }
    val reportCurrentPageZoomChanged: (Boolean) -> Unit = { zoomed ->
        if (currentPageZoomed != zoomed) {
            currentPageZoomed = zoomed
            onZoomChanged(zoomed)
        }
    }
    LaunchedEffect(state.currentIndex, resetZoomKey) {
        reportCurrentPageZoomChanged(false)
    }
    GalleryPager(
        state = state,
        modifier = modifier,
        userScrollEnabled = !currentPageZoomed,
    ) { entry ->
        val loadState by produceState<GalleryImageLoadState>(
            initialValue = GalleryImageLoadState.Loading,
            entry.contentKey,
            imageRepository,
        ) {
            // produceState retains its State object when keys change; never expose the previous
            // entry's already-unpinned handle while the next image is being acquired.
            value = GalleryImageLoadState.Loading
            try {
                val handle = imageRepository.acquire(entry)
                value = GalleryImageLoadState.Ready(handle)
                // A cache trim/clear cannot remove this blob while its Gallery page is alive.
                awaitDispose(handle::close)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = GalleryImageLoadState.Failed
            }
        }
        when (val result = loadState) {
            GalleryImageLoadState.Loading -> loading()
            is GalleryImageLoadState.Failed -> error(entry)
            is GalleryImageLoadState.Ready -> {
                val context = LocalContext.current
                val request = remember(result.handle.data, entry.contentKey, imageRepository) {
                    ImageRequest.Builder(context)
                        .data(result.handle.data)
                        .memoryCacheKey(entry.contentKey.value)
                        .apply {
                            if (imageRepository.usesManagedDiskCache) {
                                // The complete blob cache is the single policy authority for disk data.
                                diskCachePolicy(CachePolicy.DISABLED)
                            } else {
                                diskCacheKey(entry.contentKey.value)
                            }
                        }
                        .build()
                }
                var imageState by remember(request) { mutableStateOf<RenderedImageState>(RenderedImageState.Loading) }
                var intrinsicImageSize by remember(request) { mutableStateOf(Size.Unspecified) }
                val currentContentKey = state.current.contentKey
                val effectiveResetZoomKey = remember(state.currentIndex, resetZoomKey) {
                    state.currentIndex to resetZoomKey
                }
                val pageZoomChanged: (Boolean) -> Unit = if (entry.contentKey == currentContentKey) {
                    reportCurrentPageZoomChanged
                } else {
                    {}
                }
                ZoomableGalleryImage(
                    request = request,
                    contentDescription = contentDescription(entry),
                    contentScale = contentScale,
                    intrinsicImageSize = intrinsicImageSize,
                    resetZoomKey = effectiveResetZoomKey,
                    onTap = onImageTap,
                    onZoomChanged = pageZoomChanged,
                    onLoading = { imageState = RenderedImageState.Loading },
                    onSuccess = { loadedSize ->
                        imageState = RenderedImageState.Ready
                        intrinsicImageSize = loadedSize
                    },
                    onError = { imageState = RenderedImageState.Failed },
                )
                when (imageState) {
                    RenderedImageState.Loading -> loading()
                    RenderedImageState.Failed -> error(entry)
                    RenderedImageState.Ready -> Unit
                }
            }
        }
    }
}

private sealed interface GalleryImageLoadState {
    data object Loading : GalleryImageLoadState
    data class Ready(val handle: GalleryImageHandle) : GalleryImageLoadState
    data object Failed : GalleryImageLoadState
}

private enum class RenderedImageState { Loading, Ready, Failed }

@Composable
private fun ZoomableGalleryImage(
    request: ImageRequest,
    contentDescription: String?,
    contentScale: ContentScale,
    intrinsicImageSize: Size,
    resetZoomKey: Any?,
    onTap: (() -> Unit)?,
    onZoomChanged: (Boolean) -> Unit,
    onLoading: () -> Unit,
    onSuccess: (Size) -> Unit,
    onError: () -> Unit,
) {
    var viewportSize by remember(request) { mutableStateOf(IntSize.Zero) }
    var zoomTransform by remember(request, resetZoomKey) {
        mutableStateOf(GalleryZoomTransform())
    }
    val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val displayedContentSize = remember(intrinsicImageSize, viewportSize, contentScale) {
        displayedGalleryContentSize(
            sourceSize = intrinsicImageSize,
            viewportSize = viewportSize.toComposeSize(),
            contentScale = contentScale,
        )
    }
    val isZoomed = isGalleryZoomed(zoomTransform.scale)
    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestDisplayedContentSize by rememberUpdatedState(displayedContentSize)

    LaunchedEffect(displayedContentSize, viewportSize) {
        zoomTransform = clampGalleryZoomTransform(
            current = zoomTransform,
            viewportSize = viewportSize.toComposeSize(),
            contentSize = displayedContentSize,
        )
    }
    LaunchedEffect(isZoomed) {
        latestOnZoomChanged(isZoomed)
    }
    DisposableEffect(Unit) {
        onDispose { latestOnZoomChanged(false) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
            .pointerInput(resetZoomKey, contentScale) {
                awaitEachGesture {
                    var singlePointerPanStarted = false
                    var accumulatedSinglePointerPan = Offset.Zero
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        when {
                            pressed.size > 1 -> {
                                singlePointerPanStarted = true
                                val updated = applyGalleryZoomTransform(
                                    current = zoomTransform,
                                    zoomChange = event.calculateZoom(),
                                    panChange = event.calculatePan(),
                                    // The transform formula preserves the point under the previous
                                    // centroid, then applies calculatePan to reach the new centroid.
                                    centroid = event.calculateCentroid(useCurrent = false),
                                    viewportSize = latestViewportSize.toComposeSize(),
                                    contentSize = latestDisplayedContentSize,
                                )
                                if (updated != zoomTransform) {
                                    zoomTransform = updated
                                }
                                pressed.forEach { change ->
                                    if (change.positionChanged()) change.consume()
                                }
                            }

                            isGalleryZoomed(zoomTransform.scale) -> {
                                val change = pressed.first()
                                val panChange = change.position - change.previousPosition
                                accumulatedSinglePointerPan += panChange
                                val crossedTouchSlop =
                                    accumulatedSinglePointerPan.getDistance() > viewConfiguration.touchSlop
                                val startedNow = !singlePointerPanStarted && crossedTouchSlop
                                if (startedNow) singlePointerPanStarted = true
                                if (singlePointerPanStarted && panChange != Offset.Zero) {
                                    val updated = applyGalleryZoomTransform(
                                        current = zoomTransform,
                                        zoomChange = 1f,
                                        panChange = if (startedNow) accumulatedSinglePointerPan else panChange,
                                        centroid = change.position,
                                        viewportSize = latestViewportSize.toComposeSize(),
                                        contentSize = latestDisplayedContentSize,
                                    )
                                    if (updated != zoomTransform) {
                                        zoomTransform = updated
                                    }
                                }
                                if (singlePointerPanStarted && change.positionChanged()) {
                                    change.consume()
                                }
                            }
                        }
                    } while (true)
                }
            }
            .then(
                if (onTap == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(onTap) {
                        detectTapGestures(onTap = { onTap() })
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoomTransform.scale
                    scaleY = zoomTransform.scale
                    translationX = zoomTransform.offset.x
                    translationY = zoomTransform.offset.y
                },
            onLoading = { onLoading() },
            onSuccess = { state -> onSuccess(state.painter.intrinsicSize) },
            onError = { onError() },
        )
    }
}

private fun IntSize.toComposeSize(): Size = Size(width.toFloat(), height.toFloat())

@Composable
private fun BoxScope.DefaultGalleryError() {
    Text(
        text = "图片加载失败",
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.align(Alignment.Center),
    )
}
