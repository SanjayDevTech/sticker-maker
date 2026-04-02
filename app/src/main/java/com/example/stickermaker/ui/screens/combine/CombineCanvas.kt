package com.example.stickermaker.ui.screens.combine

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.stickermaker.editor.Layer
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
    selected: Layer?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppTheme.colors
    val currentLayers by rememberUpdatedState(layers)
    val currentSelected by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
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
                    val onHandle = target != null && (p0 - handleOf(target, side)).getDistance() <= handleRadius
                    if (!onHandle) {
                        // Topmost visible pixel wins; failing that, the selected layer's box.
                        target = currentLayers.lastOrNull { it.hit(p0, side) }
                            ?: currentSelected?.takeIf { it.hit(p0, side, boxOnly = true) }
                        if (target != null && target !== currentSelected) select(target.id)
                    }
                    down.consume()

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
                    // A tap on empty board clears the selection.
                    if (target == null && !pinching) select(null)
                }
            }
            .drawWithCache {
                val side = size.width
                val outline = Dimens.Border.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
                val handle = HANDLE_RADIUS.toPx()
                onDrawBehind {
                    drawRect(checker, Offset.Zero, Size(side, side))
                    for (layer in currentLayers) {
                        val s = layer.scale(side)
                        translate(layer.centerX * side, layer.centerY * side) {
                            rotate(layer.rotation, pivot = Offset.Zero) {
                                scale(if (layer.flipped) -s else s, s, pivot = Offset.Zero) {
                                    translate(-layer.bitmap.width / 2f, -layer.bitmap.height / 2f) {
                                        drawImage(
                                            layer.image,
                                            dstSize = IntSize(layer.bitmap.width, layer.bitmap.height),
                                            filterQuality = FilterQuality.Medium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (guides.vertical) drawLine(Palette.Pink, Offset(side / 2, 0f), Offset(side / 2, side), outline)
                    if (guides.horizontal) drawLine(Palette.Pink, Offset(0f, side / 2), Offset(side, side / 2), outline)

                    currentSelected?.let { layer ->
                        val hw = layer.size * side / 2
                        val hh = hw * layer.aspect
                        val corners = listOf(Offset(-hw, -hh), Offset(hw, -hh), Offset(hw, hh), Offset(-hw, hh))
                            .map { layer.fromCenter(it, side) }
                        val box = Path().apply {
                            moveTo(corners[0].x, corners[0].y)
                            corners.drop(1).forEach { lineTo(it.x, it.y) }
                            close()
                        }
                        drawPath(box, Color.White, style = Stroke(outline * 2.5f))
                        drawPath(box, colors.ink, style = Stroke(outline, pathEffect = dash))
                        val h = handleOf(layer, side)
                        drawCircle(colors.ink, handle + outline, h + Offset(outline, outline))
                        drawCircle(Palette.Yellow, handle, h)
                        drawCircle(colors.ink, handle, h, style = Stroke(outline))
                    }
                }
            },
    )
}

/** The bottom-right corner of [layer], where its scale/rotate handle sits. */
private fun handleOf(layer: Layer, side: Float): Offset {
    val hw = layer.size * side / 2
    return layer.fromCenter(Offset(hw, hw * layer.aspect), side)
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
