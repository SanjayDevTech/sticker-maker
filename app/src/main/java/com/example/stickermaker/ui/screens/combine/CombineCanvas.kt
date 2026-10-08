package com.example.stickermaker.ui.screens.combine

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.stickermaker.editor.Choreography
import com.example.stickermaker.editor.Composition
import com.example.stickermaker.editor.Gif
import com.example.stickermaker.editor.Layer
import com.example.stickermaker.editor.StickerMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import com.example.stickermaker.ui.components.rememberCheckerBrush
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/** Snap guides shown while a layer is dragged onto the centre lines. */
@Stable
class Guides {
    var vertical by mutableStateOf(false)
    var horizontal by mutableStateOf(false)
}

/**
 * The square artboard. Tap picks the topmost layer under the finger (by its visible pixels);
 * one finger drags it, the corner handle scales and rotates it, and a pinch anywhere scales,
 * rotates and moves it. Rotation snaps to right angles and the centre to the middle.
 */
@Composable
fun CombineCanvas(
    layers: List<Layer>,
    playback: Playback,
    selected: Layer?,
    onSelect: (String?) -> Unit,
    onGestureStart: () -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val currentLayers by rememberUpdatedState(layers)
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val gestureStart by rememberUpdatedState(onGestureStart)
    val gestureEnd by rememberUpdatedState(onGestureEnd)
    val guides = remember { Guides() }
    val checker = rememberCheckerBrush()

    Spacer(
        modifier
            .pointerInput(Unit) {
                val handleRadius = HANDLE_TOUCH.toPx()
                val snapPx = SNAP_FRACTION
                awaitEachGesture {
                    val side = size.width.toFloat()
                    val down = awaitFirstDown()
                    val p0 = down.position
                    var target = currentSelected
                    // The handle stays at the layer's resting place, so it's easy to grab.
                    val onHandle = target != null && (p0 - playback.handleOf(target, side)).getDistance() <= handleRadius
                    if (!onHandle) {
                        // Topmost visible pixel wins; failing that, the selected layer's box.
                        // A moving layer can be picked where it's drawn now or where it rests.
                        target = currentLayers.lastOrNull { playback.hits(it, p0, side) || playback.hits(it, p0, side, moving = false) }
                            ?: currentSelected?.takeIf { playback.hits(it, p0, side, boxOnly = true, moving = false) }
                        if (target != null && target !== currentSelected) select(target.id)
                    }
                    down.consume()
                    // A held layer stops moving until it's let go: no chasing a spinning target.
                    playback.held = target?.id
                    if (target != null) gestureStart()

                    // Raw values while the gesture runs; snapping applies to what's shown.
                    var rawX = target?.centerX ?: 0f
                    var rawY = target?.centerY ?: 0f
                    var rawRotation = target?.rotation ?: 0f
                    val startSize = target?.size ?: 0f
                    val startRotation = rawRotation
                    val handleStart = target?.let { p0 - Offset(it.centerX * side, it.centerY * side) }
                    var pinching = false
                    var moved = false

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val layer = target
                        if (event.changes.count { it.pressed } > 1) pinching = true
                        if (layer != null) {
                            if (pinching) {
                                val c = event.calculateCentroid(useCurrent = true)
                                val zoom = event.calculateZoom()
                                val turn = event.calculateRotation()
                                val pan = event.calculatePan()
                                // Scale/rotate about the fingers' centre, then follow them.
                                val center = Offset(rawX * side, rawY * side)
                                val rel = rotate(center - c, turn) * zoom
                                val next = c + rel + pan
                                rawX = next.x / side
                                rawY = next.y / side
                                rawRotation += turn
                                layer.size = (layer.size * zoom).coerceIn(MIN_SIZE, MAX_SIZE)
                            } else if (onHandle && handleStart != null) {
                                val p = event.changes.first { it.id == down.id }.position
                                val v = p - Offset(layer.centerX * side, layer.centerY * side)
                                val ratio = v.getDistance() / handleStart.getDistance().coerceAtLeast(1f)
                                layer.size = (startSize * ratio).coerceIn(MIN_SIZE, MAX_SIZE)
                                rawRotation = startRotation + degrees(v) - degrees(handleStart)
                            } else {
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change != null) {
                                    val d = change.position - change.previousPosition
                                    if ((change.position - p0).getDistance() > viewConfiguration.touchSlop) moved = true
                                    if (moved) {
                                        rawX += d.x / side
                                        rawY += d.y / side
                                    }
                                }
                            }
                            val snapX = abs(rawX - 0.5f) < snapPx
                            val snapY = abs(rawY - 0.5f) < snapPx
                            layer.centerX = if (snapX) 0.5f else rawX
                            layer.centerY = if (snapY) 0.5f else rawY
                            layer.rotation = snapRotation(rawRotation)
                            guides.vertical = snapX && (moved || pinching)
                            guides.horizontal = snapY && (moved || pinching)
                        }
                        event.changes.forEach { it.consume() }
                    }
                    guides.vertical = false
                    guides.horizontal = false
                    playback.held = null
                    if (target != null) gestureEnd()
                    // A tap on empty board clears the selection.
                    if (target == null && !pinching) select(null)
                }
            }
            .drawWithCache {
                val side = size.width
                val outline = Dimens.Border.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
                val handle = HANDLE_RADIUS.toPx()
                val matrix = Matrix()
                val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                onDrawBehind {
                    drawRect(checker, Offset.Zero, Size(side, side))
                    // The same maths as the saved sticker: placement, then the layer's motion at
                    // this moment; a GIF layer shows its current frame.
                    drawIntoCanvas { canvas ->
                        for (layer in currentLayers) {
                            playback.matrixOf(layer, side, matrix)
                            val outlined = layer.drawn
                            val bitmap = when {
                                layer.isGif -> layer.frame?.asAndroidBitmap() ?: layer.bitmap
                                outlined != null -> outlined.bitmap.also { matrix.preTranslate(-outlined.pad.toFloat(), -outlined.pad.toFloat()) }
                                else -> layer.bitmap
                            }
                            canvas.nativeCanvas.drawBitmap(bitmap, matrix, paint)
                        }
                    }
                    if (guides.vertical) drawLine(Palette.Pink, Offset(side / 2, 0f), Offset(side / 2, side), outline)
                    if (guides.horizontal) drawLine(Palette.Pink, Offset(0f, side / 2), Offset(side, side / 2), outline)

                    currentSelected?.let { layer ->
                        // The box marks where the layer rests; its motion plays inside it.
                        val w = layer.bitmap.width.toFloat()
                        val h = layer.bitmap.height.toFloat()
                        val corners = listOf(0f to 0f, w to 0f, w to h, 0f to h).map { (x, y) -> playback.map(layer, side, x, y, moving = false) }
                        val box = Path().apply {
                            moveTo(corners[0].x, corners[0].y)
                            corners.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(box, Color.White, style = Stroke(outline * 2.5f))
                        drawPath(box, colors.ink, style = Stroke(outline, pathEffect = dash))
                        val at = playback.handleOf(layer, side)
                        drawCircle(colors.ink, handle + outline, at + Offset(outline, outline))
                        drawCircle(Palette.Yellow, handle, at)
                        drawCircle(colors.ink, handle, at, style = Stroke(outline))
                    }
                }
            },
    )
}

