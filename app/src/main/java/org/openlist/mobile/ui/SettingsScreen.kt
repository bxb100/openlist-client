package org.openlist.mobile.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openlist.mobile.core.model.CachePolicy
import org.openlist.mobile.data.preferences.SessionStore
import java.text.DecimalFormat

private const val BYTES_PER_GIB = 1_073_741_824L
private const val MILLIS_PER_DAY = 86_400_000L
private val SETTINGS_MAX_CONTENT_WIDTH = 720.dp

internal object SettingsUiTags {
    const val ACCOUNT_CARD = "settings_account_card"
    const val THEME_SELECTOR = "settings_theme_selector"
    const val THEME_SYSTEM = "settings_theme_system"
    const val THEME_LIGHT = "settings_theme_light"
    const val THEME_DARK = "settings_theme_dark"
    const val DYNAMIC_COLOR_SWITCH = "settings_dynamic_color_switch"
    const val CACHE_STATUS = "settings_cache_status"
    const val CACHE_SIZE = "settings_cache_size"
    const val CACHE_AGE = "settings_cache_age"
    const val CACHE_ENTRIES = "settings_cache_entries"
    const val CACHE_REVERT = "settings_cache_revert"
    const val CACHE_SAVE = "settings_cache_save"
    const val CACHE_CLEAR = "settings_cache_clear"
    const val CACHE_CLEAR_CONFIRM = "settings_cache_clear_confirm"
    const val LOGOUT = "settings_logout"
    const val LOGOUT_CONFIRM = "settings_logout_confirm"
}

private enum class ThemeMode(
    val label: String,
    val value: Boolean?,
    val tag: String,
) {
    System("系统", null, SettingsUiTags.THEME_SYSTEM),
    Light("浅色", false, SettingsUiTags.THEME_LIGHT),
    Dark("深色", true, SettingsUiTags.THEME_DARK),
}

