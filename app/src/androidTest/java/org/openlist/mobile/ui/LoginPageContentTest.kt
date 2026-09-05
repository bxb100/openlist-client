package org.openlist.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.ui.theme.OpenListTheme

@RunWith(AndroidJUnit4::class)
class LoginPageContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savedPasswordCheckboxIsReachableAtNarrowWidthLargeFontAndShortHeight() {
        var savePassword by mutableStateOf(true)
        var loading by mutableStateOf(false)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                OpenListTheme {
                    Box(Modifier.width(320.dp).height(300.dp)) {
                        LoginPageContent(
                            step = LoginStep.Credentials,
                            endpoint = LoginEndpointDraft(host = "nas.local"),
                            username = "admin", password = "saved-password", otp = "",
                            showPassword = false, loading = loading, endpointError = null, errorMessage = null,
                            onEndpointChange = {}, onHostChange = {}, onUsernameChange = {}, onPasswordChange = {},
                            onOtpChange = {}, onTogglePassword = {}, onSubmitCredentials = {}, onSubmitOtp = {},
                            onBackToCredentials = {}, onSwitchAccount = {},
                            savePassword = savePassword,
                            onSavePasswordChange = { savePassword = it },
                        )
                    }
                }
            }
        }
        val checkbox = composeRule.onNodeWithTag(LoginUiTags.SAVE_PASSWORD)
        checkbox.performScrollTo().assertIsDisplayed().assertIsOn().performClick()
        checkbox.assertIsOff()
        composeRule.onNodeWithTag(LoginUiTags.PASSWORD).assertIsDisplayed()
        composeRule.runOnIdle { loading = true }
        checkbox.assertIsNotEnabled()
    }

    @Test
    fun credentialsPageKeepsAdvancedConnectionAndOtpOutOfTheMainForm() {
        var endpoint by mutableStateOf(LoginEndpointDraft(host = "192.168.1.100"))
        composeRule.setContent {
            OpenListTheme {
                LoginPageContent(
                    step = LoginStep.Credentials,
                    endpoint = endpoint,
                    username = "admin",
                    password = "secret",
                    otp = "",
                    showPassword = false,
                    loading = false,
                    endpointError = null,
                    errorMessage = null,
                    onEndpointChange = { endpoint = it },
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

        composeRule.onNodeWithTag(LoginUiTags.HOST).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LoginUiTags.OTP).assertCountEquals(0)
        composeRule.onNodeWithTag(LoginUiTags.SUBMIT).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(LoginUiTags.SWITCH_ACCOUNT).assertIsDisplayed()

        composeRule.onNodeWithTag(LoginUiTags.SETTINGS).performScrollTo().performClick()
        composeRule.onNodeWithTag(LoginUiTags.SETTINGS_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginUiTags.SETTINGS_PORT).assertTextContains("5244")
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle {
            assertEquals(LoginProtocol.HTTP, endpoint.protocol)
            assertEquals("5244", endpoint.port)
        }

        composeRule.onNodeWithTag(LoginUiTags.SETTINGS).performScrollTo().performClick()
        composeRule.onNodeWithText("HTTPS").performClick()
        composeRule.onNodeWithTag(LoginUiTags.SETTINGS_PORT).performTextReplacement("8443")
        composeRule.onNodeWithText("保存").performClick()
        composeRule.runOnIdle {
            assertEquals(LoginProtocol.HTTPS, endpoint.protocol)
            assertEquals("8443", endpoint.port)
        }
    }

    @Test
    fun twoFactorPageOnlyShowsTheChallengeAndCanReturn() {
        var returned = false
        composeRule.setContent {
            OpenListTheme {
                LoginPageContent(
                    step = LoginStep.TwoFactor,
                    endpoint = LoginEndpointDraft(host = "192.168.1.100"),
                    username = "admin",
                    password = "secret",
                    otp = "123456",
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
                    onBackToCredentials = { returned = true },
                    onSwitchAccount = {},
                )
            }
        }

        composeRule.onNodeWithTag(LoginUiTags.OTP).assertIsDisplayed()
        composeRule.onAllNodesWithTag(LoginUiTags.HOST).assertCountEquals(0)
        composeRule.onAllNodesWithTag(LoginUiTags.PASSWORD).assertCountEquals(0)
        composeRule.onNodeWithTag(LoginUiTags.OTP_BACK).performClick()
        composeRule.runOnIdle { assertTrue(returned) }
    }

    @Test
    fun typedFullUrlRetainsHttpsAndTheWholeProxyPathAfterFocusMoves() {
        var endpoint by mutableStateOf(LoginEndpointDraft())
        composeRule.setContent {
            OpenListTheme {
                LoginPageContent(
                    step = LoginStep.Credentials,
                    endpoint = endpoint,
                    username = "admin",
                    password = "",
                    otp = "",
                    showPassword = false,
                    loading = false,
                    endpointError = null,
                    errorMessage = null,
                    onEndpointChange = { endpoint = it },
                    onHostChange = { endpoint = endpoint.withAddressInput(it) },
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
        val host = composeRule.onNodeWithTag(LoginUiTags.HOST)
        host.performTextInput("https://files.example.com")
        host.performTextInput("/team%2Ffiles")
        host.assertTextContains("https://files.example.com/team%2Ffiles")
        composeRule.runOnIdle {
            assertEquals("https://files.example.com/team%2Ffiles", endpoint.baseUrl())
        }
        composeRule.onNodeWithTag(LoginUiTags.USERNAME).performScrollTo().performClick()
        host.performScrollTo().assertTextContains("https://files.example.com/team%2Ffiles")
    }

    @Test
    fun secondFactorRequiresCompleteCodeAndSubmitsOnlyOnceWhenBusy() {
        var otp by mutableStateOf("12345")
        var loading by mutableStateOf(false)
        var submissions = 0
        composeRule.setContent {
            OpenListTheme {
                LoginPageContent(
                    step = LoginStep.TwoFactor,
                    endpoint = LoginEndpointDraft(host = "nas.local"),
                    username = "admin",
                    password = "secret",
                    otp = otp,
                    showPassword = false,
                    loading = loading,
                    endpointError = null,
                    errorMessage = null,
                    onEndpointChange = {},
                    onHostChange = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onOtpChange = { otp = it },
                    onTogglePassword = {},
                    onSubmitCredentials = {},
                    onSubmitOtp = { submissions++; loading = true },
                    onBackToCredentials = {},
                    onSwitchAccount = {},
                )
            }
        }
        composeRule.onNodeWithTag(LoginUiTags.OTP_SUBMIT).assertIsNotEnabled()
        val otpField = composeRule.onNodeWithTag(LoginUiTags.OTP)
        otpField.performScrollTo().performClick()
        otpField.performImeAction()
        composeRule.runOnIdle { assertEquals(0, submissions) }
        otpField.performTextReplacement("123456")
        otpField.performImeAction()
        composeRule.onNodeWithTag(LoginUiTags.OTP_SUBMIT).performScrollTo().assertIsNotEnabled().performClick()
        composeRule.onNodeWithTag(LoginUiTags.OTP_BACK).assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, submissions) }
    }
}
