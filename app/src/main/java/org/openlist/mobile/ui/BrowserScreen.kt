package org.openlist.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FilterAlt
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.text.DecimalFormat
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openlist.mobile.AppContainer
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.model.joinRemotePath
import org.openlist.mobile.core.model.parentRemotePath
import org.openlist.mobile.core.util.FileVisibilityMatcher
import org.openlist.mobile.data.api.OpenListApiException
import org.openlist.mobile.data.api.dto.SearchObject
import org.openlist.mobile.media.MediaSequence
import org.openlist.mobile.media.MediaTypeDetector
import org.openlist.mobile.media.gallery.GalleryImageRepository
import org.openlist.mobile.media.gallery.GalleryState
import org.openlist.mobile.media.gallery.OpenListGallery
import org.openlist.mobile.ui.browser.BrowserActionKind
import org.openlist.mobile.ui.browser.BrowserGalleryViewModel
import org.openlist.mobile.ui.browser.BrowserSearchPage
import org.openlist.mobile.ui.browser.BrowserSessionOwner
import org.openlist.mobile.ui.browser.BrowserViewModel
import org.openlist.mobile.ui.browser.FileActionSheet
import org.openlist.mobile.ui.browser.FileDetailsPane
import org.openlist.mobile.ui.browser.FileMutationDialog
import org.openlist.mobile.ui.browser.SearchUiState
import org.openlist.mobile.ui.designsystem.OpenListEmptyState
import org.openlist.mobile.ui.designsystem.OpenListErrorState
import org.openlist.mobile.ui.filter.FileVisibilityRulesDialog
import org.openlist.mobile.ui.theme.OpenListMediaColors
import org.openlist.mobile.ui.theme.OpenListMediaTheme

