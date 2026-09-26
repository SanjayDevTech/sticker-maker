package com.example.stickermaker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.example.stickermaker.editor.Composition
import com.example.stickermaker.editor.Gif
import com.example.stickermaker.editor.MaskHistory
import com.example.stickermaker.editor.StickerMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** What the picked images turn into. */
enum class SessionMode {
    /** Each image becomes its own sticker. */
    Separate,

    /** All images are cut out and composed as layers into one sticker. */
    Combine,
}

/**
 * An image picked or shared in, copied into the session folder and ready to edit. An animated
 * GIF ([isGif]) is kept as it came: it's trimmed rather than cut out.
 */
data class SessionImage(
    val id: String,
    val file: File,
    val width: Int,
    val height: Int,
    val isGif: Boolean = false,
    /** A GIF's full length. */
    val durationMs: Int = 0,
)

/** Where one combine layer sits (artboard units; see editor.Layer) and how it moves. */
data class LayerPlacement(val x: Float, val y: Float, val size: Float, val rotation: Float, val flipped: Boolean, val motion: StickerMotion)

/** The combine canvas's arrangement: stack order (bottom first) and each layer's placement. */
data class CombineLayout(val order: List<String>, val placements: Map<String, LayerPlacement>)

/** The combine canvas's undo and redo steps, kept with the layout so they survive leaving it. */
class CombineHistory {
    val undo = ArrayDeque<CombineLayout>()
    val redo = ArrayDeque<CombineLayout>()
}

/** The part of a GIF that becomes the sticker, in ms. */
data class Trim(val startMs: Int, val endMs: Int) {
    val lengthMs get() = endMs - startMs

    companion object {
        /** From the start, as much as WhatsApp allows. */
        fun default(durationMs: Int) = Trim(0, minOf(durationMs, WhatsAppLimits.ANIMATED_MAX_MS))
    }
}

/**
 * The images of the sticker currently being made, from pick/share through arrange and edit
 * until they're saved into a pack. Lives in the container (not a ViewModel) because it spans
 * several screens and must accept shares while those screens aren't composed yet.
 *
 * Imported copies are downscaled (long side ≤ [MAX_SOURCE_PX]), EXIF-rotated by ImageDecoder,
 * and stored as PNG so any transparency an image already has survives.
 */
class StickerSession(context: Context) {

    private val resolver = context.contentResolver
    private val dir = File(context.cacheDir, "session")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Each image's undo history, kept for the whole session so it survives switching images
     * and leaving the editor. Main thread only. Patches are small, so this stays cheap.
     */
    private val histories = HashMap<String, MaskHistory>()

    fun history(image: SessionImage): MaskHistory =
        histories.getOrPut(image.id) { MaskHistory(image.width, maxBytes = HISTORY_BYTES_PER_IMAGE) }

    /** One thread for mask files, so a save queued before a load is always written first. */
    private val maskIo = Dispatchers.IO.limitedParallelism(1)

    private val _images = MutableStateFlow<List<SessionImage>>(emptyList())
    val images: StateFlow<List<SessionImage>> = _images.asStateFlow()

    val mode = MutableStateFlow(SessionMode.Separate)

    /** How many picked images are still being imported (shown as skeletons). */
    private val _importing = MutableStateFlow(0)
    val importing: StateFlow<Int> = _importing.asStateFlow()

    /** Emits how many images of a batch couldn't be opened (unreadable, revoked access, not an image). */
    private val _importFailures = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val importFailures: SharedFlow<Int> = _importFailures.asSharedFlow()

    /** The pack these stickers are meant for (started from a pack's "Add stickers"), if any. */
    val targetPackId = MutableStateFlow<String?>(null)

    /** Each GIF's chosen part; missing means [Trim.default]. */
    private val _trims = MutableStateFlow<Map<String, Trim>>(emptyMap())
    val trims: StateFlow<Map<String, Trim>> = _trims.asStateFlow()

    fun trim(image: SessionImage): Trim = _trims.value[image.id] ?: Trim.default(image.durationMs)

    fun setTrim(image: SessionImage, trim: Trim) = _trims.update { it + (image.id to trim) }

    /** Parsed GIFs, kept for the session: frames decode on demand and reuse checkpoints. */
    private val gifs = HashMap<String, Gif>()

    /** The parsed GIF behind [image], or null if it's not one (or can't be read). Call off the main thread. */
    fun gif(image: SessionImage): Gif? {
        if (!image.isGif) return null
        synchronized(gifs) { gifs[image.id]?.let { return it } }
        val gif = runCatching { Gif.parse(image.file.readBytes()) }.getOrNull() ?: return null
        return synchronized(gifs) { gifs.getOrPut(image.id) { gif } }
    }

    /**
     * The combine canvas as last arranged, so leaving it (back to the editor, say) and
     * returning finds the layers where they were. Main thread.
     */
    var combineLayout: CombineLayout? = null

    /** The combine canvas's undo history, for the same reason. Main thread. */
    var combineHistory = CombineHistory()
        private set

