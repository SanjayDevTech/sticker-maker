package com.example.stickermaker.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.LocalReduceMotion
import com.example.stickermaker.ui.theme.Motion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

enum class ToastResult { Dismissed, ActionPerformed }

object ToastDefaults {
    /** Toasts counting down at once. The rest wait, faded, until one clears. */
    const val MAX_ACTIVE = 2
    const val DURATION_MS = 4_000L
    const val UNDO_DURATION_MS = 6_000L
}

/**
 * Stacking toasts. Unlike SnackbarHostState (one at a time, queued invisibly), every toast is
 * on screen at once: the oldest at the bottom, newer ones stacked above.
 *
 * - The bottom [ToastDefaults.MAX_ACTIVE] are live: swipeable, counting down.
 * - The next one waits tucked *behind* the top live toast with only a sliver peeking out, so
 *   the user knows more are queued. Any beyond that are hidden behind it. Waiting toasts have
 *   no timer and take no input. When a live one clears, the stack slides down and the
 *   peeking toast comes alive.
 * - Cleared by swiping left/right, or by timing out (which slides it away the same way). There
 *   is no close button. An action (e.g. Undo) is still a button.
 */
@Stable
class ToastState {
    internal class Entry(
        val id: Long,
        val message: String,
        val actionLabel: String?,
        val durationMillis: Long,
        val result: CompletableDeferred<ToastResult> = CompletableDeferred(),
    )

    internal val entries = mutableStateListOf<Entry>()
    private var nextId = 0L

    /** Suspends until the toast clears. Launch one coroutine per toast to stack several. */
    suspend fun show(
        message: String,
        actionLabel: String? = null,
        durationMillis: Long = ToastDefaults.DURATION_MS,
    ): ToastResult {
        val entry = Entry(nextId++, message, actionLabel, durationMillis)
        entries += entry
        return try {
            entry.result.await()
        } finally {
            entries.remove(entry) // caller cancelled (e.g. screen left): don't leave it behind
        }
    }

    internal fun finish(entry: Entry, result: ToastResult) {
        entries.remove(entry)
        entry.result.complete(result)
    }
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/**
 * Do the destructive thing immediately, then offer Undo. Returns true if the user tapped Undo.
 * Prefer this over confirm dialogs.
 */
suspend fun ToastState.showUndo(message: String): Boolean =
    show(message, actionLabel = "Undo", durationMillis = ToastDefaults.UNDO_DURATION_MS) ==
        ToastResult.ActionPerformed

/** Put in `Scaffold(snackbarHost = { ToastHost(toasts) })`. */
@Composable
fun ToastHost(state: ToastState, modifier: Modifier = Modifier) {
    val entries = state.entries
    val gap = 10.dp
    val peek = 10.dp
    // A deck, not a list: live toasts stack with a gap; the waiting ones are placed behind the
    // top live toast (lower zIndex), offset up by just [peek]. A plain Layout (not a lazy list)
    // because items overlap and there are only ever a handful.
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.ScreenGutter,
                end = Dimens.ScreenGutter + Dimens.ShadowM,
                top = Dimens.Space8,
                bottom = 12.dp + Dimens.ShadowM,
            ),
        content = {
            entries.forEachIndexed { index, entry ->
                key(entry.id) {
                    Toast(entry = entry, slot = index, onFinish = { state.finish(entry, it) })
                }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val gapPx = gap.roundToPx()
        val peekPx = peek.roundToPx()
        val max = ToastDefaults.MAX_ACTIVE
        // Distance from the host's bottom edge to each toast's bottom edge.
        val bottoms = IntArray(placeables.size)
        var stackTop = 0
        placeables.forEachIndexed { i, p ->
            if (i < max) {
                bottoms[i] = if (i == 0) 0 else stackTop + gapPx
                stackTop = bottoms[i] + p.height
            } else {
                // Waiting: its top sits [peek] above the top live toast, the rest hidden behind.
                bottoms[i] = stackTop + peekPx - p.height
            }
        }
        val height = if (placeables.size > max) stackTop + peekPx else stackTop
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p ->
                p.place(0, height - bottoms[i] - p.height, zIndex = -i.toFloat())
            }
        }
    }
}

