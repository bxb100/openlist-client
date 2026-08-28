package org.openlist.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openlist.mobile.AppContainer
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.core.network.LocalNetworkAddress
import org.openlist.mobile.core.network.LocalNetworkPermissionController
import org.openlist.mobile.data.repository.SecondFactorRequiredException

internal enum class LoginStep { Credentials, TwoFactor }

internal object LoginUiTags {
    const val HOST = "login_host"
    const val USERNAME = "login_username"
    const val PASSWORD = "login_password"
    const val SUBMIT = "login_submit"
    const val SWITCH_ACCOUNT = "login_switch_account"
    const val SETTINGS = "login_settings"
    const val SETTINGS_DIALOG = "login_settings_dialog"
    const val SETTINGS_PORT = "login_settings_port"
    const val SETTINGS_PATH = "login_settings_path"
    const val OTP = "login_otp"
    const val OTP_SUBMIT = "login_otp_submit"
    const val OTP_BACK = "login_otp_back"
}

/** The password is intentionally memory-only and redacted if this object is ever logged. */
private class PendingLogin(
    val profile: ServerProfile,
    val password: String,
) {
    override fun toString(): String = "PendingLogin(profile=$profile, password=<redacted>)"
}

@Composable
internal fun LoginScreen(
    container: AppContainer,
    authenticating: Boolean,
    onManageAccounts: () -> Unit,
) {
    val saved by container.sessionStore.settings.collectAsStateWithLifecycle()
    val initialEndpoint = remember { LoginEndpointDraft.fromBaseUrl(saved.server.baseUrl) }
    var host by rememberSaveable { mutableStateOf(initialEndpoint.host) }
    var protocolName by rememberSaveable { mutableStateOf(initialEndpoint.protocol.name) }
    var port by rememberSaveable { mutableStateOf(initialEndpoint.port) }
    var basePath by rememberSaveable { mutableStateOf(initialEndpoint.basePath) }
    var username by rememberSaveable { mutableStateOf(saved.server.username) }
    // Credentials and the challenge must never enter Android's saved-instance-state Bundle.
    var password by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var pendingLogin by remember { mutableStateOf<PendingLogin?>(null) }
    var step by remember { mutableStateOf(LoginStep.Credentials) }
    var showPassword by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permissionController = LocalContext.current as? LocalNetworkPermissionController
    val persistedAuthenticationError by container.authenticationError.collectAsStateWithLifecycle()
    val loading = submitting || authenticating
    val endpoint = LoginEndpointDraft(
        host = host,
        protocol = runCatching { LoginProtocol.valueOf(protocolName) }
            .getOrDefault(LoginProtocol.HTTP),
        port = port,
        basePath = basePath,
    )
    val proposedProfile = runCatching {
        endpoint.serverProfile(username).also { it.normalizedBaseUrl() }
    }.getOrNull()
    val endpointError = runCatching {
        endpoint.serverProfile(username).normalizedBaseUrl()
    }.exceptionOrNull()?.message
    val displayedError = error ?: persistedAuthenticationError

    LaunchedEffect(saved.server.baseUrl, saved.server.username) {
        // beginLogin() persists/activates the target before the request. Do not let that expected
        // state change erase the in-memory password needed for a subsequent 2FA submission.
        if (pendingLogin == null && !submitting) {
            val loadedEndpoint = LoginEndpointDraft.fromBaseUrl(saved.server.baseUrl)
            host = loadedEndpoint.host
            protocolName = loadedEndpoint.protocol.name
            port = loadedEndpoint.port
            basePath = loadedEndpoint.basePath
            username = saved.server.username
            password = ""
            otp = ""
            showPassword = false
            step = LoginStep.Credentials
            error = null
            container.clearAuthenticationError()
        }
    }

    fun clearLoginError() {
        error = null
        container.clearAuthenticationError()
    }

    fun returnToCredentials() {
        if (loading) return
        step = LoginStep.Credentials
        otp = ""
        pendingLogin = null
        showPassword = false
        clearLoginError()
    }

    fun performLogin(target: PendingLogin, otpCode: String) {
        if (loading) return
        pendingLogin = target
        scope.launch {
            submitting = true
            clearLoginError()
            try {
                container.login(target.profile, target.password, otpCode)
                password = ""
                otp = ""
                pendingLogin = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecondFactorRequiredException) {
                container.clearAuthenticationError()
                if (step == LoginStep.Credentials) {
                    step = LoginStep.TwoFactor
                    otp = ""
                    error = null
                } else {
                    error = "验证码无效或已过期，请重试"
                }
            } catch (throwable: Throwable) {
                error = throwable.message ?: "无法登录，请检查服务器和凭据"
                if (step == LoginStep.Credentials) pendingLogin = null
            } finally {
                submitting = false
            }
        }
    }

    fun requestLogin(target: PendingLogin, otpCode: String) {
        val needsPermission = LocalNetworkAddress.isLikelyLocal(target.profile.baseUrl) &&
            permissionController?.hasLocalNetworkPermission() == false
        if (needsPermission) {
            permissionController.requestLocalNetworkPermission { granted ->
                if (granted) {
                    performLogin(target, otpCode)
                } else {
                    pendingLogin = null
                    error = "需要“本地网络”权限才能连接这个局域网地址。你可以在系统设置中重新授权。"
                }
            }
        } else {
            performLogin(target, otpCode)
        }
    }

    fun submitCredentials() {
        val profile = proposedProfile ?: return
        if (username.isBlank() || password.isBlank()) return
        requestLogin(PendingLogin(profile, password), "")
    }

    fun submitSecondFactor() {
        val target = pendingLogin
        if (target == null) {
            returnToCredentials()
            return
        }
        if (otp.length !in 6..8) return
        requestLogin(target, otp)
    }

    BackHandler(enabled = step == LoginStep.TwoFactor) { returnToCredentials() }

    LoginPageContent(
        step = step,
        endpoint = endpoint,
        username = username,
        password = password,
        otp = otp,
        showPassword = showPassword,
        loading = loading,
        endpointError = endpointError?.takeIf { host.isNotBlank() },
        errorMessage = displayedError,
        onEndpointChange = {
            host = it.host
            protocolName = it.protocol.name
            port = it.port
            basePath = it.basePath
            pendingLogin = null
            otp = ""
            step = LoginStep.Credentials
            clearLoginError()
        },
        onHostChange = {
            host = it
            pendingLogin = null
            clearLoginError()
        },
        onUsernameChange = {
            username = it
            pendingLogin = null
            clearLoginError()
        },
        onPasswordChange = {
            password = it
            pendingLogin = null
            clearLoginError()
        },
        onOtpChange = {
            otp = it.filter(Char::isDigit).take(8)
            clearLoginError()
        },
        onTogglePassword = { showPassword = !showPassword },
        onSubmitCredentials = ::submitCredentials,
        onSubmitOtp = ::submitSecondFactor,
        onBackToCredentials = ::returnToCredentials,
        onSwitchAccount = {
            pendingLogin = null
            password = ""
            otp = ""
            showPassword = false
            onManageAccounts()
        },
    )
}

