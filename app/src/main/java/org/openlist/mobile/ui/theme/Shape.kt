package org.openlist.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The app's Material 3 shape scale, stated explicitly so corner radii have a single editable
 * source of truth. These values mirror the M3 baseline; components that reach for a shape (cards,
 * dialogs, sheets, text fields) resolve it through `MaterialTheme.shapes.*` instead of hardcoding
 * a [RoundedCornerShape]. Adjust radii here to restyle the whole app coherently.
 */
val AppShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
