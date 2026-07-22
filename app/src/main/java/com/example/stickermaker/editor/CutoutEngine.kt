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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
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
        // A finished stroke locks the last fill and the Magic marks in; a cancelled one doesn't.
        if (history.record(before, mask, strokeBox)) {
            lastFill = null
            endMagic()
        }
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
        endMagic()
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
        if (stroking || drawingMark != null) return
        lastFill = null
        magic?.let { session ->
            // Inside a Magic session, undo takes back the last run *and* the mark that caused it.
            while (session.marks.lastOrNull()?.recorded == false) session.marks.removeAt(session.marks.lastIndex)
            val mark = session.marks.removeLastOrNull()
            if (mark != null) {
                session.undone.addLast(mark)
                history.undo(mask)?.let(::refresh)
                session.rebuildSeeds()
                version++
                return
            }
            endMagic()
        }
        history.undo(mask)?.let(::refresh)
    }

    fun redo() {
        if (stroking || drawingMark != null) return
        lastFill = null
        magic?.let { session ->
            val mark = session.undone.removeLastOrNull()
            if (mark != null) {
                history.redo(mask)?.let(::refresh)
                session.marks += mark
                session.rebuildSeeds()
                version++
                return
            }
            endMagic()
        }
        history.redo(mask)?.let(::refresh)
    }

    // ---- Magic ---------------------------------------------------------------------------

    /** A Keep or Remove scribble, in image pixels. */
    class Mark(val keep: Boolean, val radius: Float) {
        private var xy = FloatArray(64)
        var size = 0
            private set

        /** Whether this mark's run changed the mask (and so owns one undo step). */
        var recorded = false

        fun add(x: Float, y: Float) {
            if (size + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
            xy[size++] = x
            xy[size++] = y
        }

        fun x(i: Int) = xy[i * 2]
        fun y(i: Int) = xy[i * 2 + 1]
        val pointCount get() = size / 2
    }

    /**
     * Marks since the user started using Magic. [base] is the mask before the first run: what
     * was erased then stays erased. Ends with any non-Magic edit, which locks the result in.
     */
    private inner class MagicSession(val base: ByteArray) {
        /** The image prepared once for the whole session; runs warm-start from each other. */
        var prepared: Segmenter.Prepared? = null
        val marks = mutableListOf<Mark>()
        val undone = ArrayDeque<Mark>()
        val seeds = ByteArray(width * height)

        fun rasterize(mark: Mark) {
            val value = if (mark.keep) Segmenter.SEED_KEEP else Segmenter.SEED_REMOVE
            if (mark.pointCount == 1) {
                MaskOps.stamp(seeds, width, height, mark.x(0), mark.y(0), mark.radius, value)
            }
            for (i in 1 until mark.pointCount) {
                MaskOps.stampLine(seeds, width, height, mark.x(i - 1), mark.y(i - 1), mark.x(i), mark.y(i), mark.radius, value)
            }
        }

        fun rebuildSeeds() {
            seeds.fill(Segmenter.SEED_NONE)
            marks.forEach(::rasterize)
        }
    }

    private var magic: MagicSession? = null
    private val magicLock = Mutex()

    /** The mark being drawn right now, if any. */
    var drawingMark: Mark? = null
        private set

    /** Finished marks of the current Magic session (drawn over the image while Magic is on). */
    val marks: List<Mark> get() = magic?.marks ?: emptyList()

    val hasKeepMark get() = magic?.marks?.any { it.keep } == true

    fun beginMark(x: Float, y: Float, radius: Float, keep: Boolean) {
        if (stroking) return
        drawingMark = Mark(keep, radius).also { it.add(x, y) }
        version++
    }

    fun extendMark(x: Float, y: Float) {
        val mark = drawingMark ?: return
        mark.add(x, y)
        version++
    }

    fun cancelMark() {
        drawingMark = null
        version++
    }

    /** Adds the drawn mark to the session (starting one if needed). */
    fun endMark() {
        val mark = drawingMark ?: return
        drawingMark = null
        val session = magic ?: MagicSession(mask.copyOf()).also { magic = it }
        session.marks += mark
        session.undone.clear()
        session.rasterize(mark)
        version++
    }

    /**
     * Segments with the current marks off the main thread and applies the result as one undo
     * step. Returns false if there was nothing to do or the marks changed meanwhile.
     */
    suspend fun runMagic(): Boolean {
        val session = magic ?: return false
        val target = session.marks.lastOrNull() ?: return false
        val seeds = session.seeds.copyOf()
        val job = currentCoroutineContext().job
        // One run at a time: a session's prepared image and graph are reused between runs.
        val result = magicLock.withLock {
            withContext(Dispatchers.Default) {
                val prepared = session.prepared ?: Segmenter.prepare(pixels, width, height).also { session.prepared = it }
                Segmenter.segment(prepared, session.base, seeds) { job.isActive }
            }
        } ?: return false
        if (magic !== session || session.marks.lastOrNull() !== target || stroking) return false
        var box = Box.EMPTY
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        for (i in result.indices) {
            if (result[i] != mask[i]) {
                val x = i % width
                val y = i / width
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX >= 0) box = Box(minX, minY, maxX + 1, maxY + 1)
        mask.copyInto(before)
        result.copyInto(mask)
        refresh(box)
        target.recorded = history.record(before, mask, box)
        lastFill = null
        return true
    }

    private fun endMagic() {
        if (magic == null) return
        magic = null
        version++
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
