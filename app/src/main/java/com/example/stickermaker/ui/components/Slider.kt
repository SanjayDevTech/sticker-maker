package com.example.stickermaker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.stickermaker.R
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion
import com.example.stickermaker.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A chunky 0..1 slider with − / + tiles for exact steps.
 *
 * - Dragging moves the value *from where it was*, so touching the track never makes it jump;
 *   a tap without a drag still jumps to that spot. Vertical drags are left to scrolling.
 * - While held, the thumb sinks into its shadow and a tag above it shows [valueLabel].
 * - [onDecrease] / [onIncrease] nudge by one unit of whatever the caller measures (1 px, 1 %).
 *
 * [onActiveChange] reports when a finger is on the track (the editor previews the brush then).
 */
@Composable
fun BrutalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Pink,
    onActiveChange: (Boolean) -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
    ) {
        IconTile(R.drawable.ic_remove, "Decrease", onDecrease, size = STEP_TILE, enabled = value > 0f)
        Track(value, onValueChange, valueLabel, color, onActiveChange, Modifier.weight(1f))
        IconTile(R.drawable.ic_add, "Increase", onIncrease, size = STEP_TILE, enabled = value < 1f)
    }
}

@Composable
private fun Track(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    color: Color,
    onActiveChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val colors = AppTheme.colors
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val active by rememberUpdatedState(onActiveChange)
    val scope = rememberCoroutineScope()
    // 0 = resting, 1 = held: the thumb sinks into its shadow and the bubble pops up.
    val press = remember { Animatable(0f) }
    // The bubble fades on its own non-overshooting spec (press bounces on release).
    val bubble = remember { Animatable(0f) }
    var bubbleWidth by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier
            .height(THUMB + Dimens.ShadowS + Dimens.Space8)
            .progressSemantics(value)
            .semantics {
                stateDescription = valueLabel
                setProgress { change(it.coerceIn(0f, 1f)); true }
            },
    ) {
        val density = LocalDensity.current
        val thumbPx = with(density) { THUMB.toPx() }
        val shadowPx = with(density) { Dimens.ShadowS.toPx() }
        val travel = constraints.maxWidth - thumbPx - shadowPx
        fun valueAt(x: Float) = ((x - thumbPx / 2) / travel).coerceIn(0f, 1f)

        Box(
            Modifier
                .fillMaxWidth()
                .height(THUMB + Dimens.ShadowS + Dimens.Space8)
                .pointerInput(travel) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        active(true)
                        scope.launch { press.animateTo(1f, Motion.pressDown()) }
                        scope.launch { bubble.animateTo(1f, Motion.effects()) }
                        var dragging = false
                        var cancelled = false
                        var originX = 0f
                        var originValue = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val d = c.position - down.position
                            if (!dragging) {
                                // Someone else (a scrolling list) took it, or it's a vertical swipe.
                                if (c.isConsumed || abs(d.y) > slop && abs(d.y) > abs(d.x)) {
                                    cancelled = true
                                    break
                                }
                                if (abs(d.x) > slop) {
                                    dragging = true
                                    // Measure from here so crossing the slop doesn't jump.
                                    originX = c.position.x
                                    originValue = currentValue
                                }
                            }
                            if (dragging) {
                                change((originValue + (c.position.x - originX) / travel).coerceIn(0f, 1f))
                                c.consume()
                            }
                        }
                        if (!dragging && !cancelled) change(valueAt(down.position.x))
                        active(false)
                        scope.launch {
                            press.animateTo(1f, Motion.pressDown())
                            press.animateTo(0f, Motion.pressRelease())
                        }
                        scope.launch { bubble.animateTo(0f, Motion.effects()) }
                    }
                }
                .drawWithCache {
                    val border = Dimens.Border.toPx()
                    val trackH = 18.dp.toPx()
                    val top = (size.height - shadowPx - trackH) / 2
                    val left = thumbPx / 4
                    val trackW = size.width - shadowPx - thumbPx / 2
                    val radius = CornerRadius(trackH / 2)
                    val thumbTop = (size.height - shadowPx - thumbPx) / 2
                    val thumbRadius = CornerRadius(Dimens.CornerS.toPx())
                    onDrawBehind {
                        val x = travel * currentValue
                        val sink = shadowPx * press.value.coerceIn(0f, 1f)
                        // Track and fill.
                        drawRoundRect(colors.surface, Offset(left, top), Size(trackW, trackH), radius)
                        drawRoundRect(color, Offset(left, top), Size((x + thumbPx / 2 - left).coerceAtLeast(trackH), trackH), radius)
                        drawRoundRect(colors.ink, Offset(left + border / 2, top + border / 2), Size(trackW - border, trackH - border), radius, style = Stroke(border))
                        // Thumb: hard shadow, then the block, offset into the shadow while held.
                        drawRoundRect(colors.ink, Offset(x + shadowPx, thumbTop + shadowPx), Size(thumbPx, thumbPx), thumbRadius)
                        drawRoundRect(color, Offset(x + sink, thumbTop + sink), Size(thumbPx, thumbPx), thumbRadius)
                        drawRoundRect(
                            colors.ink,
                            Offset(x + sink + border / 2, thumbTop + sink + border / 2),
                            Size(thumbPx - border, thumbPx - border),
                            thumbRadius,
                            style = Stroke(border),
                        )
                    }
                },
        )

        // The value, above the thumb while it's held; popped in on the layer, so no recomposition.
        StickerTag(
            valueLabel,
            Palette.Yellow,
            rotation = -4f,
            modifier = Modifier
                .align(Alignment.TopStart)
                .onSizeChanged { bubbleWidth = it.width }
                .offset {
                    val centre = travel * currentValue + thumbPx / 2
                    IntOffset((centre - bubbleWidth / 2f).roundToInt(), -BUBBLE_LIFT.roundToPx())
                }
                .graphicsLayer {
                    val p = bubble.value
                    alpha = p
                    scaleX = 0.6f + 0.4f * p
                    scaleY = 0.6f + 0.4f * p
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
        )
    }
}

private val THUMB = 30.dp
private val STEP_TILE = 36.dp
private val BUBBLE_LIFT = 34.dp