/**
 * The combine canvas's clock: plays the layers' loops together, the way the saved sticker
 * will ([Choreography]), stepping on the same 40 ms frames. [timeMs] and the GIF layers'
 * frames are read only while drawing.
 */
@Stable
class Playback {
    var timeMs by mutableIntStateOf(0)
    var choreography by mutableStateOf(Choreography(emptyList(), emptyList()))

    /** The layer under a finger: drawn at rest until let go. */
    var held by mutableStateOf<String?>(null)

    private val scratch = Matrix()
    private val inverse = Matrix()
    private val point = FloatArray(2)

    /**
     * Where [layer] is drawn on an artboard [side] px wide: placement, then its motion now
     * (unless [moving] is false, or it's being held).
     */
    fun matrixOf(layer: Layer, side: Float, into: Matrix, moving: Boolean = true): Matrix {
        val w = layer.bitmap.width
        val h = layer.bitmap.height
        Composition.placement(w, h, layer.centerX, layer.centerY, layer.size, layer.rotation, layer.flipped, side, into)
        val motion = layer.motion
        if (moving && layer.id != held && !layer.isGif && motion != StickerMotion.None) {
            val local = choreography.localMs(motion.periodMs, timeMs) / motion.periodMs
            Composition.applyMotion(motion.pose(local), w, h, side, into)
        }
        return into
    }

    /** Layer pixel ([x], [y]) on the artboard, as drawn now (or at rest). Main thread. */
    fun map(layer: Layer, side: Float, x: Float, y: Float, moving: Boolean = true): Offset {
        point[0] = x
        point[1] = y
        matrixOf(layer, side, scratch, moving).mapPoints(point)
        return Offset(point[0], point[1])
    }

