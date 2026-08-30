package com.example.stickermaker.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import com.example.stickermaker.editor.MotionPlan
import com.example.stickermaker.editor.StickerExport
import com.example.stickermaker.editor.StickerMotion
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every motion as a tile, each playing it on [sample] (a still sticker, 512
 * square). [stillAllowed] false greys out Still (when the sticker must move). */
@Composable
fun MotionPicker(sample: ImageBitmap?, selected: StickerMotion, onSelect: (StickerMotion) -> Unit, stillAllowed: Boolean) {
    val colors = AppTheme.colors
    // Plans need the sample's pixels: built once per sample, off the main thread.
    val plans by produceState<Map<StickerMotion, MotionPlan?>>(emptyMap(), sample) {
        val still = sample?.asAndroidBitmap() ?: return@produceState
        value = withContext(Dispatchers.Default) { StickerMotion.entries.associateWith { StickerExport.plan(still, it) } }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(end = Dimens.ShadowM, bottom = Dimens.ShadowM),
    ) {
        StickerMotion.entries.forEach { motion ->
            val on = motion == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.Space4)) {
                BrutalSurface(
                    modifier = Modifier.size(72.dp),
                    shape = Shapes.Medium,
                    color = if (on) Palette.Yellow else colors.surface,
                    borderWidth = if (on) Dimens.BorderThick else Dimens.Border,
                    shadow = if (on) Dimens.ShadowM else Dimens.ShadowS,
                    onClick = { onSelect(motion) },
                    enabled = stillAllowed || motion != StickerMotion.None,
                    onClickLabel = motion.label,
                ) {
                    if (sample != null) {
                        MotionImage(sample, plans[motion], contentDescription = null, modifier = Modifier.fillMaxSize().padding(Dimens.Space4))
                    }
                }
                Text(motion.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
