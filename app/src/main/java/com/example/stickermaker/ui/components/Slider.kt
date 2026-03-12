package com.example.stickermaker.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlin.math.roundToInt

/**
 * A chunky 0..1 slider: an outlined pill track filled with [color] up to a square brutal
 * thumb. Tap anywhere to jump, drag to scrub. [onActiveChange] reports when a finger is on it
 * (the editor uses that to preview the brush).
 */
@Composable
fun BrutalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Pink,
    onActiveChange: (Boolean) -> Unit = {},
) {
    val colors = AppTheme.colors
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    val active by rememberUpdatedState(onActiveChange)
    val thumb = 30.dp
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(thumb + Dimens.ShadowS + Dimens.Space8)
            .progressSemantics(value)
            .semantics { setProgress { change(it.coerceIn(0f, 1f)); true } },
    ) {
        val density = LocalDensity.current
        val thumbPx = with(density) { thumb.toPx() }
        val travel = constraints.maxWidth - thumbPx - with(density) { Dimens.ShadowS.toPx() }
        fun valueAt(x: Float) = ((x - thumbPx / 2) / travel).coerceIn(0f, 1f)

        Box(
            Modifier
                .fillMaxWidth()
                .height(thumb + Dimens.ShadowS + Dimens.Space8)
                .pointerInput(travel) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        active(true)
                        change(valueAt(down.position.x))
                        down.consume()
                        horizontalDrag(down.id) { drag ->
                            change(valueAt(drag.position.x))
                            drag.consume()
                        }
                        active(false)
                    }
                }
                .drawWithCache {
                    val border = Dimens.Border.toPx()
                    val trackH = 18.dp.toPx()
                    val top = (size.height - Dimens.ShadowS.toPx() - trackH) / 2
                    val left = thumbPx / 4
                    val trackW = size.width - Dimens.ShadowS.toPx() - thumbPx / 2
                    val radius = CornerRadius(trackH / 2)
                    onDrawBehind {
                        val fillW = thumbPx / 2 + travel * currentValue - left
                        drawRoundRect(colors.surface, Offset(left, top), Size(trackW, trackH), radius)
                        drawRoundRect(color, Offset(left, top), Size(fillW.coerceAtLeast(trackH), trackH), radius)
                        drawRoundRect(
                            colors.ink,
                            Offset(left + border / 2, top + border / 2),
                            Size(trackW - border, trackH - border),
                            radius,
                            style = Stroke(border),
                        )
                    }
                },
        )
        BrutalSurface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset((travel * currentValue).roundToInt(), -(Dimens.ShadowS.toPx() / 2).roundToInt()) }
                .size(thumb),
            shape = Shapes.Small,
            color = color,
            shadow = Dimens.ShadowS,
        ) {}
    }
}
