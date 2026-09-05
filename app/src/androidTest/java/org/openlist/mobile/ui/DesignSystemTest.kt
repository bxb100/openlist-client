package org.openlist.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openlist.mobile.ui.designsystem.OpenListErrorState
import org.openlist.mobile.ui.theme.OpenListMediaTheme
import org.openlist.mobile.ui.theme.OpenListTheme

@RunWith(AndroidJUnit4::class)
class DesignSystemTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun textRolesRemainReadableAcrossLightDarkAndMediaSurfaces() {
        val schemes = mutableMapOf<String, ColorScheme>()
        composeRule.setContent {
            OpenListTheme(darkTheme = false) {
                val colors = MaterialTheme.colorScheme
                SideEffect { schemes["light"] = colors }
            }
            OpenListTheme(darkTheme = true) {
                val colors = MaterialTheme.colorScheme
                SideEffect { schemes["dark"] = colors }
            }
            OpenListMediaTheme {
                val colors = MaterialTheme.colorScheme
                SideEffect { schemes["media"] = colors }
            }
        }

        composeRule.runOnIdle {
            assertEquals(3, schemes.size)
            for ((name, colors) in schemes) {
                val textPairs = listOf(
                    colors.onPrimary to colors.primary,
                    colors.onPrimaryContainer to colors.primaryContainer,
                    colors.onSecondary to colors.secondary,
                    colors.onSecondaryContainer to colors.secondaryContainer,
                    colors.onTertiary to colors.tertiary,
                    colors.onTertiaryContainer to colors.tertiaryContainer,
                    colors.onError to colors.error,
                    colors.onErrorContainer to colors.errorContainer,
                    colors.onBackground to colors.background,
                    colors.onSurfaceVariant to colors.surfaceVariant,
                    colors.inverseOnSurface to colors.inverseSurface,
                ) + listOf(
                    colors.surface,
                    colors.surfaceContainerLowest,
                    colors.surfaceContainerLow,
                    colors.surfaceContainer,
                    colors.surfaceContainerHigh,
                    colors.surfaceContainerHighest,
                ).flatMap { surface ->
                    listOf(colors.onSurface to surface, colors.onSurfaceVariant to surface)
                }
                textPairs.forEach { (foreground, background) ->
                    assertTrue("$name has a text role below 4.5:1 contrast", contrast(foreground, background) >= 4.5f)
                }
            }
        }
    }

    @Test
    fun connectionErrorKeepsRecoveryReachableWithLargeTextInANarrowWindow() {
        var retried = false
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                OpenListTheme {
                    Surface {
                        Column(Modifier.width(280.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                            OpenListErrorState(
                                title = "暂时无法连接到服务器",
                                description = "检查网络连接后重试。你仍然可以切换到其他账户。",
                                onRetry = { retried = true },
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("重试").performScrollTo().assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(retried) }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.luminance()
        val second = background.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
