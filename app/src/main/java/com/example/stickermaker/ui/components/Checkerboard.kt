package com.example.stickermaker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme

/** The classic transparency checkerboard, so cut-out areas read as "empty", not "white". */
@Composable
fun Modifier.checkerboard(cell: Dp = 10.dp): Modifier {
    val colors = AppTheme.colors
    val light = if (colors.isDark) Color(0xFF2A2826) else Color(0xFFFFFFFF)
    val dark = if (colors.isDark) Color(0xFF1C1B1A) else Color(0xFFE9E3D8)
    return drawBehind {
        val c = cell.toPx()
        drawRect(light)
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else c
            while (x < size.width) {
                drawRect(dark, Offset(x, y), Size(c, c))
                x += 2 * c
            }
            y += c
            row++
        }
    }
}