@Composable
internal fun LoginPageContent(
    step: LoginStep,
    endpoint: LoginEndpointDraft,
    username: String,
    password: String,
    otp: String,
    showPassword: Boolean,
    loading: Boolean,
    endpointError: String?,
    errorMessage: String?,
    onEndpointChange: (LoginEndpointDraft) -> Unit,
    onHostChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onOtpChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmitCredentials: () -> Unit,
    onSubmitOtp: () -> Unit,
    onBackToCredentials: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    var showConnectionSettings by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 400.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "OpenList",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (step == LoginStep.Credentials) {
                        "连接你的自托管文件空间"
                    } else {
                        "完成账号安全验证"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(36.dp))
                when (step) {
                    LoginStep.Credentials -> CredentialsLoginContent(
                        endpoint = endpoint,
                        username = username,
                        password = password,
                        showPassword = showPassword,
                        loading = loading,
                        endpointError = endpointError,
                        errorMessage = errorMessage,
                        onHostChange = onHostChange,
                        onUsernameChange = onUsernameChange,
                        onPasswordChange = onPasswordChange,
                        onTogglePassword = onTogglePassword,
                        onSubmit = onSubmitCredentials,
                        onSwitchAccount = onSwitchAccount,
                    )
                    LoginStep.TwoFactor -> TwoFactorLoginContent(
                        otp = otp,
                        loading = loading,
                        errorMessage = errorMessage,
                        onOtpChange = onOtpChange,
                        onSubmit = onSubmitOtp,
                        onBack = onBackToCredentials,
                    )
                }
            }
        }

        // Keep top actions after the full-screen scroll container so real pointer input reaches
        // them. Semantics-only tests do not detect a scroll layer intercepting these taps.
        if (step == LoginStep.Credentials) {
            TextButton(
                onClick = { showConnectionSettings = true },
                enabled = !loading,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(LoginUiTags.SETTINGS),
            ) {
                Text("连接设置")
            }
        } else {
            TextButton(
                onClick = onBackToCredentials,
                enabled = !loading,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .align(Alignment.TopStart)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(LoginUiTags.OTP_BACK),
            ) {
                Text("返回")
            }
        }
    }

    if (showConnectionSettings) {
        LoginConnectionSettingsDialog(
            endpoint = endpoint,
            onDismiss = { showConnectionSettings = false },
            onSave = {
                onEndpointChange(it)
                showConnectionSettings = false
            },
        )
    }
}

