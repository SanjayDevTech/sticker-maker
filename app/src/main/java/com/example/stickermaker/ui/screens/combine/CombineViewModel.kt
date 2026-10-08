package com.example.stickermaker.ui.screens.combine

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stickermaker.data.CombineLayout
import com.example.stickermaker.data.LayerPlacement
import com.example.stickermaker.data.StickerSession
import android.graphics.Bitmap
import com.example.stickermaker.editor.Composition
import com.example.stickermaker.editor.Compositor
import com.example.stickermaker.editor.StickerMotion
import com.example.stickermaker.editor.Layer
import com.example.stickermaker.editor.OutlinedBitmap
import kotlinx.coroutines.Job
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
    /** Images left out because they couldn't be read (their numbers). */
    val unreadable: List<Int> = emptyList(),
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

    /** One image as loaded: its layer, or null when fully erased or ([failed]) unreadable. */
    private class Loaded(val number: Int, val layer: Layer?, val failed: Boolean)

    /** Images added (in Arrange) since the history was recorded: older steps don't know them. */
    private var newThisVisit: Set<String> = emptySet()

    // Held by the session, so leaving the screen and coming back keeps them.
    private val undoStack = session.combineHistory.undo
    private val redoStack = session.combineHistory.redo
    private var gestureStart: CombineLayout? = null

    init {
        viewModelScope.launch {
            val images = session.images.value
            val loaded = images.mapIndexed { i, image ->
                async {
                    val mask = if (image.isGif) null else session.readMask(image)
                    var failed = false
                    val layer = withContext(Dispatchers.Default) {
                        runCatching {
                            if (image.isGif) {
                                // A GIF layer is the whole frame, from its first kept moment.
                                val gif = session.gif(image) ?: return@runCatching null.also { failed = true }
                                val trim = session.trim(image)
                                val first = Bitmap.createBitmap(gif.frame(gif.frameAt(trim.startMs)), gif.width, gif.height, Bitmap.Config.ARGB_8888)
                                Layer(image.id, i + 1, first, gif, trim)
                            } else {
                                Compositor.cutout(image.file, mask)?.let { Layer(image.id, i + 1, it) }
                            }
                        }.onFailure { Log.w(TAG, "Couldn't open ${image.id}", it); failed = true }.getOrNull()
                    }
                    Loaded(i + 1, layer, failed)
                }
            }.awaitAll()
            val fresh = loaded.mapNotNull { it.layer }
            fresh.forEach { all[it.id] = it }
            // Back from the editor: put layers where they were. Images added since go on top in
            // the default spread; removed ones simply drop out.
            val saved = session.combineLayout
            val known = saved?.order.orEmpty().mapNotNull { id -> fresh.firstOrNull { it.id == id } }
            val added = fresh.filterNot { it in known }
            newThisVisit = added.map { it.id }.toSet()
            arrange(added)
            val layers = known + added
            saved?.let { apply(it.placements, known) }
            pruneHistory(fresh.map { it.id }.toSet(), snapshot(layers))
            persist(layers)
            _state.update {
                CombineUiState(
                    loading = false,
                    layers = layers,
                    selectedId = layers.lastOrNull()?.id,
                    empty = loaded.filter { it.layer == null && !it.failed }.map { it.number },
                    unreadable = loaded.filter { it.failed }.map { it.number },
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
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

    private fun snapshot(layers: List<Layer> = _state.value.layers) = CombineLayout(
        order = layers.map { it.id },
        placements = layers.associate { it.id to LayerPlacement(it.centerX, it.centerY, it.size, it.rotation, it.flipped, it.motion, it.outline) },
    )

    private fun apply(placements: Map<String, LayerPlacement>, layers: List<Layer>) {
        layers.forEach { layer ->
            placements[layer.id]?.let { p ->
                layer.centerX = p.x
                layer.centerY = p.y
                layer.size = p.size
                layer.rotation = p.rotation
                layer.flipped = p.flipped
                layer.motion = p.motion
                layer.outline = p.outline
            }
        }
        refreshOutlines(layers)
    }

    private fun restore(snapshot: CombineLayout) {
        // A step from an earlier visit doesn't know images added since: they stay, on top.
        val layers = snapshot.order.mapNotNull { all[it] } +
            _state.value.layers.filter { it.id in newThisVisit && it.id !in snapshot.order }
        apply(snapshot.placements, layers)
        _state.update { s ->
            s.copy(layers = layers, selectedId = s.selectedId?.takeIf { id -> layers.any { it.id == id } })
        }
        persist()
    }

    /**
     * After images were removed in Arrange: strips them from every undo/redo step, then drops
     * steps that no longer change anything (they only touched a removed image), so each
     * Undo or Redo visibly does something. [now] is the layout as it opens.
     */
    private fun pruneHistory(present: Set<String>, now: CombineLayout) {
        fun strip(l: CombineLayout) = CombineLayout(l.order.filter { it in present }, l.placements.filterKeys { it in present })
        // Compare only images every step can know: new ones are absent from older steps.
        val shared = present - newThisVisit
        fun key(l: CombineLayout) = CombineLayout(l.order.filter { it in shared }, l.placements.filterKeys { it in shared })

        // Undo goes newest first: a step equal to the state it would return from is dead.
        val undo = undoStack.map(::strip)
        undoStack.clear()
        var after = key(now)
        for (step in undo.asReversed()) {
            val k = key(step)
            if (k != after) undoStack.addFirst(step)
            after = k
        }
        // Redo, next first, likewise against the state before it.
        val redo = redoStack.map(::strip)
        redoStack.clear()
        var before = key(now)
        for (step in redo.asReversed()) {
            val k = key(step)
            if (k != before) redoStack.addFirst(step)
            before = k
        }
    }

    /** Keeps the arrangement in the session, so it survives leaving this screen. */
    private fun persist(layers: List<Layer> = _state.value.layers) {
        session.combineLayout = snapshot(layers)
    }

    /** Records [before] as an undo step if anything changed since. */
    private fun commit(before: CombineLayout) {
        persist()
        refreshOutlines()
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

    /** Sets how the selected cut-out moves (GIF layers play instead). */
    fun setMotion(motion: StickerMotion) {
        val layer = _state.value.selected?.takeIf { !it.isGif } ?: return
        if (layer.motion == motion) return
        val before = snapshot()
        layer.motion = motion
        commit(before)
    }

    /** Turns the selected cut-out's outline on or off. */
    fun setOutline(on: Boolean) {
        val layer = _state.value.selected?.takeIf { !it.isGif } ?: return
        if (layer.outline == on) return
        val before = snapshot()
        layer.outline = on
        commit(before)
    }

    /** Every cut-out's outline on or off, as one step. */
    fun setOutlineAll(on: Boolean) {
        val before = snapshot()
        _state.value.layers.filterNot { it.isGif }.forEach { it.outline = on }
        commit(before)
    }

    private val outlineJobs = HashMap<String, Job>()

    /**
     * Makes the outlined copy of every outlined cut-out whose copy is missing or was made for
     * another size (the band keeps one width on the sticker; a resize changes it in layer
     * pixels). Until it's ready, the old copy (or the plain cut-out) shows.
     */
    private fun refreshOutlines(layers: List<Layer> = _state.value.layers) {
        layers.forEach { layer ->
            if (!layer.outline || layer.isGif) return@forEach
            val thickness = layer.outlineThickness()
            if (layer.outlined?.thickness == thickness) return@forEach
            outlineJobs[layer.id]?.cancel()
            outlineJobs[layer.id] = viewModelScope.launch {
                layer.outlined = withContext(Dispatchers.Default) { OutlinedBitmap.of(layer.bitmap, thickness) }
            }
        }
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

    /**
     * Freezes the layers, as they are now, for the Save screen (which draws or animates them),
     * once every outline is ready.
     */
    fun finish(then: () -> Unit) {
        viewModelScope.launch {
            refreshOutlines()
            outlineJobs.values.toList().forEach { it.join() }
            session.combined = Composition(_state.value.layers.map(Layer::snapshot))
            then()
        }
    }

    private companion object {
        const val TAG = "Combine"
        const val FIT = 0.7f
        const val FAN = 0.16f
        const val MAX_UNDO = 100
    }
}
