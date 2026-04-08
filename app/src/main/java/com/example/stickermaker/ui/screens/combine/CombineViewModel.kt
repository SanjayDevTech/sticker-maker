package com.example.stickermaker.ui.screens.combine

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stickermaker.data.StickerSession
import com.example.stickermaker.editor.Compositor
import com.example.stickermaker.editor.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

data class CombineUiState(
    val loading: Boolean = true,
    /** Bottom of the stack first. */
    val layers: List<Layer> = emptyList(),
    val selectedId: String? = null,
    /** Images left out because everything in them was erased (their numbers). */
    val empty: List<Int> = emptyList(),
    /** The flattened sticker, while its preview is open. */
    val result: Bitmap? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
) {
    val selected: Layer? get() = layers.firstOrNull { it.id == selectedId }
    val selectedIndex: Int get() = layers.indexOfFirst { it.id == selectedId }
}

class CombineViewModel(private val session: StickerSession) : ViewModel() {

    private val _state = MutableStateFlow(CombineUiState())
    val state: StateFlow<CombineUiState> = _state.asStateFlow()

    /** Every layer ever loaded, deleted ones too, so undo can bring them back. */
    private val all = mutableMapOf<String, Layer>()

    private data class Placement(val x: Float, val y: Float, val size: Float, val rotation: Float, val flipped: Boolean)

    /** The whole arrangement: stack order (bottom first) and where each layer sits. */
    private data class Snapshot(val order: List<String>, val placements: Map<String, Placement>)

    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var gestureStart: Snapshot? = null

    init {
        viewModelScope.launch {
            val images = session.images.value
            val cutouts = images.mapIndexed { i, image ->
                async {
                    val mask = session.readMask(image)
                    val bitmap = withContext(Dispatchers.Default) {
                        runCatching { Compositor.cutout(image.file, mask) }
                            .onFailure { Log.w(TAG, "Couldn't cut ${image.id}", it) }
                            .getOrNull()
                    }
                    Triple(image.id, i + 1, bitmap)
                }
            }.awaitAll()
            val layers = cutouts.mapNotNull { (id, number, bitmap) -> bitmap?.let { Layer(id, number, it) } }
            arrange(layers)
            layers.forEach { all[it.id] = it }
            _state.update {
                CombineUiState(
                    loading = false,
                    layers = layers,
                    selectedId = layers.lastOrNull()?.id,
                    empty = cutouts.filter { it.third == null }.map { it.second },
                )
            }
        }
    }

    /** Starting layout: each fits ~70% of the board, fanned out a little so all are visible. */
    private fun arrange(layers: List<Layer>) {
        val n = layers.size
        layers.forEachIndexed { i, layer ->
            val longSide = FIT / max(1f, layer.aspect)
            layer.size = if (layer.aspect > 1f) longSide else FIT
            val spread = if (n > 1) (i - (n - 1) / 2f) / (n - 1) else 0f
            layer.centerX = 0.5f + spread * FAN
            layer.centerY = 0.5f + spread * FAN * 0.6f
        }
    }

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    // ---- Undo ----------------------------------------------------------------------------

    private fun snapshot(): Snapshot {
        val layers = _state.value.layers
        return Snapshot(
            order = layers.map { it.id },
            placements = layers.associate { it.id to Placement(it.centerX, it.centerY, it.size, it.rotation, it.flipped) },
        )
    }

    private fun restore(snapshot: Snapshot) {
        val layers = snapshot.order.mapNotNull { all[it] }
        layers.forEach { layer ->
            snapshot.placements[layer.id]?.let { p ->
                layer.centerX = p.x
                layer.centerY = p.y
                layer.size = p.size
                layer.rotation = p.rotation
                layer.flipped = p.flipped
            }
        }
        _state.update { s ->
            s.copy(layers = layers, selectedId = s.selectedId?.takeIf { id -> layers.any { it.id == id } })
        }
    }

    /** Records [before] as an undo step if anything changed since. */
    private fun commit(before: Snapshot) {
        if (before == snapshot()) return
        undoStack.addLast(before)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        syncHistory()
    }

    private fun syncHistory() = _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }

    /** A drag, handle pull or pinch starts; its end becomes one undo step. */
    fun beginGesture() {
        gestureStart = snapshot()
    }

    fun endGesture() {
        gestureStart?.let(::commit)
        gestureStart = null
    }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(snapshot())
        restore(previous)
        syncHistory()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(snapshot())
        restore(next)
        syncHistory()
    }

    // ---- Edits ---------------------------------------------------------------------------

    /** Moves the selected layer one step up (toward the front) or down the stack. */
    fun restack(up: Boolean) {
        val before = snapshot()
        _state.update { s ->
            val i = s.selectedIndex
            val j = if (up) i + 1 else i - 1
            if (i < 0 || j !in s.layers.indices) return@update s
            s.copy(layers = s.layers.toMutableList().apply { add(j, removeAt(i)) })
        }
        commit(before)
    }

    fun flip() {
        val layer = _state.value.selected ?: return
        val before = snapshot()
        layer.flipped = !layer.flipped
        commit(before)
    }

    /** Removes a layer (never the last one). Returns its stack index for the toast's Undo. */
    fun delete(id: String): Int {
        val before = snapshot()
        val index = _state.value.layers.indexOfFirst { it.id == id }
        if (index < 0 || _state.value.layers.size <= 1) return -1
        _state.update { s ->
            s.copy(
                layers = s.layers.filterNot { it.id == id },
                selectedId = s.selectedId?.takeIf { it != id },
            )
        }
        commit(before)
        return index
    }

    /** The toast's Undo: puts that layer back where it was, if it's still gone. */
    fun undoDelete(id: String, index: Int) {
        val layer = all[id] ?: return
        if (_state.value.layers.any { it.id == id }) return
        val before = snapshot()
        _state.update { s ->
            s.copy(
                layers = s.layers.toMutableList().apply { add(index.coerceIn(0, size), layer) },
                selectedId = id,
            )
        }
        commit(before)
    }

    fun flatten() {
        val layers = _state.value.layers
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) { Compositor.flatten(layers) }
            _state.update { it.copy(result = bitmap) }
        }
    }

    fun closeResult() = _state.update { it.copy(result = null) }

    private companion object {
        const val TAG = "Combine"
        const val FIT = 0.7f
        const val FAN = 0.16f
        const val MAX_UNDO = 100
    }
}
