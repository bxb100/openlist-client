package org.openlist.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.core.model.FileVisibilityAction
import org.openlist.mobile.core.model.FileVisibilityRule
import org.openlist.mobile.ui.filter.FileVisibilityRulesEditor
import org.openlist.mobile.ui.filter.rememberFileVisibilityEditorState
import org.openlist.mobile.ui.theme.OpenListTheme

@RunWith(AndroidJUnit4::class)
class FileVisibilityRulesTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reorderingRulesSavesTheirNewPrecedence() {
        val rules = listOf(
            FileVisibilityRule("hide", "*", FileVisibilityAction.Hide),
            FileVisibilityRule("show", "*.jpg", FileVisibilityAction.Show),
        )
        var saved = emptyList<FileVisibilityRule>()
        setEditor(rules, onSave = { saved = it })

        composeRule.onNodeWithContentDescription("下移规则 1").performClick()
        composeRule.onNodeWithTag("file_rules_save").performClick()

        composeRule.runOnIdle { assertEquals(rules.reversed(), saved) }
    }

    @Test
    fun failedSaveKeepsEditsAndAllowsRetryWithoutPrematureDismissal() {
        val firstSave = CompletableDeferred<Unit>()
        var attempts = 0
        var dismissals = 0
        var saved = emptyList<FileVisibilityRule>()
        setEditor(
            rules = listOf(FileVisibilityRule("draft", "*.tmp")),
            onSave = {
                attempts++
                if (attempts == 1) firstSave.await()
                saved = it
            },
            onDismiss = { dismissals++ },
        )
        composeRule.onNodeWithTag("file_rule_pattern_draft").performTextReplacement("*.bak")
        composeRule.onNodeWithTag("file_rules_save").performClick()
        composeRule.onNodeWithTag("file_rules_save").assertIsNotEnabled()
        composeRule.runOnIdle {
            assertEquals(1, attempts)
            assertEquals(0, dismissals)
            firstSave.completeExceptionally(IllegalStateException("storage unavailable"))
        }

        composeRule.onNodeWithText("未能保存规则，请重试。你的编辑已保留。").assertIsDisplayed()
        composeRule.onNodeWithTag("file_rule_pattern_draft").assertTextContains("*.bak")
        composeRule.onNodeWithTag("file_rules_save").performClick()
        composeRule.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(1, dismissals)
            assertEquals("*.bak", saved.single().pattern)
        }
    }

    @Test
    fun clearingPatternDoesNotSaveUntilTheDraftIsCorrected() {
        var saves = 0
        setEditor(listOf(FileVisibilityRule("draft", "*.tmp")), onSave = { saves++ })
        composeRule.onNodeWithTag("file_rule_pattern_draft").performTextReplacement("")
        composeRule.onNodeWithTag("file_rules_save").performClick()
        composeRule.onNodeWithText("请输入名称通配符，或删除此规则").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, saves) }

        composeRule.onNodeWithTag("file_rule_pattern_draft").performTextReplacement("*.bak")
        composeRule.onNodeWithTag("file_rules_save").performClick()
        composeRule.runOnIdle { assertEquals(1, saves) }
    }

    private fun setEditor(
        rules: List<FileVisibilityRule>,
        onSave: suspend (List<FileVisibilityRule>) -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            OpenListTheme {
                FileVisibilityRulesEditor(rememberFileVisibilityEditorState(rules), onSave, onDismiss)
            }
        }
    }
}
