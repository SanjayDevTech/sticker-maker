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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stickermaker.data.Trim
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.editor.Animation
import com.example.stickermaker.editor.StickerMotion
import com.example.stickermaker.editor.Timeline
import com.example.stickermaker.editor.VideoFrames
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.checkerboard
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
fun GifPlayer(gif: Animation, trim: Trim, scrubMs: Int?, playhead: MutableIntState, modifier: Modifier = Modifier) {
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
                val cursor = gif.cursor(timeline.frames[0])
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
    gif: Animation,
    trim: Trim,
    onTrim: (Trim) -> Unit,
    onScrub: (Int?) -> Unit,
    playhead: MutableIntState,
) {
    val colors = AppTheme.colors
    val tooLong = gif.durationMs > WhatsAppLimits.ANIMATED_MAX_MS
    val scrolls = gif.durationMs > VISIBLE_MS
    val what = if (gif is VideoFrames) "video" else "GIF"
    val loaded = remember(gif) { gif.loadedFlow() }.collectAsStateWithLifecycle()
    // Whole seconds, so the help line recomposes once a second rather than every frame.
    val loadedSeconds by remember { derivedStateOf { loaded.value.totalMs / 1000 } }
    val finished by remember { derivedStateOf { loaded.value.finished } }
    val pick = if (scrolls) "Slide the window, or drag the frames to scroll." else "Slide the window to pick the part to keep."
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
        TrimBar(gif, trim, onTrim, onScrub, playhead, loaded)
        Text(
            (if (!finished) {
                "Loading the video: $loadedSeconds of ${gif.durationMs / 1000} s ready, what's on screen first. Trim within the ready parts."
            } else if (loadedSeconds < gif.durationMs / 1000) {
                "Couldn't read all of this video; trim within the parts that loaded."
            } else if (gif is VideoFrames && gif.durationMs >= VideoFrames.MAX_MS) {
                "Here's the video's first minute; a sticker plays up to 10 s. $pick"
            } else if (tooLong) {
                "This $what runs ${seconds(gif.durationMs)}; a sticker plays up to 10 s. $pick"
            } else {
                "Drag the ends to trim. A $what isn't cut out: the whole frame becomes the sticker."
            }) + if (gif is VideoFrames && gif.hdrUnconverted) {
                " This phone can't convert its HDR colours, so they may look a little flat."
            } else {
                ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
    }
}

private fun seconds(ms: Int) = "%.1f s".format(ms / 1000f)

/** Which parts of [this]'s frames are ready: a video may still be loading, a GIF always is. */
private fun Animation.loadedFlow(): StateFlow<VideoFrames.Loaded> =
    (this as? VideoFrames)?.loaded
        ?: MutableStateFlow(VideoFrames.Loaded(listOf(VideoFrames.Loaded.Span(0, durationMs)), finished = true))

private enum class Grab { Start, End, Window, Scroll }

/**
 * The frames with the kept part as a window: drag its ends to resize, drag inside to slide
 * it, tap elsewhere to move it there. The window never exceeds WhatsApp's 10 s, nor leaves the
 * ready stretch it's in ([loaded]; the rest is hatched).
 *
 * The bar shows at most [VISIBLE_MS] at once, so even a 3 s window stays wide enough to grab
 * on a long video. Longer ones scroll: drag the frames, or hold a dragged end near an edge.
 * A line underneath shows where the view is in the whole video. A video loads what's in
 * view first ([VideoFrames.focus]).
 */
@Composable
private fun TrimBar(
    gif: Animation,
    trim: Trim,
    onTrim: (Trim) -> Unit,
    onScrub: (Int?) -> Unit,
    playhead: MutableIntState,
    loaded: State<VideoFrames.Loaded>,
) {
    val colors = AppTheme.colors
    val duration = gif.durationMs
    val visible = minOf(duration, VISIBLE_MS)
    val maxLength = minOf(duration, WhatsAppLimits.ANIMATED_MAX_MS)
    val current by rememberUpdatedState(trim)
    val change by rememberUpdatedState(onTrim)
    val scrub by rememberUpdatedState(onScrub)
    // The ms at the bar's left edge; starts with the window in the middle of the view.
    val viewStart = remember(gif) {
        mutableIntStateOf((trim.startMs - (visible - trim.lengthMs) / 2).coerceIn(0, duration - visible))
    }

    if (gif is VideoFrames) {
        LaunchedEffect(gif) {
            snapshotFlow { viewStart.intValue }.collect { gif.focus(it, it + visible) }
        }
        DisposableEffect(gif) { onDispose { gif.unfocus() } }
    }

    // A frame every VISIBLE_THUMBS-th of the view, each appearing once its frame has loaded.
    val thumbMs = visible / VISIBLE_THUMBS
    val thumbCount = (duration + thumbMs - 1) / thumbMs
    val thumbs by produceState<List<ImageBitmap?>>(emptyList(), gif) {
        val made = arrayOfNulls<ImageBitmap>(thumbCount)
        gif.loadedFlow().collect { ready ->
            var changed = false
            for (j in 0 until thumbCount) {
                val at = minOf(duration - 1, j * thumbMs + thumbMs / 2)
                if (made[j] != null || ready.spanAt(at) == null) continue
                made[j] = withContext(Dispatchers.Default) {
                    val frame = gif.frame(gif.frameAt(at))
                    val bitmap = Bitmap.createBitmap(frame, gif.width, gif.height, Bitmap.Config.ARGB_8888)
                    val h = THUMB_PX
                    val w = maxOf(1, h * gif.width / gif.height)
                    Bitmap.createScaledBitmap(bitmap, w, h, true).also { if (it !== bitmap) bitmap.recycle() }.asImageBitmap()
                }
                changed = true
            }
            if (changed) value = made.toList()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
        BrutalSurface(Modifier.fillMaxWidth().padding(end = Dimens.ShadowS), shape = Shapes.Small, shadow = Dimens.ShadowS) {
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    // The handles sit by the screen edges, where a drag would otherwise go back.
                    .systemGestureExclusion()
                    .pointerInput(gif) {
                        val edgeSlop = 28.dp.toPx()
                        val autoScrollEdge = AUTO_SCROLL_EDGE.toPx()
                        fun msAt(x: Float) = viewStart.intValue + (x / size.width * visible).roundToInt()
                        fun xOf(ms: Int) = (ms - viewStart.intValue).toFloat() / visible * size.width
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val base = current
                            val ready = loaded.value
                            // The ready stretch the window is in bounds every move.
                            val span = ready.spanAt(base.startMs) ?: VideoFrames.Loaded.Span(0, duration)
                            val x0 = xOf(base.startMs)
                            val x1 = xOf(base.endMs)
                            val x = down.position.x
                            val grab = when {
                                abs(x - x0) <= edgeSlop && abs(x - x0) <= abs(x - x1) -> Grab.Start
                                abs(x - x1) <= edgeSlop -> Grab.End
                                x in x0..x1 -> Grab.Window
                                else -> Grab.Scroll
                            }
                            down.consume()
                            val startView = viewStart.intValue
                            val downMs = msAt(x)
                            var lastX = x
                            var moved = false

                            fun apply() {
                                if (grab == Grab.Scroll) {
                                    val dMs = ((lastX - x) / size.width * visible).roundToInt()
                                    viewStart.intValue = (startView - dMs).coerceIn(0, duration - visible)
                                    return
                                }
                                val d = msAt(lastX) - downMs
                                val next = when (grab) {
                                    Grab.Start -> {
                                        val lo = maxOf(span.fromMs, base.endMs - maxLength)
                                        Trim((base.startMs + d).coerceIn(lo, maxOf(lo, base.endMs - MIN_TRIM_MS)), base.endMs)
                                    }
                                    Grab.End -> {
                                        val lo = base.startMs + MIN_TRIM_MS
                                        Trim(base.startMs, (base.endMs + d).coerceIn(lo, maxOf(lo, minOf(span.toMs, base.startMs + maxLength))))
                                    }
                                    else -> {
                                        val start = (base.startMs + d).coerceIn(span.fromMs, maxOf(span.fromMs, span.toMs - base.lengthMs))
                                        Trim(start, start + base.lengthMs)
                                    }
                                }
                                change(next)
                                scrub(if (grab == Grab.End) next.endMs - 1 else next.startMs)
                            }

                            while (true) {
                                // Ticks while the finger rests, so holding by an edge keeps scrolling.
                                val event = withTimeoutOrNull(AUTO_SCROLL_TICK_MS) { awaitPointerEvent() }
                                if (event != null) {
                                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    if (abs(c.position.x - x) > viewConfiguration.touchSlop) moved = true
                                    lastX = c.position.x
                                } else if (grab != Grab.Scroll && moved) {
                                    val step = (visible * AUTO_SCROLL_TICK_MS / AUTO_SCROLL_VIEW_MS).toInt()
                                    val shift = when {
                                        lastX < autoScrollEdge -> -step
                                        lastX > size.width - autoScrollEdge -> step
                                        else -> 0
                                    }
                                    if (shift == 0) continue
                                    viewStart.intValue = (viewStart.intValue + shift).coerceIn(0, duration - visible)
                                } else {
                                    continue
                                }
                                if (moved) apply()
                            }
                            if (grab == Grab.Scroll && !moved) {
                                // A tap: move the window there, if that part is ready.
                                val at = msAt(x)
                                val target = loaded.value.spanAt(at)
                                if (target != null && target.toMs - target.fromMs >= base.lengthMs) {
                                    val start = (at - base.lengthMs / 2).coerceIn(target.fromMs, target.toMs - base.lengthMs)
                                    change(Trim(start, start + base.lengthMs))
                                }
                            }
                            scrub(null)
                        }
                    }
                    .drawBehind {
                        val border = Dimens.BorderThick.toPx()
                        val handle = HANDLE.toPx()
                        val view = viewStart.intValue
                        fun xOf(ms: Int) = (ms - view).toFloat() / visible * size.width
                        clipRect {
                            // Frames along the bar, each cropped to its cell.
                            val cell = size.width / VISIBLE_THUMBS
                            thumbs.forEachIndexed { j, image ->
                                if (image == null) return@forEachIndexed
                                val left = xOf(j * thumbMs)
                                if (left > size.width || left + cell < 0) return@forEachIndexed
                                val cropW = minOf(image.width, (image.height * cell / size.height).roundToInt().coerceAtLeast(1))
                                drawImage(
                                    image,
                                    srcOffset = IntOffset((image.width - cropW) / 2, 0),
                                    srcSize = IntSize(cropW, image.height),
                                    dstOffset = IntOffset(left.roundToInt(), 0),
                                    dstSize = IntSize(cell.roundToInt() + 1, size.height.roundToInt()),
                                )
                            }
                            val x0 = xOf(current.startMs)
                            val x1 = xOf(current.endMs)
                            // What's left out is dimmed.
                            val scrim = colors.ink.copy(alpha = 0.6f)
                            drawRect(scrim, Offset.Zero, Size(x0.coerceAtLeast(0f), size.height))
                            if (x1 < size.width) drawRect(scrim, Offset(x1, 0f), Size(size.width - x1, size.height))
                            // What's still loading: hatched.
                            var from = 0
                            for (span in loaded.value.spans + VideoFrames.Loaded.Span(duration, duration)) {
                                if (span.fromMs > from) hatch(xOf(from), xOf(span.fromMs), colors.surface, colors.inkMuted)
                                from = span.toMs
                            }
                            // Where the preview is.
                            val p = xOf(playhead.intValue)
                            if (p in x0..x1) drawRect(colors.surface, Offset(p - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                            // The window and its handles.
                            drawRect(Palette.Yellow, Offset(x0 + border / 2, border / 2), Size(x1 - x0 - border, size.height - border), style = Stroke(border))
                            for (hx in listOf(x0, x1 - handle)) {
                                drawRoundRect(Palette.Yellow, Offset(hx, 0f), Size(handle, size.height), CornerRadius(4.dp.toPx()))
                                drawRoundRect(colors.ink, Offset(hx + 1, 1f), Size(handle - 2, size.height - 2), CornerRadius(4.dp.toPx()), style = Stroke(2.dp.toPx()))
                                drawRect(colors.ink, Offset(hx + handle / 2 - 1.dp.toPx(), size.height * 0.35f), Size(2.dp.toPx(), size.height * 0.3f))
                            }
                        }
                    },
            )
        }
        if (duration > visible) {
            // The whole video: what's in view, and where the window is.
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .padding(end = Dimens.ShadowS)
                    .height(OVERVIEW_HEIGHT)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2)
                        fun xOf(ms: Int) = ms.toFloat() / duration * size.width
                        drawRoundRect(colors.ink.copy(alpha = 0.15f), cornerRadius = r)
                        val v = viewStart.intValue
                        drawRoundRect(colors.ink.copy(alpha = 0.45f), Offset(xOf(v), 0f), Size(xOf(visible), size.height), r)
                        val a = xOf(current.startMs)
                        drawRoundRect(Palette.Yellow, Offset(a, 0f), Size(maxOf(xOf(current.lengthMs), size.height), size.height), r)
                        drawRoundRect(colors.ink, Offset(a, 0f), Size(maxOf(xOf(current.lengthMs), size.height), size.height), r, style = Stroke(1.dp.toPx()))
                    },
            )
        }
    }
}