internal object OpenListUiTags {
    const val FILE_COLLECTION = "file_collection"
    const val EMPTY_STATE = "empty_state"
    const val ERROR_STATE = "error_state"
    const val BROWSER_APP_BAR = "browser_app_bar"
    const val BREADCRUMB_BAR = "breadcrumb_bar"
    const val SEARCH_FIELD = "search_field"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserScreen(
    container: AppContainer,
    onOpenMedia: (String, OpenListObject, List<OpenListObject>) -> Unit,
    onUploadRequested: (String) -> Unit,
    onDownloadRequested: (String, OpenListObject) -> Unit,
    onFileActionsRequested: (String, OpenListObject) -> Unit,
    galleryImageRepository: GalleryImageRepository?,
    onAccountsRequested: () -> Unit = {},
) {
    val accounts by container.sessionStore.accountSummaries.collectAsStateWithLifecycle()
    val settings by container.sessionStore.settings.collectAsStateWithLifecycle()
    val visibilityRules = settings.fileVisibilityRules
    val visibilityMatcher = remember(visibilityRules) { FileVisibilityMatcher.compile(visibilityRules) }
    var showVisibilityRules by rememberSaveable { mutableStateOf(false) }
    val invalidation by container.playbackInvalidation.collectAsStateWithLifecycle()
    val sessionBusy by container.sessionBusy.collectAsStateWithLifecycle()
    val sessionFactory = remember(container) {
        viewModelFactory { initializer { BrowserSessionOwner(container.playbackInvalidation, container.sessionBusy) } }
    }
    val sessionOwner: BrowserSessionOwner = viewModel(key = "browser-session-owner", factory = sessionFactory)
    val active = accounts.firstOrNull { it.isActive }
    val profile = active?.server ?: container.sessionStore.snapshot().server
    val accountId = active?.id
    val accountLabel = listOf(
        active?.displayName?.takeIf { it.isNotBlank() }
            ?: profile.baseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/'),
        profile.username,
    ).filter { it.isNotBlank() }.joinToString(" · ")
    val sessionKey = "$accountId:${profile.baseUrl}:${profile.username}:$invalidation"
    var restoredPath by rememberSaveable { mutableStateOf("/") }
    var restoredQuery by rememberSaveable { mutableStateOf("") }
    var restoredSearchVisible by rememberSaveable { mutableStateOf(false) }
    if (sessionBusy) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val browserOwner = remember(sessionOwner, sessionKey) { sessionOwner.select(sessionKey, invalidation) }
    val accountActive = remember(container, sessionKey) {
        {
            !container.sessionBusy.value &&
                container.playbackInvalidation.value == invalidation &&
                container.sessionStore.accountSnapshot().firstOrNull { it.isActive }?.id == accountId &&
                container.sessionStore.snapshot().server == profile
        }
    }
    val factory = remember(container, sessionKey) {
        viewModelFactory {
            initializer {
                BrowserViewModel(
                    initialPath = restoredPath,
                    initialQuery = restoredQuery,
                    initialSearchVisible = restoredSearchVisible,
                    loadDirectory = { path, refresh -> container.repository.list(path, refresh) },
                    search = { parent, query, page ->
                        val result = container.repository.search(parent, query, page = page)
                        BrowserSearchPage(result.content.map { it.toBrowserEntry() }, result.total)
                    },
                    unlockDirectory = { path, password -> container.repository.unlockDirectory(path, password) },
                    loadDetails = container.repository::details,
                    renameEntry = { path, name -> container.api.rename(path, name) },
                    removeEntry = container.api::remove,
                    accountActive = accountActive,
                )
            }
        }
    }
    val model: BrowserViewModel = viewModel(viewModelStoreOwner = browserOwner, key = "browser", factory = factory)
    val state by model.state.collectAsStateWithLifecycle()
    SideEffect {
        restoredPath = state.path
        restoredQuery = state.query
        restoredSearchVisible = state.searchVisible
    }
    var layout by rememberSaveable { mutableStateOf(CollectionLayout.List) }
    var sortField by rememberSaveable { mutableStateOf(BrowserSortField.Name) }
    var sortDirection by rememberSaveable { mutableStateOf(BrowserSortDirection.Ascending) }
    val galleryFactory = remember(container, sessionKey) {
        viewModelFactory {
            initializer {
                BrowserGalleryViewModel(
                    loadSequence = { entry, completeSiblings ->
                        if (completeSiblings.isEmpty()) {
                            container.mediaSequenceBuilder.build(entry.path, entry.item)
                        } else {
                            container.mediaSequenceBuilder.build(
                                currentPath = entry.path,
                                current = entry.item,
                                siblings = computeStableDirectorySiblingEntries(entry, completeSiblings).map(BrowserEntry::item),
                            )
                        }
                    },
                    accountActive = accountActive,
                )
            }
        }
    }
    val galleryModel: BrowserGalleryViewModel = viewModel(viewModelStoreOwner = browserOwner, key = "gallery", factory = galleryFactory)
    val galleryState by galleryModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedCollections = rememberSaveableStateHolder()
    val sort = BrowserSort(sortField, sortDirection)
    val completeDirectoryEntries = remember(state.listing?.content, state.path, sort) {
        sortBrowserEntries(
            state.listing?.content.orEmpty().map { item ->
                BrowserEntry(joinRemotePath(state.path, item.name), state.path, item)
            },
            sort,
        )
    }
    val directoryEntries = remember(completeDirectoryEntries, visibilityMatcher) {
        completeDirectoryEntries.filter { visibilityMatcher.isPathVisible(it.path, it.item.isDirectory) }
    }
    val hiddenDirectoryCount = state.listing?.content.orEmpty().size - directoryEntries.size

    fun cancelMediaPreparation() = galleryModel.cancelPreparation()

    fun editVisibilityRules() {
        cancelMediaPreparation()
        showVisibilityRules = true
    }

    fun closeSearch() {
        cancelMediaPreparation()
        model.closeSearch()
    }

    fun navigate(nextPath: String) {
        cancelMediaPreparation()
        model.navigate(nextPath)
    }

    fun openEntry(entry: BrowserEntry, fromSearch: Boolean = state.searchVisible) {
        cancelMediaPreparation()
        when (MediaTypeDetector.kind(entry.item)) {
            MediaKind.DIRECTORY -> navigate(entry.path)
            MediaKind.TEXT, MediaKind.OTHER -> model.showDetails(entry)
            MediaKind.AUDIO, MediaKind.VIDEO -> {
                // Recursive results do not describe complete siblings; the media builder lists
                // the selected result's real parent when no snapshot is supplied.
                // Keep hidden sidecars available for subtitle discovery; the builder filters
                // the playable queue using the same visibility rules.
                val peers = if (fromSearch) emptyList() else stableDirectorySiblingEntries(entry, completeDirectoryEntries)
                onOpenMedia(entry.path, entry.item, peers.map(BrowserEntry::item))
            }
            MediaKind.IMAGE -> galleryModel.open(entry, if (fromSearch) emptyList() else completeDirectoryEntries)
        }
    }

    fun showActions(entry: BrowserEntry) {
        cancelMediaPreparation()
        model.showActions(entry)
        onFileActionsRequested(entry.path, entry.item)
    }

    LaunchedEffect(galleryState.error) {
        galleryState.error?.let {
            snackbarHostState.showSnackbar(it)
            galleryModel.consumeError()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            model.consumeMessage()
        }
    }
    BackHandler(state.details != null || state.searchVisible || state.path != "/") {
        when {
            state.details != null -> model.closeDetails()
            state.searchVisible -> closeSearch()
            else -> navigate(parentRemotePath(state.path))
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = maxWidth >= 840.dp
        Scaffold(
            topBar = {
                Column(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                    if (state.searchVisible) {
                        BrowserSearchHeader(
                            query = state.query,
                            path = state.path,
                            accountLabel = accountLabel,
                            onQueryChange = model::updateQuery,
                            onClose = ::closeSearch,
                            ruleCount = visibilityRules.size,
                            onFilterRequested = ::editVisibilityRules,
                        )
                    } else {
                        BrowserDirectoryHeader(
                            path = state.path,
                            accountLabel = accountLabel,
                            summary = if (hiddenDirectoryCount > 0) {
                                "显示 ${directoryEntries.size} 项 · 隐藏 $hiddenDirectoryCount 项"
                            } else state.listing?.let { browserDirectorySummary(it.total, it.provider) } ?: "目录内容",
                            layout = layout,
                            sort = sort,
                            refreshing = state.loading,
                            onNavigate = ::navigate,
                            onAccountsRequested = { cancelMediaPreparation(); onAccountsRequested() },
                            onSearch = { cancelMediaPreparation(); model.showSearch() },
                            onSortChange = { sortField = it.field; sortDirection = it.direction },
                            onLayoutChange = { layout = it },
                            onRefresh = { model.refresh() },
                            ruleCount = visibilityRules.size,
                            onFilterRequested = ::editVisibilityRules,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            floatingActionButton = {
                if (!state.searchVisible && state.listing?.write == true) {
                    ExtendedFloatingActionButton(
                        onClick = { onUploadRequested(state.path) },
                        modifier = Modifier.testTag(OpenListUiTags.UPLOAD_FAB),
                        icon = { Icon(Icons.Default.UploadFile, null) },
                        text = { Text("上传") },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.searchVisible) {
                        key(state.path, state.query) {
                            BrowserSearchResults(
                                state = state.search,
                                query = state.query,
                                layout = layout,
                                onRetry = model::retrySearch,
                                onLoadMore = model::loadMoreSearch,
                                onOpen = { openEntry(it, fromSearch = true) },
                                onFileActions = ::showActions,
                                visibilityMatcher = visibilityMatcher,
                                onFilterRequested = ::editVisibilityRules,
                            )
                        }
                    } else {
                        savedCollections.SaveableStateProvider(state.path) {
                            DirectoryContent(
                                entries = directoryEntries,
                                listing = state.listing,
                                layout = layout,
                                loading = state.loading,
                                error = state.error,
                                onRetry = { model.refresh(forceRefresh = false) },
                                onUpload = { onUploadRequested(state.path) },
                                onOpen = { openEntry(it) },
                                onFileActions = ::showActions,
                                hiddenCount = hiddenDirectoryCount,
                                onFilterRequested = ::editVisibilityRules,
                            )
                        }
                    }
                    if (galleryState.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                if (expanded) {
                    VerticalDivider()
                    Surface(Modifier.width(360.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        state.details?.let { details ->
                            FileDetailsPane(details, model::closeDetails, model::retryDetails, { showActions(details.entry) })
                        } ?: OpenListEmptyState(
                            icon = Icons.Default.Info,
                            title = "文件信息",
                            description = "从文件的更多操作中选择“详细信息”，在此查看位置与属性。",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        if (!expanded) state.details?.let { details ->
            ModalBottomSheet(onDismissRequest = model::closeDetails) {
                FileDetailsPane(details, model::closeDetails, model::retryDetails, { showActions(details.entry) })
            }
        }
    }
    state.action?.let { action ->
        if (action.kind == BrowserActionKind.Menu) {
            FileActionSheet(
                entry = action.entry,
                onDismiss = model::dismissAction,
                onOpen = { model.dismissAction(); model.closeDetails(); openEntry(action.entry) },
                onDetails = { model.dismissAction(); model.showDetails(action.entry) },
                onDownload = { model.dismissAction(); onDownloadRequested(action.entry.path, action.entry.item) },
                onRename = { model.chooseAction(BrowserActionKind.Rename) },
                onDelete = { model.chooseAction(BrowserActionKind.Delete) },
            )
        } else {
            FileMutationDialog(action, model::renameInput, model::submitAction, model::dismissAction)
        }
    }
    state.challenge?.let { challenge ->
        ProtectedDirectoryPasswordDialog(
            path = challenge.path,
            submitting = challenge.submitting,
            error = challenge.error,
            onDismiss = model::dismissPassword,
            onSubmit = model::submitPassword,
        )
    }
    galleryState.sequence?.let { sequence ->
        GalleryDialog(
            container, sequence, galleryImageRepository,
            initialIndex = galleryState.selectedIndex ?: sequence.currentIndex,
            onIndexChange = galleryModel::show,
            onDismiss = galleryModel::close,
        )
    }
    if (showVisibilityRules) {
        FileVisibilityRulesDialog(
            rules = visibilityRules,
            onSave = { rules ->
                container.sessionStore.updateFileVisibilityRules(rules)
                model.dismissAction()
                model.closeDetails()
                cancelMediaPreparation()
            },
            onDismiss = { showVisibilityRules = false },
        )
    }
}

@Composable
internal fun BrowserDirectoryHeader(
    path: String,
    accountLabel: String,
    summary: String,
    layout: CollectionLayout,
    sort: BrowserSort,
    refreshing: Boolean,
    onNavigate: (String) -> Unit,
    onAccountsRequested: () -> Unit,
    onSearch: () -> Unit,
    onSortChange: (BrowserSort) -> Unit,
    onLayoutChange: (CollectionLayout) -> Unit,
    onRefresh: () -> Unit,
    ruleCount: Int = 0,
    onFilterRequested: () -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(OpenListUiTags.BROWSER_APP_BAR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path != "/") {
                IconButton(onClick = { onNavigate(parentRemotePath(path)) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回上级目录")
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (path == "/") "文件" else path.substringAfterLast('/'),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.heightIn(min = 48.dp)
                        .clickable(onClickLabel = "切换服务器或账户", onClick = onAccountsRequested),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        accountLabel,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索当前目录") }
        }
        BreadcrumbBar(path, onNavigate)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                summary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrowserSortMenu(sort, onSortChange)
            FileVisibilityButton(ruleCount, onFilterRequested)
            IconButton(onClick = {
                onLayoutChange(if (layout == CollectionLayout.List) CollectionLayout.Grid else CollectionLayout.List)
            }) {
                Icon(
                    if (layout == CollectionLayout.List) Icons.Default.GridView else Icons.AutoMirrored.Filled.List,
                    if (layout == CollectionLayout.List) "切换到网格" else "切换到列表",
                )
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, "刷新") }
        }
    }
}

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
                    "请输入访问 $path 的目录密码。",
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
internal fun BrowserSearchHeader(
    query: String,
    path: String,
    accountLabel: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    ruleCount: Int = 0,
    onFilterRequested: () -> Unit = {},
) {
    Column {
        SearchTopBar(query, onQueryChange, placeholder = "搜索文件与文件夹", onClose = onClose)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    accountLabel,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                )
                FileVisibilityButton(ruleCount, onFilterRequested)
            }
            Text(
                "搜索范围：$path（包含子目录）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun FileVisibilityButton(ruleCount: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics {
            stateDescription = if (ruleCount == 0) "显示全部文件" else "已应用 $ruleCount 条规则"
        },
    ) {
        BadgedBox(badge = { if (ruleCount > 0) Badge { Text(ruleCount.toString()) } }) {
            Icon(
                Icons.Default.FilterAlt,
                contentDescription = "筛选规则",
                tint = if (ruleCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Surface {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.weight(1f).focusRequester(focusRequester).testTag(OpenListUiTags.SEARCH_FIELD),
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
internal fun BrowserSearchResults(
    state: SearchUiState,
    query: String,
    layout: CollectionLayout,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (BrowserEntry) -> Unit,
    onFileActions: (BrowserEntry) -> Unit,
    visibilityMatcher: FileVisibilityMatcher? = null,
    onFilterRequested: () -> Unit = {},
) {
    val visibleResults = remember(state.results, visibilityMatcher) {
        state.results.filter { visibilityMatcher?.isPathVisible(it.path, it.item.isDirectory) != false }
    }
    val hiddenCount = state.results.size - visibleResults.size
    Box(Modifier.fillMaxSize()) {
        when {
            query.isBlank() -> EmptyState(
                Icons.Default.Search,
                "按名称查找文件",
                "输入关键词，查找当前目录及子目录中的内容。",
            )
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null && state.results.isEmpty() -> ErrorState(state.error, onRetry)
            state.searched && state.results.isEmpty() -> EmptyState(
                Icons.Default.SearchOff,
                "没有找到“${query.trim()}”",
                "换一个关键词，或返回上级目录扩大搜索范围。",
            )
            else -> Column(Modifier.fillMaxSize()) {
                Text(
                    if (hiddenCount > 0) "显示 ${visibleResults.size} 项 · 已隐藏 $hiddenCount 项"
                    else if (state.total > state.results.size) "已显示 ${state.results.size} / ${state.total} 项" else "${state.results.size} 项结果",
                    Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (visibleResults.isEmpty() && hiddenCount > 0) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        EmptyState(
                            icon = Icons.Default.FilterAlt,
                            title = "当前结果已被隐藏",
                            message = if (state.hasMore) "可以调整规则，或继续加载更多搜索结果。" else "调整筛选规则以显示这些结果。",
                            action = { TextButton(onClick = onFilterRequested) { Text("调整规则") } },
                        )
                    }
                } else {
                    FileCollection(
                        entries = visibleResults,
                        layout = layout,
                        contentPadding = PaddingValues(bottom = 16.dp),
                        onOpen = onOpen,
                        onFileActions = onFileActions,
                        showParent = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (state.hasMore) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        TextButton(onClick = onLoadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                            if (state.loadingMore) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(if (state.error != null) "重试加载更多" else "加载更多结果")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DirectoryContent(
    entries: List<BrowserEntry>,
    listing: DirectoryListing?,
    layout: CollectionLayout,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onUpload: () -> Unit,
    onOpen: (BrowserEntry) -> Unit,
    onFileActions: (BrowserEntry) -> Unit,
    hiddenCount: Int = 0,
    onFilterRequested: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
        when {
            loading && listing == null -> Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            error != null && listing == null -> Box(Modifier.fillMaxSize()) {
                ErrorState(error, onRetry)
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { message -> ErrorBanner(message, onRetry) }
                if (entries.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (hiddenCount > 0) {
                            EmptyState(
                                icon = Icons.Default.FilterAlt,
                                title = "文件已被筛选规则隐藏",
                                message = "此文件夹有 $hiddenCount 项内容。调整规则后即可显示。",
                                action = { TextButton(onClick = onFilterRequested) { Text("调整规则") } },
                            )
                        } else {
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
                    }
                } else {
                    FileCollection(
                        entries = entries,
                        layout = layout,
                        contentPadding = PaddingValues(bottom = 96.dp),
                        onOpen = onOpen,
                        onFileActions = onFileActions,
                        modifier = Modifier.weight(1f),
                    )
                }
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
                    onFileActions = { onFileActions(entry) },
                )
                HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            }
        }
        CollectionLayout.Grid -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = modifier.fillMaxWidth().testTag(OpenListUiTags.FILE_COLLECTION),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 12.dp,
                end = 12.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = BrowserEntry::path) { entry ->
                FileGridCard(
                    entry = entry,
                    showParent = showParent,
                    onOpen = { onOpen(entry) },
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
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconContainerColor()),
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
            FileMoreButton(entry.item, onFileActions)
        },
        modifier = Modifier.fileClickable(onOpen),
    )
}

@Composable
private fun FileGridCard(
    entry: BrowserEntry,
    showParent: Boolean,
    onOpen: () -> Unit,
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
                        .background(iconContainerColor()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        iconFor(MediaTypeDetector.kind(entry.item)),
                        contentDescription = null,
                        tint = iconColor(MediaTypeDetector.kind(entry.item)),
                        modifier = Modifier.size(28.dp),
                    )
                }
                FileMoreButton(entry.item, onFileActions)
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
private fun FileMoreButton(
    item: OpenListObject,
    onFileActions: () -> Unit,
) {
    IconButton(onClick = onFileActions) {
        Icon(Icons.Default.MoreVert, contentDescription = "${item.name} 的更多操作")
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
            .heightIn(min = 48.dp)
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
private fun GalleryDialog(
    container: AppContainer,
    sequence: MediaSequence,
    imageRepository: GalleryImageRepository?,
    initialIndex: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        OpenListMediaTheme {
        val galleryState = remember(sequence) { GalleryState(sequence.items, initialIndex) }
        LaunchedEffect(galleryState) {
            snapshotFlow { galleryState.currentIndex }.collect { onIndexChange(it) }
        }
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
                val galleryModifier = Modifier.fillMaxSize()
                val loadingContent: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = OpenListMediaColors.onControlSurface,
                    )
                }
                val errorContent: @Composable androidx.compose.foundation.layout.BoxScope.(org.openlist.mobile.media.MediaEntry) -> Unit = {
                    GalleryLoadError(onRetry = { retryKey++ })
                }
                val repository = resolvedImageRepository
                if (repository == null) {
                    Box(galleryModifier, contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = OpenListMediaColors.onControlSurface)
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
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var imageListVisible by rememberSaveable { mutableStateOf(false) }
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
        color = OpenListMediaColors.canvas,
    ) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            imageContent(
                { controlsVisible = !controlsVisible },
                resetZoomKey,
                onZoomChanged,
            )
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OpenListMediaColors.controlScrim)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭图片浏览", tint = OpenListMediaColors.onControlSurface)
                }
                Text(
                    state.current.name,
                    modifier = Modifier.weight(1f),
                    color = OpenListMediaColors.onControlSurface,
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
                            tint = OpenListMediaColors.onControlSurface,
                        )
                    }
                }
                IconButton(onClick = { imageListVisible = !imageListVisible }) {
                    Icon(Icons.Default.Collections, "同目录图片", tint = OpenListMediaColors.onControlSurface)
                }
                Text(
                    "${state.currentIndex + 1} / ${state.items.size}",
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = OpenListMediaColors.onControlSurface.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
            Row(
                modifier = Modifier
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
            }
            AnimatedVisibility(
                visible = controlsVisible && imageListVisible,
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
        color = OpenListMediaColors.controlSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, OpenListMediaColors.onControlSurface.copy(alpha = 0.18f)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "同目录图片 · ${state.items.size}",
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                color = OpenListMediaColors.onControlSurface.copy(alpha = 0.86f),
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
                            .heightIn(min = 64.dp)
                            .testTag(OpenListUiTags.galleryImageItem(index))
                            .selectable(
                                selected = selected,
                                onClick = { state.show(index) },
                                role = Role.Button,
                            ),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else OpenListMediaColors.onControlSurface.copy(alpha = 0.09f),
                        contentColor = OpenListMediaColors.onControlSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else OpenListMediaColors.onControlSurface.copy(alpha = 0.14f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = if (selected) MaterialTheme.colorScheme.primary else OpenListMediaColors.onControlSurface.copy(alpha = 0.72f),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = "第 ${index + 1} 张",
                                    maxLines = 1,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = OpenListMediaColors.onControlSurface.copy(alpha = 0.7f),
                                )
                                Text(
                                    text = entry.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OpenListMediaColors.onControlSurface,
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
        Icon(Icons.Default.ErrorOutline, null, tint = OpenListMediaColors.onControlSurface, modifier = Modifier.size(40.dp))
        Text("图片加载失败", color = OpenListMediaColors.onControlSurface)
        OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        OpenListErrorState(
            title = "暂时无法读取内容",
            description = message,
            onRetry = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .testTag(OpenListUiTags.ERROR_STATE),
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        OpenListEmptyState(
            icon = icon,
            title = title,
            description = message,
            action = action,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .testTag(OpenListUiTags.EMPTY_STATE),
        )
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

internal fun normalizedRemotePath(path: String): String = when {
    path.isBlank() || path == "/" -> "/"
    else -> "/${path.trim('/')}"
}

internal fun Throwable.isForbiddenAccess(): Boolean {
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
    val modified = item.modified.takeIf(String::isNotBlank)?.let { value ->
        runCatching { OffsetDateTime.parse(value).toLocalDate().toString() }.getOrDefault(value)
    }
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
private fun iconContainerColor(): Color =
    MaterialTheme.colorScheme.surfaceContainerHigh

@Composable
private fun iconColor(kind: MediaKind): Color =
    if (kind == MediaKind.DIRECTORY) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "未知大小"
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
