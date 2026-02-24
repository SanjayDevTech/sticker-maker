package com.example.stickermaker.ui.screens.create

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.toOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Long-press-and-drag reordering for a LazyVerticalGrid. Only items whose key passes
 * [isDraggable] can be picked up or dropped onto. The dragged item's offset is exposed for a
 * graphicsLayer (read in the draw phase); the others move via animateItem().
 */
@Stable
class GridDragState internal constructor(
    private val grid: LazyGridState,
    private val scope: CoroutineScope,
    private val isDraggable: (Any) -> Boolean,
    private val onMove: (fromKey: Any, toKey: Any) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set

    /** Pointer travel since pickup, minus how far the item itself moved in the layout. */
    var dragOffset by mutableStateOf(Offset.Zero)
        private set

    private var startOffset = Offset.Zero
    private var autoScroll: Job? = null

    private fun itemAt(position: Offset): LazyGridItemInfo? =
        grid.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            val o = info.offset.toOffset()
            position.x in o.x..(o.x + info.size.width) && position.y in o.y..(o.y + info.size.height)
        }

    private fun current(): LazyGridItemInfo? =
        grid.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /**
     * Pointer events are relative to the grid's bounds, but item offsets are relative to where
     * the content starts, which is *after* the top content padding (viewportStartOffset is minus
     * that padding). Without this shift every touch hit the item one padding-height higher.
     */
    private fun toItemSpace(pointer: Offset) =
        pointer + Offset(0f, grid.layoutInfo.viewportStartOffset.toFloat())

    internal fun start(position: Offset) {
        val item = itemAt(toItemSpace(position))?.takeIf { isDraggable(it.key) } ?: return
        draggingKey = item.key
        startOffset = item.offset.toOffset()
        dragOffset = Offset.Zero
    }

    internal fun drag(amount: Offset, pointer: Offset) {
        if (draggingKey == null) return
        dragOffset += amount
        val dragged = current() ?: return
        val center = startOffset + dragOffset + Offset(dragged.size.width / 2f, dragged.size.height / 2f)
        val target = itemAt(center)
        if (target != null && target.key != dragged.key && isDraggable(target.key)) {
            onMove(dragged.key, target.key)
        }
        // Keep the dragged item under the finger as the layout moves it to its new slot.
        val now = current()?.offset?.toOffset() ?: return
        if (now != startOffset) {
            dragOffset -= now - startOffset
            startOffset = now
        }
        updateAutoScroll(pointer)
    }

    private fun updateAutoScroll(pointer: Offset) {
        val viewport = grid.layoutInfo.viewportSize.height
        val edge = viewport * 0.12f
        val speed = when {
            pointer.y < edge -> -(edge - pointer.y) / edge * 28f
            pointer.y > viewport - edge -> (pointer.y - (viewport - edge)) / edge * 28f
            else -> 0f
        }
        autoScroll?.cancel()
        if (speed == 0f) return
        autoScroll = scope.launch {
            while (isActive) {
                grid.scrollBy(speed)
                delay(16)
            }
        }
    }

    internal fun end() {
        autoScroll?.cancel()
        draggingKey = null
        dragOffset = Offset.Zero
    }
}

@Composable
fun rememberGridDragState(
    grid: LazyGridState,
    isDraggable: (Any) -> Boolean,
    onMove: (fromKey: Any, toKey: Any) -> Unit,
): GridDragState {
    val scope = rememberCoroutineScope()
    return remember(grid) { GridDragState(grid, scope, isDraggable, onMove) }
}

fun Modifier.gridDragReorder(state: GridDragState): Modifier = pointerInput(state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(it) },
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount, change.position)
        },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
    )
}
