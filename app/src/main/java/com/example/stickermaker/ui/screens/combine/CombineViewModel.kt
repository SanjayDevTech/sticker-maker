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
) {
    val selected: Layer? get() = layers.firstOrNull { it.id == selectedId }
    val selectedIndex: Int get() = layers.indexOfFirst { it.id == selectedId }
}

class CombineViewModel(private val session: StickerSession) : ViewModel() {

    private val _state = MutableStateFlow(CombineUiState())
    val state: StateFlow<CombineUiState> = _state.asStateFlow()

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

    /** Moves the selected layer one step up (toward the front) or down the stack. */
    fun restack(up: Boolean) = _state.update { s ->
        val i = s.selectedIndex
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in s.layers.indices) return@update s
        s.copy(layers = s.layers.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun flip() {
        _state.value.selected?.let { it.flipped = !it.flipped }
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
    }
}
