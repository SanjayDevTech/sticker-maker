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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
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
 * thing so the layout doesn't jump; the fill breathes unless animations are off.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.Medium,
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
    )
}

/** versionName from the installed package (BuildConfig stays disabled). */
@Composable
fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
}
