package org.openlist.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openlist.mobile.AppContainer
import org.openlist.mobile.SessionLoadState
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.media.gallery.GalleryImageRepository
import org.openlist.mobile.data.cache.CacheStats
import org.openlist.mobile.ui.designsystem.OpenListLayout
import org.openlist.mobile.ui.transfers.TransferCenterState
import org.openlist.mobile.ui.transfers.TransfersRoute
import org.openlist.mobile.ui.transfers.rememberTransferCenterState
import org.openlist.mobile.ui.viewer.NowPlayingBar

/**
 * The root UI exposes platform actions as callbacks. It can browse and describe files without
 * pretending that a player, document picker, or cache service has already handled an action.
 */
@Composable
fun OpenListApp(
    container: AppContainer,
    onOpenMedia: (
        path: String,
        item: OpenListObject,
        siblings: List<OpenListObject>,
    ) -> Unit = { _, _, _ -> },
    hasPlaybackQueue: Boolean = false,
    playbackTitle: String? = null,
    playbackIsPlaying: Boolean = false,
    playbackIsVideo: Boolean = false,
    playbackStatusLabel: String? = null,
    playbackActionLabel: String? = null,
    onPlaybackToggle: () -> Unit = {},
    onPlaybackQueueRequested: () -> Unit = {},
    onUploadRequested: (directory: String) -> Unit = {},
    onDownloadRequested: (path: String, item: OpenListObject) -> Unit = { _, _ -> },
    onClearCacheRequested: suspend () -> Unit = {},
    onCacheUsageRequested: suspend () -> CacheStats? = { null },
    onFileActionsRequested: (path: String, item: OpenListObject) -> Unit = { _, _ -> },
    // The disk cache reconciles on an IO dispatcher. Resolve this dependency only when a gallery
    // is actually opened so login/session restoration never waits for cache directory scanning.
    galleryImageRepository: GalleryImageRepository? = null,
) {
    val settings by container.sessionStore.settings.collectAsStateWithLifecycle()
    val authenticating by container.authenticating.collectAsStateWithLifecycle()
    val sessionLoadState by container.sessionLoadState.collectAsStateWithLifecycle()
    val accounts by container.sessionStore.accountSummaries.collectAsStateWithLifecycle()
    var manageAccountsWhileSignedOut by rememberSaveable { mutableStateOf(false) }
    val sessionDestination = when {
        sessionLoadState != SessionLoadState.Ready -> SessionDestination.Loading
        settings.token.isNotBlank() && !authenticating -> SessionDestination.SignedIn
        manageAccountsWhileSignedOut -> SessionDestination.Accounts
        else -> SessionDestination.Login
    }

    BackHandler(enabled = sessionDestination == SessionDestination.Accounts) {
        manageAccountsWhileSignedOut = false
    }

    LaunchedEffect(sessionDestination) {
        if (sessionDestination == SessionDestination.SignedIn) {
            manageAccountsWhileSignedOut = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = sessionDestination,
            contentKey = { it },
            label = "session",
        ) { destination ->
            when (destination) {
                SessionDestination.Loading -> SessionLoadingScreen(
                    errorMessage = (sessionLoadState as? SessionLoadState.Failed)?.message,
                    onRetry = container::retrySessionLoad,
                )
                SessionDestination.Login -> LoginScreen(
                    container = container,
                    authenticating = authenticating,
                    onManageAccounts = { manageAccountsWhileSignedOut = true },
                )
                SessionDestination.Accounts -> AccountScreen(
                    container = container,
                    onBack = { manageAccountsWhileSignedOut = false },
                )
                SessionDestination.SignedIn -> key(
                    accounts.firstOrNull { it.isActive }?.id?.value,
                ) {
                    SignedInShell(
                        container = container,
                        onOpenMedia = onOpenMedia,
                        hasPlaybackQueue = hasPlaybackQueue,
                        playbackTitle = playbackTitle,
                        playbackIsPlaying = playbackIsPlaying,
                        playbackIsVideo = playbackIsVideo,
                        playbackStatusLabel = playbackStatusLabel,
                        playbackActionLabel = playbackActionLabel,
                        onPlaybackToggle = onPlaybackToggle,
                        onPlaybackQueueRequested = onPlaybackQueueRequested,
                        onUploadRequested = onUploadRequested,
                        onDownloadRequested = onDownloadRequested,
                        onClearCacheRequested = onClearCacheRequested,
                        onCacheUsageRequested = onCacheUsageRequested,
                        onFileActionsRequested = onFileActionsRequested,
                        galleryImageRepository = galleryImageRepository,
                    )
                }
            }
        }
    }
}

private enum class SessionDestination { Loading, Login, Accounts, SignedIn }

