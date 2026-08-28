package org.openlist.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.openlist.mobile.data.preferences.SessionStore

// Every role below is a complete Material 3 tonal mapping generated from the brand seed
// 0xFF5156A4 (indigo). Keep the schemes exhaustive: partially overriding a scheme leaves the
// remaining roles at Material's default purple baseline, which no longer harmonizes with the
// brand primary. Regenerate the whole scheme from the seed rather than hand-tuning single roles.
private val LightColors = lightColorScheme(
    primary = Color(0xFF5156A9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E0FF),
    onPrimaryContainer = Color(0xFF060764),
    inversePrimary = Color(0xFFBFC2FF),
    secondary = Color(0xFF5C5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF78536A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8ED),
    onTertiaryContainer = Color(0xFF2E1126),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FD),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFCF8FD),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF5156A9),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF3EFF4),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFCF8FD),
    surfaceDim = Color(0xFFDCD9DE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2F7),
    surfaceContainer = Color(0xFFF0EDF1),
    surfaceContainerHigh = Color(0xFFEAE7EC),
    surfaceContainerHighest = Color(0xFFE5E1E6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBFC2FF),
    onPrimary = Color(0xFF212578),
    primaryContainer = Color(0xFF383D8F),
    onPrimaryContainer = Color(0xFFE0E0FF),
    inversePrimary = Color(0xFF5156A9),
    secondary = Color(0xFFC5C4DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFE8B9D4),
    onTertiary = Color(0xFF46263B),
    tertiaryContainer = Color(0xFF5F3C52),
    onTertiaryContainer = Color(0xFFFFD8ED),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE5E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE5E1E6),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFBFC2FF),
    inverseSurface = Color(0xFFE5E1E6),
    inverseOnSurface = Color(0xFF303034),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39393C),
    surfaceDim = Color(0xFF131316),
    surfaceContainerLowest = Color(0xFF0E0E11),
    surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF201F23),
    surfaceContainerHigh = Color(0xFF2A292D),
    surfaceContainerHighest = Color(0xFF353438),
)

@Composable
fun OpenListTheme(
    sessionStore: SessionStore,
    content: @Composable () -> Unit,
) {
    val settings by sessionStore.settings.collectAsStateWithLifecycle()
    val dark = settings.darkTheme ?: isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

