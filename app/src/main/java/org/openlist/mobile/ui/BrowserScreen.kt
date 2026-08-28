package org.openlist.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.openlist.mobile.AppContainer
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.model.joinRemotePath
import org.openlist.mobile.core.model.parentRemotePath
import org.openlist.mobile.data.api.OpenListApiException
import org.openlist.mobile.data.api.dto.SearchObject
import org.openlist.mobile.media.MediaTypeDetector
import org.openlist.mobile.media.MediaSequence
import org.openlist.mobile.media.gallery.GalleryState
import org.openlist.mobile.media.gallery.GalleryImageRepository
import org.openlist.mobile.media.gallery.OpenListGallery
import org.openlist.mobile.media.gallery.rememberGalleryState
import java.text.DecimalFormat
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.math.roundToInt

internal object OpenListUiTags {
    const val FILE_COLLECTION = "file_collection"
    const val EMPTY_STATE = "empty_state"
    const val ERROR_STATE = "error_state"
    const val BROWSER_APP_BAR = "browser_app_bar"
    const val BROWSER_APP_BAR_FRAME = "browser_app_bar_frame"
    const val BREADCRUMB_BAR = "breadcrumb_bar"
    const val SEARCH_FIELD = "search_field"
    const val PLAYBACK_QUEUE_FAB = "playback_queue_fab"
    const val UPLOAD_FAB = "upload_fab"
    const val GALLERY = "gallery"
    const val GALLERY_IMAGE_LIST = "gallery_image_list"
    const val GALLERY_RESET_ZOOM = "gallery_reset_zoom"

    fun galleryImageItem(index: Int): String = "gallery_image_item_$index"
}

internal enum class CollectionLayout { List, Grid }

internal enum class BrowserSortField(val label: String) {
    Name("名称"),
    Modified("修改时间"),
    Type("类型"),
    Size("大小"),
}

internal enum class BrowserSortDirection(val label: String) {
    Ascending("升序"),
    Descending("降序"),
}

internal data class BrowserSort(
    val field: BrowserSortField = BrowserSortField.Name,
    val direction: BrowserSortDirection = BrowserSortDirection.Ascending,
)

internal fun visibleStorageProvider(provider: String): String? = provider
    .trim()
    .takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }

internal fun browserDirectorySummary(total: Long, provider: String): String =
    visibleStorageProvider(provider)?.let { "$total 项 · $it" } ?: "$total 项"

internal data class BrowserEntry(
    val path: String,
    val parent: String,
    val item: OpenListObject,
)

/** Keeps the official app-bar state bounded by the measured TopAppBar height. */
internal fun updateBrowserHeaderHeight(
    state: TopAppBarState,
    fullHeightPx: Float,
) {
    if (!fullHeightPx.isFinite() || fullHeightPx < 0f) return
    val previousLimit = state.heightOffsetLimit
    val collapsedFraction = if (previousLimit.isFinite() && previousLimit < 0f) {
        (state.heightOffset / previousLimit).coerceIn(0f, 1f)
    } else {
        0f
    }
    val newLimit = -fullHeightPx
    if (newLimit == previousLimit) return
    state.heightOffsetLimit = newLimit
    state.heightOffset = newLimit * collapsedFraction
}

internal fun resetBrowserHeaderScroll(state: TopAppBarState) {
    state.heightOffset = 0f
    state.contentOffset = 0f
}

internal fun browserHeaderVisibleHeight(
    fullHeightPx: Int,
    heightOffsetPx: Float,
): Int = (fullHeightPx + heightOffsetPx)
    .roundToInt()
    .coerceIn(0, fullHeightPx)

private fun Modifier.browserHeaderScrollLayout(
    state: TopAppBarState,
): Modifier = clipToBounds().layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minHeight = 0))
    val visibleHeight = browserHeaderVisibleHeight(placeable.height, state.heightOffset)
    layout(placeable.width, visibleHeight) {
        // The fixed breadcrumb and body move up naturally while neither enters this clipped frame.
        placeable.placeRelative(x = 0, y = visibleHeight - placeable.height)
    }
}

/** Emits drag/fling nested-scroll events even when the displayed state has no scrollable list. */
@Composable
internal fun BrowserScrollGestureSurface(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val scrollableState = rememberScrollableState { 0f }
    Box(
        modifier = modifier.scrollable(
            state = scrollableState,
            orientation = Orientation.Vertical,
        ),
        content = content,
    )
}

/** Measures and clips only the app bar; persistent navigation belongs outside this layout. */
@Composable
internal fun BrowserHeaderLayout(
    state: TopAppBarState,
    modifier: Modifier = Modifier,
    onMeasuredHeightChange: (Int) -> Unit = {},
    appBar: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .browserHeaderScrollLayout(state)
            .testTag(OpenListUiTags.BROWSER_APP_BAR_FRAME),
    ) {
        Box(
            modifier = Modifier.onSizeChanged {
                onMeasuredHeightChange(it.height)
                updateBrowserHeaderHeight(state, it.height.toFloat())
            },
            content = appBar,
        )
    }
}

/**
 * Sorts one immutable directory snapshot. Directories always remain ahead of files, while missing
 * values stay at the end in both directions. The final name/path comparison makes every result
 * deterministic, which also keeps same-kind media queues aligned with the visible collection.
 */
internal fun sortBrowserEntries(
    entries: List<BrowserEntry>,
    sort: BrowserSort,
): List<BrowserEntry> {
    if (entries.size < 2) return entries
    val decorated = entries.map(::SortableBrowserEntry)
    return decorated.sortedWith { first, second ->
        compareValues(first.entry.item.isDirectory.not(), second.entry.item.isDirectory.not())
            .takeUnless { it == 0 }
            ?: compareBrowserSortValue(first, second, sort)
                .takeUnless { it == 0 }
            ?: first.normalizedName.compareTo(second.normalizedName)
                .takeUnless { it == 0 }
            ?: first.entry.item.name.compareTo(second.entry.item.name)
                .takeUnless { it == 0 }
            ?: first.entry.path.compareTo(second.entry.path)
    }.map(SortableBrowserEntry::entry)
}

private data class SortableBrowserEntry(
    val entry: BrowserEntry,
    val normalizedName: String = entry.item.name.lowercase(Locale.ROOT),
    val modified: String? = entry.item.modified.trim().takeIf(String::isNotEmpty),
    val modifiedInstant: java.time.Instant? = modified?.let { value ->
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    },
    val typeRank: Int = mediaTypeSortRank(MediaTypeDetector.kind(entry.item)),
    val extension: String? = entry.item.name.substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf(String::isNotEmpty),
    val size: Long? = entry.item.size.takeIf { it >= 0L },
)

