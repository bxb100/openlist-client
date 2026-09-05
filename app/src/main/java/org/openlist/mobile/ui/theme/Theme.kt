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

// Blue marks a destination or an available action. Neutral surfaces carry the files themselves;
// use each on-* role with its matching container so both appearance modes remain readable.
private val LightColors = lightColorScheme(
    primary = Color(0xFF245FA6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E8FF),
    onPrimaryContainer = Color(0xFF103154),
    inversePrimary = Color(0xFFAACBFA),
    secondary = Color(0xFF526070),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4EAF2),
    onSecondaryContainer = Color(0xFF192632),
    tertiary = Color(0xFF3A6655),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDEBDD),
    onTertiaryContainer = Color(0xFF123B2D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF17212B),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF17212B),
    surfaceVariant = Color(0xFFDEE4EB),
    onSurfaceVariant = Color(0xFF4D5967),
    surfaceTint = Color(0xFF245FA6),
    inverseSurface = Color(0xFF2C3540),
    inverseOnSurface = Color(0xFFF0F4F9),
    outline = Color(0xFF6F7C8C),
    outlineVariant = Color(0xFFC2CCD7),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF8FAFC),
    surfaceDim = Color(0xFFD7DEE7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F8),
    surfaceContainer = Color(0xFFEBF0F5),
    surfaceContainerHigh = Color(0xFFE5EBF1),
    surfaceContainerHighest = Color(0xFFDFE6EE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAACBFA),
    onPrimary = Color(0xFF00315D),
    primaryContainer = Color(0xFF164773),
    onPrimaryContainer = Color(0xFFD9E8FF),
    inversePrimary = Color(0xFF245FA6),
    secondary = Color(0xFFBDC8D6),
    onSecondary = Color(0xFF283340),
    secondaryContainer = Color(0xFF3F4B58),
    onSecondaryContainer = Color(0xFFDEE7F2),
    tertiary = Color(0xFFA9D2BF),
    onTertiary = Color(0xFF133A2B),
    tertiaryContainer = Color(0xFF2B5141),
    onTertiaryContainer = Color(0xFFCDEBDD),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF11161C),
    onBackground = Color(0xFFE3E9F0),
    surface = Color(0xFF11161C),
    onSurface = Color(0xFFE3E9F0),
    surfaceVariant = Color(0xFF3F4854),
    onSurfaceVariant = Color(0xFFBEC8D4),
    surfaceTint = Color(0xFFAACBFA),
    inverseSurface = Color(0xFFE3E9F0),
    inverseOnSurface = Color(0xFF2C3540),
    outline = Color(0xFF8995A4),
    outlineVariant = Color(0xFF3F4854),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353C45),
    surfaceDim = Color(0xFF11161C),
    surfaceContainerLowest = Color(0xFF0B1015),
    surfaceContainerLow = Color(0xFF192028),
    surfaceContainer = Color(0xFF1E262F),
    surfaceContainerHigh = Color(0xFF28313B),
    surfaceContainerHighest = Color(0xFF333D48),
)

@Composable
fun OpenListTheme(
    sessionStore: SessionStore,
    content: @Composable () -> Unit,
) {
    val settings by sessionStore.settings.collectAsStateWithLifecycle()
    OpenListTheme(
        darkTheme = settings.darkTheme ?: isSystemInDarkTheme(),
        dynamicColor = settings.dynamicColor,
        content = content,
    )
}

/** The same production theme without a session dependency, suitable for previews and tests. */
@Composable
fun OpenListTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Paired overlay colors remain legible over both bright and dark video frames. */
object OpenListMediaColors {
    val canvas = Color.Black
    val controlSurface = Color(0xFF151D26).copy(alpha = 0.92f)
    val onControlSurface = Color(0xFFF5F7FA)
    val controlScrim = Color.Black.copy(alpha = 0.64f)
}

private val MediaColors = DarkColors.copy(
    primary = Color(0xFFF5F7FA),
    onPrimary = Color(0xFF11161C),
    primaryContainer = Color(0xFF2D435C),
    onPrimaryContainer = Color(0xFFE1EDFF),
    inversePrimary = Color(0xFF245FA6),
    background = OpenListMediaColors.canvas,
    onBackground = OpenListMediaColors.onControlSurface,
    surface = Color(0xFF0C1117),
    onSurface = OpenListMediaColors.onControlSurface,
    surfaceTint = Color.Transparent,
    surfaceDim = Color(0xFF0C1117),
    surfaceContainerLowest = Color(0xFF080B0F),
    surfaceContainerLow = Color(0xFF151D26),
    surfaceContainer = Color(0xFF1C2632),
    surfaceContainerHigh = Color(0xFF263341),
    surfaceContainerHighest = Color(0xFF344353),
)

/** Media chrome uses stable neutral colors so wallpaper accents do not tint the viewing space. */
@Composable
fun OpenListMediaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MediaColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
