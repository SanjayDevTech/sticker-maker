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
import com.example.stickermaker.editor.GifStickerClip
import com.example.stickermaker.editor.StickerClip
import com.example.stickermaker.editor.MotionPlan
import com.example.stickermaker.editor.StickerExport
import com.example.stickermaker.editor.StickerMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    /**
     * Stickers that are already moving (by index): GIFs and moving combinations. They play as
     * they are, with no outline or motion of their own.
     */
    val clips: Map<Int, StickerClip> = emptyMap(),
    val packs: List<StickerPack> = emptyList(),
    /** The pack to add to; null means a new pack called [newName]. */
    val targetId: String? = null,
    val newName: String = "",
    val saving: Boolean = false,
) {
    val count get() = previews.size
    /** Any clip makes the whole batch animated: a pack can't mix still and moving stickers. */
    val animated get() = motion != StickerMotion.None || clips.isNotEmpty()

    /** Some stickers are stills (outline and motion apply to them). */
    val hasStills get() = count > clips.size
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

    /** Each sticker as a still: the cut-out, or a clip's first frame. */
    private var plain: List<Bitmap> = emptyList()
    private var outlined: List<Bitmap>? = null
    private var clips: Map<Int, StickerClip> = emptyMap()

    private class Rendered(val still: Bitmap, val clip: StickerClip? = null)

    init {
        viewModelScope.launch {
            val combined = session.combined
            val rendered: List<Rendered?> = if (session.mode.value == SessionMode.Combine && combined != null) {
                listOf(withContext(Dispatchers.Default) { Rendered(combined.still(), combined.takeIf { it.animated }) })
            } else {
                session.images.value.map { image ->
                    async(Dispatchers.Default) {
                        runCatching {
                            if (image.isGif) {
                                val gif = session.gif(image) ?: return@runCatching null
                                val trim = session.trim(image)
                                val first = StickerExport.fitGifFrame(gif, gif.frameAt(trim.startMs), WhatsAppLimits.STICKER_PX)
                                val px = WhatsAppLimits.STICKER_PX
                                Rendered(Bitmap.createBitmap(first, px, px, Bitmap.Config.ARGB_8888), GifStickerClip(gif, trim))
                            } else {
                                Compositor.cutout(image.file, session.readMask(image))?.let { Rendered(StickerExport.fit(it)) }
                            }
                        }.onFailure { Log.w(TAG, "Couldn't render ${image.id}", it) }.getOrNull()
                    }
                }.awaitAll()
            }
            val kept = rendered.filterNotNull()
            plain = kept.map { it.still }
            clips = kept.withIndex().mapNotNull { (i, r) -> r.clip?.let { i to it } }.toMap()
            local.update { s ->
                s.copy(
                    skipped = rendered.count { it == null },
                    clips = clips,
                    // Stills next to a moving one must move too (all-animated pack): give them a motion.
                    motion = if (clips.isNotEmpty() && s.motion == StickerMotion.None) StickerMotion.Wiggle else s.motion,
                )
            }
            refresh()
        }
    }

    fun setOutline(on: Boolean) {
        local.update { it.copy(outline = on) }
        viewModelScope.launch { refresh() }
    }

    fun setMotion(motion: StickerMotion) {
        // Next to a moving sticker, stills can't stay still (see init).
        if (motion == StickerMotion.None && clips.isNotEmpty()) return
        local.update { s ->
            // A pack that's the wrong kind now can't take them: fall back to a new pack.
            val keep = s.target?.let { it.animated == (motion != StickerMotion.None || clips.isNotEmpty()) } ?: true
            s.copy(motion = motion, targetId = if (keep) s.targetId else null)
        }
        viewModelScope.launch { refresh() }
    }

    /** Re-renders previews and motion plans for the current outline and motion. */
    private suspend fun refresh() {
        val outline = local.value.outline
        val motion = local.value.motion
        val stills = stills(outline)
        val plans = withContext(Dispatchers.Default) {
            stills.mapIndexed { i, still -> if (i in clips) null else StickerExport.plan(still, motion) }
        }
        // A quick double toggle: only show what's currently chosen.
        val now = local.value
        if (now.outline != outline || now.motion != motion) return
        local.update {
            it.copy(loading = false, previews = stills.map(Bitmap::asImageBitmap), plans = if (motion == StickerMotion.None) emptyList() else plans)
        }
        prefetch()
    }

    private suspend fun stills(outline: Boolean): List<Bitmap> = if (outline) {
        outlined ?: withContext(Dispatchers.Default) {
            plain.mapIndexed { i, still -> if (i in clips) still else StickerExport.withOutline(still) }
        }.also { outlined = it }
    } else {
        plain
    }

    /** A clip's file depends on neither outline nor motion: its key leaves them out. */
    private data class Key(val index: Int, val outline: Boolean, val motion: StickerMotion, val gif: Boolean = false)

    private fun keyFor(index: Int, outline: Boolean, motion: StickerMotion) =
        if (index in clips) Key(index, outline = false, motion = StickerMotion.None, gif = true) else Key(index, outline, motion)

    /**
     * Finished WebP of each sticker per outline and motion, built once and shared: a copy or
     * save that arrives while it's still encoding waits for the same work.
     */
    private val encoded = HashMap<Key, Deferred<ByteArray?>>()

    private fun encode(index: Int, outline: Boolean, motion: StickerMotion): Deferred<ByteArray?> {
        val key = keyFor(index, outline, motion)
        encoded[key]?.let { if (!it.isCancelled) return it }
        val clip = clips[index]
        return viewModelScope.async {
            val still = stills(outline).getOrNull(index) ?: return@async null
            try {
                withContext(Dispatchers.Default) {
                    if (clip != null) {
                        StickerExport.encodeClip(clip)
                    } else if (motion == StickerMotion.None) {
                        StickerExport.encodeSticker(still)
                    } else {
                        StickerExport.plan(still, motion)?.let { StickerExport.encodeAnimated(still, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't encode sticker $index", e)
                null
            }
        }.also { encoded[key] = it }
    }

    private var prefetch: Job? = null

    /**
     * Moving stickers take a second or more each, so once a choice has settled they start
     * encoding in the background: Copy and Save then usually find them done. Work for choices
     * since left behind is cancelled.
     */
    private fun prefetch() {
        prefetch?.cancel()
        val s = local.value
        encoded.entries.removeAll { (key, job) ->
            (!key.gif && (key.outline != s.outline || key.motion != s.motion)).also { stale -> if (stale) job.cancel() }
        }
        if (!s.animated) return
        prefetch = viewModelScope.launch {
            delay(PREFETCH_DELAY_MS)
            plain.indices.forEach { encode(it, s.outline, s.motion).await() }
        }
    }

    /** Sticker [index] as it would be saved (outline and motion included), for copying without a pack. */
    suspend fun stickerAt(index: Int): ByteArray? {
        val s = local.value
        return encode(index, s.outline, s.motion).await()
    }

    fun setTarget(id: String?) = local.update { it.copy(targetId = id) }
    fun setNewName(name: String) = local.update { it.copy(newName = name) }

    fun save() {
        val s = state.value
        if (!s.canSave) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            val files = runCatching {
                plain.indices.map { i -> encode(i, s.outline, s.motion) }.awaitAll()
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
        const val PREFETCH_DELAY_MS = 700L
        fun defaultName(packs: List<StickerPack>) = if (packs.isEmpty()) "My stickers" else "My stickers ${packs.size + 1}"
    }
}
