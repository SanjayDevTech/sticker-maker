package com.example.stickermaker.ui.screens.editor

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import com.example.stickermaker.ui.theme.Palette
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.example.stickermaker.editor.CutoutEngine
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where the image sits on the canvas: [zoom] relative to "fit", [pan] from centred. Kept out
 * of the UI state because it changes every frame of a pinch; it's read only while drawing.
 */
@Stable
class CanvasView {
    var zoom by mutableFloatStateOf(1f)
        private set
    var pan by mutableStateOf(Offset.Zero)
        private set

    /** Finger position (canvas px) while brushing, to draw the brush ring. */
    var cursor by mutableStateOf<Offset?>(null)

    /** True while the size slider is held: shows the brush ring mid-canvas. */
    var previewBrush by mutableStateOf(false)

    val isMoved get() = zoom != 1f || pan != Offset.Zero

    /** Image scale (canvas px per image px) and top-left corner for a canvas of [size]. */
    fun frame(size: Size, w: Int, h: Int): Frame {
        val s = fitScale(size, w, h) * zoom
        return Frame(s, Offset(size.width / 2 + pan.x - w * s / 2, size.height / 2 + pan.y - h * s / 2))
    }

    /** Pinch: zooms about [centroid] by [zoomChange] and moves by [panChange]. */
    fun transform(size: Size, w: Int, h: Int, centroid: Offset, zoomChange: Float, panChange: Offset) {
        val f = frame(size, w, h)
        val newZoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val newScale = f.scale * newZoom / zoom
        // Keep the image point under the fingers under them, then follow the fingers.
        val topLeft = centroid + panChange - (centroid - f.topLeft) * (newZoom / zoom)
        val newPan = topLeft - Offset(size.width / 2, size.height / 2) + Offset(w * newScale / 2, h * newScale / 2)
        // The image may slide until its edge reaches the middle, never fully away.
        zoom = newZoom
        pan = Offset(
            newPan.x.coerceIn(-w * newScale / 2, w * newScale / 2),
            newPan.y.coerceIn(-h * newScale / 2, h * newScale / 2),
        )
    }

    /** Springs back to "fit". */
    suspend fun reset() {
        val fromZoom = zoom
        val fromPan = pan
        animate(0f, 1f, animationSpec = Motion.spatial()) { t, _ ->
            zoom = max(1f, fromZoom + (1f - fromZoom) * t)
            pan = fromPan * (1f - t)
        }
        zoom = 1f
        pan = Offset.Zero
    }

    class Frame(val scale: Float, val topLeft: Offset) {
        fun toImage(p: Offset) = (p - topLeft) / scale
    }

    companion object {
        const val MAX_ZOOM = 16f
        private const val FIT_MARGIN = 0.9f
        fun fitScale(size: Size, w: Int, h: Int) = min(size.width / w, size.height / h) * FIT_MARGIN
    }
}

/**
 * The cutting board. One finger uses the tool; a second finger turns the gesture into a
 * pinch (pan + zoom) and rolls back whatever the first finger had brushed.
 */
