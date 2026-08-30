package com.example.stickermaker.ui.components

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.example.stickermaker.editor.StickerClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Loops an animated [clip] (a GIF's trimmed part, a moving combination) frame by frame, with
 * the frames and timing the sticker will have. Frames render off the main thread into three
 * buffers (one shown, one maybe still being drawn, one filling); only drawing reads them.
 */
@Composable
fun ClipPlayer(clip: StickerClip, contentDescription: String?, modifier: Modifier = Modifier, side: Int = PREVIEW_PX) {
    val buffers = remember(side) { List(3) { Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888) } }
    val images = remember(buffers) { buffers.map(Bitmap::asImageBitmap) }
    val shown = remember(clip) { mutableIntStateOf(-1) }

    LaunchedEffect(clip, side) {
        withContext(Dispatchers.Default) {
            var slot = 0
            while (isActive) {
                val frames = clip.frames(side)
                if (frames.size == 0) return@withContext
                for (i in 0 until frames.size) {
                    val began = SystemClock.uptimeMillis()
                    buffers[slot].setPixels(frames.render(i), 0, side, 0, 0, side, side)
                    shown.intValue = slot
                    slot = (slot + 1) % buffers.size
                    delay(frames.durations[i] - (SystemClock.uptimeMillis() - began))
                }
            }
        }
    }

    Spacer(
        modifier
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .drawBehind {
                val slot = shown.intValue
                if (slot >= 0) drawImage(images[slot], dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            },
    )
}

/** Previews are small on screen: render at a third of the sticker's 512. */
private const val PREVIEW_PX = 256