@Composable
private fun CredentialsLoginContent(
    endpoint: LoginEndpointDraft,
    username: String,
    password: String,
    showPassword: Boolean,
    loading: Boolean,
    endpointError: String?,
    errorMessage: String?,
    onHostChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onSubmit: () -> Unit,
    onSwitchAccount: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LoginTextField(
            value = endpoint.host,
            onValueChange = onHostChange,
            label = "服务器地址 / IP",
            enabled = !loading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            supportingText = endpointError,
            isError = endpointError != null,
            tag = LoginUiTags.HOST,
        )
        LoginTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = "账户",
            enabled = !loading,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            tag = LoginUiTags.USERNAME,
        )
        LoginTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = "密码",
            enabled = !loading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            visualTransformation = if (showPassword) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = onTogglePassword, enabled = !loading) {
                    Icon(
                        if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                    )
                }
            },
            tag = LoginUiTags.PASSWORD,
        )
        LoginError(errorMessage)
        Button(
            onClick = onSubmit,
            enabled = !loading && endpointError == null && endpoint.host.isNotBlank() &&
                username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag(LoginUiTags.SUBMIT),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 0.dp,
                focusedElevation = 0.dp,
                hoveredElevation = 0.dp,
            ),
        ) {
            LoginButtonLabel(loading = loading, idleText = "登录")
        }
        TextButton(
            onClick = onSwitchAccount,
            enabled = !loading,
            modifier = Modifier.align(Alignment.CenterHorizontally).testTag(LoginUiTags.SWITCH_ACCOUNT),
        ) {
            Text("切换账号")
        }
    }
}

@Composable
private fun TwoFactorLoginContent(
    otp: String,
    loading: Boolean,
    errorMessage: String?,
    onOtpChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("两步验证", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "请输入身份验证器中显示的验证码",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        LoginTextField(
            value = otp,
            onValueChange = onOtpChange,
            label = "验证码",
            enabled = !loading,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            tag = LoginUiTags.OTP,
        )
        LoginError(errorMessage)
        Button(
            onClick = onSubmit,
            enabled = !loading && otp.length in 6..8,
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag(LoginUiTags.OTP_SUBMIT),
            shape = MaterialTheme.shapes.extraLarge,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        ) {
            LoginButtonLabel(loading = loading, idleText = "验证并登录")
        }
        TextButton(onClick = onBack, enabled = !loading) {
            Text("返回账号密码登录")
        }
    }
}

