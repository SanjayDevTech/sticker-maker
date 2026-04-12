package com.example.stickermaker.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.data.StickerPack
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Shapes

/** A pack's face (its first sticker, which is also its WhatsApp tray icon) on a small tile. */
@Composable
fun PackTray(pack: StickerPack, modifier: Modifier = Modifier) {
    val packs = LocalAppContainer.current.packs
    BrutalSurface(modifier, shape = Shapes.Small, color = AppTheme.colors.surface, shadow = 0.dp) {
        val first = pack.stickers.firstOrNull()
        if (first != null) {
            FileImage(
                file = packs.stickerFile(pack.id, first.id),
                maxPx = 160,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().checkerboard(4.dp),
            )
        }
    }
}
