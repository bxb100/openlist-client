package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openlist.mobile.AppContainer
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountDraft
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountSummary
import org.openlist.mobile.ui.account.ConnectionEndpointFields
import org.openlist.mobile.ui.account.accountConnectionDraft
import org.openlist.mobile.ui.designsystem.OpenListEmptyState
import org.openlist.mobile.ui.theme.OpenListTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onLoginRequired: () -> Unit = onBack,
) {
    val accounts by container.sessionStore.accountSummaries.collectAsStateWithLifecycle()
    val sessionBusy by container.sessionBusy.collectAsStateWithLifecycle()
    var editingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deletingAccountId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOperationError by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteOperationError by rememberSaveable { mutableStateOf<String?>(null) }
    var busyAccountId by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val editingAccount = editingAccountId?.let { id -> accounts.firstOrNull { it.id.value == id } }
    val deletingAccount = deletingAccountId?.let { id -> accounts.firstOrNull { it.id.value == id } }

    fun runAccountAction(
        id: String,
        onError: suspend (String) -> Unit = { message -> snackbar.showSnackbar(message) },
        action: suspend () -> Unit,
    ) {
        if (busyAccountId != null || sessionBusy) return
        busyAccountId = id
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                onError(error.message ?: "账户操作失败")
            } finally {
                busyAccountId = null
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("服务器与账户") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (busyAccountId == null && !sessionBusy) {
                        editorOperationError = null
                        editingAccountId = null
                        creating = true
                    }
                },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加账户") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).selectableGroup(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 104.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    Text(
                        "你的文件空间",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "每个账户连接一处文件空间。选择账户即可继续浏览。",
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
            if (accounts.isEmpty()) {
                item {
                    OpenListEmptyState(
                        icon = Icons.Default.Cloud,
                        title = "添加第一处文件空间",
                        description = "填写服务器地址和用户名，登录后即可访问你的文件。",
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    )
                }
            }
            if (sessionBusy) {
                item { LinearProgressIndicator(Modifier.widthIn(max = 720.dp).fillMaxWidth()) }
            }
            items(accounts, key = { it.id.value }) { account ->
                AccountCard(
                    account = account,
                    busy = busyAccountId == account.id.value,
                    actionsEnabled = busyAccountId == null && !sessionBusy,
                    onSwitch = {
                        runAccountAction(account.id.value) {
                            if (!account.isActive) container.switchAccount(account.id)
                            if (!account.isAuthenticated) onLoginRequired() else onBack()
                        }
                    },
                    onEdit = {
                        editorOperationError = null
                        creating = false
                        editingAccountId = account.id.value
                    },
                    onDelete = {
                        deleteOperationError = null
                        deletingAccountId = account.id.value
                    },
                )
            }
        }
    }

    if (creating || editingAccount != null) {
        AccountEditorDialog(
            account = editingAccount,
            onDismiss = {
                creating = false
                editingAccountId = null
                editorOperationError = null
            },
            onSave = { draft ->
                val target = editingAccount
                editorOperationError = null
                runAccountAction(
                    id = target?.id?.value ?: "new",
                    onError = { message -> editorOperationError = message },
                ) {
                    if (target == null) container.addAccount(draft) else container.editAccount(target.id, draft)
                    creating = false
                    editingAccountId = null
                    if (container.sessionStore.snapshot().token.isBlank()) onLoginRequired()
                }
            },
            busy = busyAccountId != null || sessionBusy,
            operationError = editorOperationError,
            onOperationErrorCleared = { editorOperationError = null },
        )
    }

    deletingAccount?.let { account ->
        AlertDialog(
            onDismissRequest = {
                if (busyAccountId == null && !sessionBusy) {
                    deletingAccountId = null
                    deleteOperationError = null
                }
            },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("删除“${account.displayName}”？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (account.isActive) {
                            "这会删除当前账户及其登录令牌，并自动选择其他账户。服务器上的数据不会被删除。"
                        } else {
                            "这会从本机删除该账户及其登录令牌。服务器上的数据不会被删除。"
                        },
                    )
                    deleteOperationError?.let { DialogOperationError(it) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteOperationError = null
                        runAccountAction(
                            id = account.id.value,
                            onError = { message -> deleteOperationError = message },
                        ) {
                            container.deleteAccount(account.id)
                            deletingAccountId = null
                        }
                    },
                    enabled = busyAccountId == null && !sessionBusy,
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deletingAccountId = null
                        deleteOperationError = null
                    },
                    enabled = busyAccountId == null && !sessionBusy,
                ) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
