package com.example.stickermaker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import com.example.stickermaker.editor.Composition
import com.example.stickermaker.editor.Animation
import com.example.stickermaker.editor.Gif
import com.example.stickermaker.editor.MaskHistory
import com.example.stickermaker.editor.StickerMotion
import com.example.stickermaker.editor.VideoFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
 * GIF or a video ([isAnimated]) is trimmed rather than cut out. A video's [file] is its first
 * frame; its frames live in [VideoFrames] beside it.
 */
data class SessionImage(
    val id: String,
    val file: File,
    val width: Int,
    val height: Int,
    val isAnimated: Boolean = false,
    /** A GIF's or video's full length (a video's first minute at most). */
    val durationMs: Int = 0,
    val isVideo: Boolean = false,
)

/** Where one combine layer sits (artboard units; see editor.Layer) and how it moves. */
data class LayerPlacement(
    val x: Float,
    val y: Float,
    val size: Float,
    val rotation: Float,
    val flipped: Boolean,
    val motion: StickerMotion,
    val outline: Boolean = false,
)

/** The combine canvas's arrangement: stack order (bottom first) and each layer's placement. */
data class CombineLayout(val order: List<String>, val placements: Map<String, LayerPlacement>)

/** The combine canvas's undo and redo steps, kept with the layout so they survive leaving it. */
class CombineHistory {
    val undo = ArrayDeque<CombineLayout>()
    val redo = ArrayDeque<CombineLayout>()
}

/** The part of a GIF or video that becomes the sticker, in ms. */
data class Trim(val startMs: Int, val endMs: Int) {
    val lengthMs get() = endMs - startMs

