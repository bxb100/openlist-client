package org.openlist.mobile.ui.filter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.core.model.FileVisibilityTarget
import org.openlist.mobile.ui.designsystem.OpenListEmptyState
import org.openlist.mobile.ui.designsystem.OpenListLayout
import org.openlist.mobile.ui.designsystem.OpenListSpacing
import java.util.UUID

@Composable
internal fun FileVisibilityRulesDialog(
    rules: List<FileVisibilityRule>,
    onSave: suspend (List<FileVisibilityRule>) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberFileVisibilityEditorState(rules)
    Dialog(
        onDismissRequest = { if (!state.saving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = !state.saving,
        ),
    ) {
        FileVisibilityRulesEditor(state, onSave, onDismiss)
    }
}

internal data class FileVisibilityRuleDraft(
    val id: String,
    val pattern: String,
    val action: FileVisibilityAction,
    val target: FileVisibilityTarget,
)

@Stable
internal class FileVisibilityEditorState(initialDrafts: List<FileVisibilityRuleDraft>) {
    var drafts by mutableStateOf(initialDrafts)
    var saving by mutableStateOf(false)
    var validationRequested by mutableStateOf(false)
    var saveFailed by mutableStateOf(false)

    fun update(draft: FileVisibilityRuleDraft) {
        drafts = drafts.map { if (it.id == draft.id) draft else it }
        saveFailed = false
    }

    fun move(index: Int, offset: Int) {
        drafts = drafts.toMutableList().apply { add(index + offset, removeAt(index)) }
    }

    companion object {
        val Saver = listSaver<FileVisibilityEditorState, String>(
            save = { state ->
                state.drafts.flatMap { listOf(it.id, it.pattern, it.action.name, it.target.name) }
            },
            restore = { saved ->
                FileVisibilityEditorState(saved.chunked(4).map {
                    FileVisibilityRuleDraft(it[0], it[1], FileVisibilityAction.valueOf(it[2]), FileVisibilityTarget.valueOf(it[3]))
                })
            },
        )
    }
}

@Composable
internal fun rememberFileVisibilityEditorState(rules: List<FileVisibilityRule>): FileVisibilityEditorState =
    rememberSaveable(saver = FileVisibilityEditorState.Saver) {
        FileVisibilityEditorState(rules.map { FileVisibilityRuleDraft(it.id, it.pattern, it.action, it.target) })
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileVisibilityRulesEditor(
    state: FileVisibilityEditorState,
    onSave: suspend (List<FileVisibilityRule>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var showSyntax by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("文件显示", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !state.saving) {
                        Icon(Icons.Default.Close, contentDescription = "取消编辑")
                    }
                },
                actions = {
                    TextButton(
                        enabled = !state.saving,
                        modifier = Modifier.testTag("file_rules_save"),
                        onClick = {
                            if (state.saving) return@TextButton
                            state.validationRequested = true
                            val invalidIndex = state.drafts.indexOfFirst { it.pattern.isEmpty() }
                            if (invalidIndex >= 0) {
                                scope.launch { listState.animateScrollToItem(invalidIndex + 1) }
                                return@TextButton
                            }
                            val updated = state.drafts.map {
                                FileVisibilityRule(it.id, it.pattern, it.action, it.target)
                            }
                            state.saving = true
                            state.saveFailed = false
                            scope.launch {
                                try {
                                    onSave(updated)
                                    onDismiss()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    state.saveFailed = true
                                } finally {
                                    state.saving = false
                                }
                            }
                        },
                    ) {
                        if (state.saving) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp).semantics { contentDescription = "正在保存规则" },
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("保存")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.saveFailed) {
                Text(
                    "未能保存规则，请重试。你的编辑已保留。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(OpenListSpacing.large)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag("file_rules_list"),
                state = listState,
                contentPadding = PaddingValues(horizontal = OpenListLayout.pagePadding, vertical = OpenListSpacing.medium),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item {
                    Column(Modifier.widthIn(max = OpenListLayout.contentMaxWidth).fillMaxWidth()) {
                        Text(
                            "仅改变本客户端的文件列表，不会删除服务器文件。默认显示全部，后面的规则优先；文件夹规则也作用于其内容。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showSyntax = !showSyntax }) {
                            Text(if (showSyntax) "收起通配符用法" else "通配符用法")
                        }
                        if (showSyntax) {
                            Text(
                                "按完整名称匹配，不区分大小写。\n* 匹配任意字符，? 匹配单个字符，\\ 用于转义。\n例如：*.tmp 匹配临时文件，.* 匹配以点开头的名称。只显示 JPG 文件：先对文件隐藏 *，再对文件显示 *.jpg。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(OpenListSpacing.medium))
                        }
                        if (state.drafts.isEmpty()) {
                            OpenListEmptyState(
                                icon = Icons.Outlined.FilterAlt,
                                title = "显示所有文件",
                                description = "添加规则，隐藏不需要的文件或文件夹，也可以为特定名称设置显示例外。",
                            )
                        }
                    }
                }
                itemsIndexed(state.drafts, key = { _, draft -> draft.id }) { index, draft ->
                    RuleEditor(
                        draft = draft,
                        index = index,
                        count = state.drafts.size,
                        enabled = !state.saving,
                        invalid = state.validationRequested && draft.pattern.isEmpty(),
                        onChange = state::update,
                        onMove = { offset -> state.move(index, offset) },
                        onDelete = { state.drafts = state.drafts.filterNot { it.id == draft.id } },
                    )
                }
                item {
                    TextButton(
                        enabled = !state.saving,
                        onClick = {
                            state.drafts = state.drafts + FileVisibilityRuleDraft(
                                UUID.randomUUID().toString(), "", FileVisibilityAction.Hide, FileVisibilityTarget.All,
                            )
                            scope.launch { listState.animateScrollToItem(state.drafts.size) }
                        },
                        modifier = Modifier.widthIn(max = OpenListLayout.contentMaxWidth).fillMaxWidth()
                            .padding(vertical = OpenListSpacing.small).testTag("file_rules_add"),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("添加规则", Modifier.padding(start = OpenListSpacing.small))
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleEditor(
    draft: FileVisibilityRuleDraft,
    index: Int,
    count: Int,
    enabled: Boolean,
    invalid: Boolean,
    onChange: (FileVisibilityRuleDraft) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(max = OpenListLayout.contentMaxWidth).fillMaxWidth()
            .padding(bottom = OpenListSpacing.large),
        verticalArrangement = Arrangement.spacedBy(OpenListSpacing.small),
    ) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "规则 ${index + 1}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            IconButton(onClick = { onMove(-1) }, enabled = enabled && index > 0) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "上移规则 ${index + 1}")
            }
            IconButton(onClick = { onMove(1) }, enabled = enabled && index < count - 1) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "下移规则 ${index + 1}")
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除规则 ${index + 1}")
            }
        }
        OutlinedTextField(
            value = draft.pattern,
            onValueChange = { onChange(draft.copy(pattern = it)) },
            label = { Text("名称通配符") },
            placeholder = { Text("例如 *.tmp") },
            singleLine = true,
            enabled = enabled,
            isError = invalid,
            supportingText = if (invalid) {{ Text("请输入名称通配符，或删除此规则") }} else null,
            modifier = Modifier.fillMaxWidth().testTag("file_rule_pattern_${draft.id}"),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(OpenListSpacing.small)) {
            FileVisibilityAction.entries.forEach { action ->
                FilterChip(
                    selected = draft.action == action,
                    onClick = { onChange(draft.copy(action = action)) },
                    enabled = enabled,
                    label = { Text(if (action == FileVisibilityAction.Hide) "隐藏" else "显示") },
                )
            }
        }
        Text("应用到", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(OpenListSpacing.small)) {
            listOf(FileVisibilityTarget.All, FileVisibilityTarget.Files, FileVisibilityTarget.Directories).forEach { target ->
                FilterChip(
                    selected = draft.target == target,
                    onClick = { onChange(draft.copy(target = target)) },
                    enabled = enabled,
                    label = {
                        Text(when (target) {
                            FileVisibilityTarget.All -> "全部"
                            FileVisibilityTarget.Files -> "文件"
                            FileVisibilityTarget.Directories -> "文件夹"
                        })
                    },
                )
            }
        }
    }
}