@Composable
private fun Toast(
    entry: ToastState.Entry,
    slot: Int,
    onFinish: (ToastResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = slot < ToastDefaults.MAX_ACTIVE
    val shown = slot <= ToastDefaults.MAX_ACTIVE // live ones + the one peeking out
    val reduceMotion = LocalReduceMotion.current
    val flingVelocity = with(LocalDensity.current) { 700.dp.toPx() }

    // All animated values below are read only in the layout/draw phase: no per-frame recomposition.
    var offsetX by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    val entrance = remember { Animatable(if (reduceMotion || !active) 1f else 0f) }
    val visibility = remember { Animatable(if (shown) 1f else 0f) }
    val depth = remember { Animatable(if (active) 0f else 1f) } // 1 = tucked behind the deck

    suspend fun slideAway(velocity: Float, direction: Float) {
        animate(offsetX, direction * widthPx * 1.3f, velocity, Motion.spatialFast()) { v, _ -> offsetX = v }
        onFinish(ToastResult.Dismissed)
    }

    LaunchedEffect(Unit) { entrance.animateTo(1f, Motion.spatialBouncy()) }
    LaunchedEffect(active) { depth.animateTo(if (active) 0f else 1f, Motion.spatialBouncy()) }
    LaunchedEffect(shown) { visibility.animateTo(if (shown) 1f else 0f, Motion.effects()) }
    // Countdown only while live and not held; restarts after a drag that springs back.
    LaunchedEffect(active, dragging) {
        if (!active || dragging) return@LaunchedEffect
        delay(entry.durationMillis)
        slideAway(velocity = 0f, direction = 1f)
    }

    val draggableState = rememberDraggableState { delta -> offsetX += delta }
    BrutalSurface(
        modifier = modifier
            .fillMaxWidth()
            .animatePlacement()
            .onSizeChanged { widthPx = it.width }
            .graphicsLayer {
                val enter = entrance.value
                val d = depth.value
                translationX = offsetX
                translationY = (1f - enter) * 32.dp.toPx()
                // Peels off at a slight angle as it's dragged, like a sticker.
                rotationZ = if (widthPx > 0) offsetX / widthPx * 8f else 0f
                // Tucked toasts are a bit narrower, so the peeking edge reads as a card behind.
                val scale = (0.85f + 0.15f * enter) * (1f - 0.06f * d)
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0.5f, 0f)
                // Hidden ones (4th+) are fully behind the peeking one anyway; fading them keeps
                // the swap invisible when they move up. Waiting toasts draw no shadow, so
                // ModulateAlpha has nothing to show through.
                alpha = visibility.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Horizontal,
                enabled = active,
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    val far = abs(offsetX) > widthPx * 0.35f
                    val flung = abs(velocity) > flingVelocity && (offsetX == 0f || sign(velocity) == sign(offsetX))
                    if (far || flung) {
                        slideAway(velocity, if (offsetX != 0f) sign(offsetX) else sign(velocity))
                    } else {
                        animate(offsetX, 0f, velocity, Motion.spatialBouncy()) { v, _ -> offsetX = v }
                        dragging = false
                    }
                },
            )
            .then(
                if (active) {
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        customActions = listOf(
                            CustomAccessibilityAction("Dismiss") {
                                onFinish(ToastResult.Dismissed)
                                true
                            },
                        )
                    }
                } else {
                    Modifier.clearAndSetSemantics { } // waiting: not announced, not focusable
                },
            ),
        shape = Shapes.Medium,
        color = Palette.Yellow,
        shadow = if (active) Dimens.ShadowM else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Text(
                entry.message,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            val action = entry.actionLabel
            if (action != null) {
                ChunkyButton(
                    text = action,
                    onClick = { onFinish(ToastResult.ActionPerformed) },
                    color = AppTheme.colors.surface,
                    size = ChunkySize.Medium,
                    enabled = active,
                    modifier = Modifier.padding(end = Dimens.ShadowS, bottom = Dimens.ShadowS),
                )
            }
        }
    }
}

/**
 * Animates a child to its new position whenever its parent re-places it (Compose docs'
 * onPlaced + offset {} pattern; both reads happen in the placement phase, no recomposition).
 *
 * Positions are measured from the parent's *bottom* edge. The host is bottom-anchored and
 * grows upward as toasts arrive, so top-relative positions would "change" for toasts that
 * haven't moved on screen, and they'd spring (shake) for nothing.
 */
@Composable
private fun Modifier.animatePlacement(): Modifier {
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(IntOffset.Zero) }
    var animatable by remember { mutableStateOf<Animatable<IntOffset, AnimationVector2D>?>(null) }
    return this
        .onPlaced { coordinates ->
            val parentHeight = coordinates.parentLayoutCoordinates?.size?.height ?: 0
            val position = coordinates.positionInParent().round()
            target = IntOffset(position.x, position.y - parentHeight)
        }
        .offset {
            val anim = animatable ?: Animatable(target, IntOffset.VectorConverter).also { animatable = it }
            if (anim.targetValue != target) {
                scope.launch { anim.animateTo(target, Motion.spatialBouncy()) }
            }
            animatable?.let { it.value - target } ?: IntOffset.Zero
        }
}