    /** The combine canvas's layers (still or moving), waiting to be saved. */
    var combined: Composition? = null

    /** Set when a share arrives from another app; AppNavigation opens the create flow and clears it. */
    val openRequested = MutableStateFlow(false)

    init {
        // Nothing survives from a previous process: a new run starts clean.
        dir.deleteRecursively()
    }

    /** Starts a fresh session with [uris] (from the in-app picker), optionally aimed at a pack. */
    fun startNew(uris: List<Uri>, targetPackId: String? = null) {
        clear()
        this.targetPackId.value = targetPackId
        add(uris)
    }

    /** Appends [uris] (e.g. a share while already creating). */
    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _importing.update { it + uris.size }
        // Imports run in parallel but are appended in the order they were picked.
        scope.launch {
            val imported = uris.map { uri ->
                async {
                    runCatching { import(uri) }
                        .onFailure { Log.w(TAG, "Couldn't import $uri", it) }
                        .getOrNull()
                }
            }.awaitAll()
            _images.update { it + imported.filterNotNull() }
            _importing.update { it - uris.size }
            val failed = imported.count { it == null }
            if (failed > 0) _importFailures.emit(failed)
        }
    }

    fun move(from: Int, to: Int) = _images.update { list ->
        if (from !in list.indices || to !in list.indices) return@update list
        list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun remove(id: String) = _images.update { list -> list.filterNot { it.id == id } }

    fun restore(image: SessionImage, index: Int) = _images.update { list ->
        list.toMutableList().apply { add(index.coerceIn(0, size), image) }
    }

    fun clear() {
        // Delete only what this session owned: a new pick may already be importing into dir.
        val old = _images.value
        _images.value = emptyList()
        histories.clear()
        _trims.value = emptyMap()
        synchronized(gifs) { gifs.clear() }
        targetPackId.value = null
        combined = null
        combineLayout = null
        combineHistory = CombineHistory()
        mode.value = SessionMode.Separate
        scope.launch(maskIo) {
            old.forEach { it.file.delete(); maskFile(it).delete() }
        }
    }

    /** The editor's saved cut for [image] (alpha per pixel), or null if it hasn't been edited. */
    suspend fun readMask(image: SessionImage): ByteArray? = withContext(maskIo) {
        val file = maskFile(image)
        if (!file.exists()) return@withContext null
        runCatching { GZIPInputStream(file.inputStream()).use { it.readBytes() } }
            .onFailure { Log.w(TAG, "Couldn't read mask of ${image.id}", it) }
            .getOrNull()
            ?.takeIf { it.size == image.width * image.height }
    }

    /** Saves [mask] in the background. Masks are mostly long runs, so gzip shrinks them a lot. */
    fun writeMask(image: SessionImage, mask: ByteArray) {
        scope.launch(maskIo) {
            val file = maskFile(image)
            val tmp = File(dir, "${image.id}.mask.tmp")
            runCatching {
                GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(mask) }
                tmp.renameTo(file)
            }.onFailure { Log.w(TAG, "Couldn't save mask of ${image.id}", it) }
        }
    }

    private fun maskFile(image: SessionImage) = File(dir, "${image.id}.mask")

    private fun import(uri: Uri): SessionImage {
        importGif(uri)?.let { return it }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // editor needs pixel access
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_SOURCE_PX.toFloat() / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
        }
        dir.mkdirs()
        val id = UUID.randomUUID().toString().take(12)
        val file = File(dir, "$id.png")
        file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return SessionImage(id, file, bitmap.width, bitmap.height).also { bitmap.recycle() }
    }

    /** An animated GIF, copied as is; null for anything else (a one-frame GIF is just an image). */
    private fun importGif(uri: Uri): SessionImage? {
        val head = resolver.openInputStream(uri)?.use { it.readNBytes(6) } ?: return null
        if (!Gif.isGif(head)) return null
        val bytes = resolver.openInputStream(uri)?.use { it.readNBytes(MAX_GIF_BYTES + 1) } ?: return null
        if (bytes.size > MAX_GIF_BYTES) throw IllegalArgumentException("GIF over ${MAX_GIF_BYTES / 1024 / 1024} MB")
        val gif = Gif.parse(bytes) ?: return null
        if (gif.frameCount < 2) return null
        dir.mkdirs()
        val id = UUID.randomUUID().toString().take(12)
        val file = File(dir, "$id.gif")
        file.writeBytes(bytes)
        synchronized(gifs) { gifs[id] = gif }
        return SessionImage(id, file, gif.width, gif.height, isGif = true, durationMs = gif.durationMs)
    }

    companion object {
        private const val MAX_GIF_BYTES = 40 * 1024 * 1024

        /** Editing resolution. Output is 512 px; the headroom keeps zoomed pixel-erasing precise. */
        const val MAX_SOURCE_PX = 1280
        private const val HISTORY_BYTES_PER_IMAGE = 16 * 1024 * 1024
        private const val TAG = "StickerSession"
    }
}