private fun compareBrowserSortValue(
    first: SortableBrowserEntry,
    second: SortableBrowserEntry,
    sort: BrowserSort,
): Int = when (sort.field) {
    BrowserSortField.Name -> compareOptional(
        first.normalizedName.takeIf(String::isNotEmpty),
        second.normalizedName.takeIf(String::isNotEmpty),
        sort.direction,
    )
    BrowserSortField.Modified -> compareModified(first, second, sort.direction)
    BrowserSortField.Type -> directedComparison(
        first.typeRank.compareTo(second.typeRank),
        sort.direction,
    ).takeUnless { it == 0 } ?: compareOptional(
        first.extension,
        second.extension,
        sort.direction,
    )
    BrowserSortField.Size -> compareOptional(first.size, second.size, sort.direction)
}

private fun compareModified(
    first: SortableBrowserEntry,
    second: SortableBrowserEntry,
    direction: BrowserSortDirection,
): Int = compareOptional(first.modified, second.modified, direction) { firstValue, secondValue ->
        val firstInstant = first.modifiedInstant
        val secondInstant = second.modifiedInstant
        if (firstInstant != null && secondInstant != null) {
            firstInstant.compareTo(secondInstant)
        } else {
            firstValue.compareTo(secondValue)
        }
    }

private fun <T : Comparable<T>> compareOptional(
    first: T?,
    second: T?,
    direction: BrowserSortDirection,
): Int = compareOptional(first, second, direction) { firstValue, secondValue ->
    firstValue.compareTo(secondValue)
}

private inline fun <T> compareOptional(
    first: T?,
    second: T?,
    direction: BrowserSortDirection,
    comparePresent: (T, T) -> Int,
): Int = when {
    first == null && second == null -> 0
    first == null -> 1
    second == null -> -1
    else -> directedComparison(comparePresent(first, second), direction)
}

private fun directedComparison(
    comparison: Int,
    direction: BrowserSortDirection,
): Int = if (direction == BrowserSortDirection.Ascending) comparison else -comparison

private fun mediaTypeSortRank(kind: MediaKind): Int = when (kind) {
    MediaKind.DIRECTORY -> 0
    MediaKind.IMAGE -> 1
    MediaKind.VIDEO -> 2
    MediaKind.AUDIO -> 3
    MediaKind.TEXT -> 4
    MediaKind.OTHER -> 5
}

internal class LastRequestWinsGate {
    private var latestRequestId: Long = 0L

    fun begin(): Long {
        latestRequestId += 1L
        return latestRequestId
    }

    fun invalidate() {
        latestRequestId += 1L
    }

    fun isLatest(requestId: Long): Boolean = latestRequestId == requestId

    inline fun completeIfLatest(
        requestId: Long,
        block: () -> Unit,
    ): Boolean {
        if (!isLatest(requestId)) return false
        block()
        return true
    }
}

internal data class RelatedMediaBuckets(
    val videos: List<BrowserEntry>,
    val images: List<BrowserEntry>,
) {
    companion object {
        val Empty = RelatedMediaBuckets(
            videos = emptyList(),
            images = emptyList(),
        )
    }
}

internal fun stableDirectorySiblingEntries(
    selected: BrowserEntry,
    candidates: List<BrowserEntry>,
): List<BrowserEntry> {
    val byPath = LinkedHashMap<String, BrowserEntry>()
    candidates.forEach { candidate ->
        if (candidate.parent == selected.parent) {
            byPath.putIfAbsent(candidate.path, candidate)
        }
    }
    byPath[selected.path] = selected
    return byPath.values.toList()
}

internal fun relatedMediaBuckets(entries: List<BrowserEntry>): RelatedMediaBuckets {
    val videos = ArrayList<BrowserEntry>()
    val images = ArrayList<BrowserEntry>()
    entries.forEach { entry ->
        if (entry.item.isDirectory) return@forEach
        when (MediaTypeDetector.kind(entry.item)) {
            MediaKind.VIDEO -> videos += entry
            MediaKind.IMAGE -> images += entry
            else -> Unit
        }
    }
    return RelatedMediaBuckets(videos = videos, images = images)
}

