package com.example.stickermaker.ui.screens.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stickermaker.data.SessionImage
import com.example.stickermaker.data.SessionMode
import com.example.stickermaker.data.StickerSession
import com.example.stickermaker.editor.CutoutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class EditTool { Brush, Auto }

data class EditorUiState(
    val images: List<SessionImage> = emptyList(),
    val index: Int = 0,
    val mode: SessionMode = SessionMode.Separate,
    /** Null while the current image loads. */
    val engine: CutoutEngine? = null,
    val failed: Boolean = false,
    val tool: EditTool = EditTool.Brush,
    /** Erase, or restore (the "inverse" of every tool). */
    val erase: Boolean = true,
    /** Slider position 0..1; see [brushPx]. */
    val brush: Float = 0.3f,
    val tolerance: Float = 0.15f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Whether anything was edited yet in this visit (hides the gesture hint). */
    val touched: Boolean = false,
) {
    val current: SessionImage? get() = images.getOrNull(index)
    val isLast get() = index >= images.lastIndex

    /** Brush diameter in image pixels, 1..150. Squared so the small sizes get most of the travel. */
    val brushPx: Int get() = brushPx(brush)

    companion object {
        const val MAX_BRUSH_PX = 150
        fun brushPx(slider: Float) = (1 + (MAX_BRUSH_PX - 1) * slider * slider).roundToInt()
    }
}

class EditorViewModel(private val session: StickerSession) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState(images = session.images.value, mode = session.mode.value))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Tolerance changes while a fill is live redo that fill; only the latest one matters. */
    private val refills = MutableSharedFlow<Float>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var loadJob: Job? = null

    init {
        if (_state.value.images.isNotEmpty()) load(0)
        viewModelScope.launch {
            refills.collectLatest { tolerance ->
                _state.value.engine?.refill(tolerance)
                syncHistory()
            }
        }
    }

    fun select(index: Int) {
        val s = _state.value
        if (index == s.index || index !in s.images.indices) return
        saveCurrent()
        load(index)
    }

    fun next() = select(_state.value.index + 1)

    private fun load(index: Int) {
        loadJob?.cancel()
        val image = _state.value.images[index]
        _state.update { it.copy(index = index, engine = null, failed = false, canUndo = false, canRedo = false) }
        loadJob = viewModelScope.launch {
            val history = session.history(image)
            val mask = session.readMask(image)
            val engine = runCatching { withContext(Dispatchers.IO) { CutoutEngine.load(image.file, mask, history) } }
                .onFailure { Log.w(TAG, "Couldn't open ${image.id}", it) }
                .getOrNull()
            _state.update {
                it.copy(
                    engine = engine,
                    failed = engine == null,
                    canUndo = engine?.canUndo == true,
                    canRedo = engine?.canRedo == true,
                )
            }
        }
    }

    fun setTool(tool: EditTool) = _state.update { it.copy(tool = tool) }
    fun setErase(erase: Boolean) = _state.update { it.copy(erase = erase) }
    fun setBrush(value: Float) = _state.update { it.copy(brush = value) }

    fun setTolerance(value: Float) {
        _state.update { it.copy(tolerance = value) }
        if (_state.value.engine?.canRefill == true) refills.tryEmit(value)
    }

    // Gesture callbacks (main thread). Positions are in image pixels.

    fun beginStroke(x: Float, y: Float) {
        val s = _state.value
        s.engine?.beginStroke(x, y, s.brushPx / 2f, s.erase)
    }

    fun extendStroke(x: Float, y: Float) = _state.value.engine?.extendStroke(x, y)

    fun endStroke() {
        _state.value.engine?.endStroke()
        syncHistory(touched = true)
    }

    fun cancelStroke() = _state.value.engine?.cancelStroke()

    fun fill(x: Float, y: Float) {
        val s = _state.value
        val engine = s.engine ?: return
        viewModelScope.launch {
            engine.fill(x.toInt(), y.toInt(), s.tolerance, s.erase)
            syncHistory(touched = true)
        }
    }

    fun undo() {
        _state.value.engine?.undo()
        syncHistory()
    }

    fun redo() {
        _state.value.engine?.redo()
        syncHistory()
    }

    private fun syncHistory(touched: Boolean = false) {
        val engine = _state.value.engine ?: return
        _state.update {
            it.copy(canUndo = engine.canUndo, canRedo = engine.canRedo, touched = it.touched || touched)
        }
    }

    /** Hands the current cut to the session (saved in the background). */
    fun saveCurrent() {
        val s = _state.value
        val image = s.current ?: return
        val engine = s.engine ?: return
        if (engine.canUndo || engine.canRedo) session.writeMask(image, engine.snapshot())
    }

    override fun onCleared() = saveCurrent()

    private companion object {
        const val TAG = "Editor"
    }
}
