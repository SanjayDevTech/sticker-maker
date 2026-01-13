package com.example.stickermaker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Layout + brutal-style tokens. No magic numbers in screens.
 *
 * Hard shadows paint *outside* a component's bounds (down-right by the shadow offset), so any
 * container holding brutal components needs at least [ShadowL] of padding on the end/bottom
 * edges or the shadow gets clipped.
 */
object Dimens {
    // Spacing
    val Space2 = 2.dp
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space20 = 20.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
    val Space48 = 48.dp

    val ScreenGutter = 20.dp
    val SectionGap = 28.dp

    // Brutal outline + hard shadow
    val Border = 2.dp
    val BorderThick = 3.dp
    val ShadowS = 3.dp
    val ShadowM = 5.dp
    val ShadowL = 7.dp

    // Corners
    val CornerS = 10.dp
    val CornerM = 16.dp
    val CornerL = 24.dp

    // Touch targets
    val IconTile = 48.dp
    val ButtonMedium = 54.dp
    val ButtonLarge = 68.dp
    val ButtonHero = 84.dp
}

object Shapes {
    val Small = RoundedCornerShape(Dimens.CornerS)
    val Medium = RoundedCornerShape(Dimens.CornerM)
    val Large = RoundedCornerShape(Dimens.CornerL)
    val Pill = RoundedCornerShape(percent = 50)
}
