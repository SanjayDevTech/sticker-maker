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
import com.example.stickermaker.editor.MotionPlan
import com.example.stickermaker.editor.StickerExport
import com.example.stickermaker.editor.StickerMotion
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
    /** Applied to every sticker of this batch: a pack is all still or all animated. */
    val motion: StickerMotion = StickerMotion.None,
    /** How each preview moves, aligned with [previews]; empty while still. */
    val plans: List<MotionPlan?> = emptyList(),
    val packs: List<StickerPack> = emptyList(),
    /** The pack to add to; null means a new pack called [newName]. */
    val targetId: String? = null,
    val newName: String = "",
    val saving: Boolean = false,
) {
    val count get() = previews.size
    val animated get() = motion != StickerMotion.None
    fun hasRoom(pack: StickerPack) = pack.stickers.size + count <= WhatsAppLimits.MAX_STICKERS

    /** Has room, and is the same kind (still or animated) as these stickers. */
    fun accepts(pack: StickerPack) = hasRoom(pack) && pack.animated == animated
    val target: StickerPack? get() = packs.firstOrNull { it.id == targetId }
    val canSave get() = !loading && !saving && count > 0 && (target?.let(::accepts) ?: newName.isNotBlank())
}

class SaveViewModel(
    private val session: StickerSession,
    private val packRepo: PackRepository,
) : ViewModel() {

    private val local = MutableStateFlow(
        SaveUiState(
            targetId = session.targetPackId.value,
            newName = defaultName(packRepo.packs.value.orEmpty()),
            // Adding to an animated pack: start moving, or the pack wouldn't accept them.
            motion = if (session.targetPackId.value?.let(packRepo::pack)?.animated == true) StickerMotion.Wiggle else StickerMotion.None,
        ),
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
            local.update { it.copy(skipped = rendered.count { b -> b == null }) }
            refresh()
        }
    }

    fun setOutline(on: Boolean) {
        local.update { it.copy(outline = on) }
        viewModelScope.launch { refresh() }
    }

    fun setMotion(motion: StickerMotion) {
        local.update { s ->
            // A pack that's the wrong kind now can't take them: fall back to a new pack.
            val keep = s.target?.let { it.animated == (motion != StickerMotion.None) } ?: true
            s.copy(motion = motion, targetId = if (keep) s.targetId else null)
        }
        viewModelScope.launch { refresh() }
    }

    /** Re-renders previews and motion plans for the current outline and motion. */
    private suspend fun refresh() {
        val outline = local.value.outline
        val motion = local.value.motion
        val stills = stills(outline)
        val plans = withContext(Dispatchers.Default) { stills.map { StickerExport.plan(it, motion) } }
        // A quick double toggle: only show what's currently chosen.
        val now = local.value
        if (now.outline != outline || now.motion != motion) return
        local.update {
            it.copy(loading = false, previews = stills.map(Bitmap::asImageBitmap), plans = if (motion == StickerMotion.None) emptyList() else plans)
        }
    }

    private suspend fun stills(outline: Boolean): List<Bitmap> = if (outline) {
        outlined ?: withContext(Dispatchers.Default) { plain.map(StickerExport::withOutline) }.also { outlined = it }
    } else {
        plain
    }

    /** Finished WebP of each sticker for this outline and motion, built once and reused. */
    private val encoded = HashMap<Triple<Int, Boolean, StickerMotion>, ByteArray>()

    private suspend fun encode(index: Int, outline: Boolean, motion: StickerMotion): ByteArray? {
        val key = Triple(index, outline, motion)
        encoded[key]?.let { return it }
        val still = stills(outline).getOrNull(index) ?: return null
        val bytes = withContext(Dispatchers.Default) {
            if (motion == StickerMotion.None) {
                StickerExport.encodeSticker(still)
            } else {
                StickerExport.plan(still, motion)?.let { StickerExport.encodeAnimated(still, it) }
            }
        } ?: return null
        encoded[key] = bytes
        return bytes
    }

    /** Sticker [index] as it would be saved (outline and motion included), for copying without a pack. */
    suspend fun stickerAt(index: Int): ByteArray? {
        val s = local.value
        return encode(index, s.outline, s.motion)
    }

    fun setTarget(id: String?) = local.update { it.copy(targetId = id) }
    fun setNewName(name: String) = local.update { it.copy(newName = name) }

    fun save() {
        val s = state.value
        if (!s.canSave) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val files = runCatching {
                plain.indices.map { i -> async { encode(i, s.outline, s.motion) } }.awaitAll()
            }.getOrNull()
            val id = if (files == null || files.any { it == null }) {
                Log.w(TAG, "Couldn't encode every sticker")
                null
            } else runCatching { packRepo.addStickers(s.targetId, s.newName, s.animated, files.filterNotNull()) }
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
