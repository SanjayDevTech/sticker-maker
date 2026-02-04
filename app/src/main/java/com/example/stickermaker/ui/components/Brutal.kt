package com.example.stickermaker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.launch

/**
 * Paints a brutal block behind the content: flat [fill] with an ink outline drawn fully
 * *inside* the shape (so padding means the same thing with or without a border), plus an
 * optional hard shadow offset down-right.
 */
fun Modifier.brutal(
    shape: Shape,
    fill: Color,
    border: Color,
    borderWidth: Dp = Dimens.Border,
    shadow: Dp = 0.dp,
    shadowColor: Color = border,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    // Stroke is centred on the path; clip to the shape and double it to keep it all inside.
    val stroke = Stroke(width = borderWidth.toPx() * 2)
    val shadowPx = shadow.toPx()
    onDrawBehind {
        if (shadowPx > 0f) translate(shadowPx, shadowPx) { drawPath(path, shadowColor) }
        drawPath(path, fill)
        if (borderWidth > 0.dp) clipPath(path) { drawPath(path, border, style = stroke) }
    }
}

/** Only the hard shadow (used under content that moves while the shadow stays put). */
fun Modifier.brutalShadow(shape: Shape, offset: Dp, color: Color): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val offsetPx = offset.toPx()
    onDrawBehind { translate(offsetPx, offsetPx) { drawOutline(outline, color) } }
}

/** Text/icon colour for something drawn on [background]. */
@Composable
@ReadOnlyComposable
fun stickerContentColorFor(background: Color): Color {
    val colors = AppTheme.colors
    return if (background == colors.surface || background == colors.canvas) colors.ink else colors.onBlock
}

/**
 * The base building block of the UI. A flat block with an ink outline and a hard shadow.
 *
 * Clickable surfaces *sink into their shadow* when pressed (the shadow stays where it is and
 * the block slides down-right onto it), then spring back with a wobble. Even a very quick tap
 * plays the full press so it never feels dead. No ripple — the motion is the feedback.
 *
 * Remember that the shadow paints outside the bounds: leave [shadow] of room end/bottom.
 */
@Composable
fun BrutalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.Medium,
    color: Color = AppTheme.colors.surface,
    contentColor: Color = stickerContentColorFor(color),
    borderColor: Color = AppTheme.colors.ink,
    borderWidth: Dp = Dimens.Border,
    shadow: Dp = Dimens.ShadowM,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    enabled: Boolean = true,
    role: Role = Role.Button,
    propagateMinConstraints: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val press = remember { Animatable(0f) }
    if (onClick != null) {
        val scope = rememberCoroutineScope()
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> scope.launch {
                        press.animateTo(1f, Motion.pressDown())
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> scope.launch {
                        press.animateTo(1f, Motion.pressDown()) // finish the dip on quick taps
                        press.animateTo(0f, Motion.pressRelease())
                    }
                }
            }
        }
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    val inkShadow = AppTheme.colors.ink
    // Disabled = flat (no shadow, nothing to press into) and faded. ModulateAlpha fades
    // without an offscreen buffer, which would clip anything drawn outside the bounds.
    val effectiveShadow = if (enabled) shadow else 0.dp
    Box(
        modifier = modifier
            .then(if (enabled) Modifier else DisabledLayer)
            .brutalShadow(shape, effectiveShadow, inkShadow)
            .then(clickModifier),
        propagateMinConstraints = true,
    ) {
        Box(
            // One layer: press offset (read here, in the layer phase — no recomposition) and
            // the content clip. A wobbling shape re-clips per frame via its snapshot.
            modifier = Modifier
                .graphicsLayer {
                    val dx = effectiveShadow.toPx() * press.value
                    translationX = dx
                    translationY = dx
                    this.shape = (shape as? WobblingShape)?.snapshot() ?: shape
                    clip = true
                }
                .brutal(shape = shape, fill = color, border = borderColor, borderWidth = borderWidth),
            propagateMinConstraints = propagateMinConstraints,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                content()
            }
        }
    }
}

private val DisabledLayer = Modifier.graphicsLayer {
    alpha = 0.45f
    compositingStrategy = CompositingStrategy.ModulateAlpha
}
