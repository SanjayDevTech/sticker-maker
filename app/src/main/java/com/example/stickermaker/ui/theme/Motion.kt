package com.example.stickermaker.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * One motion vocabulary for the whole app. Spatial = things that move or resize (allowed to
 * overshoot); Effects = colour/alpha (never overshoot). Bouncy is reserved for playful,
 * brutal-style feedback: presses releasing, stickers landing.
 */
object Motion {
    /** Press-down: instant and flat, it should feel like pushing a physical key. */
    fun <T> pressDown(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 2200f)

    /** Release: springs back with a little wobble. */
    fun <T> pressRelease(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.42f, stiffness = 900f)

    fun <T> spatialFast(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = 1400f)
    fun <T> spatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 500f)
    fun <T> spatialBouncy(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.55f, stiffness = 380f)
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)

    fun <T> effects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> effectsSlow(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 300f)

    /** Screen transitions use a duration (not a spring) so predictive back can scrub them. */
    const val NAV_MS = 380
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> nav(): FiniteAnimationSpec<T> = tween(NAV_MS, easing = Emphasized)

    /** Delay between items of a staggered entrance, and the cap so long lists don't crawl. */
    const val STAGGER_MS = 45
    const val STAGGER_MAX_ITEMS = 8
}

/**
 * True when the user turned animations off (Developer options / Accessibility → Remove
 * animations). Compose already zeroes finite animation durations then; this is for our own
 * *decorative* loops (squiggle wobble, skeleton pulse, stagger) which should simply not run.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
internal fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
