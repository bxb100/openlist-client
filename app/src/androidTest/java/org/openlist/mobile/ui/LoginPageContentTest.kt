package org.openlist.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginPageContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun credentialsPageKeepsAdvancedConnectionAndOtpOutOfTheMainForm() {
        var endpoint by mutableStateOf(LoginEndpointDraft(host = "192.168.1.100"))
        composeRule.setContent {
            MaterialTheme {
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
        composeRule.onNodeWithTag(LoginUiTags.SUBMIT).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginUiTags.SWITCH_ACCOUNT).assertIsDisplayed()

        composeRule.onNodeWithTag(LoginUiTags.SETTINGS).performTouchInput { click() }
        composeRule.onNodeWithTag(LoginUiTags.SETTINGS_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(LoginUiTags.SETTINGS_PORT).assertTextContains("5244")
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle {
            assertEquals(LoginProtocol.HTTP, endpoint.protocol)
            assertEquals("5244", endpoint.port)
        }

        composeRule.onNodeWithTag(LoginUiTags.SETTINGS).performTouchInput { click() }
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
            MaterialTheme {
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
}
