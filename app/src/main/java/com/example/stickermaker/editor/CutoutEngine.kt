package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.stickermaker.editor.MaskOps.Box
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cuts one image: owns its pixels, the alpha mask being edited, the undo history and the
 * bitmap the canvas draws.
 *
 * Every mask edit happens on the main thread (they're cheap: a brush stamp is at most
 * 300×300 px), then only the touched box is re-rendered into [display] and [version] is
 * bumped. The canvas reads [version] in its draw phase, so an edit redraws without
 * recomposing. Flood fills are the one costly step; their region is found off the main thread
 * from the pixels alone (which never change), then applied on main.
 */
@Stable
class CutoutEngine private constructor(
    val width: Int,
    val height: Int,
    private val pixels: IntArray,
    private val mask: ByteArray,
    source: Bitmap,
    private val history: MaskHistory,
) {
    /** The untouched image, drawn faintly under the cut while restoring. */
    val sourceImage: ImageBitmap = source.asImageBitmap()

    private val display: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val displayImage: ImageBitmap = display.asImageBitmap()

    /** Bumped after every change to [display]; read it in the draw phase. */
    var version by mutableIntStateOf(0)
        private set

    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo

    /** The mask as it was before the edit in progress. Reused: one full copy per edit. */
    private val before = ByteArray(mask.size)
    private val row = IntArray(width)

    init {
        refresh(Box(0, 0, width, height))
    }

    // ---- Brush ---------------------------------------------------------------------------

    private var stroking = false
    private var strokeBox = Box.EMPTY
    private var strokeValue = MaskOps.ERASED
    private var strokeRadius = 0f
    private var lastX = 0f
    private var lastY = 0f

    /** Starts a stroke at image point ([x], [y]); [radius] < 1 paints single pixels. */
    fun beginStroke(x: Float, y: Float, radius: Float, erase: Boolean) {
        if (stroking) cancelStroke()
        lastFill = null
        mask.copyInto(before)
        stroking = true
        strokeValue = if (erase) MaskOps.ERASED else MaskOps.KEPT
        strokeRadius = radius
        lastX = x
        lastY = y
        strokeBox = MaskOps.stamp(mask, width, height, x, y, radius, strokeValue)
        refresh(strokeBox)
    }

    fun extendStroke(x: Float, y: Float) {
        if (!stroking) return
        val box = MaskOps.stampLine(mask, width, height, lastX, lastY, x, y, strokeRadius, strokeValue)
        lastX = x
        lastY = y
        strokeBox = strokeBox.union(box)
        refresh(box)
    }

    fun endStroke() {
        if (!stroking) return
        stroking = false
        history.record(before, mask, strokeBox)
    }

    /** Rolls the stroke back, e.g. when a second finger lands and the gesture becomes a pinch. */
    fun cancelStroke() {
        if (!stroking) return
        stroking = false
        if (strokeBox.isEmpty) return
        MaskOps.pasteBox(mask, width, strokeBox, MaskOps.copyBox(before, width, strokeBox))
        refresh(strokeBox)
    }

    // ---- Auto (flood fill) ---------------------------------------------------------------

    private class Fill(val x: Int, val y: Int, val erase: Boolean) {
        /** Whether the fill's current version is on top of the undo stack (it changed pixels). */
        var recorded = false
    }

    /** The last fill, while it's still the latest edit, so the tolerance slider can redo it. */
    private var lastFill: Fill? = null
    private val fillLock = Mutex()
    private val scratch by lazy { MaskOps.Scratch(width * height) }

    val canRefill get() = lastFill != null

    /** Erases (or restores) the colour region touching image pixel ([x], [y]). Call from main. */
    suspend fun fill(x: Int, y: Int, tolerance: Float, erase: Boolean) {
        if (x !in 0 until width || y !in 0 until height) return
        val region = findRegion(x, y, tolerance)
        if (stroking) return // a stroke started while we computed; it wins
        lastFill = Fill(x, y, erase).also { it.recorded = applyFill(region, erase) }
    }

    /** Redoes the last fill with a new [tolerance], replacing it in the history. */
    suspend fun refill(tolerance: Float) {
        val fill = lastFill ?: return
        val region = findRegion(fill.x, fill.y, tolerance)
        if (lastFill !== fill || stroking) return
        if (fill.recorded) history.undo(mask)?.let(::refresh)
        fill.recorded = applyFill(region, fill.erase)
    }

    private suspend fun findRegion(x: Int, y: Int, tolerance: Float): MaskOps.Region =
        fillLock.withLock {
            withContext(Dispatchers.Default) {
                MaskOps.floodRegion(pixels, width, height, x, y, tolerance, scratch)
            }
        }

    private fun applyFill(region: MaskOps.Region, erase: Boolean): Boolean {
        mask.copyInto(before)
        MaskOps.apply(mask, region, if (erase) MaskOps.ERASED else MaskOps.KEPT)
        refresh(region.bounds)
        return history.record(before, mask, region.bounds)
    }

    // ---- History -------------------------------------------------------------------------

    fun undo() {
        if (stroking) return
        lastFill = null
        history.undo(mask)?.let(::refresh)
    }

    fun redo() {
        if (stroking) return
        lastFill = null
        history.redo(mask)?.let(::refresh)
    }

    /** A copy of the mask to save; call from main. */
    fun snapshot(): ByteArray = mask.copyOf()

    // ---- Rendering -----------------------------------------------------------------------

    /** Re-renders [box] of [display]: source pixels with their alpha scaled by the mask. */
    private fun refresh(box: Box) {
        if (box.isEmpty) return
        val w = box.width
        for (y in box.top until box.bottom) {
            val base = y * width + box.left
            for (i in 0 until w) {
                val m = mask[base + i].toInt() and 0xFF
                val p = pixels[base + i]
                row[i] = when (m) {
                    0 -> 0
                    255 -> p
                    else -> (((p ushr 24) * m / 255) shl 24) or (p and 0xFFFFFF)
                }
            }
            display.setPixels(row, 0, w, box.left, y, w, 1)
        }
        version++
    }

    companion object {
        /**
         * Decodes [file] and applies [savedMask] if there is one. [history] belongs to the
         * image, not the engine, so undo survives switching images. Call off the main thread.
         */
        fun load(file: File, savedMask: ByteArray?, history: MaskHistory): CutoutEngine {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val source = requireNotNull(BitmapFactory.decodeFile(file.path, options)) { "Couldn't decode $file" }
            val w = source.width
            val h = source.height
            val pixels = IntArray(w * h)
            source.getPixels(pixels, 0, w, 0, 0, w, h)
            val mask = savedMask?.takeIf { it.size == w * h } ?: ByteArray(w * h) { MaskOps.KEPT }
            source.prepareToDraw()
            return CutoutEngine(w, h, pixels, mask, source, history)
        }
    }
}
