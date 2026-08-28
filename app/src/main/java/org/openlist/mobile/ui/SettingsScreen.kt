package org.openlist.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.openlist.mobile.core.model.CachePolicy
import org.openlist.mobile.data.preferences.SessionStore
import java.text.DecimalFormat

private const val BYTES_PER_GIB = 1_073_741_824L
private const val MILLIS_PER_DAY = 86_400_000L

private enum class ThemeMode(val label: String, val value: Boolean?) {
    System("跟随系统", null),
    Light("浅色", false),
    Dark("深色", true),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    sessionStore: SessionStore,
    operationBusy: Boolean,
    onAccountsRequested: () -> Unit,
    onClearCacheRequested: () -> Unit,
    onLogoutRequested: suspend () -> Unit,
) {
    val settings by sessionStore.settings.collectAsStateWithLifecycle()
    val policy = settings.cachePolicy
    var cacheSizeGiB by rememberSaveable(policy.maxBytes) {
        mutableStateOf(formatDecimal(policy.maxBytes.toDouble() / BYTES_PER_GIB))
    }
    var cacheAgeDays by rememberSaveable(policy.maxAgeMillis) {
        mutableStateOf(formatDecimal(policy.maxAgeMillis.toDouble() / MILLIS_PER_DAY))
    }
    var cacheEntries by rememberSaveable(policy.maxEntries) { mutableStateOf(policy.maxEntries.toString()) }
    var clearConfirmation by rememberSaveable { mutableStateOf(false) }
    var logoutConfirmation by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val cacheValidation = validateCachePolicy(cacheSizeGiB, cacheAgeDays, cacheEntries)

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
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
                    SettingValue("地址", settings.server.baseUrl.ifBlank { "未配置" })
                    HorizontalDivider()
                    SettingValue("用户名", settings.server.username.ifBlank { "—" })
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("管理账户") },
                        supportingContent = { Text("添加、编辑或切换 OpenList 账户") },
                        leadingContent = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                        modifier = Modifier.clickable(
                            enabled = !operationBusy,
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
                    Text(
                        "主题",
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton) {
                                    scope.launch {
                                        sessionStore.setAppearance(settings.dynamicColor, mode.value)
                                    }
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.darkTheme == mode.value,
                                onClick = null,
                            )
                            Text(mode.label, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    ListItem(
                        headlineContent = { Text("动态色彩") },
                        supportingContent = { Text("Android 12 及以上使用设备壁纸配色") },
                        trailingContent = {
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = null,
                            )
                        },
                        modifier = Modifier.toggleable(
                            value = settings.dynamicColor,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                scope.launch {
                                    sessionStore.setAppearance(enabled, settings.darkTheme)
                                }
                            },
                        ),
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
            item {
                SettingsSection(
                    title = "离线缓存",
                    icon = { Icon(Icons.Default.Cached, contentDescription = null) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            "缓存同时受容量、保留时间和文件数限制；达到任一上限时会清理较旧内容。任一限制为 0 会停用缓存写入。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        SettingsInputCard {
                            OutlinedTextField(
                                value = cacheSizeGiB,
                                onValueChange = { cacheSizeGiB = sanitizeDecimal(it) },
                                label = { Text("缓存上限") },
                                suffix = { Text("GB") },
                                supportingText = { Text("当前约 ${formatBytes(policy.maxBytes)}") },
                                isError = cacheValidation.sizeBytes == null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = transparentSettingsTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        SettingsInputCard {
                            OutlinedTextField(
                                value = cacheAgeDays,
                                onValueChange = { cacheAgeDays = sanitizeDecimal(it) },
                                label = { Text("保留时间") },
                                suffix = { Text("天") },
                                isError = cacheValidation.ageMillis == null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                colors = transparentSettingsTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        SettingsInputCard {
                            OutlinedTextField(
                                value = cacheEntries,
                                onValueChange = { cacheEntries = it.filter(Char::isDigit).take(9) },
                                label = { Text("最多缓存文件") },
                                suffix = { Text("个") },
                                isError = cacheValidation.entries == null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                colors = transparentSettingsTextFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (!cacheValidation.isValid) {
                            Text(
                                "请输入有效的非负数；缓存文件数不能超过 ${Int.MAX_VALUE}。",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        ) {
                            OutlinedButton(onClick = { clearConfirmation = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("清空缓存")
                            }
                            Button(
                                onClick = {
                                    val updated = cacheValidation.toPolicy() ?: return@Button
                                    scope.launch {
                                        sessionStore.setCachePolicy(updated)
                                        snackbarHostState.showSnackbar("缓存设置已保存")
                                    }
                                },
                                enabled = cacheValidation.isValid,
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("保存")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
            item {
                OutlinedButton(
                    onClick = { logoutConfirmation = true },
                    enabled = !operationBusy,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
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
            title = { Text("清空离线缓存？") },
            text = { Text("已缓存的媒体和文件会被移除，需要时可再次从服务器下载。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearConfirmation = false
                        onClearCacheRequested()
                        scope.launch { snackbarHostState.showSnackbar("已请求清空缓存") }
                    },
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
            title = { Text("退出当前服务器？") },
            text = { Text("服务器地址和缓存偏好会保留，你可以稍后重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logoutConfirmation = false
                        scope.launch { onLogoutRequested() }
                    },
                    enabled = !operationBusy,
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
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
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
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsInputCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun transparentSettingsTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
)

@Composable
private fun SettingValue(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(value, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

private data class CacheValidation(
    val sizeBytes: Long?,
    val ageMillis: Long?,
    val entries: Int?,
) {
    val isValid: Boolean get() = sizeBytes != null && ageMillis != null && entries != null

    fun toPolicy(): CachePolicy? {
        if (!isValid) return null
        return CachePolicy(
            maxBytes = sizeBytes ?: return null,
            maxAgeMillis = ageMillis ?: return null,
            maxEntries = entries ?: return null,
        )
    }
}

private fun validateCachePolicy(sizeGiB: String, ageDays: String, entries: String): CacheValidation =
    CacheValidation(
        sizeBytes = scaledLong(sizeGiB, BYTES_PER_GIB),
        ageMillis = scaledLong(ageDays, MILLIS_PER_DAY),
        entries = entries.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
    )

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
    }.take(12)
}

private fun formatDecimal(value: Double): String = DecimalFormat("0.##").format(value).replace(',', '.')