    companion object {
        /**
         * From the start: a GIF as much as WhatsApp allows, a video [VIDEO_DEFAULT_MS] (busy
         * frames fit 500 KB far better in a short clip; the window can still grow to 10 s).
         */
        fun default(durationMs: Int, video: Boolean = false) =
            Trim(0, minOf(durationMs, if (video) VIDEO_DEFAULT_MS else WhatsAppLimits.ANIMATED_MAX_MS))

        const val VIDEO_DEFAULT_MS = 3_000
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

    /** One picked image still being imported (shown as a skeleton). */
    class PendingImport internal constructor(val id: Long) {
        /** How far a video's start has loaded (0..1); null while that isn't known (photos, GIFs). */
        val progress = MutableStateFlow<Float?>(null)
    }

    private var nextPendingId = 0L
    private val _importing = MutableStateFlow<List<PendingImport>>(emptyList())
    val importing: StateFlow<List<PendingImport>> = _importing.asStateFlow()

    /** How far [image]'s frames have loaded, for a video still loading after import; else null. */
    fun loading(image: SessionImage): StateFlow<VideoFrames.Loaded>? =
        if (image.isVideo) (synchronized(gifs) { gifs[image.id] } as? VideoFrames)?.loaded else null

    /**
     * What couldn't be opened from a batch: [images] and [videos] that were unreadable (revoked
     * access, broken file, not media), and [unplayable] videos in a format no decoder on this
     * phone handles.
     */
    data class ImportFailures(val images: Int, val videos: Int, val unplayable: Int)

    private val _importFailures = MutableSharedFlow<ImportFailures>(extraBufferCapacity = 4)
    val importFailures: SharedFlow<ImportFailures> = _importFailures.asSharedFlow()

    /** The pack these stickers are meant for (started from a pack's "Add stickers"), if any. */
    val targetPackId = MutableStateFlow<String?>(null)

    /** Each GIF's or video's chosen part; missing means [Trim.default]. */
    private val _trims = MutableStateFlow<Map<String, Trim>>(emptyMap())
    val trims: StateFlow<Map<String, Trim>> = _trims.asStateFlow()

    fun trim(image: SessionImage): Trim = _trims.value[image.id] ?: Trim.default(image.durationMs, image.isVideo)

    fun setTrim(image: SessionImage, trim: Trim) = _trims.update { it + (image.id to trim) }

    /** Opened GIFs and videos, kept for the session: GIF frames decode on demand and reuse checkpoints. */
    private val gifs = HashMap<String, Animation>()

    /** The GIF or video behind [image], or null if it's neither (or can't be read). Call off the main thread. */
    fun gif(image: SessionImage): Animation? {
        if (!image.isAnimated) return null
        synchronized(gifs) { gifs[image.id]?.let { return it } }
        val gif = runCatching {
            if (image.isVideo) VideoFrames.open(framesDir(image.id)) else Gif.parse(image.file.readBytes())
        }.getOrNull() ?: return null
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
        val pending = uris.map { PendingImport(nextPendingId++) }
        _importing.update { it + pending }
        // Imports run in parallel but are appended in the order they were picked.
        scope.launch {
            val results = uris.mapIndexed { i, uri ->
                async {
                    runCatching { import(uri, pending[i]) }
                        .onFailure { Log.w(TAG, "Couldn't import $uri", it) }
                }
            }.awaitAll()
            _images.update { it + results.mapNotNull { r -> r.getOrNull() } }
            reorderVideos()
            _importing.update { list -> list.filterNot { it in pending } }
            val failed = uris.zip(results).filter { (_, r) -> r.isFailure }
            if (failed.isNotEmpty()) {
                val unplayable = failed.count { (_, r) -> r.exceptionOrNull() is VideoFrames.UnsupportedVideo }
                val videos = failed.count { (uri, r) ->
                    r.exceptionOrNull() !is VideoFrames.UnsupportedVideo && resolver.getType(uri)?.startsWith("video/") == true
                }
                _importFailures.emit(ImportFailures(failed.size - unplayable - videos, videos, unplayable))
            }
        }
    }

    fun move(from: Int, to: Int) {
        _images.update { list ->
            if (from !in list.indices || to !in list.indices) return@update list
            list.toMutableList().apply { add(to, removeAt(from)) }
        }
        reorderVideos()
    }

    fun remove(id: String) {
        _images.update { list -> list.filterNot { it.id == id } }
        reorderVideos()
    }

    fun restore(image: SessionImage, index: Int) {
        _images.update { list -> list.toMutableList().apply { add(index.coerceIn(0, size), image) } }
        reorderVideos()
    }

    /** Videos load the rest of their frames in list order; removed ones (still undoable) last. */
    private fun reorderVideos() {
        val position = _images.value.withIndex().associate { (i, image) -> image.id to i }
        synchronized(gifs) {
            for ((id, gif) in gifs) (gif as? VideoFrames)?.order = position[id] ?: REMOVED_ORDER
        }
    }

    fun clear() {
        // Delete only what this session owned: a new pick may already be importing into dir.
        val old = _images.value
        _images.value = emptyList()
        histories.clear()
        _trims.value = emptyMap()
        synchronized(gifs) {
            gifs.values.forEach { (it as? VideoFrames)?.close() }
            gifs.clear()
        }
        targetPackId.value = null
        combined = null
        combineLayout = null
        combineHistory = CombineHistory()
        mode.value = SessionMode.Separate
        scope.launch(maskIo) {
            old.forEach { it.file.delete(); maskFile(it).delete(); framesDir(it.id).deleteRecursively() }
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

    private fun framesDir(id: String) = File(dir, "$id.frames")

    private fun import(uri: Uri, pending: PendingImport): SessionImage {
        if (resolver.getType(uri)?.startsWith("video/") == true) return importVideo(uri, pending)
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
        val head = resolver.openInputStream(uri)?.use { it.readUpTo(6) } ?: return null
        if (!Gif.isGif(head)) return null
        val bytes = resolver.openInputStream(uri)?.use { it.readUpTo(MAX_GIF_BYTES + 1) } ?: return null
        if (bytes.size > MAX_GIF_BYTES) throw IllegalArgumentException("GIF over ${MAX_GIF_BYTES / 1024 / 1024} MB")
        val gif = Gif.parse(bytes) ?: return null
        if (gif.frameCount < 2) return null
        dir.mkdirs()
        val id = UUID.randomUUID().toString().take(12)
        val file = File(dir, "$id.gif")
        file.writeBytes(bytes)
        synchronized(gifs) { gifs[id] = gif }
        return SessionImage(id, file, gif.width, gif.height, isAnimated = true, durationMs = gif.durationMs)
    }

    /**
     * A video, decoded into frames (its first minute; sound dropped). Returns once the default
     * trim's frames are ready; the rest keeps loading while the user edits.
     */
    private fun importVideo(uri: Uri, pending: PendingImport): SessionImage {
        val id = UUID.randomUUID().toString().take(12)
        val headMs = Trim.VIDEO_DEFAULT_MS
        var watching: Job? = null
        val frames = try {
            VideoFrames.extract(
                { requireNotNull(resolver.openFileDescriptor(uri, "r")) },
                framesDir(id),
                headMs = headMs,
                onLoading = { loading ->
                    // Progress is known only while frames load progressively (not on the slow fallback).
                    watching?.cancel()
                    pending.progress.value = null
                    watching = loading?.let { f ->
                        scope.launch {
                            val need = minOf(headMs, f.durationMs).coerceAtLeast(1)
                            f.loaded.collect { pending.progress.value = (it.readyMs.toFloat() / need).coerceAtMost(1f) }
                        }
                    }
                },
            )
        } catch (e: Exception) {
            framesDir(id).deleteRecursively()
            throw e
        } finally {
            watching?.cancel()
        }
        if (frames == null) {
            framesDir(id).deleteRecursively()
            throw IllegalArgumentException("Couldn't read any frames from $uri")
        }
        // The first frame stands in for the video wherever a still is shown.
        val file = File(dir, "$id.jpg")
        File(framesDir(id), "00000.jpg").copyTo(file, overwrite = true)
        synchronized(gifs) { gifs[id] = frames }
        return SessionImage(id, file, frames.width, frames.height, isAnimated = true, durationMs = frames.durationMs, isVideo = true)
    }

    /** Up to [n] bytes (fewer at the end). InputStream.readNBytes needs API 33. */
    private fun InputStream.readUpTo(n: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(n, 1 shl 16))
        val buffer = ByteArray(8192)
        while (out.size() < n) {
            val read = read(buffer, 0, minOf(buffer.size, n - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_GIF_BYTES = 40 * 1024 * 1024
        private const val REMOVED_ORDER = 10_000

        /** Editing resolution. Output is 512 px; the headroom keeps zoomed pixel-erasing precise. */
        const val MAX_SOURCE_PX = 1280
        private const val HISTORY_BYTES_PER_IMAGE = 16 * 1024 * 1024
        private const val TAG = "StickerSession"
    }
}
