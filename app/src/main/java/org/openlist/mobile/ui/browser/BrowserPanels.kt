package org.openlist.mobile.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openlist.mobile.core.model.MediaKind
import org.openlist.mobile.media.MediaTypeDetector
import org.openlist.mobile.ui.BrowserEntry
import org.openlist.mobile.ui.formatBytes
import org.openlist.mobile.ui.iconFor
import org.openlist.mobile.ui.visibleStorageProvider
import org.openlist.mobile.ui.designsystem.OpenListErrorState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer

/** One action surface shared by rows, grid items, search, and the detail pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileActionSheet(
    entry: BrowserEntry,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        FileActionContent(entry, onOpen, onDetails, onDownload, onRename, onDelete)
    }
}

/** The same action content is rendered in the modal and in component previews. */
@Composable
internal fun FileActionContent(
    entry: BrowserEntry,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            entry.item.name,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            entry.parent,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val kind = MediaTypeDetector.kind(entry.item)
        if (kind !in listOf(MediaKind.TEXT, MediaKind.OTHER)) {
            FileActionRow(
                when (kind) {
                    MediaKind.DIRECTORY -> "打开文件夹"
                    MediaKind.IMAGE -> "查看图片"
                    else -> "播放"
                },
                Icons.AutoMirrored.Filled.OpenInNew,
                onOpen,
            )
        }
        if (!entry.item.isDirectory) FileActionRow("下载到本地", Icons.Default.Download, onDownload)
        FileActionRow("详细信息", Icons.Default.Info, onDetails)
        FileActionRow("重命名", Icons.Default.DriveFileRenameOutline, onRename)
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        FileActionRow("删除", Icons.Default.Delete, onDelete, destructive = true)
    }
}

@Composable
private fun FileActionRow(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        modifier = Modifier.clickable(onClickLabel = label, onClick = onClick),
    )
}

@Composable
internal fun FileMutationDialog(
    state: BrowserActionState,
    onNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val deleting = state.kind == BrowserActionKind.Delete
    val name = state.name.trim()
    val valid = name.isNotEmpty() && name != "." && name != ".." &&
        '/' !in name && '\\' !in name && name.none(Char::isISOControl)
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        icon = {
            Icon(
                if (deleting) Icons.Default.Delete else Icons.Default.DriveFileRenameOutline,
                contentDescription = null,
            )
        },
        title = { Text(if (deleting) "删除“${state.entry.item.name}”？" else "重命名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (deleting) {
                    Text(
                        if (state.entry.item.isDirectory) "文件夹及其中内容将从服务器删除，无法在应用内撤销。"
                        else "文件将从服务器删除，无法在应用内撤销。",
                    )
                } else {
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = onNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                        label = { Text("新名称") },
                        isError = !valid || state.error != null,
                        supportingText = { Text(if (valid) "位置：${state.entry.parent}" else "名称不能为空或包含路径分隔符") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (valid && name != state.entry.item.name && !state.busy) onSubmit()
                        }),
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = !state.busy && (deleting || valid && name != state.entry.item.name),
                colors = if (deleting) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors(),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (deleting) "删除" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("取消") } },
    )
}

@Composable
internal fun FileDetailsPane(
    state: BrowserDetailsState,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                "详细信息",
                modifier = Modifier.weight(1f).padding(top = 12.dp).semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "${state.entry.item.name} 的更多操作") }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭详细信息") }
        }
        Icon(iconFor(MediaTypeDetector.kind(state.entry.item)), null, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
        SelectionContainer {
            Text(state.entry.item.name, style = MaterialTheme.typography.headlineSmall)
        }
        HorizontalDivider()
        DetailValue("位置", state.entry.parent)
        DetailValue("名称", state.entry.item.name)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error != null) {
            OpenListErrorState("无法读取详细信息", state.error, onRetry = onRetry)
        }
        state.details?.let { value ->
            DetailValue("大小", if (value.isDirectory) "文件夹" else formatBytes(value.size))
            if (value.modified.isNotBlank()) DetailValue("修改时间", value.modified)
            if (value.created.isNotBlank()) DetailValue("创建时间", value.created)
            visibleStorageProvider(value.provider)?.let { DetailValue("存储位置", it) }
            value.hashes.orEmpty().forEach { (algorithm, hash) -> DetailValue(algorithm.uppercase(), hash) }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}