@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    tag: String,
    modifier: Modifier = Modifier,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = trailingIcon,
        supportingText = supportingText?.let {
            { Text(it, maxLines = 2) }
        },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun LoginButtonLabel(loading: Boolean, idleText: String) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(10.dp))
        Text("正在连接")
    } else {
        Text(idleText, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }
}

@Composable
private fun LoginError(message: String?) {
    message?.let {
        Text(
            text = it,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LoginConnectionSettingsDialog(
    endpoint: LoginEndpointDraft,
    onDismiss: () -> Unit,
    onSave: (LoginEndpointDraft) -> Unit,
) {
    var protocolName by remember(endpoint) { mutableStateOf(endpoint.protocol.name) }
    var port by remember(endpoint) { mutableStateOf(endpoint.port) }
    var basePath by remember(endpoint) { mutableStateOf(endpoint.basePath) }
    val protocol = runCatching { LoginProtocol.valueOf(protocolName) }.getOrDefault(LoginProtocol.HTTP)
    val candidate = endpoint.copy(protocol = protocol, port = port, basePath = basePath)
    val settingsError = runCatching {
        candidate.copy(host = "localhost").baseUrl()
    }.exceptionOrNull()?.message

    AlertDialog(
        modifier = Modifier.testTag(LoginUiTags.SETTINGS_DIALOG),
        onDismissRequest = onDismiss,
        title = { Text("连接设置") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "普通 OpenList 服务保持默认 HTTP 和 5244 端口即可。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                ConnectionSettingCard(
                    title = "连接协议",
                    supportingText = "仅在服务器启用 HTTPS 时切换。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoginProtocol.entries.forEach { option ->
                            FilterChip(
                                selected = protocol == option,
                                onClick = { protocolName = option.name },
                                label = { Text(option.scheme.uppercase()) },
                            )
                        }
                    }
                }
                ConnectionSettingCard(
                    title = "端口",
                    supportingText = "留空时使用协议默认端口。",
                ) {
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("端口") },
                        placeholder = { Text(DEFAULT_LOGIN_PORT) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = transparentOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag(LoginUiTags.SETTINGS_PORT),
                    )
                }
                ConnectionSettingCard(
                    title = "服务器路径",
                    supportingText = "仅反向代理部署在子路径时填写。",
                ) {
                    OutlinedTextField(
                        value = basePath,
                        onValueChange = { basePath = it },
                        label = { Text("路径（可选）") },
                        placeholder = { Text("/openlist") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        colors = transparentOutlinedTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag(LoginUiTags.SETTINGS_PATH),
                    )
                }
                if (protocol == LoginProtocol.HTTP) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            "HTTP 不会加密账号、密码和验证码；公网服务器强烈建议改用 HTTPS。",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                settingsError?.let {
                    Text(
                        it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(candidate) }, enabled = settingsError == null) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConnectionSettingCard(
    title: String,
    supportingText: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                supportingText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            content()
        }
    }
}

@Composable
internal fun transparentOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    errorContainerColor = Color.Transparent,
)

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun LoginPagePreview() {
    MaterialTheme {
        LoginPageContent(
            step = LoginStep.Credentials,
            endpoint = LoginEndpointDraft(host = "192.168.1.100"),
            username = "admin",
            password = "",
            otp = "",
            showPassword = false,
            loading = false,
            endpointError = null,
            errorMessage = null,
            onEndpointChange = {},
            onHostChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onOtpChange = {},
            onTogglePassword = {},
            onSubmitCredentials = {},
            onSubmitOtp = {},
            onBackToCredentials = {},
            onSwitchAccount = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun LoginConnectionSettingsPreview() {
    MaterialTheme {
        LoginConnectionSettingsDialog(
            endpoint = LoginEndpointDraft(host = "192.168.1.100"),
            onDismiss = {},
            onSave = {},
        )
    }
}
