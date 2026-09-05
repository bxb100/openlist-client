package org.openlist.mobile.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.openlist.mobile.ui.LoginEndpointDraft
import org.openlist.mobile.ui.LoginProtocol
import org.openlist.mobile.ui.LoginUiTags

/** Shared server address and progressive options for login, new connections, and editing. */
@Composable
internal fun ConnectionEndpointFields(
    endpoint: LoginEndpointDraft,
    onHostChange: (String) -> Unit,
    onEndpointChange: (LoginEndpointDraft) -> Unit,
    enabled: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val resolvedAddress = runCatching { endpoint.baseUrl() }.getOrNull()
    val unfocusedAddress = if (compact) resolvedAddress ?: endpoint.host else endpoint.host
    var addressInput by rememberSaveable { mutableStateOf(unfocusedAddress) }
    var addressFocused by remember { mutableStateOf(false) }
    // Keep the editable full URL intact while typing. The endpoint model can normalize it for
    // validation without replacing the text/cursor after each character of a proxy path.
    LaunchedEffect(unfocusedAddress, addressFocused) {
        if (!addressFocused) addressInput = unfocusedAddress
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = addressInput,
            onValueChange = {
                addressInput = it
                onHostChange(it)
            },
            enabled = enabled,
            label = { Text("服务器地址") },
            placeholder = { Text(if (compact) "https://your-server.com" else "域名、IP 或完整网址") },
            supportingText = if (compact && error == null) null else {
                { Text(error ?: "支持粘贴完整网址，自动保留协议、端口与路径") }
            },
            shape = if (compact) MaterialTheme.shapes.medium else OutlinedTextFieldDefaults.shape,
            colors = if (compact) OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ) else OutlinedTextFieldDefaults.colors(),
            isError = error != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().onFocusChanged { addressFocused = it.isFocused }
                .testTag(LoginUiTags.HOST),
        )
        if (compact) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    Text(
                        "域名、IP 或完整网址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { showOptions = true },
                    enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(LoginUiTags.SETTINGS),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("连接选项")
                }
            }
        } else {
            TextButton(
                onClick = { showOptions = true },
                enabled = enabled,
                modifier = Modifier.testTag(LoginUiTags.SETTINGS),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("连接选项 · ${endpoint.protocol.scheme.uppercase()}")
            }
            if (resolvedAddress != null) {
                Text(
                    text = resolvedAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
    if (showOptions) {
        ConnectionOptionsDialog(
            endpoint = endpoint,
            onDismiss = { showOptions = false },
            onSave = {
                onEndpointChange(it)
                if (compact) addressInput = runCatching { it.baseUrl() }.getOrDefault(it.host)
                showOptions = false
            },
        )
    }
}

@Composable
internal fun ConnectionOptionsDialog(
    endpoint: LoginEndpointDraft,
    onDismiss: () -> Unit,
    onSave: (LoginEndpointDraft) -> Unit,
) {
    var protocol by remember(endpoint) { mutableStateOf(endpoint.protocol) }
    var port by remember(endpoint) { mutableStateOf(endpoint.port) }
    var basePath by remember(endpoint) { mutableStateOf(endpoint.basePath) }
    val candidate = endpoint.copy(protocol = protocol, port = port, basePath = basePath)
    val error = runCatching { candidate.copy(host = "localhost").baseUrl() }.exceptionOrNull()?.message
    AlertDialog(
        modifier = Modifier.testTag(LoginUiTags.SETTINGS_DIALOG),
        onDismissRequest = onDismiss,
        title = { Text("连接选项") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "新连接默认使用 HTTP 和 5244 端口；完整网址会使用网址中的配置。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoginProtocol.entries.forEach { option ->
                        FilterChip(
                            selected = protocol == option,
                            onClick = { protocol = option },
                            label = { Text(option.scheme.uppercase()) },
                        )
                    }
                }
                Text(
                    if (protocol == LoginProtocol.HTTP) "HTTP 连接不会加密登录凭据。" else "HTTPS 加密连接需要服务器已配置证书。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    supportingText = { Text("留空使用${if (protocol == LoginProtocol.HTTPS) " 443" else " 80"} 端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(LoginUiTags.SETTINGS_PORT),
                )
                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text("服务器路径（可选）") },
                    placeholder = { Text("/openlist") },
                    supportingText = { Text("用于部署在子路径下的反向代理") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag(LoginUiTags.SETTINGS_PATH),
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(candidate) }, enabled = error == null) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