internal fun AccountCard(
    account: AccountSummary,
    busy: Boolean,
    actionsEnabled: Boolean,
    onSwitch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        color = if (account.isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f)
            else MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f)
                        .selectable(
                            selected = account.isActive,
                            enabled = actionsEnabled,
                            role = Role.RadioButton,
                            onClick = onSwitch,
                        ).padding(vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (account.isActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            account.server.baseUrl,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "${account.server.username} · ${if (account.isAuthenticated) "已登录" else "需要登录"}" +
                                if (account.isActive) " · 当前使用" else "",
                            color = if (account.requiresLogin) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (busy) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(20.dp), strokeWidth = 2.dp)
                }
                Box {
                    IconButton(onClick = { showMenu = true }, enabled = actionsEnabled) {
                        Icon(Icons.Default.MoreVert, contentDescription = "${account.displayName}的更多操作")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑连接") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = { showMenu = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("移除账户", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            onClick = { showMenu = false; onDelete() },
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountEditorDialog(
    account: AccountSummary?,
    onDismiss: () -> Unit,
    onSave: (AccountDraft) -> Unit,
    busy: Boolean,
    operationError: String?,
    onOperationErrorCleared: () -> Unit,
) {
    val initial = remember(account?.id?.value) {
        LoginEndpointDraft.fromBaseUrl(account?.server?.baseUrl.orEmpty())
    }
    var displayName by rememberSaveable(account?.id?.value) { mutableStateOf(account?.displayName.orEmpty()) }
    var host by rememberSaveable(account?.id?.value) { mutableStateOf(initial.host) }
    var protocolName by rememberSaveable(account?.id?.value) { mutableStateOf(initial.protocol.name) }
    var port by rememberSaveable(account?.id?.value) { mutableStateOf(initial.port) }
    var basePath by rememberSaveable(account?.id?.value) { mutableStateOf(initial.basePath) }
    var username by rememberSaveable(account?.id?.value) { mutableStateOf(account?.server?.username.orEmpty()) }
    var allowHttp by rememberSaveable(account?.id?.value) { mutableStateOf(account?.server?.allowInsecureHttp ?: true) }
    val endpoint = LoginEndpointDraft(host, LoginProtocol.valueOf(protocolName), port, basePath)
    val validation = runCatching {
        accountConnectionDraft(
            displayName = displayName,
            endpoint = endpoint,
            username = username,
            allowInsecureHttp = allowHttp,
            savedServer = account?.server,
        ).also { it.server.normalizedBaseUrl() }
    }
    val draft = validation.getOrNull()
    val canSave = draft != null && username.isNotBlank() && !busy
    fun updateEndpoint(value: LoginEndpointDraft) {
        host = value.host
        protocolName = value.protocol.name
        port = value.port
        basePath = value.basePath
        allowHttp = value.protocol == LoginProtocol.HTTP
        onOperationErrorCleared()
    }
    fun save() {
        if (canSave && draft != null) onSave(draft)
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (account == null) "添加账户" else "编辑连接") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消编辑")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(
                        if (account == null) "连接另一处文件空间" else "连接信息",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    ConnectionEndpointFields(
                        endpoint = endpoint,
                        onHostChange = { updateEndpoint(endpoint.withAddressInput(it)) },
                        onEndpointChange = ::updateEndpoint,
                        enabled = !busy,
                        error = validation.exceptionOrNull()?.message?.takeIf { host.isNotBlank() },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it; onOperationErrorCleared() },
                        label = { Text("用户名") },
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(80); onOperationErrorCleared() },
                        label = { Text("账户名称（可选）") },
                        supportingText = { Text("例如：家里的 NAS、工作文件") },
                        enabled = !busy,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (account != null) {
                        Text(
                            "修改服务器或用户名后，这个账户需要重新登录。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    operationError?.let { DialogOperationError(it) }
                    Button(
                        onClick = ::save,
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("正在保存")
                        } else Text(if (account == null) "保存并继续登录" else "保存连接")
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogOperationError(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(name = "Saved connection", showBackground = true, widthDp = 412)
@Preview(name = "Saved connection large text", showBackground = true, widthDp = 360, fontScale = 2f)
@Composable
private fun AccountConnectionPreview() {
    OpenListTheme {
        AccountCard(
            account = AccountSummary(
                id = AccountId("preview"),
                displayName = "家里的 NAS",
                server = ServerProfile("https://nas.example.com/openlist", "xiaobo"),
                isActive = true,
                isAuthenticated = true,
                requiresLogin = false,
            ),
            busy = false,
            actionsEnabled = true,
            onSwitch = {},
            onEdit = {},
            onDelete = {},
        )
    }
}
