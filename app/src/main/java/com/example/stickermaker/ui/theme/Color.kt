package com.example.stickermaker.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Neo-brutalist palette: flat pastel blocks, one heavy "ink" for outlines, hard shadows and
 * text. Pastels never change between themes; only the canvas and ink flip. Text that sits on a
 * pastel block always uses [StickerColors.onBlock] (dark) so blocks read the same in both themes.
 */
object Palette {
    val Lavender = Color(0xFFD4BBFF)
    val Pink = Color(0xFFFF90E8)
    val Yellow = Color(0xFFFFDE4D)
    val Lime = Color(0xFFD9F56B)
    val Mint = Color(0xFFBDEBD4)
    val Peach = Color(0xFFFFD2A6)
    val Sky = Color(0xFFB9DCFF)
    val Orange = Color(0xFFF7B544)
    val Coral = Color(0xFFFF7A66)

    val Ink = Color(0xFF141414)
    val Paper = Color(0xFFFBF4E8)
    val Cream = Color(0xFFFFF8EC)
    val White = Color(0xFFFFFFFF)

    val InkDark = Color(0xFFF4EBDC)      // cream ink on the AMOLED canvas
    val SurfaceDark = Color(0xFF151413)
}

@Immutable
data class StickerColors(
    /** Screen canvas. */
    val canvas: Color,
    /** Default card / sheet fill when no pastel is chosen. */
    val surface: Color,
    /** Outlines, hard shadows and default text. */
    val ink: Color,
    /** Secondary text on the canvas / surface. */
    val inkMuted: Color,
    /** Text and icons on any pastel block. Constant across themes. */
    val onBlock: Color,
    val isDark: Boolean,
) {
    /** Cycle of pastels for lists of cards, tags, avatars… */
    val blocks: List<Color> = listOf(
        Palette.Lavender, Palette.Mint, Palette.Peach, Palette.Pink, Palette.Sky, Palette.Lime,
    )

    fun block(index: Int): Color = blocks[Math.floorMod(index, blocks.size)]
}

val LightStickerColors = StickerColors(
    canvas = Palette.Paper,
    surface = Palette.White,
    ink = Palette.Ink,
    inkMuted = Color(0xFF5E5850),
    onBlock = Palette.Ink,
    isDark = false,
)

/** AMOLED: pure black canvas, cream ink so outlines and shadows still carry the style. */
val DarkStickerColors = StickerColors(
    canvas = Color.Black,
    surface = Palette.SurfaceDark,
    ink = Palette.InkDark,
    inkMuted = Color(0xFFB3AA9C),
    onBlock = Palette.Ink,
    isDark = true,
)
