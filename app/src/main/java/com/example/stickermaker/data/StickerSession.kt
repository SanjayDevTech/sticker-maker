package com.example.stickermaker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
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
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** An image picked or shared in, copied into the session folder and ready to edit. */
data class SessionImage(val id: String, val file: File, val width: Int, val height: Int)

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

    private val _images = MutableStateFlow<List<SessionImage>>(emptyList())
    val images: StateFlow<List<SessionImage>> = _images.asStateFlow()

    /** How many picked images are still being imported (shown as skeletons). */
    private val _importing = MutableStateFlow(0)
    val importing: StateFlow<Int> = _importing.asStateFlow()

    /** Emits how many images of a batch couldn't be opened (unreadable, revoked access, not an image). */
    private val _importFailures = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val importFailures: SharedFlow<Int> = _importFailures.asSharedFlow()

    /** Set when a share arrives from another app; AppNavigation opens the create flow and clears it. */
    val openRequested = MutableStateFlow(false)

    init {
        // Nothing survives from a previous process: a new run starts clean.
        dir.deleteRecursively()
    }

    /** Starts a fresh session with [uris] (from the in-app picker). */
    fun startNew(uris: List<Uri>) {
        clear()
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
        _images.value = emptyList()
        scope.launch { dir.listFiles()?.forEach { it.delete() } }
    }

    private fun import(uri: Uri): SessionImage {
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

    companion object {
        /** Editing resolution. Output is 512 px; the headroom keeps zoomed pixel-erasing precise. */
        const val MAX_SOURCE_PX = 1280
        private const val TAG = "StickerSession"
    }
}