    /**
     * The scale/rotate handle: the bottom-right of where the layer rests (its pixels mirror
     * when flipped). It never moves with the motion: a spinning handle can't be grabbed.
     */
    fun handleOf(layer: Layer, side: Float): Offset =
        map(layer, side, if (layer.flipped) 0f else layer.bitmap.width.toFloat(), layer.bitmap.height.toFloat(), moving = false)

    /** True if [p] lands on a visible pixel of [layer] as drawn now, or at rest (or anywhere in its box). Main thread. */
    fun hits(layer: Layer, p: Offset, side: Float, boxOnly: Boolean = false, moving: Boolean = true): Boolean {
        if (!matrixOf(layer, side, scratch, moving).invert(inverse)) return false
        point[0] = p.x
        point[1] = p.y
        inverse.mapPoints(point)
        return layer.hitsPixel(point[0], point[1], boxOnly)
    }
}

/** Runs [Playback] for [layers]: restarts when a GIF or motion changes the timing. */
@Composable
fun rememberPlayback(layers: List<Layer>): Playback {
    val playback = remember { Playback() }
    val choreography by remember(layers) {
        derivedStateOf {
            Choreography(
                gifLengthsMs = layers.mapNotNull { it.trim?.lengthMs },
                motionPeriodsMs = layers.filter { !it.isGif && it.motion != StickerMotion.None }.map { it.motion.periodMs },
            )
        }
    }
    LaunchedEffect(layers, choreography) {
        playback.choreography = choreography
        playback.timeMs = 0
        if (!choreography.animated) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            // Per GIF layer: a cursor playing forward and three buffers (one shown, one maybe
            // still being drawn, one filling).
            val gifs = layers.filter { it.isGif }
            val cursors = arrayOfNulls<Gif.Cursor>(gifs.size)
            val buffers = gifs.map { l -> List(3) { Bitmap.createBitmap(l.bitmap.width, l.bitmap.height, Bitmap.Config.ARGB_8888) } }
            val images = buffers.map { list -> list.map(Bitmap::asImageBitmap) }
            val slots = IntArray(gifs.size)
            val start = SystemClock.uptimeMillis()
            while (isActive) {
                val elapsed = SystemClock.uptimeMillis() - start
                val t = ((elapsed / StickerMotion.FRAME_MS) * StickerMotion.FRAME_MS % choreography.durationMs).toInt()
                gifs.forEachIndexed { i, layer ->
                    val gif = requireNotNull(layer.gif)
                    val trim = requireNotNull(layer.trim)
                    val index = gif.frameAt(trim.startMs + choreography.localMs(trim.lengthMs, t).toInt())
                    val c = cursors[i]
                    if (c != null && c.index == index && layer.frame != null) return@forEachIndexed
                    val cursor = c?.takeIf { it.index <= index } ?: gif.Cursor(index).also { cursors[i] = it }
                    while (cursor.index < index) cursor.advance()
                    buffers[i][slots[i]].setPixels(cursor.canvas, 0, gif.width, 0, 0, gif.width, gif.height)
                    layer.frame = images[i][slots[i]]
                    slots[i] = (slots[i] + 1) % 3
                }
                playback.timeMs = t
                delay(StickerMotion.FRAME_MS - (SystemClock.uptimeMillis() - start - elapsed))
            }
        }
    }
    return playback
}

private fun rotate(v: Offset, degrees: Float): Offset {
    val r = degrees * PI / 180
    return Offset(
        (v.x * kotlin.math.cos(r) - v.y * kotlin.math.sin(r)).toFloat(),
        (v.x * kotlin.math.sin(r) + v.y * kotlin.math.cos(r)).toFloat(),
    )
}

private fun degrees(v: Offset) = (atan2(v.y, v.x) * 180 / PI).toFloat()

/** Within a few degrees of a right angle reads as "straight": snap to it. */
private fun snapRotation(raw: Float): Float {
    val nearest = (raw / 90f).roundToInt() * 90f
    return if (abs(raw - nearest) < SNAP_DEGREES) nearest else raw
}

private const val SNAP_DEGREES = 4f
private const val SNAP_FRACTION = 0.015f
private const val MIN_SIZE = 0.06f
private const val MAX_SIZE = 3f
private val HANDLE_RADIUS = 11.dp
private val HANDLE_TOUCH = 32.dp
