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
    /** Images left out because they couldn't be read (a broken GIF, a deleted file). */
    val unreadable: Int = 0,
    /** Per sticker (aligned with [previews]): a white outline. Clips ignore it. */
    val outlines: List<Boolean> = emptyList(),
    /**
     * Per sticker: how it moves. Clips ignore it. Still ones next to moving ones are saved as
     * animated files that hold still: a pack is all still or all animated.
     */
    val motions: List<StickerMotion> = emptyList(),
    /** How each preview moves, aligned with [previews]; null while still. */
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
    /** Anything moving makes the batch animated: it needs an animated pack. */
    val animated get() = clips.isNotEmpty() || stillIndices.any { motions[it] != StickerMotion.None }

    /** Stickers that are stills (outline and motion apply to them). */
    val stillIndices: List<Int> get() = previews.indices.filter { it !in clips }
    val hasStills get() = count > clips.size

    /** The outline every still shares; null when they differ. */
    val outline: Boolean? get() = stillIndices.map { outlines[it] }.distinct().singleOrNull()

    /** The motion every still shares; null when they differ. */
    val motion: StickerMotion? get() = stillIndices.map { motions[it] }.distinct().singleOrNull()
    fun hasRoom(pack: StickerPack) = pack.stickers.size + count <= WhatsAppLimits.MAX_STICKERS

    /**
     * Has room, and can take these: an animated pack takes anything (stills join it holding
     * still); a still pack only stills.
     */
    fun accepts(pack: StickerPack) = hasRoom(pack) && (pack.animated || !animated)
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
    private var outlined: Array<Bitmap?> = emptyArray()
    private var clips: Map<Int, StickerClip> = emptyMap()

    private class Rendered(val still: Bitmap, val clip: StickerClip? = null)

    /** How loading one image went. */
    private sealed interface Outcome {
        class Ok(val rendered: Rendered) : Outcome
        data object Erased : Outcome
        data object Unreadable : Outcome
    }

    init {
        viewModelScope.launch {
            val combined = session.combined
            val outcomes: List<Outcome> = if (session.mode.value == SessionMode.Combine && combined != null) {
                listOf(Outcome.Ok(withContext(Dispatchers.Default) { Rendered(combined.still(), combined.takeIf { it.animated }) }))
            } else {
                session.images.value.map { image ->
                    async(Dispatchers.Default) {
                        runCatching {
                            if (image.isGif) {
                                val gif = session.gif(image) ?: return@runCatching Outcome.Unreadable
                                val trim = session.trim(image)
                                val first = StickerExport.fitGifFrame(gif, gif.frameAt(trim.startMs), WhatsAppLimits.STICKER_PX)
                                val px = WhatsAppLimits.STICKER_PX
                                Outcome.Ok(Rendered(Bitmap.createBitmap(first, px, px, Bitmap.Config.ARGB_8888), GifStickerClip(gif, trim)))
                            } else {
                                Compositor.cutout(image.file, session.readMask(image))?.let { Outcome.Ok(Rendered(StickerExport.fit(it))) }
                                    ?: Outcome.Erased
                            }
                        }.onFailure { Log.w(TAG, "Couldn't render ${image.id}", it) }.getOrDefault(Outcome.Unreadable)
                    }
                }.awaitAll()
            }
            val kept = outcomes.filterIsInstance<Outcome.Ok>().map { it.rendered }
            plain = kept.map { it.still }
            outlined = arrayOfNulls(plain.size)
            clips = kept.withIndex().mapNotNull { (i, r) -> r.clip?.let { i to it } }.toMap()
            local.update { s ->
                s.copy(
                    skipped = outcomes.count { it == Outcome.Erased },
                    unreadable = outcomes.count { it == Outcome.Unreadable },
                    clips = clips,
                    outlines = List(plain.size) { false },
                    motions = List(plain.size) { StickerMotion.None },
                )
            }
            refresh()
        }
    }

    /** The outline for every still. */
    fun setOutline(on: Boolean) = updateStills { s -> s.copy(outlines = s.outlines.map { on }) }

    /** The outline for sticker [index] alone. */
    fun setOutline(index: Int, on: Boolean) = updateStills { s ->
        s.copy(outlines = s.outlines.mapIndexed { i, o -> if (i == index) on else o })
    }

    /** The motion for every still. */
    fun setMotion(motion: StickerMotion) {
        updateStills { s -> s.copy(motions = s.motions.mapIndexed { i, m -> if (i in clips) m else motion }) }
    }

    /** The motion for sticker [index] alone. */
    fun setMotion(index: Int, motion: StickerMotion) = updateStills { s ->
        s.copy(motions = s.motions.mapIndexed { i, m -> if (i == index) motion else m })
    }

    private fun updateStills(change: (SaveUiState) -> SaveUiState) {
        local.update { s ->
            val next = change(s)
            // A pack that can't take them now: fall back to a new pack.
            val keep = next.target?.let { it.animated || !next.animated } ?: true
            if (keep) next else next.copy(targetId = null)
        }
        viewModelScope.launch { refresh() }
    }

    /** Re-renders previews and motion plans for each sticker's outline and motion. */
    private suspend fun refresh() {
        val outlines = local.value.outlines
        val motions = local.value.motions
        val stills = plain.indices.map { stillFor(it, outlines[it]) }
        val plans = withContext(Dispatchers.Default) {
            stills.indices.map { i -> if (i in clips) null else planFor(i, outlines[i], motions[i]) }
        }
        // A quick double toggle: only show what's currently chosen.
        val now = local.value
        if (now.outlines != outlines || now.motions != motions) return
        local.update { it.copy(loading = false, previews = stills.map(Bitmap::asImageBitmap), plans = plans) }
        prefetch()
    }

    /** Sticker [index]'s still, outlined or not (clips never are). Outlines are made once, on demand. */
    private suspend fun stillFor(index: Int, outline: Boolean): Bitmap {
        if (!outline || index in clips) return plain[index]
        outlined[index]?.let { return it }
        return withContext(Dispatchers.Default) { StickerExport.withOutline(plain[index]) }.also { outlined[index] = it }
    }

    private val plans = HashMap<Key, MotionPlan?>()

    private fun planFor(index: Int, outline: Boolean, motion: StickerMotion): MotionPlan? {
        if (motion == StickerMotion.None) return null
        val key = Key(index, outline, motion)
        synchronized(plans) { if (key in plans) return plans[key] }
        val still = if (outline) outlined[index] ?: return null else plain[index]
        return StickerExport.plan(still, motion).also { synchronized(plans) { plans[key] = it } }
    }

    /**
     * A clip's file depends on neither outline nor motion: its key leaves them out. [held]: a
     * still saved as an animated file that doesn't move (for an animated pack).
     */
    private data class Key(val index: Int, val outline: Boolean, val motion: StickerMotion, val gif: Boolean = false, val held: Boolean = false)

    private fun keyFor(index: Int, outline: Boolean, motion: StickerMotion, held: Boolean) = when {
        index in clips -> Key(index, outline = false, motion = StickerMotion.None, gif = true)
        motion == StickerMotion.None -> Key(index, outline, motion, held = held)
        else -> Key(index, outline, motion)
    }

    /**
     * Finished WebP of each sticker per outline and motion, built once and shared: a copy or
     * save that arrives while it's still encoding waits for the same work.
     */
    private val encoded = HashMap<Key, Deferred<ByteArray?>>()

    private fun encode(index: Int, outline: Boolean, motion: StickerMotion, held: Boolean = false): Deferred<ByteArray?> {
        val key = keyFor(index, outline, motion, held)
        encoded[key]?.let { if (!it.isCancelled) return it }
        val clip = clips[index]
        return viewModelScope.async {
            if (index !in plain.indices) return@async null
            val still = stillFor(index, outline)
            try {
                withContext(Dispatchers.Default) {
                    if (clip != null) {
                        StickerExport.encodeClip(clip)
                    } else if (motion == StickerMotion.None) {
                        if (key.held) StickerExport.encodeHeld(still) else StickerExport.encodeSticker(still)
                    } else {
                        planFor(index, outline, motion)?.let { StickerExport.encodeAnimated(still, it) }
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
            (!key.gif && (key.outline != s.outlines[key.index] || key.motion != s.motions[key.index])).also { stale -> if (stale) job.cancel() }
        }
        if (!s.animated) return
        prefetch = viewModelScope.launch {
            delay(PREFETCH_DELAY_MS)
            plain.indices.forEach { encode(it, s.outlines[it], s.motions[it]).await() }
        }
    }

    /** Sticker [index] as it would be saved (its outline and motion included), for copying without a pack. */
    suspend fun stickerAt(index: Int): ByteArray? {
        val s = local.value
        if (index !in s.outlines.indices) return null
        return encode(index, s.outlines[index], s.motions[index]).await()
    }

    fun setTarget(id: String?) = local.update { it.copy(targetId = id) }
    fun setNewName(name: String) = local.update { it.copy(newName = name) }

    fun save() {
        val s = state.value
        if (!s.canSave) return
        local.update { it.copy(saving = true) }
        viewModelScope.launch {
            // Into an animated pack, stills go as animated files that hold still.
            val animatedPack = s.target?.animated ?: s.animated
            val files = runCatching {
                plain.indices.map { i -> encode(i, s.outlines[i], s.motions[i], held = animatedPack) }.awaitAll()
            }.getOrNull()
            val id = if (files == null || files.any { it == null }) {
                Log.w(TAG, "Couldn't encode every sticker")
                null
            } else runCatching { packRepo.addStickers(s.targetId, s.newName, animatedPack, files.filterNotNull()) }
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
