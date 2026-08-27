package com.example.stickermaker.ui.screens.editor

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.stickermaker.data.Trim
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.editor.Gif
import com.example.stickermaker.editor.StickerMotion
import com.example.stickermaker.editor.Timeline
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.checkerboard
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shortest part of a GIF that can be kept. */
private const val MIN_TRIM_MS = 300

/**
 * Loops [trim] of [gif] with the same frames and timing the sticker will have. While
 * [scrubMs] is set (a trim edge being dragged) it holds that moment instead. Writes where it
 * is to [playhead] for the trim bar.
 */
@Composable
fun GifPlayer(gif: Gif, trim: Trim, scrubMs: Int?, playhead: MutableIntState, modifier: Modifier = Modifier) {
    // Three buffers: one shown, one possibly still being drawn by the render thread, one filling.
    val buffers = remember(gif) { List(3) { Bitmap.createBitmap(gif.width, gif.height, Bitmap.Config.ARGB_8888) } }
    val images = remember(buffers) { buffers.map(Bitmap::asImageBitmap) }
    val shown = remember(gif) { mutableIntStateOf(-1) }

    LaunchedEffect(gif, trim, scrubMs) {
        withContext(Dispatchers.Default) {
            var slot = 0
            fun show(pixels: IntArray) {
                buffers[slot].setPixels(pixels, 0, gif.width, 0, 0, gif.width, gif.height)
                shown.intValue = slot
                slot = (slot + 1) % buffers.size
            }
            if (scrubMs != null) {
                show(gif.frame(gif.frameAt(scrubMs)))
                playhead.intValue = scrubMs
                return@withContext
            }
            val timeline = Timeline.of(gif.startMs, gif.durationMs, trim.startMs, trim.endMs, StickerMotion.FRAME_MS)
            if (timeline.size == 0) return@withContext
            while (isActive) {
                val cursor = gif.Cursor(timeline.frames[0])
                var at = trim.startMs
                for (k in 0 until timeline.size) {
                    val began = SystemClock.uptimeMillis()
                    while (cursor.index < timeline.frames[k]) cursor.advance()
                    show(cursor.canvas)
                    playhead.intValue = at
                    at += timeline.durations[k]
                    delay(timeline.durations[k] - (SystemClock.uptimeMillis() - began))
                }
            }
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .aspectRatio(gif.width.toFloat() / gif.height)
                .checkerboard()
                .drawBehind {
                    val slot = shown.intValue
                    if (slot < 0) return@drawBehind
                    drawImage(
                        images[slot],
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        filterQuality = if (size.width > gif.width * 2) FilterQuality.None else FilterQuality.Low,
                    )
                },
        )
    }
}

