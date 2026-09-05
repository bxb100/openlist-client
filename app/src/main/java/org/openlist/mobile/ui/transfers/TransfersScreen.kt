package org.openlist.mobile.ui.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.openlist.mobile.data.download.DownloadFailureCode
import org.openlist.mobile.ui.designsystem.OpenListEmptyState
import org.openlist.mobile.ui.designsystem.OpenListErrorState
import org.openlist.mobile.ui.designsystem.OpenListSectionHeader
import org.openlist.mobile.ui.designsystem.OpenListLayout
import org.openlist.mobile.ui.designsystem.OpenListSpacing
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Continuous task list; progress, cancellation and recovery remain available without notifications. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    state: TransferCenterState,
    onCancel: (TransferEntry) -> Unit,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier,
    cancelling: Set<UUID> = emptySet(),
    snackbarHost: @Composable () -> Unit = {},
) {
    val active = remember(state.entries) { state.entries.filter { it.status.isActive } }
    val finished = remember(state.entries) { state.entries.filterNot { it.status.isActive } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val contentModifier = Modifier.widthIn(max = OpenListLayout.contentMaxWidth).fillMaxWidth()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("传输", style = MaterialTheme.typography.headlineSmall) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = snackbarHost,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(contentPadding),
            contentPadding = PaddingValues(
                horizontal = OpenListLayout.pagePadding,
                vertical = OpenListSpacing.medium,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                state.isLoading -> item(key = "loading") {
                    Column(
                        modifier = contentModifier.fillParentMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(
                            OpenListSpacing.large,
                            Alignment.CenterVertically,
                        ),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Text("正在读取传输任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.loadFailed -> item(key = "load-error") {
                    OpenListErrorState(
                        title = "暂时无法读取任务",
                        description = "正在重新连接任务状态。你可以稍后返回查看。",
                        modifier = contentModifier.fillParentMaxHeight(),
                    )
                }
                state.entries.isEmpty() -> item(key = "empty") {
                    OpenListEmptyState(
                        icon = Icons.Default.SyncAlt,
                        title = "还没有传输任务",
                        description = "在文件页上传或下载文件后，可在这里查看当前会话的进度和结果。",
                        modifier = contentModifier.fillParentMaxHeight(),
                        action = {
                            FilledTonalButton(onClick = onBrowseFiles) {
                                Text("浏览文件")
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.padding(start = OpenListSpacing.small),
                                )
                            }
                        },
                    )
                }
                else -> item(key = "session-scope") {
                    Text(
                        text = "当前会话的上传与下载",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = contentModifier.padding(bottom = OpenListSpacing.small),
                    )
                }
            }
            if (active.isNotEmpty()) {
                item(key = "active-heading") {
                    OpenListSectionHeader(
                        title = "进行中 · ${active.size}",
                        modifier = contentModifier,
                    )
                }
                items(active, key = { it.id }) { entry ->
                    TransferRow(
                        entry = entry,
                        isCancelling = entry.id in cancelling,
                        onCancel = { onCancel(entry) },
                        onBrowseFiles = onBrowseFiles,
                        modifier = contentModifier,
                    )
                    HorizontalDivider(
                        modifier = contentModifier,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
            if (finished.isNotEmpty()) {
                item(key = "finished-heading") {
                    OpenListSectionHeader(
                        title = "最近结束 · ${finished.size}",
                        description = "近期结果可能由系统清理。",
                        modifier = contentModifier.padding(top = if (active.isEmpty()) 0.dp else OpenListSpacing.xl),
                    )
                }
                items(finished, key = { it.id }) { entry ->
                    TransferRow(
                        entry = entry,
                        isCancelling = false,
                        onCancel = {},
                        onBrowseFiles = onBrowseFiles,
                        modifier = contentModifier,
                    )
                    HorizontalDivider(
                        modifier = contentModifier,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferRow(
    entry: TransferEntry,
    isCancelling: Boolean,
    onCancel: () -> Unit,
    onBrowseFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondaryColor = if (entry.status == TransferStatus.FAILED) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OpenListSpacing.large),
        horizontalArrangement = Arrangement.spacedBy(OpenListSpacing.medium),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (entry.direction == TransferDirection.UPLOAD) {
                Icons.Default.CloudUpload
            } else {
                Icons.Default.CloudDownload
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = OpenListSpacing.xs).size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(OpenListSpacing.small),
        ) {
            Text(text = entry.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = entry.statusLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
            )
            entry.byteLabel()?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.status == TransferStatus.RUNNING) {
                val progress = entry.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (entry.status == TransferStatus.FAILED || entry.status == TransferStatus.CANCELLED) {
                Text(
                    text = entry.recoveryMessage(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.createdAtMillis?.let { createdAt ->
                val date = remember(createdAt) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(createdAt))
                }
                Text(
                    text = "发起于 $date",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.status.isActive) {
                TextButton(
                    onClick = onCancel,
                    enabled = !isCancelling,
                    modifier = Modifier.semantics {
                        contentDescription = "取消${entry.name}的${entry.direction.label}"
                    },
                ) {
                    Text(if (isCancelling) "正在取消" else "取消${entry.direction.label}")
                }
            } else if (entry.status == TransferStatus.FAILED) {
                TextButton(onClick = onBrowseFiles) { Text("返回文件页") }
            }
        }
    }
}

private fun TransferEntry.statusLabel(): String = when (status) {
    TransferStatus.RUNNING -> if (progress != null) {
        "正在${direction.label} · ${(requireNotNull(progress) * 100).toInt()}%"
    } else {
        "正在准备${direction.label}"
    }
    TransferStatus.WAITING -> if (isRetrying) {
        "${direction.label}等待重试 · 系统会自动继续"
    } else {
        "${direction.label}等待网络或系统调度"
    }
    TransferStatus.SUCCEEDED -> "${direction.label}完成"
    TransferStatus.FAILED -> when (failureCode) {
        DownloadFailureCode.SESSION_CHANGED -> "下载已停止 · 登录会话已变化"
        DownloadFailureCode.AUTH_REQUIRED -> "下载失败 · 需要登录"
        else -> "${direction.label}失败"
    }
    TransferStatus.CANCELLED -> "${direction.label}已取消"
}

private fun TransferEntry.byteLabel(): String? {
    val transferred = transferredBytes ?: return null
    val total = totalBytes
    return if (total != null && status.isActive) {
        "${formatTransferBytes(transferred)} / ${formatTransferBytes(total)}"
    } else {
        formatTransferBytes(transferred)
    }
}

private fun formatTransferBytes(bytes: Long): String {
    val nonnegativeBytes = bytes.coerceAtLeast(0L)
    if (nonnegativeBytes < 1024) return "$nonnegativeBytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = nonnegativeBytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}
