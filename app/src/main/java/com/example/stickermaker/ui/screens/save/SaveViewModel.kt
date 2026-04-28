package com.example.stickermaker.ui.screens.save

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stickermaker.data.PackRepository
import com.example.stickermaker.data.SessionMode
import com.example.stickermaker.data.StickerPack
import com.example.stickermaker.data.StickerSession
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.editor.Compositor
import com.example.stickermaker.editor.StickerExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SaveUiState(
    val loading: Boolean = true,
    /** What will be saved, as shown (with the outline when it's on). */
    val previews: List<ImageBitmap> = emptyList(),
    /** Images left out because they were fully erased. */
    val skipped: Int = 0,
    val outline: Boolean = false,
    val packs: List<StickerPack> = emptyList(),
    /** The pack to add to; null means a new pack called [newName]. */
    val targetId: String? = null,
    val newName: String = "",
    val saving: Boolean = false,
) {
    val count get() = previews.size
    fun hasRoom(pack: StickerPack) = pack.stickers.size + count <= WhatsAppLimits.MAX_STICKERS
    val target: StickerPack? get() = packs.firstOrNull { it.id == targetId }
    val canSave get() = !loading && !saving && count > 0 && (target?.let(::hasRoom) ?: newName.isNotBlank())
}

class SaveViewModel(
    private val session: StickerSession,
    private val packRepo: PackRepository,
) : ViewModel() {

    private val local = MutableStateFlow(
        SaveUiState(targetId = session.targetPackId.value, newName = defaultName(packRepo.packs.value.orEmpty())),
    )

    val state: StateFlow<SaveUiState> = combine(local, packRepo.packs) { s, packs ->
        s.copy(packs = packs.orEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the pack id once the stickers are written. */
    val saved: SharedFlow<String> = _saved.asSharedFlow()

    private val _failed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val failed: SharedFlow<Unit> = _failed.asSharedFlow()

    private var plain: List<Bitmap> = emptyList()
    private var outlined: List<Bitmap>? = null

    init {
        viewModelScope.launch {
            val combined = session.combined
            val rendered: List<Bitmap?> = if (session.mode.value == SessionMode.Combine && combined != null) {
                listOf(combined)
            } else {
                session.images.value.map { image ->
                    async(Dispatchers.Default) {
                        val mask = session.readMask(image)
                        runCatching { Compositor.cutout(image.file, mask)?.let(StickerExport::fit) }
                            .onFailure { Log.w(TAG, "Couldn't render ${image.id}", it) }
                            .getOrNull()
                    }
                }.awaitAll()
            }
            plain = rendered.filterNotNull()
            local.update {
                it.copy(
                    loading = false,
                    previews = plain.map(Bitmap::asImageBitmap),
                    skipped = rendered.count { b -> b == null },
                )
            }
        }
    }

    fun setOutline(on: Boolean) {
        local.update { it.copy(outline = on) }
        viewModelScope.launch {
            val list = if (on) {
                outlined ?: withContext(Dispatchers.Default) { plain.map(StickerExport::withOutline) }.also { outlined = it }
            } else {
                plain
            }
            // A quick double toggle: only show what's currently chosen.
            if (local.value.outline == on) local.update { it.copy(previews = list.map(Bitmap::asImageBitmap)) }
        }
    }

    fun setTarget(id: String?) = local.update { it.copy(targetId = id) }
    fun setNewName(name: String) = local.update { it.copy(newName = name) }

    fun save() {
        val s = state.value
        if (!s.canSave) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val bitmaps = if (s.outline) {
                outlined ?: withContext(Dispatchers.Default) { plain.map(StickerExport::withOutline) }
            } else {
                plain
            }
            val id = runCatching { packRepo.addStickers(s.targetId, s.newName, bitmaps) }
                .onFailure { Log.w(TAG, "Couldn't save stickers", it) }
                .getOrNull()
            if (id == null) {
                local.update { it.copy(saving = false) }
                _failed.emit(Unit)
                return@launch
            }
            session.clear()
            _saved.emit(id)
        }
    }

    private companion object {
        const val TAG = "Save"
        fun defaultName(packs: List<StickerPack>) = if (packs.isEmpty()) "My stickers" else "My stickers ${packs.size + 1}"
    }
}
