package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import org.openlist.mobile.core.model.FileDetails
import org.openlist.mobile.core.model.DirectoryListing
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.core.util.FileVisibilityMatcher
import org.openlist.mobile.ui.browser.BrowserDetailsState
import org.openlist.mobile.ui.browser.FileDetailsPane
import org.openlist.mobile.ui.browser.SearchUiState
import org.openlist.mobile.ui.theme.OpenListTheme

@PreviewTest
@Preview(name = "Files · light", widthDp = 412, heightDp = 860, showBackground = true)
@Preview(name = "Files · large text", widthDp = 412, heightDp = 915, fontScale = 2f, showBackground = true)
@Composable
fun BrowserFilesPreview() = BrowserPreview(dark = false)

@PreviewTest
@Preview(name = "Files · dark", widthDp = 412, heightDp = 860, showBackground = true)
@Composable
fun BrowserFilesDarkPreview() = BrowserPreview(dark = true)

@PreviewTest
@Preview(name = "Files · expanded", widthDp = 1100, heightDp = 800, showBackground = true)
@Composable
fun BrowserFilesExpandedPreview() = BrowserPreview(dark = false, expanded = true)

@Composable
private fun BrowserPreview(dark: Boolean, expanded: Boolean = false) {
    val entries = listOf(
        OpenListObject(name = "设计资料", isDirectory = true),
        OpenListObject(name = "旅行影像", isDirectory = true),
        OpenListObject(name = "秋日海岸.jpg", size = 4260000, type = 5, modified = "2026-09-03"),
        OpenListObject(name = "City lights.mp4", size = 282600000, type = 2, modified = "2026-09-02"),
        OpenListObject(name = "Morning playlist.flac", size = 31250000, type = 3, modified = "2026-08-28"),
        OpenListObject(name = "项目规划与交付说明.pdf", size = 630000, modified = "2026-08-25"),
    ).map { BrowserEntry("/工作空间/${it.name}", "/工作空间", it) }
    OpenListTheme(darkTheme = dark) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                BrowserDirectoryHeader(
                    path = "/工作空间",
                    accountLabel = "家庭云盘 · xiaobo",
                    summary = "6 项 · 本地存储",
                    layout = CollectionLayout.List,
                    sort = BrowserSort(),
                    refreshing = false,
                    onNavigate = {},
                    onAccountsRequested = {},
                    onSearch = {},
                    onSortChange = {},
                    onLayoutChange = {},
                    onRefresh = {},
                )
                HorizontalDivider()
                Row(Modifier.weight(1f)) {
                    FileCollection(
                        entries, CollectionLayout.List, PaddingValues(bottom = 24.dp),
                        onOpen = {}, onFileActions = {}, modifier = Modifier.weight(1f),
                    )
                    if (expanded) {
                        VerticalDivider()
                        Surface(Modifier.width(360.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            FileDetailsPane(
                                state = BrowserDetailsState(
                                    entry = entries.last(),
                                    loading = false,
                                    details = FileDetails(name = entries.last().item.name, size = 630000, modified = "2026-08-25 14:30"),
                                ),
                                onClose = {}, onRetry = {}, onMore = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "Search · scoped results", widthDp = 412, heightDp = 860, showBackground = true)
@Preview(name = "Search · narrow large text", widthDp = 320, heightDp = 720, fontScale = 2f, showBackground = true)
@Composable
fun BrowserSearchPreview() {
    OpenListTheme {
        Surface(Modifier.fillMaxSize()) {
            Column {
                BrowserSearchHeader(
                    query = "旅行",
                    path = "/工作空间",
                    accountLabel = "家庭云盘 · xiaobo",
                    onQueryChange = {},
                    onClose = {},
                )
                HorizontalDivider()
                BrowserSearchResults(
                    state = org.openlist.mobile.ui.browser.SearchUiState(
                        results = listOf(
                            BrowserEntry("/工作空间/旅行计划.pdf", "/工作空间", OpenListObject(name = "旅行计划.pdf", size = 850000)),
                            BrowserEntry("/工作空间/2026/海边旅行.jpg", "/工作空间/2026", OpenListObject(name = "海边旅行.jpg", size = 5100000, type = 5)),
                        ),
                        searched = true,
                        total = 2,
                    ),
                    query = "旅行",
                    layout = CollectionLayout.List,
                    onRetry = {}, onLoadMore = {}, onOpen = {}, onFileActions = {},
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "Files · hidden by rules", widthDp = 412, heightDp = 700, showBackground = true)
@Composable
fun BrowserHiddenDirectoryPreview() {
    OpenListTheme {
        Surface(Modifier.fillMaxSize()) {
            DirectoryContent(
                entries = emptyList(), listing = DirectoryListing(), layout = CollectionLayout.List,
                loading = false, error = null,
                onRetry = {}, onUpload = {}, onOpen = {}, onFileActions = {},
                hiddenCount = 3, onFilterRequested = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "Search · hidden page large text", widthDp = 320, heightDp = 560, fontScale = 2f, showBackground = true)
@Composable
fun BrowserHiddenSearchPreview() {
    OpenListTheme {
        Surface(Modifier.fillMaxSize()) {
            BrowserSearchResults(
                state = SearchUiState(
                    results = listOf(BrowserEntry("/report.tmp", "/", OpenListObject(name = "report.tmp"))),
                    searched = true, total = 10, hasMore = true,
                ),
                query = "report", layout = CollectionLayout.List,
                onRetry = {}, onLoadMore = {}, onOpen = {}, onFileActions = {},
                visibilityMatcher = FileVisibilityMatcher.compile(listOf(FileVisibilityRule("tmp", "*.tmp"))),
                onFilterRequested = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "File · action content", widthDp = 412, heightDp = 420, showBackground = true)
@Preview(name = "File · action content large text", widthDp = 412, heightDp = 700, fontScale = 2f, showBackground = true)
@Composable
fun BrowserActionsPreview() {
    OpenListTheme {
        Surface(Modifier.fillMaxSize()) {
            org.openlist.mobile.ui.browser.FileActionContent(
                entry = BrowserEntry("/工作空间/项目交付说明.pdf", "/工作空间", OpenListObject(name = "项目交付说明.pdf", size = 630000)),
                onOpen = {}, onDetails = {}, onDownload = {}, onRename = {}, onDelete = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "File · rename large text", widthDp = 412, heightDp = 915, fontScale = 2f, showBackground = true)
@Composable
fun BrowserRenamePreview() {
    OpenListTheme {
        Surface(Modifier.fillMaxSize()) {
            org.openlist.mobile.ui.browser.FileMutationDialog(
                state = org.openlist.mobile.ui.browser.BrowserActionState(
                    entry = BrowserEntry("/工作空间/项目交付说明.pdf", "/工作空间", OpenListObject(name = "项目交付说明.pdf", size = 630000)),
                    kind = org.openlist.mobile.ui.browser.BrowserActionKind.Rename,
                ),
                onNameChange = {}, onSubmit = {}, onDismiss = {},
            )
        }
    }
}