@Composable
internal fun SessionLoadingScreen(errorMessage: String?, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text("OpenList", style = MaterialTheme.typography.headlineSmall)
            if (errorMessage == null) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Card(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(
                            "无法读取本机会话",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetry) { Text("重试读取") }
                    }
                }
            }
        }
    }
}

private enum class AppDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Files("文件", Icons.Filled.Folder, Icons.Outlined.Folder),
    Transfers("传输", Icons.Filled.SwapVert, Icons.Filled.SwapVert),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

private enum class SettingsPage { Root, Accounts }

@Composable
private fun SignedInShell(
    container: AppContainer,
    onOpenMedia: (String, OpenListObject, List<OpenListObject>) -> Unit,
    hasPlaybackQueue: Boolean,
    playbackTitle: String?,
    playbackIsPlaying: Boolean,
    playbackIsVideo: Boolean,
    playbackStatusLabel: String?,
    playbackActionLabel: String?,
    onPlaybackToggle: () -> Unit,
    onPlaybackQueueRequested: () -> Unit,
    onUploadRequested: (String) -> Unit,
    onDownloadRequested: (String, OpenListObject) -> Unit,
    onClearCacheRequested: suspend () -> Unit,
    onCacheUsageRequested: suspend () -> CacheStats?,
    onFileActionsRequested: (String, OpenListObject) -> Unit,
    galleryImageRepository: GalleryImageRepository?,
) {
    val sessionBusy by container.sessionBusy.collectAsStateWithLifecycle()
    val settings by container.sessionStore.settings.collectAsStateWithLifecycle()
    val transfers = rememberTransferCenterState(settings)
    var destination by rememberSaveable { mutableStateOf(AppDestination.Files) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    var accountsReturnDestination by rememberSaveable { mutableStateOf(AppDestination.Settings) }
    fun closeAccounts() {
        settingsPage = SettingsPage.Root
        destination = accountsReturnDestination
    }
    fun openAccounts() {
        accountsReturnDestination = destination
        settingsPage = SettingsPage.Accounts
        destination = AppDestination.Settings
    }
    val backAction = signedInBackAction(
        isFilesDestination = destination == AppDestination.Files,
        isSettingsRoot = settingsPage == SettingsPage.Root,
    )
    BackHandler(backAction != SignedInBackAction.None) {
        when (backAction) {
            SignedInBackAction.None -> Unit
            SignedInBackAction.SettingsRoot -> closeAccounts()
            SignedInBackAction.Files -> destination = AppDestination.Files
        }
    }

    fun selectDestination(next: AppDestination) {
        destination = next
        settingsPage = SettingsPage.Root
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp
        ResponsiveDestinationHost(
            useNavigationRail = useNavigationRail,
            navigationRail = {
                NavigationRail(
                    header = {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 16.dp)
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = "OpenList",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    },
                ) {
                    Spacer(Modifier.weight(1f))
                    AppDestination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item,
                            onClick = { selectDestination(item) },
                            icon = {
                                DestinationIcon(item, destination, transfers.activeCount)
                            },
                            label = { Text(item.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            },
            bottomBar = {
                if (settingsPage == SettingsPage.Root) {
                    NavigationBar {
                        AppDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { selectDestination(item) },
                                icon = {
                                    DestinationIcon(item, destination, transfers.activeCount)
                                },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
            persistentBar = {
                if (settingsPage == SettingsPage.Root) {
                    if (transfers.activeCount > 0 && destination != AppDestination.Transfers) {
                        TransferActivitySummary(
                            count = transfers.activeCount,
                            onClick = { selectDestination(AppDestination.Transfers) },
                        )
                    }
                    if (hasPlaybackQueue) {
                        NowPlayingBar(
                            title = playbackTitle?.takeIf(String::isNotBlank) ?: "播放队列",
                            isPlaying = playbackIsPlaying,
                            isVideo = playbackIsVideo,
                            onOpen = onPlaybackQueueRequested,
                            onToggle = onPlaybackToggle,
                            statusLabel = playbackStatusLabel ?: if (playbackIsPlaying) "正在播放" else "已暂停",
                            playActionLabel = playbackActionLabel ?: "继续播放",
                        )
                    }
                }
            },
        ) {
            DestinationContent(
                destination = destination,
                settingsPage = settingsPage,
                onAccountsRequested = ::openAccounts,
                onAccountsBack = ::closeAccounts,
                transfers = transfers,
                onBrowseFiles = { selectDestination(AppDestination.Files) },
                container = container,
                onOpenMedia = onOpenMedia,
                onUploadRequested = onUploadRequested,
                onDownloadRequested = onDownloadRequested,
                onClearCacheRequested = onClearCacheRequested,
                onCacheUsageRequested = onCacheUsageRequested,
                onFileActionsRequested = onFileActionsRequested,
                galleryImageRepository = galleryImageRepository,
            )
        }
    }

    if (sessionBusy) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Column {
                        Text("正在更新账户", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "请稍候，当前文件会话已暂停。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Keeps the destination subtree at one call site while the navigation chrome changes width. */
@Composable
internal fun ResponsiveDestinationHost(
    useNavigationRail: Boolean,
    modifier: Modifier = Modifier,
    navigationRail: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    persistentBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        if (useNavigationRail) navigationRail()
        Scaffold(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                Column(
                    if (useNavigationRail) Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    else Modifier,
                ) {
                    persistentBar()
                    if (!useNavigationRail) bottomBar()
                }
            },
        ) { contentPadding ->
            Box(Modifier.fillMaxSize().padding(contentPadding)) {
                content()
            }
        }
    }
}

internal enum class SignedInBackAction { None, SettingsRoot, Files }

internal fun signedInBackAction(
    isFilesDestination: Boolean,
    isSettingsRoot: Boolean,
): SignedInBackAction = when {
    isFilesDestination -> SignedInBackAction.None
    !isSettingsRoot -> SignedInBackAction.SettingsRoot
    else -> SignedInBackAction.Files
}

@Composable
private fun DestinationContent(
    destination: AppDestination,
    settingsPage: SettingsPage,
    onAccountsRequested: () -> Unit,
    onAccountsBack: () -> Unit,
    transfers: TransferCenterState,
    onBrowseFiles: () -> Unit,
    container: AppContainer,
    onOpenMedia: (String, OpenListObject, List<OpenListObject>) -> Unit,
    onUploadRequested: (String) -> Unit,
    onDownloadRequested: (String, OpenListObject) -> Unit,
    onClearCacheRequested: suspend () -> Unit,
    onCacheUsageRequested: suspend () -> CacheStats?,
    onFileActionsRequested: (String, OpenListObject) -> Unit,
    galleryImageRepository: GalleryImageRepository?,
) {
    val sessionBusy by container.sessionBusy.collectAsStateWithLifecycle()
    val settingsStateHolder = rememberSaveableStateHolder()
    SaveableAnimatedContent(
        targetState = destination,
        contentKey = { it },
        label = "destination",
    ) { target ->
        when (target) {
            AppDestination.Files -> BrowserScreen(
                container = container,
                onOpenMedia = onOpenMedia,
                onUploadRequested = onUploadRequested,
                onDownloadRequested = onDownloadRequested,
                onFileActionsRequested = onFileActionsRequested,
                galleryImageRepository = galleryImageRepository,
                onAccountsRequested = onAccountsRequested,
            )

            AppDestination.Transfers -> TransfersRoute(
                state = transfers,
                onBrowseFiles = onBrowseFiles,
            )

            AppDestination.Settings -> SaveableContent(
                stateHolder = settingsStateHolder,
                key = settingsPage,
            ) {
                when (settingsPage) {
                    SettingsPage.Root -> SettingsScreen(
                        sessionStore = container.sessionStore,
                        operationBusy = sessionBusy,
                        onAccountsRequested = onAccountsRequested,
                        onClearCacheRequested = onClearCacheRequested,
                        onCacheUsageRequested = onCacheUsageRequested,
                        onLogoutRequested = { container.logout() },
                    )
                    SettingsPage.Accounts -> AccountScreen(
                        container = container,
                        onBack = onAccountsBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun DestinationIcon(
    item: AppDestination,
    selected: AppDestination,
    activeTransferCount: Int,
) {
    BadgedBox(
        badge = {
            if (item == AppDestination.Transfers && activeTransferCount > 0) {
                Badge { Text(activeTransferCount.toString()) }
            }
        },
    ) {
        Icon(
            if (item == selected) item.selectedIcon else item.unselectedIcon,
            contentDescription = null,
        )
    }
}

@Composable
private fun TransferActivitySummary(count: Int, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "查看传输", onClick = onClick)
                    .padding(horizontal = OpenListLayout.pagePadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("$count 项传输进行中", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
    }
}


@Composable
internal fun <T : Any> SaveableAnimatedContent(
    targetState: T,
    contentKey: (T) -> Any = { it },
    label: String,
    content: @Composable (T) -> Unit,
) {
    val stateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = targetState,
        contentKey = contentKey,
        label = label,
    ) { target ->
        SaveableContent(
            stateHolder = stateHolder,
            key = contentKey(target),
        ) {
            content(target)
        }
    }
}

@Composable
internal fun SaveableContent(
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    key: Any,
    content: @Composable () -> Unit,
) {
    stateHolder.SaveableStateProvider(key, content)
}