@Composable
internal fun SettingsScreen(
    sessionStore: SessionStore,
    operationBusy: Boolean,
    onAccountsRequested: () -> Unit,
    onClearCacheRequested: () -> Unit,
    onLogoutRequested: suspend () -> Unit,
) {
    val settings by sessionStore.settings.collectAsStateWithLifecycle()

    SettingsPageContent(
        serverBaseUrl = settings.server.baseUrl,
        username = settings.server.username,
        darkTheme = settings.darkTheme,
        dynamicColor = settings.dynamicColor,
        dynamicColorAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
        cachePolicy = settings.cachePolicy,
        operationBusy = operationBusy,
        onAccountsRequested = onAccountsRequested,
        onAppearanceChanged = sessionStore::setAppearance,
        onCachePolicySave = sessionStore::setCachePolicy,
        onClearCacheRequested = onClearCacheRequested,
        onLogoutRequested = onLogoutRequested,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageContent(
    serverBaseUrl: String,
    username: String,
    darkTheme: Boolean?,
    dynamicColor: Boolean,
    dynamicColorAvailable: Boolean,
    cachePolicy: CachePolicy,
    operationBusy: Boolean,
    onAccountsRequested: () -> Unit,
    onAppearanceChanged: suspend (dynamicColor: Boolean, darkTheme: Boolean?) -> Unit,
    onCachePolicySave: suspend (CachePolicy) -> Unit,
    onClearCacheRequested: () -> Unit,
    onLogoutRequested: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cacheSizeGiB by rememberSaveable(cachePolicy.maxBytes) {
        mutableStateOf(formatDecimal(cachePolicy.maxBytes.toDouble() / BYTES_PER_GIB))
    }
    var cacheAgeDays by rememberSaveable(cachePolicy.maxAgeMillis) {
        mutableStateOf(formatDecimal(cachePolicy.maxAgeMillis.toDouble() / MILLIS_PER_DAY))
    }
    var cacheEntries by rememberSaveable(cachePolicy.maxEntries) {
        mutableStateOf(cachePolicy.maxEntries.toString())
    }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    var logoutConfirmation by rememberSaveable { mutableStateOf(false) }
    var cacheSaving by remember { mutableStateOf(false) }
    var appearanceSaving by remember { mutableStateOf(false) }
    var logoutInProgress by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val cacheValidation = validateCachePolicy(cacheSizeGiB, cacheAgeDays, cacheEntries)
    val editedCachePolicy = cacheValidation.toPolicy()
    val cacheDirty = editedCachePolicy != cachePolicy

    fun resetCacheEditor() {
        cacheSizeGiB = formatDecimal(cachePolicy.maxBytes.toDouble() / BYTES_PER_GIB)
        cacheAgeDays = formatDecimal(cachePolicy.maxAgeMillis.toDouble() / MILLIS_PER_DAY)
        cacheEntries = cachePolicy.maxEntries.toString()
    }

    fun updateAppearance(updatedDynamicColor: Boolean, updatedDarkTheme: Boolean?) {
        if (appearanceSaving) return
        appearanceSaving = true
        scope.launch {
            var failure: Throwable? = null
            try {
                onAppearanceChanged(updatedDynamicColor, updatedDarkTheme)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                appearanceSaving = false
            }
            failure?.let {
                snackbarHostState.showSnackbar(actionFailureMessage("更新外观失败", it))
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text("设置") }, scrollBehavior = scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                SettingsSection(
                    title = "账户与服务器",
                    icon = { Icon(Icons.Default.Cloud, contentDescription = null) },
                ) {
                    ListItem(
                        headlineContent = {
                            Text(
                                username.ifBlank { "未设置用户名" },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    serverBaseUrl.ifBlank { "尚未配置服务器" },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "管理账户、服务器与登录",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.padding(12.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        },
                        trailingContent = {
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        },
                        colors = transparentListItemColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SettingsUiTags.ACCOUNT_CARD)
                            .clickable(
                                enabled = !operationBusy && !logoutInProgress,
                                role = Role.Button,
                                onClickLabel = "打开账户管理",
                                onClick = onAccountsRequested,
                            ),
                    )
                }
            }

            item { Spacer(Modifier.height(20.dp)) }

            item {
                SettingsSection(
                    title = "外观",
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "主题",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .testTag(SettingsUiTags.THEME_SELECTOR),
                        ) {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = darkTheme == mode.value,
                                    onClick = { updateAppearance(dynamicColor, mode.value) },
                                    enabled = !appearanceSaving,
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = ThemeMode.entries.size,
                                    ),
                                    modifier = Modifier.testTag(mode.tag),
                                    label = { Text(mode.label, maxLines = 1) },
                                )
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = { Text("动态色彩") },
                            supportingContent = {
                                Text(
                                    if (dynamicColorAvailable) {
                                        "使用设备壁纸配色"
                                    } else if (dynamicColor) {
                                        "此偏好已开启，但当前设备需要 Android 12 或更高版本"
                                    } else {
                                        "当前设备不可用，需要 Android 12 或更高版本"
                                    },
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = dynamicColor,
                                    onCheckedChange = null,
                                    enabled = dynamicColorAvailable && !appearanceSaving,
                                )
                            },
                            colors = transparentListItemColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SettingsUiTags.DYNAMIC_COLOR_SWITCH)
                                .toggleable(
                                    value = dynamicColor,
                                    enabled = dynamicColorAvailable && !appearanceSaving,
                                    role = Role.Switch,
                                    onValueChange = { enabled ->
                                        updateAppearance(enabled, darkTheme)
                                    },
                                ),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }

            item {
                SettingsSection(
                    title = "离线缓存",
                    icon = { Icon(Icons.Default.Cached, contentDescription = null) },
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        CacheStatusSummary(
                            currentEnabled = cachePolicy.cacheEnabled,
                            editedEnabled = cacheValidation.cacheEnabled,
                            isDirty = cacheDirty,
                        )
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text(
                                "缓存同时受容量、保留时间和文件数限制；达到任一上限时会优先清理较旧内容。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            CachePolicyFields(
                                sizeGiB = cacheSizeGiB,
                                onSizeGiBChange = { cacheSizeGiB = sanitizeDecimal(it) },
                                ageDays = cacheAgeDays,
                                onAgeDaysChange = { cacheAgeDays = sanitizeDecimal(it) },
                                entries = cacheEntries,
                                onEntriesChange = {
                                    cacheEntries = it.filter(Char::isDigit).take(10)
                                },
                                validation = cacheValidation,
                                enabled = !cacheSaving,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (cacheDirty) {
                                    TextButton(
                                        onClick = ::resetCacheEditor,
                                        enabled = !cacheSaving,
                                        modifier = Modifier.testTag(SettingsUiTags.CACHE_REVERT),
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("撤销更改")
                                    }
                                }
                                Button(
                                    onClick = {
                                        val updated = editedCachePolicy ?: return@Button
                                        cacheSaving = true
                                        scope.launch {
                                            var snackbarMessage = "缓存设置已保存"
                                            try {
                                                onCachePolicySave(updated)
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (error: Throwable) {
                                                snackbarMessage = actionFailureMessage(
                                                    "保存缓存设置失败",
                                                    error,
                                                )
                                            } finally {
                                                cacheSaving = false
                                            }
                                            snackbarHostState.showSnackbar(snackbarMessage)
                                        }
                                    },
                                    enabled = cacheDirty && cacheValidation.isValid && !cacheSaving,
                                    modifier = Modifier.testTag(SettingsUiTags.CACHE_SAVE),
                                ) {
                                    if (cacheSaving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Default.Save, contentDescription = null)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (cacheSaving) "保存中" else "保存")
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        ListItem(
                            headlineContent = {
                                Text("清空现有缓存", color = MaterialTheme.colorScheme.error)
                            },
                            supportingContent = {
                                Text("移除已缓存的媒体和文件，不会更改以上限制")
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            colors = transparentListItemColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(SettingsUiTags.CACHE_CLEAR)
                                .clickable(
                                    enabled = !cacheSaving,
                                    role = Role.Button,
                                    onClickLabel = "清空离线缓存",
                                    onClick = { clearConfirmation = true },
                                ),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }

            item {
                TextButton(
                    onClick = { logoutConfirmation = true },
                    enabled = !operationBusy && !logoutInProgress,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .widthIn(max = SETTINGS_MAX_CONTENT_WIDTH)
                        .fillMaxWidth()
                        .testTag(SettingsUiTags.LOGOUT),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("退出登录")
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text("清空离线缓存？") },
            text = { Text("已缓存的媒体和文件会被移除，需要时可再次从服务器下载。缓存限制不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearConfirmation = false
                        try {
                            onClearCacheRequested()
                            scope.launch { snackbarHostState.showSnackbar("清空请求已提交") }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    actionFailureMessage("清空缓存失败", error),
                                )
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag(SettingsUiTags.CACHE_CLEAR_CONFIRM),
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmation = false }) { Text("取消") }
            },
        )
    }

    if (logoutConfirmation) {
        AlertDialog(
            onDismissRequest = { logoutConfirmation = false },
            icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
            title = { Text("退出当前服务器？") },
            text = { Text("服务器地址和缓存偏好会保留，你可以稍后重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logoutConfirmation = false
                        logoutInProgress = true
                        scope.launch {
                            var failure: Throwable? = null
                            try {
                                onLogoutRequested()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                failure = error
                            } finally {
                                logoutInProgress = false
                            }
                            failure?.let {
                                snackbarHostState.showSnackbar(
                                    actionFailureMessage("退出登录失败", it),
                                )
                            }
                        }
                    },
                    enabled = !operationBusy && !logoutInProgress,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag(SettingsUiTags.LOGOUT_CONFIRM),
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { logoutConfirmation = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.widthIn(max = SETTINGS_MAX_CONTENT_WIDTH).fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            content()
        }
    }
}

@Composable
private fun CacheStatusSummary(
    currentEnabled: Boolean,
    editedEnabled: Boolean?,
    isDirty: Boolean,
) {
    val headline = when {
        editedEnabled == null -> "缓存设置需要修正"
        !isDirty && currentEnabled -> "缓存写入已启用"
        !isDirty -> "缓存写入已停用"
        editedEnabled == currentEnabled && currentEnabled -> "缓存写入保持启用"
        editedEnabled == currentEnabled -> "缓存写入保持停用"
        editedEnabled -> "保存后将启用缓存"
        else -> "保存后将停用缓存"
    }
    val supportingText = when {
        editedEnabled == null -> "修正下方字段后才能保存"
        isDirty && editedEnabled == currentEnabled -> "限制已更改；保存后缓存状态不变"
        editedEnabled -> "应用会按这三项限制保存离线内容"
        else -> "至少一项限制为 0，不会写入新缓存；现有缓存不会自动删除"
    }
    val statusColor = when (editedEnabled) {
        null -> MaterialTheme.colorScheme.error
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ListItem(
        headlineContent = { Text(headline, color = statusColor) },
        supportingContent = { Text(supportingText) },
        leadingContent = {
            Icon(Icons.Default.Cached, contentDescription = null, tint = statusColor)
        },
        colors = transparentListItemColors(),
        modifier = Modifier.fillMaxWidth().testTag(SettingsUiTags.CACHE_STATUS),
    )
}

@Composable
private fun CachePolicyFields(
    sizeGiB: String,
    onSizeGiBChange: (String) -> Unit,
    ageDays: String,
    onAgeDaysChange: (String) -> Unit,
    entries: String,
    onEntriesChange: (String) -> Unit,
    validation: CacheValidation,
    enabled: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fieldContent: @Composable (Modifier) -> Unit = { fieldModifier ->
            CachePolicyField(
                value = sizeGiB,
                onValueChange = onSizeGiBChange,
                label = "缓存容量",
                suffix = "GB",
                helperText = validation.sizeError ?: "0 GB 会停用缓存写入",
                isError = validation.sizeError != null,
                keyboardType = KeyboardType.Decimal,
                tag = SettingsUiTags.CACHE_SIZE,
                enabled = enabled,
                modifier = fieldModifier,
            )
            CachePolicyField(
                value = ageDays,
                onValueChange = onAgeDaysChange,
                label = "保留时间",
                suffix = "天",
                helperText = validation.ageError ?: "0 天会停用缓存写入",
                isError = validation.ageError != null,
                keyboardType = KeyboardType.Decimal,
                tag = SettingsUiTags.CACHE_AGE,
                enabled = enabled,
                modifier = fieldModifier,
            )
            CachePolicyField(
                value = entries,
                onValueChange = onEntriesChange,
                label = "文件数量",
                suffix = "个",
                helperText = validation.entriesError ?: "0 个会停用缓存写入",
                isError = validation.entriesError != null,
                keyboardType = KeyboardType.Number,
                tag = SettingsUiTags.CACHE_ENTRIES,
                enabled = enabled,
                modifier = fieldModifier,
            )
        }

        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                fieldContent(Modifier.weight(1f))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                fieldContent(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CachePolicyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suffix: String,
    helperText: String,
    isError: Boolean,
    keyboardType: KeyboardType,
    tag: String,
    enabled: Boolean,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(suffix) },
        supportingText = { Text(helperText) },
        isError = isError,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier
            .testTag(tag)
            .semantics {
                if (isError) {
                    error(helperText)
                    liveRegion = LiveRegionMode.Polite
                }
            },
    )
}

@Composable
private fun transparentListItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

private data class CacheValidation(
    val sizeBytes: Long?,
    val ageMillis: Long?,
    val entries: Int?,
    val sizeError: String?,
    val ageError: String?,
    val entriesError: String?,
) {
    val isValid: Boolean get() = sizeError == null && ageError == null && entriesError == null

    val cacheEnabled: Boolean?
        get() = if (!isValid) {
            null
        } else {
            requireNotNull(sizeBytes) > 0L &&
                requireNotNull(ageMillis) > 0L &&
                requireNotNull(entries) > 0
        }

    fun toPolicy(): CachePolicy? {
        if (!isValid) return null
        return CachePolicy(
            maxBytes = sizeBytes ?: return null,
            maxAgeMillis = ageMillis ?: return null,
            maxEntries = entries ?: return null,
        )
    }
}

private val CachePolicy.cacheEnabled: Boolean
    get() = maxBytes > 0L && maxAgeMillis > 0L && maxEntries > 0

private fun validateCachePolicy(
    sizeGiB: String,
    ageDays: String,
    entries: String,
): CacheValidation {
    val sizeBytes = scaledLong(sizeGiB, BYTES_PER_GIB)
    val ageMillis = scaledLong(ageDays, MILLIS_PER_DAY)
    val parsedEntries = entries.toLongOrNull()
        ?.takeIf { it in 0..Int.MAX_VALUE }
        ?.toInt()

    return CacheValidation(
        sizeBytes = sizeBytes,
        ageMillis = ageMillis,
        entries = parsedEntries,
        sizeError = when {
            sizeBytes != null -> null
            sizeGiB.isBlank() -> "请输入缓存容量"
            else -> "缓存容量必须是有效的非负数"
        },
        ageError = when {
            ageMillis != null -> null
            ageDays.isBlank() -> "请输入保留天数"
            else -> "保留时间必须是有效的非负数"
        },
        entriesError = when {
            parsedEntries != null -> null
            entries.isBlank() -> "请输入文件数上限"
            else -> "文件数必须在 0 至 ${Int.MAX_VALUE} 之间"
        },
    )
}

private fun scaledLong(value: String, scale: Long): Long? {
    val number = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
    if (number > Long.MAX_VALUE.toDouble() / scale) return null
    return (number * scale).toLong()
}

private fun sanitizeDecimal(value: String): String {
    val normalized = value.replace(',', '.')
    val firstDot = normalized.indexOf('.')
    return normalized.filterIndexed { index, char ->
        char.isDigit() || (char == '.' && index == firstDot)
    }.take(14)
}

private fun formatDecimal(value: Double): String = DecimalFormat("0.##").format(value).replace(',', '.')

private fun actionFailureMessage(action: String, error: Throwable): String {
    val detail = error.message?.trim()?.takeIf(String::isNotEmpty)
    return if (detail == null) "$action，请稍后重试" else "$action：$detail"
}

@Preview(name = "Settings phone", showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun SettingsPhonePreview() {
    MaterialTheme {
        SettingsPageContent(
            serverBaseUrl = "https://openlist.example.com",
            username = "xiaobo",
            darkTheme = null,
            dynamicColor = false,
            dynamicColorAvailable = false,
            cachePolicy = CachePolicy(),
            operationBusy = false,
            onAccountsRequested = {},
            onAppearanceChanged = { _, _ -> },
            onCachePolicySave = { _ -> },
            onClearCacheRequested = {},
            onLogoutRequested = {},
        )
    }
}

@Preview(name = "Settings tablet", showBackground = true, widthDp = 840, heightDp = 1100)
@Composable
private fun SettingsTabletPreview() {
    MaterialTheme {
        SettingsPageContent(
            serverBaseUrl = "https://openlist.example.com",
            username = "xiaobo",
            darkTheme = false,
            dynamicColor = true,
            dynamicColorAvailable = true,
            cachePolicy = CachePolicy(),
            operationBusy = false,
            onAccountsRequested = {},
            onAppearanceChanged = { _, _ -> },
            onCachePolicySave = { _ -> },
            onClearCacheRequested = {},
            onLogoutRequested = {},
        )
    }
}