/** Diagonal hatching over [x0, x1) of the bar: frames not loaded yet. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.hatch(x0: Float, x1: Float, fill: Color, line: Color) {
    val left = x0.coerceAtLeast(0f)
    val right = x1.coerceAtMost(size.width)
    if (right <= left) return
    drawRect(fill, Offset(left, 0f), Size(right - left, size.height))
    val gap = HATCH.toPx()
    clipRect(left = left, right = right) {
        // Lines on a fixed grid, so they don't crawl as a stretch grows.
        var hx = left - size.height - (left % gap)
        while (hx < right) {
            drawLine(line, Offset(hx, size.height), Offset(hx + size.height, 0f), strokeWidth = 2.dp.toPx())
            hx += gap
        }
    }
}

/** The most the trim bar shows at once; longer GIFs and videos scroll. */
private const val VISIBLE_MS = 20_000
private const val VISIBLE_THUMBS = 8
private const val THUMB_PX = 112
private val BAR_HEIGHT = 64.dp
private val OVERVIEW_HEIGHT = 6.dp
private val HANDLE = 16.dp
private val HATCH = 12.dp

/** Holding a dragged end this close to an edge scrolls, a whole view per [AUTO_SCROLL_VIEW_MS]. */
private val AUTO_SCROLL_EDGE = 32.dp
private const val AUTO_SCROLL_TICK_MS = 16L
private const val AUTO_SCROLL_VIEW_MS = 2_000L
