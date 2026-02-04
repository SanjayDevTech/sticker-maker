package com.example.stickermaker.ui.components

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import com.example.stickermaker.ui.theme.LocalReduceMotion
import com.example.stickermaker.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-appearance entrance: the item drops in, slightly tilted, and lands with a bounce,
 * [index] steps after the previous one. Plays once per item — the "played" flag is saveable,
 * so scrolling a lazy list back or returning to the screen doesn't replay it.
 *
 * Alpha runs on its own non-overshooting spring and uses [CompositingStrategy.ModulateAlpha].
 * A layer with alpha < 1 otherwise renders offscreen, clipped to its own bounds, which cuts
 * off hard shadows (they paint outside the bounds); and driving alpha from the bouncy spring
 * made it dip back under 1 after the overshoot, so shadows flickered off for a few frames.
 */
@Composable
fun Modifier.staggeredEntrance(index: Int): Modifier {
    if (LocalReduceMotion.current) return this
    var played by rememberSaveable { mutableStateOf(false) }
    val motion = remember { Animatable(if (played) 1f else 0f) }
    val fade = remember { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        delay(index.coerceAtMost(Motion.STAGGER_MAX_ITEMS) * Motion.STAGGER_MS.toLong())
        launch { fade.animateTo(1f, Motion.effectsSlow()) }
        motion.animateTo(1f, Motion.spatialBouncy())
        played = true
    }
    val tilt = if (index % 2 == 0) -2.5f else 2.5f
    return graphicsLayer {
        val p = motion.value
        alpha = fade.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
        translationY = (1f - p) * 28.dp.toPx()
        rotationZ = (1f - p) * tilt
    }
}

/** Set by AppNavigation around the NavDisplay; null in previews. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/**
 * Container transform between screens: give the source (e.g. a card) and the destination
 * (e.g. the next screen's header) the same [key] and the block morphs across the transition.
 * No-op outside a navigation transition (previews).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedBlock(key: Any): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animatedScope = LocalNavAnimatedContentScope.current
    return with(shared) {
        this@sharedBlock.sharedBounds(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedScope,
            boundsTransform = BlockBoundsTransform,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}

private val BlockBoundsTransform = BoundsTransform { _, _ -> tween(Motion.NAV_MS, easing = Motion.Emphasized) }