@Composable
fun EditorCanvas(
    engine: CutoutEngine,
    view: CanvasView,
    tool: EditTool,
    erase: Boolean,
    brushPx: Int,
    onBeginStroke: (Float, Float) -> Unit,
    onExtendStroke: (Float, Float) -> Unit,
    onEndStroke: () -> Unit,
    onCancelStroke: () -> Unit,
    onFill: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val currentTool by rememberUpdatedState(tool)
    val ghost by rememberUpdatedState(!erase)
    val brush by rememberUpdatedState(brushPx)
    val callbacks by rememberUpdatedState(
        StrokeCallbacks(onBeginStroke, onExtendStroke, onEndStroke, onCancelStroke, onFill),
    )
    val w = engine.width
    val h = engine.height
    val checker = rememberChecker(colors.isDark)

    Spacer(
        modifier
            .clipToBounds()
            .pointerInput(engine, view) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val activeTool = currentTool
                    val frame = view.frame(size.toSize(), w, h)
                    val start = frame.toImage(down.position)
                    var pinching = false
                    var moved = false
                    val strokes = activeTool != EditTool.Auto
                    if (strokes) {
                        callbacks.begin(start.x, start.y)
                        view.cursor = down.position
                    }
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        if (!pinching && event.changes.count { it.pressed } > 1) {
                            pinching = true
                            if (strokes) callbacks.cancel()
                            view.cursor = null
                        }
                        if (pinching) {
                            view.transform(
                                size.toSize(), w, h,
                                event.calculateCentroid(useCurrent = true),
                                event.calculateZoom(),
                                event.calculatePan(),
                            )
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                            val d = change.position - down.position
                            if (abs(d.x) > slop || abs(d.y) > slop) moved = true
                            if (strokes) {
                                // Historical points keep fast strokes smooth.
                                change.historical.forEach {
                                    val p = frame.toImage(it.position)
                                    callbacks.extend(p.x, p.y)
                                }
                                val p = frame.toImage(change.position)
                                callbacks.extend(p.x, p.y)
                                view.cursor = change.position
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    view.cursor = null
                    if (!pinching) {
                        if (strokes) callbacks.end() else if (!moved) callbacks.fill(start.x, start.y)
                    }
                }
            }
            .drawWithCache {
                val outline = Dimens.Border.toPx()
                val ringMin = 3.dp.toPx()
                onDrawBehind {
                    engine.version // redraw on every mask change
                    val f = view.frame(size, w, h)
                    val dw = w * f.scale
                    val dh = h * f.scale
                    drawRect(checker, f.topLeft, Size(dw, dh))
                    // Big image pixels stay crisp squares once zoomed in, for pixel-exact work.
                    val quality = if (f.scale >= 3f) FilterQuality.None else FilterQuality.Low
                    translate(f.topLeft.x, f.topLeft.y) {
                        scale(f.scale, f.scale, pivot = Offset.Zero) {
                            if (ghost) drawBitmap(engine.sourceImage, w, h, GHOST_ALPHA, quality)
                            drawBitmap(engine.displayImage, w, h, 1f, quality)
                            if (currentTool == EditTool.Magic) {
                                engine.marks.forEach { drawMark(it) }
                                engine.drawingMark?.let { drawMark(it) }
                            }
                        }
                    }
                    drawRect(colors.ink, f.topLeft - Offset(outline / 2, outline / 2), Size(dw + outline, dh + outline), style = Stroke(outline))

                    val ringAt = view.cursor ?: if (view.previewBrush) center else null
                    if (ringAt != null && (currentTool != EditTool.Auto || view.previewBrush)) {
                        val r = max(ringMin, brush / 2f * f.scale)
                        drawCircle(Color.White, r, ringAt, style = Stroke(outline * 2))
                        drawCircle(Color.Black, r, ringAt, style = Stroke(outline))
                    }
                }
            },
    )
}

private class StrokeCallbacks(
    val begin: (Float, Float) -> Unit,
    val extend: (Float, Float) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
    val fill: (Float, Float) -> Unit,
)

private const val GHOST_ALPHA = 0.3f
private const val MARK_ALPHA = 0.6f

/** A Magic mark as a fat translucent scribble: lime keeps, coral removes. */
private fun DrawScope.drawMark(mark: CutoutEngine.Mark) {
    val color = if (mark.keep) Palette.Lime else Palette.Coral
    val path = Path()
    path.moveTo(mark.x(0), mark.y(0))
    if (mark.pointCount == 1) path.lineTo(mark.x(0) + 0.01f, mark.y(0))
    for (i in 1 until mark.pointCount) path.lineTo(mark.x(i), mark.y(i))
    drawPath(
        path,
        color,
        alpha = MARK_ALPHA,
        style = Stroke(width = mark.radius * 2, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawBitmap(image: ImageBitmap, w: Int, h: Int, alpha: Float, quality: FilterQuality) =
    drawImage(image, dstSize = IntSize(w, h), alpha = alpha, filterQuality = quality)

/** A repeating two-tone checker as a shader brush: one rect draw instead of hundreds. */
@Composable
private fun rememberChecker(dark: Boolean): ShaderBrush {
    val light = if (dark) Color(0xFF2A2826) else Color(0xFFFFFFFF)
    val shade = if (dark) Color(0xFF1C1B1A) else Color(0xFFE9E3D8)
    val cellPx = with(androidx.compose.ui.platform.LocalDensity.current) { 10.dp.roundToPx() }
    return remember(dark, cellPx) {
        val tile = ImageBitmap(cellPx * 2, cellPx * 2)
        val canvas = ComposeCanvas(tile)
        val paint = Paint()
        paint.color = light
        canvas.drawRect(0f, 0f, cellPx * 2f, cellPx * 2f, paint)
        paint.color = shade
        canvas.drawRect(0f, 0f, cellPx.toFloat(), cellPx.toFloat(), paint)
        canvas.drawRect(cellPx.toFloat(), cellPx.toFloat(), cellPx * 2f, cellPx * 2f, paint)
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}