/** Trim controls for a GIF: the bar, what's kept, and a line of help. */
@Composable
fun TrimPanel(
    gif: Gif,
    trim: Trim,
    onTrim: (Trim) -> Unit,
    onScrub: (Int?) -> Unit,
    playhead: MutableIntState,
) {
    val colors = AppTheme.colors
    val tooLong = gif.durationMs > WhatsAppLimits.ANIMATED_MAX_MS
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
            Text("Trim", style = MaterialTheme.typography.titleMedium)
            Text(
                "${seconds(trim.startMs)} – ${seconds(trim.endMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
            StickerTag(seconds(trim.lengthMs), Palette.Lime, rotation = 3f)
        }
        TrimBar(gif, trim, onTrim, onScrub, playhead)
        Text(
            if (tooLong) {
                "This GIF runs ${seconds(gif.durationMs)}; a sticker plays up to 10 s. Slide the window to pick the part to keep."
            } else {
                "Drag the ends to trim. GIFs aren't cut out: the whole frame becomes the sticker."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
    }
}

private fun seconds(ms: Int) = "%.1f s".format(ms / 1000f)

private enum class Grab { Start, End, Window }

/**
 * Frames of the whole GIF with the kept part as a window: drag its ends to resize, drag inside
 * to slide it, tap outside to move it there. The window never exceeds WhatsApp's 10 s.
 */
@Composable
private fun TrimBar(gif: Gif, trim: Trim, onTrim: (Trim) -> Unit, onScrub: (Int?) -> Unit, playhead: MutableIntState) {
    val colors = AppTheme.colors
    val duration = gif.durationMs
    val maxLength = minOf(duration, WhatsAppLimits.ANIMATED_MAX_MS)
    val current by rememberUpdatedState(trim)
    val change by rememberUpdatedState(onTrim)
    val scrub by rememberUpdatedState(onScrub)

    val thumbs by produceState<List<ImageBitmap>>(emptyList(), gif) {
        value = withContext(Dispatchers.Default) {
            List(THUMBS) { j ->
                val frame = gif.frame(gif.frameAt(duration * (2 * j + 1) / (2 * THUMBS)))
                val bitmap = Bitmap.createBitmap(frame, gif.width, gif.height, Bitmap.Config.ARGB_8888)
                val h = THUMB_PX
                val w = maxOf(1, h * gif.width / gif.height)
                Bitmap.createScaledBitmap(bitmap, w, h, true).also { if (it !== bitmap) bitmap.recycle() }.asImageBitmap()
            }
        }
    }

    BrutalSurface(Modifier.fillMaxWidth().padding(end = Dimens.ShadowS), shape = Shapes.Small, shadow = Dimens.ShadowS) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .pointerInput(duration) {
                    val edgeSlop = 28.dp.toPx()
                    fun msAt(x: Float) = (x / size.width * duration).roundToInt()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val origin = current
                        val x0 = origin.startMs.toFloat() / duration * size.width
                        val x1 = origin.endMs.toFloat() / duration * size.width
                        val x = down.position.x
                        val grab = when {
                            abs(x - x0) <= edgeSlop && abs(x - x0) <= abs(x - x1) -> Grab.Start
                            abs(x - x1) <= edgeSlop -> Grab.End
                            x in x0..x1 -> Grab.Window
                            else -> {
                                // Outside: centre the window on the tap, then slide from there.
                                val len = origin.lengthMs
                                val start = (msAt(x) - len / 2).coerceIn(0, duration - len)
                                change(Trim(start, start + len))
                                Grab.Window
                            }
                        }
                        val base = current
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            val d = msAt(c.position.x - down.position.x)
                            val next = when (grab) {
                                Grab.Start -> {
                                    val start = (base.startMs + d).coerceIn(maxOf(0, base.endMs - maxLength), base.endMs - MIN_TRIM_MS)
                                    Trim(start, base.endMs)
                                }
                                Grab.End -> {
                                    val end = (base.endMs + d).coerceIn(base.startMs + MIN_TRIM_MS, minOf(duration, base.startMs + maxLength))
                                    Trim(base.startMs, end)
                                }
                                Grab.Window -> {
                                    val start = (base.startMs + d).coerceIn(0, duration - base.lengthMs)
                                    Trim(start, start + base.lengthMs)
                                }
                            }
                            change(next)
                            scrub(if (grab == Grab.End) next.endMs - 1 else next.startMs)
                        }
                        scrub(null)
                    }
                }
                .drawBehind {
                    val border = Dimens.BorderThick.toPx()
                    val handle = HANDLE.toPx()
                    // Frames along the bar, each cropped to its cell.
                    if (thumbs.isNotEmpty()) {
                        val cell = size.width / thumbs.size
                        thumbs.forEachIndexed { j, image ->
                            val cropW = minOf(image.width, (image.height * cell / size.height).roundToInt().coerceAtLeast(1))
                            drawImage(
                                image,
                                srcOffset = IntOffset((image.width - cropW) / 2, 0),
                                srcSize = IntSize(cropW, image.height),
                                dstOffset = IntOffset((j * cell).roundToInt(), 0),
                                dstSize = IntSize(cell.roundToInt() + 1, size.height.roundToInt()),
                            )
                        }
                    }
                    val x0 = current.startMs.toFloat() / duration * size.width
                    val x1 = current.endMs.toFloat() / duration * size.width
                    // What's left out is dimmed.
                    val scrim = colors.ink.copy(alpha = 0.6f)
                    drawRect(scrim, Offset.Zero, Size(x0, size.height))
                    drawRect(scrim, Offset(x1, 0f), Size(size.width - x1, size.height))
                    // Where the preview is.
                    val p = playhead.intValue.toFloat() / duration * size.width
                    if (p in x0..x1) drawRect(colors.surface, Offset(p - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                    // The window and its handles.
                    drawRect(Palette.Yellow, Offset(x0 + border / 2, border / 2), Size(x1 - x0 - border, size.height - border), style = Stroke(border))
                    for (hx in listOf(x0, x1 - handle)) {
                        drawRoundRect(Palette.Yellow, Offset(hx, 0f), Size(handle, size.height), CornerRadius(4.dp.toPx()))
                        drawRoundRect(colors.ink, Offset(hx + 1, 1f), Size(handle - 2, size.height - 2), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                        drawRect(colors.ink, Offset(hx + handle / 2 - 1.dp.toPx(), size.height * 0.35f), Size(2.dp.toPx(), size.height * 0.3f))
                    }
                },
        )
    }
}

private const val THUMBS = 8
private const val THUMB_PX = 112
private val BAR_HEIGHT = 64.dp
private val HANDLE = 16.dp
