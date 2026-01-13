package com.example.stickermaker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A rounded rectangle whose edge ripples like a hand-cut sticker. Works anywhere a [Shape]
 * does: [BrutalSurface], clip, background, border.
 *
 * The wavelength is nudged so a whole number of waves fits the perimeter (no seam), and the
 * waves stay inside the bounds. Animate [phase] (0..1 = one wave) for a living edge — see
 * [rememberWobblePhase].
 */
@Immutable
data class SquigglyShape(
    val cornerRadius: Dp = 22.dp,
    val amplitude: Dp = 2.5.dp,
    val wavelength: Dp = 20.dp,
    val phase: Float = 0f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        with(density) {
            val amp = amplitude.toPx()
            val maxRadius = min(size.width, size.height) / 2f - amp
            val radius = cornerRadius.toPx().coerceIn(0f, max(0f, maxRadius))
            val base = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = amp, top = amp,
                        right = size.width - amp, bottom = size.height - amp,
                        cornerRadius = CornerRadius(radius),
                    ),
                )
            }
            val measure = PathMeasure().apply { setPath(base, forceClosed = true) }
            val length = measure.length
            if (length <= 0f) return Outline.Generic(base)

            val waves = max(1, (length / wavelength.toPx()).roundToInt())
            val samples = max(waves * 10, (length / 1.5.dp.toPx()).roundToInt())
            val path = Path()
            for (i in 0..samples) {
                val distance = length * i / samples
                val position = measure.getPosition(distance)
                val tangent = measure.getTangent(distance)
                val wave = amp * sin(2 * PI * (waves * distance / length + phase)).toFloat()
                // Normal = tangent rotated 90°.
                val x = position.x - tangent.y * wave
                val y = position.y + tangent.x * wave
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            Outline.Generic(path)
        }
}

/**
 * 0→1 looping phase for [SquigglyShape.phase]. Frozen at 0 when the user turned animations
 * off. Read it as late as possible (inside the shape) to keep recomposition local.
 */
@Composable
fun rememberWobblePhase(periodMillis: Int = 2600): State<Float> {
    if (LocalReduceMotion.current) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "wobblePhase",
    )
}
