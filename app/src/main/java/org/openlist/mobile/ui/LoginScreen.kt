package org.openlist.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.openlist.mobile.AppContainer
import org.openlist.mobile.R
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.core.network.LocalNetworkAddress
import org.openlist.mobile.core.network.LocalNetworkPermissionController
import org.openlist.mobile.data.repository.SecondFactorRequiredException
import org.openlist.mobile.ui.account.ConnectionEndpointFields
import org.openlist.mobile.ui.account.ConnectionOptionsDialog
import org.openlist.mobile.ui.theme.OpenListTheme

internal enum class LoginStep { Credentials, TwoFactor }

internal object LoginUiTags {
    const val HOST = "login_host"
    const val USERNAME = "login_username"
    const val PASSWORD = "login_password"
    const val SAVE_PASSWORD = "login_save_password"
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
    val savePassword: Boolean,
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
    var otp by remember { mutableStateOf("") }
    var pendingLogin by remember { mutableStateOf<PendingLogin?>(null) }
    var step by remember { mutableStateOf(LoginStep.Credentials) }
    var showPassword by remember { mutableStateOf(false) }
    var changingPasswordPreference by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permissionController = LocalContext.current as? LocalNetworkPermissionController
    val persistedAuthenticationError by container.authenticationError.collectAsStateWithLifecycle()
    val loading = submitting || authenticating || changingPasswordPreference
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
    val credentialIdentity = proposedProfile?.let { it.normalizedBaseUrl() to it.username }
    var password by remember(credentialIdentity) { mutableStateOf("") }
    var savePassword by remember(credentialIdentity) { mutableStateOf(false) }
    var passwordEditRevision by remember(credentialIdentity) { mutableStateOf(0) }
    var savePreferenceEditRevision by remember(credentialIdentity) { mutableStateOf(0) }

