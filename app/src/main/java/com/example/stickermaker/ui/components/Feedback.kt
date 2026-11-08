package com.example.stickermaker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.LocalReduceMotion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

/**
 * Designed empty state: a wobbling sticker-cut tile with an icon, a headline, one line of
 * help and (optionally) the action that fills the space.
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Mint,
    action: (@Composable () -> Unit)? = null,
) {
    val phase = rememberWobblePhase(periodMillis = 4000)
    val tileShape = remember(phase) {
        WobblingShape(SquigglyShape(cornerRadius = 30.dp, amplitude = 3.dp, wavelength = 18.dp), phase)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.Space24, horizontal = Dimens.Space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        BrutalSurface(
            modifier = Modifier
                .size(104.dp)
                .graphicsLayer { rotationZ = -6f },
            shape = tileShape,
            color = color,
            shadow = Dimens.ShadowM,
            borderWidth = Dimens.BorderThick,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Dimens.Space8),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        if (action != null) Box(Modifier.padding(top = Dimens.Space8)) { action() }
    }
}

/**
 * Placeholder block shown while content loads, instead of a spinner. Outlined like the real
 * thing so the layout doesn't jump; the fill breathes unless animations are off. With a
 * [progress] (0..1, or null while it isn't known) it also shows a [LoadRing] in the middle.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.Medium,
    progress: State<Float?>? = null,
) {
    val colors = AppTheme.colors
    val pulse: State<Float> = if (LocalReduceMotion.current) {
        remember { mutableFloatStateOf(0.5f) }
    } else {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        )
    }
    // Pulse is read only inside the draw block: the skeleton redraws, never recomposes.
    Box(
        modifier
            .brutal(shape, fill = colors.surface, border = colors.ink.copy(alpha = 0.25f))
            .drawWithCache {
                val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
                onDrawBehind { drawPath(path, colors.ink.copy(alpha = 0.04f + 0.06f * pulse.value)) }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (progress != null) {
            // Recomposes only when the progress becomes known, not on every step.
            val known by remember(progress) { derivedStateOf { progress.value != null } }
            if (known) LoadRing({ progress.value ?: 0f })
        }
    }
}

/** versionName from the installed package (BuildConfig stays disabled). */
@Composable
fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
}

/**
 * How far something has loaded, as a brutal dial: an outlined disc on a hard shadow, with a
 * lime wedge filling clockwise from the top. [progress] (0..1) is read only while drawing, so
 * a fast-changing value redraws the dial without recomposing. The shadow stays inside [size].
 */
@Composable
fun LoadRing(progress: () -> Float, modifier: Modifier = Modifier, size: Dp = LoadRingSize) {
    val colors = AppTheme.colors
    Box(
        modifier
            .size(size)
            .drawBehind {
                val border = Dimens.Border.toPx()
                val shadow = Dimens.ShadowS.toPx()
                val d = this.size.minDimension - shadow
                val r = d / 2
                val c = Offset(r, r)
                drawCircle(colors.ink, r, c + Offset(shadow, shadow))
                drawCircle(colors.surface, r, c)
                val sweep = 360f * progress().coerceIn(0f, 1f)
                if (sweep > 0f) {
                    val inset = border
                    val wedge = Size(d - 2 * inset, d - 2 * inset)
                    drawArc(Palette.Lime, -90f, sweep, useCenter = true, topLeft = Offset(inset, inset), size = wedge)
                    if (sweep < 360f) {
                        drawArc(colors.ink, -90f, sweep, useCenter = true, topLeft = Offset(inset, inset), size = wedge, style = Stroke(border))
                    }
                }
                drawCircle(colors.ink, r - border / 2, c, style = Stroke(border))
            },
    )
}

private val LoadRingSize = 44.dp
