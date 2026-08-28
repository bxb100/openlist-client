package org.openlist.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.CachePolicy

@RunWith(AndroidJUnit4::class)
class SettingsPageContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accountAndThemeActionsKeepTheCurrentAppearanceContext() {
        var accountRequests = 0
        val appearanceChanges = mutableListOf<Pair<Boolean, Boolean?>>()
        setSettingsContent(
            darkTheme = null,
            dynamicColor = true,
            onAccountsRequested = { accountRequests += 1 },
            onAppearanceChanged = { updatedDynamicColor, updatedDarkTheme ->
                appearanceChanges += updatedDynamicColor to updatedDarkTheme
            },
        )

        composeRule.onNodeWithTag(SettingsUiTags.ACCOUNT_CARD).performClick()
        composeRule.runOnIdle { assertEquals(1, accountRequests) }

        scrollTo(SettingsUiTags.THEME_LIGHT)
        composeRule.onNodeWithTag(SettingsUiTags.THEME_LIGHT).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(true to false), appearanceChanges)
        }

        composeRule.onNodeWithTag(SettingsUiTags.THEME_DARK).performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(true to false, true to true), appearanceChanges)
        }
    }

    @Test
    fun dynamicColorIsUnavailableBeforeAndroid12AndExplainsWhy() {
        var appearanceChange: Pair<Boolean, Boolean?>? = null
        setSettingsContent(
            darkTheme = false,
            dynamicColor = true,
            dynamicColorAvailable = false,
            onAppearanceChanged = { updatedDynamicColor, updatedDarkTheme ->
                appearanceChange = updatedDynamicColor to updatedDarkTheme
            },
        )

        scrollTo(SettingsUiTags.DYNAMIC_COLOR_SWITCH)
        composeRule.onNodeWithTag(SettingsUiTags.DYNAMIC_COLOR_SWITCH)
            .assertIsNotEnabled()
            .assertIsOn()
        composeRule.onNodeWithText("此偏好已开启，但当前设备需要 Android 12 或更高版本")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertNull(appearanceChange) }
    }

    @Test
    fun cacheEditorEnablesOnlyChangedPoliciesAndCanRevertOrSave() {
        val initialPolicy = CachePolicy(
            maxBytes = 2L * BYTES_PER_GIB,
            maxAgeMillis = 7L * MILLIS_PER_DAY,
            maxEntries = 2_000,
        )
        var currentPolicy by mutableStateOf(initialPolicy)
        var savedPolicy: CachePolicy? = null
        composeRule.setContent {
            MaterialTheme {
                SettingsPageContent(
                    serverBaseUrl = "https://openlist.example.com",
                    username = "admin",
                    darkTheme = null,
                    dynamicColor = true,
                    dynamicColorAvailable = true,
                    cachePolicy = currentPolicy,
                    operationBusy = false,
                    onAccountsRequested = {},
                    onAppearanceChanged = { _, _ -> },
                    onCachePolicySave = { updatedPolicy ->
                        savedPolicy = updatedPolicy
                        currentPolicy = updatedPolicy
                    },
                    onClearCacheRequested = {},
                    onLogoutRequested = {},
                )
            }
        }

        scrollTo(SettingsUiTags.CACHE_SIZE)
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SIZE)
            .performScrollTo()
            .performTextReplacement("3")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE).assertIsEnabled()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_REVERT)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SIZE).assertTextContains("2")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE).assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_REVERT).assertDoesNotExist()

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SIZE)
            .performTextReplacement("3.5")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_AGE)
            .performScrollTo()
            .performTextReplacement("5.25")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_ENTRIES)
            .performScrollTo()
            .performTextReplacement("321")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(
                CachePolicy(
                    maxBytes = 3_758_096_384L,
                    maxAgeMillis = 453_600_000L,
                    maxEntries = 321,
                ),
                savedPolicy,
            )
        }
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE).assertIsNotEnabled()
    }

    @Test
    fun zeroCacheLimitsStaySaveableAndExplainThatWritesWillStop() {
        val initialPolicy = CachePolicy(
            maxBytes = 2L * BYTES_PER_GIB,
            maxAgeMillis = 7L * MILLIS_PER_DAY,
            maxEntries = 2_000,
        )
        var currentPolicy by mutableStateOf(initialPolicy)
        var savedPolicy: CachePolicy? = null
        composeRule.setContent {
            MaterialTheme {
                SettingsPageContent(
                    serverBaseUrl = "https://openlist.example.com",
                    username = "admin",
                    darkTheme = null,
                    dynamicColor = true,
                    dynamicColorAvailable = true,
                    cachePolicy = currentPolicy,
                    operationBusy = false,
                    onAccountsRequested = {},
                    onAppearanceChanged = { _, _ -> },
                    onCachePolicySave = { updatedPolicy ->
                        savedPolicy = updatedPolicy
                        currentPolicy = updatedPolicy
                    },
                    onClearCacheRequested = {},
                    onLogoutRequested = {},
                )
            }
        }

        scrollTo(SettingsUiTags.CACHE_STATUS)
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_STATUS)
            .assertTextContains("缓存写入已启用")

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SIZE)
            .performScrollTo()
            .performTextReplacement("0")

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_STATUS)
            .assertTextContains("保存后将停用缓存")
        composeRule.onNodeWithText("至少一项限制为 0，不会写入新缓存；现有缓存不会自动删除")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(
                CachePolicy(
                    maxBytes = 0L,
                    maxAgeMillis = 7L * MILLIS_PER_DAY,
                    maxEntries = 2_000,
                ),
                savedPolicy,
            )
        }
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_STATUS)
            .assertTextContains("缓存写入已停用")
    }

    @Test
    fun eachInvalidCacheFieldShowsItsOwnErrorAndBlocksSaving() {
        setSettingsContent()

        scrollTo(SettingsUiTags.CACHE_SIZE)
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SIZE)
            .performTextReplacement("3")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsEnabled()

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SIZE)
            .performScrollTo()
            .performTextReplacement(".")
        composeRule.onNodeWithText("缓存容量必须是有效的非负数")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SIZE)
            .performScrollTo()
            .performTextReplacement("3")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsEnabled()

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_AGE)
            .performScrollTo()
            .performTextReplacement(".")
        composeRule.onNodeWithText("保留时间必须是有效的非负数")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsNotEnabled()

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_AGE)
            .performScrollTo()
            .performTextReplacement("7")
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsEnabled()

        composeRule.onNodeWithTag(SettingsUiTags.CACHE_ENTRIES)
            .performScrollTo()
            .performTextReplacement("9999999999")
        composeRule.onNodeWithText("文件数必须在 0 至 ${Int.MAX_VALUE} 之间")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_SAVE)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun destructiveActionsWaitForExplicitConfirmation() {
        var clearRequests = 0
        var logoutRequests = 0
        setSettingsContent(
            onClearCacheRequested = { clearRequests += 1 },
            onLogoutRequested = { logoutRequests += 1 },
        )

        scrollTo(SettingsUiTags.CACHE_CLEAR)
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_CLEAR)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_CLEAR_CONFIRM).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, clearRequests) }
        composeRule.onNodeWithTag(SettingsUiTags.CACHE_CLEAR_CONFIRM).performClick()
        composeRule.runOnIdle { assertEquals(1, clearRequests) }

        scrollTo(SettingsUiTags.LOGOUT)
        composeRule.onNodeWithTag(SettingsUiTags.LOGOUT)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SettingsUiTags.LOGOUT_CONFIRM).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, logoutRequests) }
        composeRule.onNodeWithTag(SettingsUiTags.LOGOUT_CONFIRM).performClick()
        composeRule.runOnIdle { assertEquals(1, logoutRequests) }
    }

    private fun setSettingsContent(
        darkTheme: Boolean? = null,
        dynamicColor: Boolean = true,
        dynamicColorAvailable: Boolean = true,
        onAccountsRequested: () -> Unit = {},
        onAppearanceChanged: suspend (Boolean, Boolean?) -> Unit = { _, _ -> },
        onCachePolicySave: suspend (CachePolicy) -> Unit = {},
        onClearCacheRequested: () -> Unit = {},
        onLogoutRequested: suspend () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                SettingsPageContent(
                    serverBaseUrl = "https://openlist.example.com",
                    username = "admin",
                    darkTheme = darkTheme,
                    dynamicColor = dynamicColor,
                    dynamicColorAvailable = dynamicColorAvailable,
                    cachePolicy = CachePolicy(),
                    operationBusy = false,
                    onAccountsRequested = onAccountsRequested,
                    onAppearanceChanged = onAppearanceChanged,
                    onCachePolicySave = onCachePolicySave,
                    onClearCacheRequested = onClearCacheRequested,
                    onLogoutRequested = onLogoutRequested,
                )
            }
        }
    }

    private fun scrollTo(tag: String) {
        composeRule.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
    }

    private companion object {
        const val BYTES_PER_GIB = 1_073_741_824L
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
