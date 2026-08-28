package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import org.openlist.mobile.worker.UploadWorker
import java.util.UUID

/**
 * Notification-independent upload status. Android may hide foreground notifications when the
 * user denies notification permission, but an open app must still expose progress, failure and a
 * cancellation affordance.
 */
@Composable
fun UploadStatusHost(
    workInfos: List<WorkInfo>,
    onCancel: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissedFailures by remember { mutableStateOf(emptySet<UUID>()) }
    val active = remember(workInfos) {
        workInfos
            .filter { it.state == WorkInfo.State.RUNNING || !it.state.isFinished }
            .sortedByDescending { it.state == WorkInfo.State.RUNNING }
    }
    val failure = remember(workInfos, dismissedFailures) {
        workInfos.firstOrNull {
            it.state == WorkInfo.State.FAILED && it.id !in dismissedFailures
        }
    }
    if (active.isEmpty() && failure == null) return

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null)
                    Text(
                        text = if (active.size == 1) "上传任务" else "${active.size} 个上传任务",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                active.take(MAX_VISIBLE_UPLOADS).forEach { info ->
                    UploadWorkRow(info = info, onCancel = { onCancel(info.id) })
                }
                if (active.size > MAX_VISIBLE_UPLOADS) {
                    Text(
                        text = "另有 ${active.size - MAX_VISIBLE_UPLOADS} 个任务正在队列中",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            failure?.let { info ->
                UploadFailureRow(
                    info = info,
                    onDismiss = { dismissedFailures = dismissedFailures + info.id },
                )
            }
        }
    }
}

@Composable
private fun UploadWorkRow(info: WorkInfo, onCancel: () -> Unit) {
    val remoteName = uploadRemoteName(info)
    val progress = uploadProgress(
        uploadedBytes = info.progress.getLong(UploadWorker.KEY_UPLOADED_BYTES, 0L),
        totalBytes = info.progress.getLong(UploadWorker.KEY_TOTAL_BYTES, -1L)
            .takeIf { it > 0L },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = remoteName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = when {
                        info.state == WorkInfo.State.RUNNING && progress != null ->
                            "正在上传 · ${(progress * 100).toInt()}%"
                        info.state == WorkInfo.State.RUNNING -> "正在准备上传"
                        else -> "等待网络或系统调度"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onCancel) { Text("取消") }
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun UploadFailureRow(info: WorkInfo, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "${uploadRemoteName(info)} 上传失败",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = info.outputData.getString(UploadWorker.KEY_ERROR)
                    ?.takeIf(String::isNotBlank)
                    ?: "请重新选择文件后再试",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}

private fun uploadRemoteName(info: WorkInfo): String =
    info.progress.getString(UploadWorker.KEY_REMOTE_NAME)
        ?.takeIf(String::isNotBlank)
        ?: info.outputData.getString(UploadWorker.KEY_REMOTE_NAME)?.takeIf(String::isNotBlank)
        ?: "文件"

internal fun uploadProgress(uploadedBytes: Long, totalBytes: Long?): Float? {
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return (uploadedBytes.coerceIn(0L, total).toDouble() / total.toDouble()).toFloat()
}

private const val MAX_VISIBLE_UPLOADS = 3
