package org.openlist.mobile.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import org.openlist.mobile.core.model.ServerProfile
import org.openlist.mobile.data.account.AccountId
import org.openlist.mobile.data.account.AccountSummary
import org.openlist.mobile.ui.theme.OpenListTheme

@PreviewTest
@Preview(name = "Connection phone", widthDp = 412, heightDp = 915, showBackground = true)
@Preview(name = "Connection dark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Connection large text", widthDp = 360, heightDp = 900, fontScale = 2f)
@Preview(name = "Connection narrow saved password", widthDp = 320, heightDp = 1100, fontScale = 2f)
@Preview(name = "Connection wide", widthDp = 1000, heightDp = 800)
@Composable
fun ConnectionScreenshot() {
    OpenListTheme {
        ConnectionLoginSample(LoginStep.Credentials)
    }
}

@PreviewTest
@Preview(name = "Two factor", widthDp = 412, heightDp = 915)
@Preview(name = "Two factor large text", widthDp = 360, heightDp = 900, fontScale = 2f)
@Composable
fun TwoFactorScreenshot() {
    OpenListTheme {
        ConnectionLoginSample(LoginStep.TwoFactor)
    }
}

@PreviewTest
@Preview(name = "Connection empty", widthDp = 412, heightDp = 800)
@Composable
fun ConnectionEmptyScreenshot() {
    OpenListTheme { ConnectionLoginSample(LoginStep.Credentials, empty = true) }
}

@PreviewTest
@Preview(name = "Connection error short window", widthDp = 360, heightDp = 600)
@Composable
fun ConnectionErrorFormScreenshot() {
    OpenListTheme {
        ConnectionLoginSample(LoginStep.Credentials, error = "用户名或密码不正确，请检查后重试。")
    }
}

@PreviewTest
@Preview(name = "Two factor dark", widthDp = 412, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TwoFactorDarkScreenshot() {
    OpenListTheme { ConnectionLoginSample(LoginStep.TwoFactor) }
}

@PreviewTest
@Preview(name = "Saved account", widthDp = 412)
@Preview(name = "Saved account large text", widthDp = 360, fontScale = 2f)
@Composable
fun AccountRowScreenshot() {
    OpenListTheme {
        AccountCard(
            account = AccountSummary(
                id = AccountId("screenshot"),
                displayName = "家里的 NAS",
                server = ServerProfile("https://nas.example.com/openlist", "xiaobo"),
                isActive = true,
                isAuthenticated = true,
                requiresLogin = false,
            ),
            busy = false,
            actionsEnabled = true,
            onSwitch = {},
            onEdit = {},
            onDelete = {},
        )
    }
}

@Composable
private fun ConnectionLoginSample(step: LoginStep, empty: Boolean = false, error: String? = null) {
    LoginPageContent(
        step = step,
        endpoint = if (empty) LoginEndpointDraft() else LoginEndpointDraft.fromBaseUrl("https://nas.example.com/openlist"),
        username = if (empty) "" else "xiaobo",
        password = if (empty) "" else "saved-password",
        otp = "",
        showPassword = false,
        savePassword = !empty,
        loading = false,
        endpointError = null,
        errorMessage = error,
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
