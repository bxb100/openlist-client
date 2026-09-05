package org.openlist.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * File names and body copy share a readable 16 sp baseline; metadata stays at 14 sp. Headings
 * use weight and spacing instead of oversized display text. System fonts retain Android's
 * language coverage and font scaling; containers must grow with these text styles.
 */
val AppTypography = Typography(
    displayLarge = appTextStyle(44, 52, FontWeight.SemiBold),
    displayMedium = appTextStyle(40, 48, FontWeight.SemiBold),
    displaySmall = appTextStyle(36, 44, FontWeight.SemiBold),
    headlineLarge = appTextStyle(32, 40, FontWeight.SemiBold),
    headlineMedium = appTextStyle(28, 36, FontWeight.SemiBold),
    headlineSmall = appTextStyle(24, 32, FontWeight.SemiBold),
    titleLarge = appTextStyle(22, 30, FontWeight.Medium),
    titleMedium = appTextStyle(16, 24, FontWeight.Medium),
    titleSmall = appTextStyle(14, 20, FontWeight.Medium),
    bodyLarge = appTextStyle(16, 24),
    bodyMedium = appTextStyle(14, 20),
    bodySmall = appTextStyle(12, 18),
    labelLarge = appTextStyle(14, 20, FontWeight.Medium),
    labelMedium = appTextStyle(12, 16, FontWeight.Medium),
    labelSmall = appTextStyle(11, 16, FontWeight.Medium),
)

private fun appTextStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)
