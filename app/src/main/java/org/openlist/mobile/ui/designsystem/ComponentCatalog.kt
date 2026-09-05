package org.openlist.mobile.ui.designsystem

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.openlist.mobile.ui.theme.OpenListMediaTheme
import org.openlist.mobile.ui.theme.OpenListTheme

/** Real Material controls under the production theme; previews deliberately include long names. */
@Composable
internal fun OpenListComponentCatalog() {
    Surface {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = OpenListLayout.contentMaxWidth)
                    .fillMaxWidth()
                    .padding(OpenListLayout.pagePadding),
                verticalArrangement = Arrangement.spacedBy(OpenListSpacing.small),
            ) {
                Text("文件，井然有序", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "家庭服务器 · xiaobo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OpenListSectionHeader("最近使用", description = "文件名在前，详情紧随其后")
                ListItem(
                    headlineContent = { Text("旅行影像", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("文件夹 · 昨天") },
                    leadingContent = {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingContent = {
                        IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, contentDescription = "旅行影像的更多操作") }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text("周末去山里的一百个理由.pdf", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text("2.4 MB · 9 月 5 日") },
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null)
                    },
                    trailingContent = {
                        IconButton(onClick = {}) { Icon(Icons.Outlined.MoreVert, contentDescription = "PDF 的更多操作") }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
                OpenListSectionHeader("传输", description = "过程清晰，结果可见")
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(OpenListSpacing.large),
                        verticalArrangement = Arrangement.spacedBy(OpenListSpacing.small),
                    ) {
                        Text("山间日出.mp4", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "正在下载 · 64 MB / 100 MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(progress = { 0.64f }, modifier = Modifier.fillMaxWidth())
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(OpenListSpacing.small),
                    verticalArrangement = Arrangement.spacedBy(OpenListSpacing.small),
                ) {
                    Button(onClick = {}) { Text("上传文件") }
                    OutlinedButton(onClick = {}) { Text("新建文件夹") }
                }
                OpenListEmptyState(
                    icon = Icons.Outlined.CloudDone,
                    title = "所有传输已完成",
                    description = "新的上传和下载会显示在这里。",
                )
            }
        }
    }
}

@Preview(name = "OpenList · Light", widthDp = 393, showBackground = true)
@Preview(name = "OpenList · Dark", widthDp = 393, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "OpenList · Large text", widthDp = 320, fontScale = 2f)
@Preview(name = "OpenList · Expanded", widthDp = 840)
@Composable
private fun ComponentCatalogPreview() {
    OpenListTheme { OpenListComponentCatalog() }
}

@Preview(name = "OpenList · Connection error", widthDp = 320, fontScale = 2f)
@Composable
private fun ErrorStatePreview() {
    OpenListTheme {
        Surface {
            OpenListErrorState(
                title = "暂时无法连接",
                description = "检查网络连接后重试。当前路径与文件操作会保留。",
                onRetry = {},
            )
        }
    }
}

@Preview(name = "OpenList · Media", widthDp = 393)
@Composable
private fun MediaPalettePreview() {
    OpenListMediaTheme {
        Surface {
            Column(
                modifier = Modifier.padding(OpenListSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(OpenListSpacing.large),
            ) {
                Text("山间日出", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "旅行影像 · 2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(progress = { 0.4f }, modifier = Modifier.fillMaxWidth())
                FilledIconButton(onClick = {}, modifier = Modifier.align(Alignment.CenterHorizontally).size(64.dp)) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = "播放")
                }
            }
        }
    }
}
