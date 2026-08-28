package org.openlist.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/**
 * The app's Material 3 type scale. Sizes, line heights, and tracking stay on the M3 baseline so
 * every text style keeps its spec metrics; only the display and headline weights are lifted to
 * [FontWeight.SemiBold] to give headings a consistent brand voice. Screens must use these tokens
 * (via `MaterialTheme.typography`) rather than setting `fontWeight`/`fontSize` inline, so heading
 * emphasis has a single source of truth.
 */
val AppTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontWeight = FontWeight.SemiBold),
        displayMedium = displayMedium.copy(fontWeight = FontWeight.SemiBold),
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}
