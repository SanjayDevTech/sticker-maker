package com.example.stickermaker.ui.screens.editor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stickermaker.data.SessionImage
import com.example.stickermaker.data.SessionMode
import com.example.stickermaker.data.StickerSession
import com.example.stickermaker.editor.CutoutEngine
import kotlinx.coroutines.CoroutineStart
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

enum class EditTool { Brush, Auto, Magic }

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
    /** Magic's marker size, same 0..1 scale as [brush]; fat by default since marks are rough. */
    val marker: Float = 0.5f,
    val tolerance: Float = 0.15f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Whether anything was edited yet in this visit (hides the gesture hint). */
    val touched: Boolean = false,
    /** The last Auto fill is still live: moving Match redoes it. */
    val adjustingFill: Boolean = false,
    /** Marks in the current Magic session. */
    val magicMarks: Int = 0,
    /** A Magic run is computing. */
    val magicBusy: Boolean = false,
) {
    val current: SessionImage? get() = images.getOrNull(index)
    val isLast get() = index >= images.lastIndex

    /** Brush diameter in image pixels, 1..150. Squared so the small sizes get most of the travel. */
    val brushPx: Int get() = brushPx(brush)
    val markerPx: Int get() = brushPx(marker)

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
    private var magicJob: Job? = null

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
        magicJob?.cancel()
        val image = _state.value.images[index]
        _state.update {
            it.copy(index = index, engine = null, failed = false, canUndo = false, canRedo = false, adjustingFill = false,
                magicMarks = 0, magicBusy = false)
        }
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
                    adjustingFill = false,
                )
            }
        }
    }

    fun setTool(tool: EditTool) = _state.update {
        // Magic starts by marking the subject, so it opens on Keep.
        if (tool == EditTool.Magic && it.magicMarks == 0) it.copy(tool = tool, erase = false) else it.copy(tool = tool)
    }
    fun setErase(erase: Boolean) = _state.update { it.copy(erase = erase) }
    fun setBrush(value: Float) = _state.update {
        if (it.tool == EditTool.Magic) it.copy(marker = value) else it.copy(brush = value)
    }

    fun setTolerance(value: Float) {
        _state.update { it.copy(tolerance = value) }
        if (_state.value.engine?.canRefill == true) refills.tryEmit(value)
    }

    // Gesture callbacks (main thread). Positions are in image pixels.

    // Brush and Magic both draw strokes; Magic's become Keep/Remove marks instead of edits.

    fun beginStroke(x: Float, y: Float) {
        val s = _state.value
        val engine = s.engine ?: return
        if (s.tool == EditTool.Magic) {
            engine.beginMark(x, y, s.markerPx / 2f, keep = !s.erase)
        } else {
            engine.beginStroke(x, y, s.brushPx / 2f, s.erase)
        }
    }

    fun extendStroke(x: Float, y: Float) {
        val s = _state.value
        val engine = s.engine ?: return
        if (s.tool == EditTool.Magic) engine.extendMark(x, y) else engine.extendStroke(x, y)
    }

    fun endStroke() {
        val s = _state.value
        val engine = s.engine ?: return
        if (s.tool == EditTool.Magic) {
            engine.endMark()
            runMagic(engine)
        } else {
            engine.endStroke()
        }
        syncHistory(touched = true)
    }

    fun cancelStroke() {
        val s = _state.value
        if (s.tool == EditTool.Magic) s.engine?.cancelMark() else s.engine?.cancelStroke()
    }

    private fun runMagic(engine: CutoutEngine) {
        // Without a Keep mark there's no subject to find yet; the hint asks for one.
        if (!engine.hasKeepMark) return
        magicJob?.cancel()
        // Lazy so magicJob is set before the body can finish (Main.immediate runs it inline).
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                engine.runMagic()
            } finally {
                if (magicJob === coroutineContext[Job]) {
                    _state.update { it.copy(magicBusy = false) }
                    syncHistory()
                }
            }
        }
        magicJob = job
        _state.update { it.copy(magicBusy = true) }
        job.start()
    }

    fun fill(x: Float, y: Float) {
        val s = _state.value
        val engine = s.engine ?: return
        magicJob?.cancel()
        viewModelScope.launch {
            engine.fill(x.toInt(), y.toInt(), s.tolerance, s.erase)
            syncHistory(touched = true)
        }
    }

    fun undo() {
        magicJob?.cancel()
        _state.value.engine?.undo()
        syncHistory()
    }

    fun redo() {
        magicJob?.cancel()
        _state.value.engine?.redo()
        syncHistory()
    }

    private fun syncHistory(touched: Boolean = false) {
        val engine = _state.value.engine ?: return
        _state.update {
            it.copy(
                canUndo = engine.canUndo,
                canRedo = engine.canRedo,
                touched = it.touched || touched,
                adjustingFill = engine.canRefill,
                magicMarks = engine.marks.size,
            )
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
