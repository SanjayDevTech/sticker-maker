package com.example.stickermaker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.LocalReduceMotion
import com.example.stickermaker.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * A hidden toy around [content]: each tap plays a random move (wiggle, bounce, spin, squash,
 * pop or jelly), never the same one twice running. A plain tap gesture rather than a button,
 * so screen readers don't announce it. Does nothing when animations are off.
 */
@Composable
fun EasterEgg(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalReduceMotion.current) {
        Box(modifier) { content() }
        return
    }
    val scope = rememberCoroutineScope()
    val rotation = remember { Animatable(0f) }
    val scaleX = remember { Animatable(1f) }
    val scaleY = remember { Animatable(1f) }
    val lift = remember { Animatable(0f) }
    val jump = with(LocalDensity.current) { JUMP.toPx() }
    val state = remember { object { var last = -1; var job: Job? = null } }

    suspend fun play(move: Int) {
        // A move cut short by a new tap may leave the badge mid-spin: settle it near upright.
        rotation.snapTo(((rotation.value + 180f) % 360f + 360f) % 360f - 180f)
        when (move) {
            WIGGLE -> {
                for (angle in listOf(14f, -12f, 9f, -6f, 3f)) rotation.animateTo(angle, Motion.spatialFast())
                rotation.animateTo(0f, Motion.spatial())
            }
            BOUNCE -> {
                lift.animateTo(-jump, Motion.spatialFast())
                lift.animateTo(0f, Motion.spatialBouncy())
            }
            SPIN -> {
                rotation.animateTo(rotation.value + if (Random.nextBoolean()) 360f else -360f, Motion.spatialSlow())
                rotation.snapTo(0f)
            }
            SQUASH -> {
                coroutineScope {
                    launch { scaleX.animateTo(1.3f, Motion.spatialFast()); scaleX.animateTo(1f, Motion.spatialBouncy()) }
                    launch { scaleY.animateTo(0.7f, Motion.spatialFast()); scaleY.animateTo(1f, Motion.spatialBouncy()) }
                }
            }
            POP -> {
                coroutineScope {
                    launch { scaleX.animateTo(1.45f, Motion.spatialFast()); scaleX.animateTo(1f, Motion.spatialBouncy()) }
                    launch { scaleY.animateTo(1.45f, Motion.spatialFast()); scaleY.animateTo(1f, Motion.spatialBouncy()) }
                }
            }
            else -> { // JELLY
                for ((x, y) in listOf(1.25f to 0.8f, 0.85f to 1.2f, 1.1f to 0.92f)) {
                    coroutineScope {
                        launch { scaleX.animateTo(x, Motion.spatialFast()) }
                        launch { scaleY.animateTo(y, Motion.spatialFast()) }
                    }
                }
                coroutineScope {
                    launch { scaleX.animateTo(1f, Motion.spatialBouncy()) }
                    launch { scaleY.animateTo(1f, Motion.spatialBouncy()) }
                }
            }
        }
    }

    Box(
        modifier
            .graphicsLayer {
                rotationZ = rotation.value
                this.scaleX = scaleX.value
                this.scaleY = scaleY.value
                translationY = lift.value
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    // Any move but the last one.
                    var move = Random.nextInt(if (state.last < 0) MOVES else MOVES - 1)
                    if (state.last in 0..move) move++
                    state.last = move
                    state.job?.cancel()
                    state.job = scope.launch {
                        // Anything a cut-short move left stretched or lifted springs back first.
                        launch { scaleX.animateTo(1f, Motion.spatialFast()) }
                        launch { scaleY.animateTo(1f, Motion.spatialFast()) }
                        launch { lift.animateTo(0f, Motion.spatialFast()) }
                        play(move)
                    }
                }
            },
    ) { content() }
}

private const val WIGGLE = 0
private const val BOUNCE = 1
private const val SPIN = 2
private const val SQUASH = 3
private const val POP = 4
private const val MOVES = 6
private val JUMP = 24.dp
