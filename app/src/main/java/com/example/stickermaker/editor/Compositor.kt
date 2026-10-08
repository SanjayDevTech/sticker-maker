package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.stickermaker.data.Trim
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One layer in the combine canvas: a cut-out, or a GIF ([gif], trimmed to [trim]; [bitmap] is
 * then its first kept frame, used for size and hit tests). Its placement is snapshot state in
 * artboard units (0..1 across the square), changed every frame by gestures and read only
 * while drawing.
 */
@Stable
class Layer(val id: String, val number: Int, val bitmap: Bitmap, val gif: Gif? = null, val trim: Trim? = null) {
    val image: ImageBitmap = bitmap.asImageBitmap()
    val aspect: Float = bitmap.height.toFloat() / bitmap.width

    var centerX by mutableFloatStateOf(0.5f)
    var centerY by mutableFloatStateOf(0.5f)

    /** Width as a fraction of the artboard's width. */
    var size by mutableFloatStateOf(0.7f)

    /** Degrees, clockwise. */
    var rotation by mutableFloatStateOf(0f)
    var flipped by mutableStateOf(false)

    /** How a cut-out moves (GIF layers play instead). */
    var motion by mutableStateOf(StickerMotion.None)

    /** A white outline around a cut-out (GIF layers have none). */
    var outline by mutableStateOf(false)

    /**
     * The cut-out with its outline drawn, made for one size (the band is a fixed width on the
     * sticker, so a resize needs a new one). Null until made; may lag behind [outline] and
     * [size] for a moment.
     */
    var outlined by mutableStateOf<OutlinedBitmap?>(null)

    /** The outline's width in this layer's pixels at its current size, to the half pixel. */
    fun outlineThickness(): Float {
        val px = StickerExport.OUTLINE_PX * bitmap.width / (size * Compositor.STICKER_PX)
        return (px * 2).roundToInt().coerceAtLeast(1) / 2f
    }

    /** What to draw for this layer: the outlined copy when it's on and ready, else the cut-out. */
    val drawn: OutlinedBitmap? get() = outlined?.takeIf { outline && !isGif }

    /** A GIF layer's frame showing right now in the canvas; set by the canvas's player. */
    var frame by mutableStateOf<ImageBitmap?>(null)

    val isGif: Boolean get() = gif != null

    /** Frozen for saving. */
    fun snapshot() = LayerSnapshot(bitmap, centerX, centerY, size, rotation, flipped, if (isGif) StickerMotion.None else motion, gif, trim, drawn)

    /** True if layer pixel ([lx], [ly]) is visible (or just inside the layer, when [boxOnly]). */
    fun hitsPixel(lx: Float, ly: Float, boxOnly: Boolean = false): Boolean {
        val x = lx.toInt()
        val y = ly.toInt()
        if (lx < 0 || ly < 0 || x >= bitmap.width || y >= bitmap.height) return false
        return boxOnly || (bitmap.getPixel(x, y) ushr 24) > HIT_ALPHA
    }

    companion object {
        private const val HIT_ALPHA = 24
    }
}

/**
 * A cut-out with a white outline: [bitmap] is the cut-out padded by [pad] px on every side
 * (room for the band), so its pixel (x, y) is the cut-out's (x − pad, y − pad).
 */
class OutlinedBitmap(val bitmap: Bitmap, val pad: Int, val thickness: Float) {
    companion object {
        /** [cutout] with a [thickness] px outline. Call off the main thread. */
        fun of(cutout: Bitmap, thickness: Float): OutlinedBitmap {
            val pad = ceil(thickness).toInt() + 1
            val w = cutout.width + 2 * pad
            val h = cutout.height + 2 * pad
            val pixels = IntArray(w * h)
            cutout.getPixels(pixels, pad * w + pad, w, 0, 0, cutout.width, cutout.height)
            Outline.apply(pixels, w, h, thickness)
            return OutlinedBitmap(Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888), pad, thickness)
        }
    }
}

object Compositor {

    /** Layers are kept at most this big: the sticker is 512 px, so this allows ~1.5× zoom. */
    const val LAYER_MAX_PX = 768
    const val STICKER_PX = 512

    /**
     * The cut-out of [file] through [mask] (null = nothing erased), trimmed to its visible
     * pixels. Null if everything was erased. Call off the main thread.
     */
    fun cutout(file: File, mask: ByteArray?): Bitmap? {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val source = BitmapFactory.decodeFile(file.path, options) ?: return null
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        source.recycle()
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val m = if (mask != null && mask.size == w * h) mask[i].toInt() and 0xFF else 255
                val p = pixels[i]
                val a = (p ushr 24) * m / 255
                pixels[i] = (a shl 24) or (p and 0xFFFFFF)
                if (a > 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return null
        val cw = right - left + 1
        val ch = bottom - top + 1
        val trimmed = Bitmap.createBitmap(pixels, top * w + left, w, cw, ch, Bitmap.Config.ARGB_8888)
        val scale = min(1f, LAYER_MAX_PX.toFloat() / max(cw, ch))
        if (scale >= 1f) return trimmed
        return Bitmap.createScaledBitmap(trimmed, max(1, (cw * scale).roundToInt()), max(1, (ch * scale).roundToInt()), true)
            .also { trimmed.recycle() }
    }
}
