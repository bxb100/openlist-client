package org.openlist.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openlist.mobile.AppContainer
import org.openlist.mobile.SessionLoadState
import org.openlist.mobile.core.model.OpenListObject
import org.openlist.mobile.media.gallery.GalleryImageRepository

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
    onPlaybackQueueRequested: () -> Unit = {},
    onUploadRequested: (directory: String) -> Unit = {},
    onDownloadRequested: (path: String, item: OpenListObject) -> Unit = { _, _ -> },
    onClearCacheRequested: () -> Unit = {},
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
                        onPlaybackQueueRequested = onPlaybackQueueRequested,
                        onUploadRequested = onUploadRequested,
                        onDownloadRequested = onDownloadRequested,
                        onClearCacheRequested = onClearCacheRequested,
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
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

private enum class SettingsPage { Root, Accounts }

@Composable
private fun SignedInShell(
    container: AppContainer,
    onOpenMedia: (String, OpenListObject, List<OpenListObject>) -> Unit,
    hasPlaybackQueue: Boolean,
    onPlaybackQueueRequested: () -> Unit,
    onUploadRequested: (String) -> Unit,
    onDownloadRequested: (String, OpenListObject) -> Unit,
    onClearCacheRequested: () -> Unit,
    onFileActionsRequested: (String, OpenListObject) -> Unit,
    galleryImageRepository: GalleryImageRepository?,
) {
    val sessionBusy by container.sessionBusy.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(AppDestination.Files) }
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    val backAction = signedInBackAction(
        isFilesDestination = destination == AppDestination.Files,
        isSettingsRoot = settingsPage == SettingsPage.Root,
    )
    BackHandler(backAction != SignedInBackAction.None) {
        when (backAction) {
            SignedInBackAction.None -> Unit
            SignedInBackAction.SettingsRoot -> settingsPage = SettingsPage.Root
            SignedInBackAction.Files -> destination = AppDestination.Files
        }
    }

    fun selectDestination(next: AppDestination) {
        destination = next
        settingsPage = SettingsPage.Root
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 840.dp
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
                                Icon(
                                    if (destination == item) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = null,
                                )
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
                                    Icon(
                                        if (destination == item) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            },
        ) {
            DestinationContent(
                destination = destination,
                settingsPage = settingsPage,
                onSettingsPageChange = { settingsPage = it },
                container = container,
                onOpenMedia = onOpenMedia,
                hasPlaybackQueue = hasPlaybackQueue,
                onPlaybackQueueRequested = onPlaybackQueueRequested,
                onUploadRequested = onUploadRequested,
                onDownloadRequested = onDownloadRequested,
                onClearCacheRequested = onClearCacheRequested,
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
    content: @Composable () -> Unit,
) {
    Row(modifier.fillMaxSize()) {
        if (useNavigationRail) navigationRail()
        Scaffold(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = { if (!useNavigationRail) bottomBar() },
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
    onSettingsPageChange: (SettingsPage) -> Unit,
    container: AppContainer,
    onOpenMedia: (String, OpenListObject, List<OpenListObject>) -> Unit,
    hasPlaybackQueue: Boolean,
    onPlaybackQueueRequested: () -> Unit,
    onUploadRequested: (String) -> Unit,
    onDownloadRequested: (String, OpenListObject) -> Unit,
    onClearCacheRequested: () -> Unit,
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
                hasPlaybackQueue = hasPlaybackQueue,
                onPlaybackQueueRequested = onPlaybackQueueRequested,
                onUploadRequested = onUploadRequested,
                onDownloadRequested = onDownloadRequested,
                onFileActionsRequested = onFileActionsRequested,
                galleryImageRepository = galleryImageRepository,
            )

            AppDestination.Settings -> SaveableContent(
                stateHolder = settingsStateHolder,
                key = settingsPage,
            ) {
                when (settingsPage) {
                    SettingsPage.Root -> SettingsScreen(
                        sessionStore = container.sessionStore,
                        operationBusy = sessionBusy,
                        onAccountsRequested = { onSettingsPageChange(SettingsPage.Accounts) },
                        onClearCacheRequested = onClearCacheRequested,
                        onLogoutRequested = { container.logout() },
                    )
                    SettingsPage.Accounts -> AccountScreen(
                        container = container,
                        onBack = { onSettingsPageChange(SettingsPage.Root) },
                    )
                }
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
