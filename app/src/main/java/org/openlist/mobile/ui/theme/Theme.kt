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

private val LightColors = lightColorScheme(
    primary = Color(0xFF5156A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF0C0D60),
    secondary = Color(0xFF5D5D72),
    secondaryContainer = Color(0xFFE2E0F9),
    tertiary = Color(0xFF78536B),
    surface = Color(0xFFFBF8FF),
    surfaceContainer = Color(0xFFF0EDF5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC0C1FF),
    onPrimary = Color(0xFF212372),
    primaryContainer = Color(0xFF393C8B),
    onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFFC6C4DD),
    secondaryContainer = Color(0xFF454559),
    tertiary = Color(0xFFE8B9D4),
    surface = Color(0xFF131318),
    surfaceContainer = Color(0xFF201F25),
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
    MaterialTheme(colorScheme = colors, content = content)
}