private data class GalleryRequest(
    val sequence: MediaSequence,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserScreen(
    container: AppContainer,
    onOpenMedia: (String, OpenListObject, List<OpenListObject>) -> Unit,
    onUploadRequested: (String) -> Unit,
    onDownloadRequested: (String, OpenListObject) -> Unit,
    onFileActionsRequested: (String, OpenListObject) -> Unit,
    galleryImageRepository: GalleryImageRepository?,
    hasPlaybackQueue: Boolean = false,
    onPlaybackQueueRequested: () -> Unit = {},
) {
    var path by rememberSaveable { mutableStateOf("/") }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<DirectoryListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var forceRefreshPath by remember { mutableStateOf<String?>(null) }
    var layout by rememberSaveable { mutableStateOf(CollectionLayout.List) }
    var sortField by rememberSaveable { mutableStateOf(BrowserSortField.Name) }
    var sortDirection by rememberSaveable { mutableStateOf(BrowserSortDirection.Ascending) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var detailEntry by remember { mutableStateOf<BrowserEntry?>(null) }
    var fileActionsEntry by remember { mutableStateOf<BrowserEntry?>(null) }
    var gallery by remember { mutableStateOf<GalleryRequest?>(null) }
    var preparingMedia by remember { mutableStateOf(false) }
    var passwordChallengePath by remember { mutableStateOf<String?>(null) }
    var passwordSubmitting by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var mediaPreparationJob by remember { mutableStateOf<Job?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val mediaRequestGate = remember { LastRequestWinsGate() }
    val scope = rememberCoroutineScope()
    val browserHeaderState = rememberTopAppBarState()
    val browserHeaderCanScroll = rememberUpdatedState(!searchVisible)
    val browserHeaderCanScrollCallback = remember {
        { browserHeaderCanScroll.value }
    }
    // Keep Material 3's default snap/fling specs so a released half-collapsed header settles.
    val browserHeaderScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = browserHeaderState,
        canScroll = browserHeaderCanScrollCallback,
    )

    fun cancelPendingMediaPreparation() {
        mediaRequestGate.invalidate()
        mediaPreparationJob?.cancel()
        mediaPreparationJob = null
        preparingMedia = false
    }

    fun setSearchVisible(visible: Boolean) {
        resetBrowserHeaderScroll(browserHeaderState)
        searchVisible = visible
    }

    fun navigateTo(nextPath: String) {
        cancelPendingMediaPreparation()
        resetBrowserHeaderScroll(browserHeaderState)
        path = normalizedRemotePath(nextPath)
        searchVisible = false
    }

    DisposableEffect(mediaRequestGate) {
        onDispose {
            mediaRequestGate.invalidate()
            mediaPreparationJob?.cancel()
        }
    }

    LaunchedEffect(path, searchVisible) {
        // A new directory and the stable search layout both start with all navigation controls.
        resetBrowserHeaderScroll(browserHeaderState)
    }

    LaunchedEffect(path, reloadKey) {
        if (passwordChallengePath != path) {
            passwordChallengePath = null
            passwordSubmitting = false
            passwordError = null
        }
        val refreshing = forceRefreshPath == path
        forceRefreshPath = null
        if (loadedPath != path) {
            listing = null
            loadedPath = null
        }
        loading = true
        error = null
        try {
            val result = withTimeout(DIRECTORY_LOAD_TIMEOUT_MS) {
                container.repository.list(path, refresh = refreshing)
            }
            listing = result
            loadedPath = path
        } catch (_: TimeoutCancellationException) {
            error = "目录加载超时，请检查服务器连接后重试"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            error = throwable.message ?: "无法加载此目录"
            if (throwable.isForbiddenAccess()) {
                passwordChallengePath = path
                passwordError = null
            }
        } finally {
            loading = false
        }
    }

    val sort = BrowserSort(sortField, sortDirection)
    val directoryEntries = remember(listing?.content, path, sortField, sortDirection) {
        val entries = listing?.content.orEmpty().map { item ->
            BrowserEntry(path = joinRemotePath(path, item.name), parent = path, item = item)
        }
        sortBrowserEntries(entries, sort)
    }
    fun openEntry(
        entry: BrowserEntry,
        completeDirectoryPeers: List<BrowserEntry> = emptyList(),
    ) {
        val selectedKind = MediaTypeDetector.kind(entry.item)
        when (selectedKind) {
            MediaKind.DIRECTORY -> {
                cancelPendingMediaPreparation()
                navigateTo(entry.path)
            }
            MediaKind.IMAGE -> {
                val requestId = mediaRequestGate.begin()
                mediaPreparationJob?.cancel()
                mediaPreparationJob = scope.launch {
                    preparingMedia = true
                    try {
                        val siblings = if (completeDirectoryPeers.isNotEmpty()) {
                            computeStableDirectorySiblingEntries(entry, completeDirectoryPeers)
                        } else {
                            emptyList()
                        }
                        val sequence = withTimeout(BROWSER_REQUEST_TIMEOUT_MS) {
                            if (siblings.isNotEmpty()) {
                                container.mediaSequenceBuilder.build(
                                    currentPath = entry.path,
                                    current = entry.item,
                                    siblings = siblings.map(BrowserEntry::item),
                                )
                            } else {
                                container.mediaSequenceBuilder.build(entry.path, entry.item)
                            }
                        }
                        mediaRequestGate.completeIfLatest(requestId) {
                            gallery = GalleryRequest(sequence)
                        }
                    } catch (_: TimeoutCancellationException) {
                        if (mediaRequestGate.isLatest(requestId)) {
                            snackbarHostState.showSnackbar("读取同目录图片超时，请重试")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        if (mediaRequestGate.isLatest(requestId)) {
                            snackbarHostState.showSnackbar(throwable.message ?: "无法打开图片")
                        }
                    } finally {
                        if (mediaRequestGate.isLatest(requestId)) {
                            preparingMedia = false
                            mediaPreparationJob = null
                        }
                    }
                }
            }
            MediaKind.AUDIO, MediaKind.VIDEO -> {
                val requestId = mediaRequestGate.begin()
                mediaPreparationJob?.cancel()
                mediaPreparationJob = scope.launch {
                    preparingMedia = true
                    try {
                        val siblings = if (completeDirectoryPeers.isNotEmpty()) {
                            computeStableDirectorySiblingEntries(entry, completeDirectoryPeers)
                        } else {
                            emptyList()
                        }
                        if (mediaRequestGate.isLatest(requestId)) {
                            onOpenMedia(entry.path, entry.item, siblings.map(BrowserEntry::item))
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        if (mediaRequestGate.isLatest(requestId)) {
                            snackbarHostState.showSnackbar(throwable.message ?: "无法打开媒体")
                        }
                    } finally {
                        if (mediaRequestGate.isLatest(requestId)) {
                            preparingMedia = false
                            mediaPreparationJob = null
                        }
                    }
                }
            }
            MediaKind.TEXT, MediaKind.OTHER -> {
                cancelPendingMediaPreparation()
                detailEntry = entry
            }
        }
    }

    BackHandler(searchVisible || path != "/") {
        if (searchVisible) setSearchVisible(false) else navigateTo(parentRemotePath(path))
    }

    Column(
        Modifier
            .fillMaxSize()
            .nestedScroll(browserHeaderScrollBehavior.nestedScrollConnection),
    ) {
        // Only BrowserHeaderLayout changes height. The breadcrumb is a fixed sibling outside
        // both the clipped app-bar frame and the content Scaffold.
        BrowserScrollGestureSurface(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(TopAppBarDefaults.windowInsets),
        ) {
            Column {
                BrowserHeaderLayout(state = browserHeaderState) {
                    TopAppBar(
                        modifier = Modifier.testTag(OpenListUiTags.BROWSER_APP_BAR),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        title = {
                            Column {
                                Text(
                                    if (path == "/") "文件" else path.substringAfterLast('/'),
                                    modifier = Modifier.basicMarquee(),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip,
                                )
                                Text(
                                    listing?.let {
                                        browserDirectorySummary(it.total, it.provider)
                                    } ?: path,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        navigationIcon = {
                            if (path != "/") {
                                IconButton(onClick = { navigateTo(parentRemotePath(path)) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回上级目录",
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { setSearchVisible(true) }) {
                                Icon(Icons.Default.Search, contentDescription = "搜索当前目录")
                            }
                            BrowserSortMenu(
                                sort = sort,
                                onSortChange = {
                                    sortField = it.field
                                    sortDirection = it.direction
                                },
                            )
                            IconButton(
                                onClick = {
                                    layout = if (layout == CollectionLayout.List) {
                                        CollectionLayout.Grid
                                    } else {
                                        CollectionLayout.List
                                    }
                                },
                            ) {
                                Icon(
                                    if (layout == CollectionLayout.List) {
                                        Icons.Default.GridView
                                    } else {
                                        Icons.AutoMirrored.Filled.List
                                    },
                                    contentDescription = if (layout == CollectionLayout.List) {
                                        "切换到网格"
                                    } else {
                                        "切换到列表"
                                    },
                                )
                            }
                            IconButton(
                                onClick = {
                                    forceRefreshPath = path
                                    reloadKey++
                                },
                                enabled = !loading,
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
                BreadcrumbBar(
                    path = path,
                    onNavigate = ::navigateTo,
                )
            }
        }

        Scaffold(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // The fixed header owns the top safe area; the body still protects display edges
            // and the navigation bar when ResponsiveDestinationHost has no bottom navigation.
            contentWindowInsets = WindowInsets.safeDrawing.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
            ),
            floatingActionButton = {
                val showUpload = !searchVisible && listing?.write == true
                BrowserFloatingActions(
                    showPlaybackQueue = hasPlaybackQueue,
                    showUpload = showUpload,
                    onPlaybackQueueRequested = onPlaybackQueueRequested,
                    onUploadRequested = { onUploadRequested(path) },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                if (searchVisible) {
                    Column(Modifier.fillMaxSize()) {
                        SearchTopBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            placeholder = "在 $path 中搜索",
                            onClose = { setSearchVisible(false) },
                        )
                        Box(Modifier.weight(1f)) {
                            SearchResults(
                                container = container,
                                parent = path,
                                query = searchQuery,
                                layout = layout,
                                onOpen = { entry, peers -> openEntry(entry, peers) },
                                onDetails = { detailEntry = it },
                                onFileActions = { entry ->
                                    fileActionsEntry = entry
                                    onFileActionsRequested(entry.path, entry.item)
                                },
                            )
                        }
                    }
                } else {
                    DirectoryContent(
                        entries = directoryEntries,
                        listing = listing,
                        layout = layout,
                        loading = loading,
                        error = error,
                        onRetry = { reloadKey++ },
                        onUpload = { onUploadRequested(path) },
                        onOpen = { entry -> openEntry(entry, directoryEntries) },
                        onDetails = { detailEntry = it },
                        onFileActions = { entry ->
                            fileActionsEntry = entry
                            onFileActionsRequested(entry.path, entry.item)
                        },
                    )
                }
                if (preparingMedia) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = CircleShape,
                        tonalElevation = 6.dp,
                        shadowElevation = 4.dp,
                    ) {
                        CircularProgressIndicator(Modifier.padding(18.dp).size(30.dp))
                    }
                }
            }
        }
    }

    detailEntry?.let { entry ->
        FileDetailsSheet(
            container = container,
            entry = entry,
            onDismiss = { detailEntry = null },
            onOpen = {
                detailEntry = null
                openEntry(entry, directoryEntries)
            },
            onMoreActions = {
                detailEntry = null
                fileActionsEntry = entry
                onFileActionsRequested(entry.path, entry.item)
            },
            onDownload = {
                detailEntry = null
                onDownloadRequested(entry.path, entry.item)
            },
            onOpenRelated = { related, relatedEntries ->
                detailEntry = null
                openEntry(related, relatedEntries)
            },
        )
    }

    fileActionsEntry?.let { entry ->
        FileActionsDialog(
            container = container,
            entry = entry,
            onDismiss = { fileActionsEntry = null },
            onDownload = {
                fileActionsEntry = null
                onDownloadRequested(entry.path, entry.item)
            },
            onChanged = { message ->
                fileActionsEntry = null
                if (searchVisible) setSearchVisible(false)
                forceRefreshPath = path
                reloadKey++
                scope.launch { snackbarHostState.showSnackbar(message) }
            },
        )
    }

    gallery?.let { request ->
        GalleryDialog(
            container = container,
            request = request,
            imageRepository = galleryImageRepository,
            onDismiss = { gallery = null },
        )
    }

    passwordChallengePath?.let { challengedPath ->
        ProtectedDirectoryPasswordDialog(
            path = challengedPath,
            submitting = passwordSubmitting,
            error = passwordError,
            onDismiss = {
                if (!passwordSubmitting) {
                    passwordChallengePath = null
                    passwordError = null
                }
            },
            onSubmit = { password ->
                scope.launch {
                    passwordSubmitting = true
                    passwordError = null
                    try {
                        // A challenge retry intentionally avoids force-refresh: OpenList also
                        // uses 403 when a user may browse but lacks refresh permission.
                        val result = container.repository.unlockDirectory(
                            path = challengedPath,
                            password = password,
                        )
                        if (path == challengedPath) {
                            listing = result
                            loadedPath = challengedPath
                            error = null
                            loading = false
                        }
                        passwordChallengePath = null
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        passwordError = if (throwable.isForbiddenAccess()) {
                            "密码不正确，或当前账号无权访问。请修改后重试。"
                        } else {
                            throwable.message ?: "验证目录密码失败"
                        }
                    } finally {
                        passwordSubmitting = false
                    }
                }
            },
        )
    }
}

private const val BROWSER_REQUEST_TIMEOUT_MS = 30_000L
private const val DIRECTORY_LOAD_TIMEOUT_MS = BROWSER_REQUEST_TIMEOUT_MS

@Composable
private fun ProtectedDirectoryPasswordDialog(
    path: String,
    submitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var password by remember(path) { mutableStateOf("") }
    var passwordVisible by remember(path) { mutableStateOf(false) }
    val canSubmit = password.isNotEmpty() && !submitting
    val submit = { if (canSubmit) onSubmit(password) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("需要访问密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "请输入受保护目录的密码以打开 $path。密码仅保存在本次应用进程的内存中。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !submitting,
                    isError = error != null,
                    label = { Text("目录密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(
                            onClick = { passwordVisible = !passwordVisible },
                            enabled = !submitting,
                        ) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    supportingText = error?.let { message ->
                        { Text(message) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = canSubmit) {
                if (submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("解锁")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        },
    )
}

@Composable
internal fun BrowserFloatingActions(
    showPlaybackQueue: Boolean,
    showUpload: Boolean,
    onPlaybackQueueRequested: () -> Unit,
    onUploadRequested: () -> Unit,
) {
    if (!showPlaybackQueue && !showUpload) return
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPlaybackQueue) {
            SmallFloatingActionButton(
                onClick = onPlaybackQueueRequested,
                modifier = Modifier.testTag(OpenListUiTags.PLAYBACK_QUEUE_FAB),
            ) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "打开播放列表")
            }
        }
        if (showUpload) {
            ExtendedFloatingActionButton(
                onClick = onUploadRequested,
                modifier = Modifier.testTag(OpenListUiTags.UPLOAD_FAB),
                icon = { Icon(Icons.Default.UploadFile, contentDescription = null) },
                text = { Text("上传") },
            )
        }
    }
}

@Composable
private fun BrowserSortMenu(
    sort: BrowserSort,
    onSortChange: (BrowserSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "排序：${sort.field.label}，${sort.direction.label}",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                text = "排序仅作用于当前目录",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            BrowserSortField.entries.forEach { field ->
                val selected = sort.field == field
                DropdownMenuItem(
                    text = { Text(field.label) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSortChange(sort.copy(field = field))
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            BrowserSortDirection.entries.forEach { direction ->
                val selected = sort.direction == direction
                DropdownMenuItem(
                    text = { Text(direction.label) },
                    leadingIcon = {
                        Icon(
                            if (direction == BrowserSortDirection.Ascending) {
                                Icons.Default.ArrowUpward
                            } else {
                                Icons.Default.ArrowDownward
                            },
                            contentDescription = null,
                        )
                    },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSortChange(sort.copy(direction = direction))
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClose: () -> Unit,
) {
    Surface(shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭搜索")
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(placeholder) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).testTag(OpenListUiTags.SEARCH_FIELD),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = "清除搜索词")
                }
            }
        }
    }
}

@Composable
private fun SearchResults(
    container: AppContainer,
    parent: String,
    query: String,
    layout: CollectionLayout,
    onOpen: (BrowserEntry, List<BrowserEntry>) -> Unit,
    onDetails: (BrowserEntry) -> Unit,
    onFileActions: (BrowserEntry) -> Unit,
) {
    // SearchTopBar owns the field for layout, so observe its semantics-backed state through a
    // hoisted bridge local to this search session.
    SearchResultsStateHost(
        container = container,
        parent = parent,
        query = query,
        layout = layout,
        onOpen = onOpen,
        onDetails = onDetails,
        onFileActions = onFileActions,
    )
}

/**
 * This state host is split out to keep all search-path construction in one place. Every result
 * uses SearchData.content.parent; joining it to the current directory would silently open the
 * wrong object for recursive search results.
 */
@Composable
private fun SearchResultsStateHost(
    container: AppContainer,
    parent: String,
    query: String,
    layout: CollectionLayout,
    onOpen: (BrowserEntry, List<BrowserEntry>) -> Unit,
    onDetails: (BrowserEntry) -> Unit,
    onFileActions: (BrowserEntry) -> Unit,
) {
    var results by remember(parent) { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var loading by remember(parent) { mutableStateOf(false) }
    var searched by remember(parent) { mutableStateOf(false) }
    var error by remember(parent) { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(parent, query, retryKey) {
        if (query.isBlank()) {
            results = emptyList()
            loading = false
            searched = false
            error = null
            return@LaunchedEffect
        }
        delay(350)
        loading = true
        error = null
        try {
            results = withTimeout(BROWSER_REQUEST_TIMEOUT_MS) {
                container.repository.search(parent = parent, keywords = query.trim()).content.map { result ->
                    result.toBrowserEntry()
                }
            }
            searched = true
        } catch (_: TimeoutCancellationException) {
            error = "搜索超时，请检查服务器连接后重试"
            searched = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            error = throwable.message ?: "搜索失败"
            searched = true
        } finally {
            loading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            query.isBlank() -> EmptyState(
                icon = Icons.Default.Search,
                title = "搜索此目录",
                message = "输入名称关键词，结果会包含子目录中的匹配项。",
            )
            loading && !searched -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            error != null && results.isEmpty() -> ErrorState(error.orEmpty(), onRetry = { retryKey++ })
            searched && results.isEmpty() -> EmptyState(
                icon = Icons.Default.SearchOff,
                title = "没有找到结果",
                message = "请尝试更短的关键词或检查拼写。",
            )
            else -> Column(Modifier.fillMaxSize()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { message -> ErrorBanner(message, onRetry = { retryKey++ }) }
                FileCollection(
                    entries = results,
                    layout = layout,
                    contentPadding = PaddingValues(bottom = 96.dp),
                    // Recursive search results are a filtered, cross-directory set rather than a
                    // complete sibling snapshot. Let MediaSequenceBuilder list the selected
                    // item's real parent so queues, subtitles, and galleries stay complete.
                    onOpen = { onOpen(it, emptyList()) },
                    onDetails = onDetails,
                    onFileActions = onFileActions,
                    showParent = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DirectoryContent(
    entries: List<BrowserEntry>,
    listing: DirectoryListing?,
    layout: CollectionLayout,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onUpload: () -> Unit,
    onOpen: (BrowserEntry) -> Unit,
    onDetails: (BrowserEntry) -> Unit,
    onFileActions: (BrowserEntry) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when {
            loading && listing == null -> BrowserScrollGestureSurface(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            error != null && listing == null -> BrowserScrollGestureSurface(Modifier.fillMaxSize()) {
                ErrorState(error, onRetry)
            }
            entries.isEmpty() -> BrowserScrollGestureSurface(Modifier.fillMaxSize()) {
                EmptyState(
                    icon = Icons.Default.Folder,
                    title = "此文件夹为空",
                    message = if (listing?.write == true) {
                        "上传文件，或返回上级目录。"
                    } else {
                        "返回上级目录查看其他内容。"
                    },
                    action = if (listing?.write == true) {
                        {
                            FilledTonalButton(onClick = onUpload) {
                                Icon(Icons.Default.Add, null)
                                Spacer(Modifier.width(8.dp))
                                Text("上传文件")
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { message -> ErrorBanner(message, onRetry) }
                FileCollection(
                    entries = entries,
                    layout = layout,
                    contentPadding = PaddingValues(bottom = 96.dp),
                    onOpen = onOpen,
                    onDetails = onDetails,
                    onFileActions = onFileActions,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun FileCollection(
    entries: List<BrowserEntry>,
    layout: CollectionLayout,
    contentPadding: PaddingValues,
    onOpen: (BrowserEntry) -> Unit,
    onDetails: (BrowserEntry) -> Unit,
    onFileActions: (BrowserEntry) -> Unit,
    modifier: Modifier = Modifier,
    showParent: Boolean = false,
) {
    when (layout) {
        CollectionLayout.List -> LazyColumn(
            modifier = modifier.fillMaxWidth().testTag(OpenListUiTags.FILE_COLLECTION),
            contentPadding = contentPadding,
        ) {
            items(entries, key = BrowserEntry::path) { entry ->
                FileListRow(
                    entry = entry,
                    showParent = showParent,
                    onOpen = { onOpen(entry) },
                    onDetails = { onDetails(entry) },
                    onFileActions = { onFileActions(entry) },
                )
                HorizontalDivider(Modifier.padding(start = 80.dp))
            }
        }
        CollectionLayout.Grid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = modifier.fillMaxWidth().testTag(OpenListUiTags.FILE_COLLECTION),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = BrowserEntry::path) { entry ->
                FileGridCard(
                    entry = entry,
                    showParent = showParent,
                    onOpen = { onOpen(entry) },
                    onDetails = { onDetails(entry) },
                    onFileActions = { onFileActions(entry) },
                )
            }
        }
    }
}

@Composable
private fun FileListRow(
    entry: BrowserEntry,
    showParent: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onFileActions: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(entry.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                if (showParent) entry.parent else metadataLine(entry.item),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconContainerColor(MediaTypeDetector.kind(entry.item))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(MediaTypeDetector.kind(entry.item)),
                    contentDescription = null,
                    tint = iconColor(MediaTypeDetector.kind(entry.item)),
                )
            }
        },
        trailingContent = {
            FileOverflowMenu(entry.item, onOpen, onDetails, onFileActions)
        },
        modifier = Modifier.fileClickable(onOpen),
    )
}

@Composable
private fun FileGridCard(
    entry: BrowserEntry,
    showParent: Boolean,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onFileActions: () -> Unit,
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(iconContainerColor(MediaTypeDetector.kind(entry.item))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        iconFor(MediaTypeDetector.kind(entry.item)),
                        contentDescription = null,
                        tint = iconColor(MediaTypeDetector.kind(entry.item)),
                        modifier = Modifier.size(28.dp),
                    )
                }
                FileOverflowMenu(entry.item, onOpen, onDetails, onFileActions)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                entry.item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (showParent) entry.parent else metadataLine(entry.item),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FileOverflowMenu(
    item: OpenListObject,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onFileActions: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "${item.name} 的更多操作")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(primaryActionLabel(item)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    expanded = false
                    onOpen()
                },
            )
            DropdownMenuItem(
                text = { Text("详细信息") },
                leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDetails()
                },
            )
            DropdownMenuItem(
                text = { Text("文件操作") },
                leadingIcon = { Icon(Icons.Default.MoreVert, contentDescription = null) },
                onClick = {
                    expanded = false
                    onFileActions()
                },
            )
        }
    }
}

@Composable
internal fun BreadcrumbBar(
    path: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = path.trim('/').split('/').filter(String::isNotBlank)
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag(OpenListUiTags.BREADCRUMB_BAR),
        contentPadding = PaddingValues(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            TextButton(onClick = { onNavigate("/") }) {
                Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("根目录")
            }
        }
        segments.forEachIndexed { index, segment ->
            item(key = "separator-$index") {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "segment-$index-$segment") {
                TextButton(
                    onClick = {
                        onNavigate("/${segments.take(index + 1).joinToString("/")}")
                    },
                ) {
                    Text(segment, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private suspend fun computeStableDirectorySiblingEntries(
    selected: BrowserEntry,
    candidates: List<BrowserEntry>,
): List<BrowserEntry> = withContext(Dispatchers.Default) {
    stableDirectorySiblingEntries(selected, candidates)
}

@Composable
private fun FileActionsDialog(
    container: AppContainer,
    entry: BrowserEntry,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onChanged: (String) -> Unit,
) {
    var newName by rememberSaveable(entry.path) { mutableStateOf(entry.item.name) }
    var confirmDelete by rememberSaveable(entry.path) { mutableStateOf(false) }
    var busy by remember(entry.path) { mutableStateOf(false) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val trimmedName = newName.trim()
    val validName = trimmedName.isNotBlank() &&
        trimmedName != "." &&
        trimmedName != ".." &&
        '/' !in trimmedName &&
        '\\' !in trimmedName

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("删除“${entry.item.name}”？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (entry.item.isDirectory) {
                            "文件夹及其中内容将从服务器删除，此操作无法在应用内撤销。"
                        } else {
                            "文件将从服务器删除，此操作无法在应用内撤销。"
                        },
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            try {
                                container.api.remove(entry.parent, listOf(entry.item.name))
                                onChanged("已删除 ${entry.item.name}")
                            } catch (throwable: Throwable) {
                                error = throwable.message ?: "删除失败"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }, enabled = !busy) { Text("取消") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("文件操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    entry.path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = newName,
                    onValueChange = {
                        newName = it
                        error = null
                    },
                    label = { Text("名称") },
                    supportingText = {
                        Text(if (validName) "重命名不会移动文件" else "名称不能为空，且不能包含 / 或 \\")
                    },
                    isError = !validName || error != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!entry.item.isDirectory) {
                    TextButton(onClick = onDownload, enabled = !busy) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("下载到本地")
                    }
                }
                TextButton(
                    onClick = { confirmDelete = true },
                    enabled = !busy,
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            container.api.rename(entry.path, trimmedName)
                            onChanged("已重命名为 $trimmedName")
                        } catch (throwable: Throwable) {
                            error = throwable.message ?: "重命名失败"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = validName && trimmedName != entry.item.name && !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("重命名")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileDetailsSheet(
    container: AppContainer,
    entry: BrowserEntry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onMoreActions: () -> Unit,
    onDownload: () -> Unit,
    onOpenRelated: (BrowserEntry, List<BrowserEntry>) -> Unit,
) {
    var details by remember(entry.path) { mutableStateOf<FileDetails?>(null) }
    var loading by remember(entry.path) { mutableStateOf(true) }
    var error by remember(entry.path) { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    var relatedEntries by remember(entry.parent) { mutableStateOf<List<BrowserEntry>>(emptyList()) }
    var relatedLoading by remember(entry.parent) { mutableStateOf(true) }
    var selectedMediaKind by rememberSaveable(entry.path) {
        mutableStateOf(
            if (MediaTypeDetector.kind(entry.item) == MediaKind.IMAGE) MediaKind.IMAGE else MediaKind.VIDEO,
        )
    }

    LaunchedEffect(entry.path, retryKey) {
        loading = true
        error = null
        try {
            details = withTimeout(BROWSER_REQUEST_TIMEOUT_MS) {
                container.repository.details(entry.path)
            }
        } catch (_: TimeoutCancellationException) {
            error = "读取详细信息超时，请重试"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            error = throwable.message ?: "无法读取详细信息"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(entry.parent, retryKey) {
        relatedLoading = true
        relatedEntries = try {
            withTimeout(BROWSER_REQUEST_TIMEOUT_MS) {
                computeStableDirectorySiblingEntries(
                    selected = entry,
                    candidates = container.repository.list(entry.parent).content.map { item ->
                        BrowserEntry(
                            path = joinRemotePath(entry.parent, item.name),
                            parent = entry.parent,
                            item = item,
                        )
                    },
                )
            }
        } catch (_: TimeoutCancellationException) {
            listOf(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            listOf(entry)
        } finally {
            relatedLoading = false
        }
    }

    val relatedMedia by produceState(
        initialValue = RelatedMediaBuckets.Empty,
        key1 = relatedEntries,
    ) {
        value = withContext(Dispatchers.Default) {
            relatedMediaBuckets(relatedEntries)
        }
    }
    val videos = relatedMedia.videos
    val images = relatedMedia.images
    val selectedMedia = if (selectedMediaKind == MediaKind.IMAGE) images else videos

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(iconContainerColor(MediaTypeDetector.kind(entry.item))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        iconFor(MediaTypeDetector.kind(entry.item)),
                        contentDescription = null,
                        tint = iconColor(MediaTypeDetector.kind(entry.item)),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.item.name,
                        modifier = Modifier.semantics { heading() },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        entry.path,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            when {
                loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                error != null -> ErrorBanner(error.orEmpty(), onRetry = { retryKey++ })
                details != null -> {
                    val value = details ?: return@Column
                    DetailRow("类型", mediaLabel(value.asObject()))
                    DetailRow("大小", if (value.isDirectory) "文件夹" else formatBytes(value.size))
                    if (value.modified.isNotBlank()) DetailRow("修改时间", value.modified)
                    if (value.created.isNotBlank()) DetailRow("创建时间", value.created)
                    visibleStorageProvider(value.provider)?.let {
                        DetailRow("存储驱动", it)
                    }
                    value.hashes.orEmpty().entries.take(3).forEach { (algorithm, hash) ->
                        DetailRow(algorithm.uppercase(), hash)
                    }
                }
            }
            Text(
                "同目录媒体",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedMediaKind == MediaKind.VIDEO,
                    onClick = { selectedMediaKind = MediaKind.VIDEO },
                    label = { Text("视频 ${videos.size}") },
                    leadingIcon = { Icon(Icons.Default.Movie, contentDescription = null) },
                )
                FilterChip(
                    selected = selectedMediaKind == MediaKind.IMAGE,
                    onClick = { selectedMediaKind = MediaKind.IMAGE },
                    label = { Text("图片 ${images.size}") },
                    leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                )
            }
            when {
                relatedLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                selectedMedia.isEmpty() -> Text(
                    if (selectedMediaKind == MediaKind.IMAGE) "此目录没有图片" else "此目录没有视频",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(selectedMedia, key = BrowserEntry::path) { media ->
                        Card(
                            onClick = { onOpenRelated(media, relatedEntries) },
                            modifier = Modifier.widthIn(min = 168.dp, max = 220.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (media.path == entry.path) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    iconFor(MediaTypeDetector.kind(media.item)),
                                    contentDescription = null,
                                    tint = iconColor(MediaTypeDetector.kind(media.item)),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        media.item.name,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        formatBytes(media.item.size),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                OutlinedButton(onClick = onMoreActions) { Text("文件操作") }
                if (!entry.item.isDirectory) {
                    OutlinedButton(onClick = onDownload) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("下载")
                    }
                }
                if (MediaTypeDetector.kind(entry.item) !in setOf(MediaKind.TEXT, MediaKind.OTHER)) {
                    Button(onClick = onOpen) { Text(primaryActionLabel(entry.item)) }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GalleryDialog(
    container: AppContainer,
    request: GalleryRequest,
    imageRepository: GalleryImageRepository?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val galleryState = rememberGalleryState(request.sequence)
        val resolvedImageRepository by produceState<GalleryImageRepository?>(
            initialValue = imageRepository,
            key1 = imageRepository,
            key2 = container,
        ) {
            if (imageRepository == null) {
                value = runCatching {
                    withContext(Dispatchers.IO) {
                        container.galleryImageRepository
                    }
                }.getOrElse {
                    GalleryImageRepository(container.mediaUrlResolver)
                }
            }
        }
        var retryKey by remember { mutableIntStateOf(0) }
        GalleryViewerContent(
            state = galleryState,
            onDismiss = onDismiss,
            modifier = Modifier.fillMaxSize(),
        ) { onImageTap, resetZoomKey, onZoomChanged ->
            key(retryKey) {
                val galleryModifier = Modifier.fillMaxSize().padding(top = 64.dp, bottom = 72.dp)
                val loadingContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
                val errorContent: @Composable androidx.compose.foundation.layout.BoxScope.(org.openlist.mobile.media.MediaEntry) -> Unit = {
                    GalleryLoadError(onRetry = { retryKey++ })
                }
                val repository = resolvedImageRepository
                if (repository == null) {
                    Box(galleryModifier, contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else {
                    OpenListGallery(
                        state = galleryState,
                        imageRepository = repository,
                        modifier = galleryModifier,
                        onImageTap = onImageTap,
                        resetZoomKey = resetZoomKey,
                        onZoomChanged = onZoomChanged,
                        contentScale = ContentScale.Fit,
                        loading = loadingContent,
                        error = errorContent,
                    )
                }
            }
        }
    }
}

@Composable
internal fun GalleryViewerContent(
    state: GalleryState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    imageContent: @Composable androidx.compose.foundation.layout.BoxScope.(
        onImageTap: () -> Unit,
        resetZoomKey: Any?,
        onZoomChanged: (Boolean) -> Unit,
    ) -> Unit,
) {
    var imageListVisible by remember { mutableStateOf(false) }
    var imageZoomed by remember { mutableStateOf(false) }
    var resetZoomKey by remember { mutableIntStateOf(0) }
    val onZoomChanged = remember {
        { zoomed: Boolean -> imageZoomed = zoomed }
    }

    LaunchedEffect(state.currentIndex) {
        imageZoomed = false
        resetZoomKey += 1
    }

    Surface(
        modifier = modifier.testTag(OpenListUiTags.GALLERY),
        color = Color(0xFF0B0B0E),
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            imageContent(
                { imageListVisible = !imageListVisible },
                resetZoomKey,
                onZoomChanged,
            )
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭图片浏览", tint = Color.White)
                }
                Text(
                    state.current.name,
                    modifier = Modifier.weight(1f),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (imageZoomed) {
                    IconButton(
                        onClick = {
                            imageZoomed = false
                            resetZoomKey += 1
                        },
                        modifier = Modifier.testTag(OpenListUiTags.GALLERY_RESET_ZOOM),
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = "重置图片缩放",
                            tint = Color.White,
                        )
                    }
                }
                Text(
                    "${state.currentIndex + 1} / ${state.items.size}",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FilledTonalIconButton(
                    onClick = { state.previous() },
                    enabled = state.canGoPrevious,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一张")
                }
                FilledTonalIconButton(
                    onClick = { state.next() },
                    enabled = state.canGoNext,
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一张")
                }
            }
            AnimatedVisibility(
                visible = imageListVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = 76.dp),
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            ) {
                GalleryImageList(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
internal fun GalleryImageList(
    state: GalleryState,
    modifier: Modifier = Modifier,
) {
    val initialIndex = state.currentIndex.coerceIn(state.items.indices)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    LaunchedEffect(state.currentIndex, state.items.size) {
        if (state.currentIndex !in state.items.indices) return@LaunchedEffect
        val currentItemVisible = listState.layoutInfo.visibleItemsInfo.any {
            it.index == state.currentIndex
        }
        if (!currentItemVisible) listState.scrollToItem(state.currentIndex)
    }

    Surface(
        modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
        color = Color.Black.copy(alpha = 0.82f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "同目录图片 · ${state.items.size}",
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelLarge,
            )
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(OpenListUiTags.GALLERY_IMAGE_LIST),
                state = listState,
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = state.items,
                    key = { index, entry -> "${entry.contentKey.value}:$index" },
                ) { index, entry ->
                    val selected = index == state.currentIndex
                    Surface(
                        modifier = Modifier
                            .width(144.dp)
                            .height(64.dp)
                            .testTag(OpenListUiTags.galleryImageItem(index))
                            .selectable(
                                selected = selected,
                                onClick = { state.show(index) },
                                role = Role.Button,
                            ),
                        color = if (selected) Color(0xFF234B66) else Color.White.copy(alpha = 0.09f),
                        contentColor = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Color(0xFF90CAF9) else Color.White.copy(alpha = 0.14f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = if (selected) Color(0xFF90CAF9) else Color.White.copy(alpha = 0.72f),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "第 ${index + 1} 张",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                )
                                Text(
                                    text = entry.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.GalleryLoadError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text("图片加载失败", color = Color.White)
        OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(OpenListUiTags.ERROR_STATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Text("无法显示内容", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("重试")
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag(OpenListUiTags.EMPTY_STATE),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(88.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        action?.let {
            Spacer(Modifier.height(20.dp))
            it()
        }
    }
}

internal fun SearchObject.toBrowserEntry(): BrowserEntry {
    val documentedPath = path.trim()
        .takeIf(::isSafeSearchRemotePath)
        ?.trimEnd('/')
    val resultPath = documentedPath ?: joinRemotePath(normalizedRemotePath(parent), name)
    val resultParent = documentedPath?.let(::parentRemotePath) ?: normalizedRemotePath(parent)
    val resultName = documentedPath?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: name
    return BrowserEntry(
        path = resultPath,
        parent = resultParent,
        item = OpenListObject(
            path = resultPath,
            name = resultName,
            size = size,
            isDirectory = isDirectory,
            type = type,
        ),
    )
}

private fun isSafeSearchRemotePath(path: String): Boolean {
    if (path.length <= 1 || !path.startsWith('/') || path.startsWith("//")) return false
    if (
        '\\' in path ||
        '?' in path ||
        '#' in path ||
        "://" in path ||
        path.contains("%2f", ignoreCase = true) ||
        path.contains("%5c", ignoreCase = true) ||
        path.any(Char::isISOControl)
    ) {
        return false
    }
    return path.split('/').none { segment ->
        val decodedDots = segment.replace("%2e", ".", ignoreCase = true)
        decodedDots == "." || decodedDots == ".."
    }
}

private fun normalizedRemotePath(path: String): String = when {
    path.isBlank() || path == "/" -> "/"
    else -> "/${path.trim('/')}"
}

private fun Throwable.isForbiddenAccess(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is OpenListApiException &&
            (current.apiCode == 403 || current.httpStatus == 403)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

private fun primaryActionLabel(item: OpenListObject): String = when (MediaTypeDetector.kind(item)) {
    MediaKind.DIRECTORY -> "打开文件夹"
    MediaKind.IMAGE -> "查看图片"
    MediaKind.AUDIO, MediaKind.VIDEO -> "播放"
    MediaKind.TEXT, MediaKind.OTHER -> "查看详情"
}

private fun mediaLabel(item: OpenListObject): String = when (MediaTypeDetector.kind(item)) {
    MediaKind.DIRECTORY -> "文件夹"
    MediaKind.IMAGE -> "图片"
    MediaKind.AUDIO -> "音频"
    MediaKind.VIDEO -> "视频"
    MediaKind.TEXT -> "文本"
    MediaKind.OTHER -> "文件"
}

private fun metadataLine(item: OpenListObject): String {
    val type = mediaLabel(item)
    val size = if (item.isDirectory) null else formatBytes(item.size)
    val modified = item.modified.takeIf(String::isNotBlank)
    return listOfNotNull(type, size, modified).joinToString(" · ")
}

internal fun iconFor(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.DIRECTORY -> Icons.Default.Folder
    MediaKind.AUDIO -> Icons.Default.AudioFile
    MediaKind.VIDEO -> Icons.Default.Movie
    MediaKind.IMAGE -> Icons.Default.Image
    MediaKind.TEXT -> Icons.Default.Description
    MediaKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun iconContainerColor(kind: MediaKind): Color = when (kind) {
    MediaKind.DIRECTORY -> MaterialTheme.colorScheme.primaryContainer
    MediaKind.IMAGE -> MaterialTheme.colorScheme.tertiaryContainer
    MediaKind.AUDIO, MediaKind.VIDEO -> MaterialTheme.colorScheme.secondaryContainer
    MediaKind.TEXT, MediaKind.OTHER -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun iconColor(kind: MediaKind): Color = when (kind) {
    MediaKind.DIRECTORY -> MaterialTheme.colorScheme.onPrimaryContainer
    MediaKind.IMAGE -> MaterialTheme.colorScheme.onTertiaryContainer
    MediaKind.AUDIO, MediaKind.VIDEO -> MaterialTheme.colorScheme.onSecondaryContainer
    MediaKind.TEXT, MediaKind.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit++
    } while (value >= 1_024 && unit < units.lastIndex)
    return "${DecimalFormat("0.#").format(value)} ${units[unit]}"
}

private fun Modifier.fileClickable(onClick: () -> Unit): Modifier =
    clickable(role = Role.Button, onClick = onClick)
