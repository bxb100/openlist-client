package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openlist.mobile.AppContainer
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountDraft
import org.openlist.mobile.data.account.AccountSummary

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
        scope.launch {
            busyAccountId = id
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("账户") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().widthIn(max = 760.dp)) {
                    Text(
                        "已保存账户",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "切换账户会停止当前播放并清除受保护目录的临时密码。修改服务器或用户名后需要重新登录。",
                        modifier = Modifier.padding(top = 6.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (sessionBusy) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth().widthIn(max = 760.dp)) }
            }
            items(accounts, key = { it.id.value }) { account ->
                AccountCard(
                    account = account,
                    busy = busyAccountId == account.id.value,
                    actionsEnabled = busyAccountId == null && !sessionBusy,
                    onSwitch = {
                        runAccountAction(account.id.value) {
                            container.switchAccount(account.id)
                            if (!account.isAuthenticated) onLoginRequired()
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
private fun AccountCard(
    account: AccountSummary,
    busy: Boolean,
    actionsEnabled: Boolean,
    onSwitch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().widthIn(max = 760.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (account.isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (account.isActive) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = account.isActive,
                        enabled = actionsEnabled,
                        role = Role.RadioButton,
                        onClick = { if (!account.isActive) onSwitch() },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = MaterialTheme.shapes.large,
                    color = if (account.isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp),
                        tint = if (account.isActive) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${account.server.username} · ${account.server.baseUrl}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (account.isActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    RadioButton(
                        selected = account.isActive,
                        onClick = null,
                        enabled = actionsEnabled,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDelete, enabled = actionsEnabled) {
                    Icon(Icons.Default.Delete, contentDescription = "删除 ${account.displayName}")
                }
                IconButton(onClick = onEdit, enabled = actionsEnabled) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑 ${account.displayName}")
                }
                if (account.isActive) {
                    Text(
                        "当前使用",
                        modifier = Modifier.clearAndSetSemantics { },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else if (!account.isAuthenticated) {
                    Text(
                        "需要登录",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountEditorDialog(
    account: AccountSummary?,
    onDismiss: () -> Unit,
    onSave: (AccountDraft) -> Unit,
    busy: Boolean,
    operationError: String?,
    onOperationErrorCleared: () -> Unit,
) {
    val creating = account == null
    var displayName by rememberSaveable(account?.id?.value) { mutableStateOf(account?.displayName.orEmpty()) }
    var server by rememberSaveable(account?.id?.value) { mutableStateOf(account?.server?.baseUrl.orEmpty()) }
    var username by rememberSaveable(account?.id?.value) { mutableStateOf(account?.server?.username.orEmpty()) }
    var allowHttp by rememberSaveable(account?.id?.value) {
        mutableStateOf(account?.server?.allowInsecureHttp ?: false)
    }
    val usesHttp = server.isNotBlank() && if (creating) {
        LoginEndpointDraft.fromBaseUrl(server).protocol == LoginProtocol.HTTP
    } else {
        server.trim().startsWith("http://", ignoreCase = true)
    }
    val draftProfile = runCatching {
        accountEditorServerProfile(
            server = server,
            username = username,
            creating = creating,
            allowHttp = allowHttp,
        )
    }.getOrNull()
    val validationError = when {
        server.isBlank() -> "请输入服务器地址"
        username.isBlank() -> "请输入用户名"
        usesHttp && !creating && !allowHttp -> "使用 HTTP 前需要确认允许明文连接"
        draftProfile == null -> "服务器地址无效"
        else -> runCatching { draftProfile.normalizedBaseUrl() }.exceptionOrNull()?.message
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
        title = { Text(if (account == null) "添加账户" else "编辑账户") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountEditorFieldCard {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = {
                            displayName = it.take(80)
                            onOperationErrorCleared()
                        },
                        label = { Text("显示名称（可选）") },
                        singleLine = true,
                        enabled = !busy,
                        colors = transparentOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AccountEditorFieldCard {
                    OutlinedTextField(
                        value = server,
                        onValueChange = {
                            server = it
                            if (!it.trim().startsWith("http://", ignoreCase = true)) allowHttp = false
                            onOperationErrorCleared()
                        },
                        label = { Text(if (creating) "服务器地址 / IP" else "服务器地址") },
                        placeholder = { Text(if (creating) "192.168.1.100" else "https://files.example.com") },
                        supportingText = if (creating) {
                            { Text("未填写协议时默认使用 HTTP 和 5244 端口") }
                        } else {
                            null
                        },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = transparentOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AccountEditorFieldCard {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            onOperationErrorCleared()
                        },
                        label = { Text("用户名") },
                        singleLine = true,
                        enabled = !busy,
                        colors = transparentOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (usesHttp && creating) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            "HTTP 不会加密登录凭据；公网服务器强烈建议改用 HTTPS。",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else if (usesHttp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = allowHttp,
                                enabled = !busy,
                                role = Role.Switch,
                                onValueChange = {
                                    allowHttp = it
                                    onOperationErrorCleared()
                                },
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("允许明文 HTTP", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "连接和登录凭据不会加密",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = allowHttp, onCheckedChange = null, enabled = !busy)
                    }
                }
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                operationError?.let { DialogOperationError(it) }
                if (account != null) {
                    Text(
                        "更改服务器或用户名会清除该账户原有令牌；下次使用该账户时需要重新登录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    draftProfile?.let { onSave(AccountDraft(displayName.trim(), it)) }
                },
                enabled = validationError == null && !busy,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (account == null) "继续登录" else "保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

@Composable
private fun AccountEditorFieldCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            content()
        }
    }
}

/** New accounts share login endpoint defaults; edits preserve the stored URL's exact semantics. */
internal fun accountEditorServerProfile(
    server: String,
    username: String,
    creating: Boolean,
    allowHttp: Boolean,
): ServerProfile = if (creating) {
    LoginEndpointDraft.fromBaseUrl(server).serverProfile(username)
} else {
    val trimmedServer = server.trim()
    ServerProfile(
        baseUrl = trimmedServer,
        username = username.trim(),
        allowInsecureHttp = trimmedServer.startsWith("http://", ignoreCase = true) && allowHttp,
    )
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
            text = "操作失败：$message",
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