    LaunchedEffect(credentialIdentity) {
        if (submitting || pendingLogin != null) return@LaunchedEffect
        val profile = proposedProfile ?: return@LaunchedEffect
        val revision = passwordEditRevision
        val preferenceRevision = savePreferenceEditRevision
        try {
            val credential = container.savedLoginCredential(profile)
            // Loading a saved credential must never replace text or a preference the user has
            // already edited while disk/Keystore access was in flight.
            if (!submitting && pendingLogin == null) {
                if (revision == passwordEditRevision) password = credential?.password.orEmpty()
                if (preferenceRevision == savePreferenceEditRevision) savePassword = credential != null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (revision == passwordEditRevision) error = "无法读取本机保存的密码，请重新输入"
        }
    }

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
        if (submitting || authenticating || changingPasswordPreference) return
        pendingLogin = target
        submitting = true
        scope.launch {
            clearLoginError()
            try {
                container.login(target.profile, target.password, otpCode, target.savePassword)
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
        if (loading || username.isBlank() || password.isBlank()) return
        requestLogin(PendingLogin(profile, password, savePassword), "")
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
        savePassword = savePassword,
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
            val changed = endpoint.withAddressInput(it)
            host = changed.host
            protocolName = changed.protocol.name
            port = changed.port
            basePath = changed.basePath
            pendingLogin = null
            clearLoginError()
        },
        onUsernameChange = {
            username = it
            pendingLogin = null
            clearLoginError()
        },
        onPasswordChange = {
            passwordEditRevision++
            password = it
            pendingLogin = null
            clearLoginError()
        },
        onOtpChange = {
            otp = it.filter(Char::isDigit).take(8)
            clearLoginError()
        },
        onTogglePassword = { showPassword = !showPassword },
        onSavePasswordChange = { selected ->
            savePreferenceEditRevision++
            savePassword = selected
            pendingLogin = null
            clearLoginError()
            val profile = proposedProfile
            if (!selected && profile != null) {
                changingPasswordPreference = true
                scope.launch {
                    try {
                        container.clearSavedLoginCredential(profile)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        savePassword = true
                        error = "无法移除本机保存的密码，请重试"
                    } finally {
                        changingPasswordPreference = false
                    }
                }
            }
        },
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

@OptIn(ExperimentalMaterial3Api::class)
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
    savePassword: Boolean = false,
    onSavePasswordChange: (Boolean) -> Unit = {},
) {
    val canSubmitCredentials = !loading && endpointError == null && endpoint.host.isNotBlank() &&
        username.isNotBlank() && password.isNotBlank()
    val canSubmitOtp = !loading && otp.length in 6..8 && otp.all(Char::isDigit)
    val form: @Composable () -> Unit = {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.extraLarge,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (step) {
                    LoginStep.Credentials -> {
                        ConnectionEndpointFields(
                            endpoint = endpoint,
                            onHostChange = onHostChange,
                            onEndpointChange = onEndpointChange,
                            enabled = !loading,
                            error = endpointError,
                            compact = true,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            LoginTextField(
                                value = username,
                                onValueChange = onUsernameChange,
                                label = "用户名",
                                enabled = !loading,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                tag = LoginUiTags.USERNAME,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                LoginTextField(
                                    value = password,
                                    onValueChange = onPasswordChange,
                                    label = "密码",
                                    enabled = !loading,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { if (canSubmitCredentials) onSubmitCredentials() }),
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
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
                                SavePasswordControl(
                                    checked = savePassword,
                                    enabled = !loading,
                                    onCheckedChange = onSavePasswordChange,
                                    modifier = Modifier.align(Alignment.End),
                                )
                            }
                        }
                        LoginError(errorMessage)
                        Button(
                            onClick = onSubmitCredentials,
                            enabled = canSubmitCredentials,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(LoginUiTags.SUBMIT),
                        ) { LoginButtonLabel(loading, "连接并登录", "正在连接") }
                    }
                    LoginStep.TwoFactor -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(username, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    runCatching { endpoint.baseUrl() }.getOrDefault(endpoint.host),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        LoginTextField(
                            value = otp,
                            onValueChange = onOtpChange,
                            label = "验证码",
                            enabled = !loading,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (canSubmitOtp) onSubmitOtp() }),
                            tag = LoginUiTags.OTP,
                        )
                        LoginError(errorMessage)
                        Button(
                            onClick = onSubmitOtp,
                            enabled = canSubmitOtp,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(LoginUiTags.OTP_SUBMIT),
                        ) { LoginButtonLabel(loading, "验证并登录", "正在验证") }
                    }
                }
            }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_launcher_foreground), null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp),
                        )
                        Text("OpenList", style = MaterialTheme.typography.titleMedium)
                    }
                },
                navigationIcon = {
                    if (step == LoginStep.TwoFactor) {
                        IconButton(
                            onClick = onBackToCredentials,
                            enabled = !loading,
                            modifier = Modifier.testTag(LoginUiTags.OTP_BACK),
                        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回登录") }
                    }
                },
                actions = {
                    if (step == LoginStep.Credentials) {
                        TextButton(
                            onClick = onSwitchAccount,
                            enabled = !loading,
                            modifier = Modifier.testTag(LoginUiTags.SWITCH_ACCOUNT),
                        ) { Text("已存账户") }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val expanded = maxWidth >= 840.dp && LocalDensity.current.fontScale < 1.6f
            val inset = if (maxWidth < 360.dp) 16.dp else 24.dp
            Column(
                modifier = Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = inset, vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (expanded) {
                    Row(
                        Modifier.widthIn(max = 920.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) { LoginIntroduction(step, expanded = true) }
                        Column(Modifier.width(440.dp)) { form() }
                    }
                } else {
                    Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                        LoginIntroduction(step, expanded = false)
                        Spacer(Modifier.height(28.dp))
                        form()
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginIntroduction(step: LoginStep, expanded: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (expanded) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground), null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            if (step == LoginStep.TwoFactor) "验证你的身份"
            else if (expanded) "连接你的\n文件空间" else "登录你的云盘",
            style = if (expanded) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            if (step == LoginStep.Credentials) "从这里，回到你的文件。"
            else "输入身份验证器中的 6–8 位验证码。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavePasswordControl(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .semantics { contentDescription = "在本机加密记住密码" }
            .testTag(LoginUiTags.SAVE_PASSWORD)
            .padding(horizontal = 8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.size(20.dp))
        Text(
            "记住密码",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        )
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
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        trailingIcon = trailingIcon,
        enabled = enabled,
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun LoginButtonLabel(loading: Boolean, idleText: String, busyText: String) {
    if (loading) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = MaterialTheme.colorScheme.onPrimary,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.size(12.dp))
        Text(busyText)
    } else {
        Text(idleText)
        Spacer(Modifier.size(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LoginError(message: String?) {
    message?.let {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, modifier = Modifier.size(20.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Preview(name = "Connection", showBackground = true, widthDp = 412, heightDp = 915)
@Preview(name = "Connection large text", showBackground = true, widthDp = 360, heightDp = 800, fontScale = 2f)
@Composable
private fun LoginPagePreview() {
    OpenListTheme {
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
    OpenListTheme {
        ConnectionOptionsDialog(
            endpoint = LoginEndpointDraft(host = "192.168.1.100"),
            onDismiss = {},
            onSave = {},
        )
    }
}
