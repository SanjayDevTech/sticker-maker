package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.stickermaker.data.Trim
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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

    /** A GIF layer's frame showing right now in the canvas; set by the canvas's player. */
    var frame by mutableStateOf<ImageBitmap?>(null)

    val isGif: Boolean get() = gif != null

    /** Frozen for saving. */
    fun snapshot() = LayerSnapshot(bitmap, centerX, centerY, size, rotation, flipped, if (isGif) StickerMotion.None else motion, gif, trim)

    /** Artboard px per layer px, for an artboard [side] px wide. */
    fun scale(side: Float) = size * side / bitmap.width

    /** Maps an artboard point (px, artboard [side]) into layer pixels. */
    fun toLayer(p: Offset, side: Float): Offset {
        val s = scale(side)
        val dx = p.x - centerX * side
        val dy = p.y - centerY * side
        val r = Math.toRadians(-rotation.toDouble())
        val x = (dx * cos(r) - dy * sin(r)).toFloat() / s
        val y = (dx * sin(r) + dy * cos(r)).toFloat() / s
        return Offset((if (flipped) -x else x) + bitmap.width / 2f, y + bitmap.height / 2f)
    }

    /** Maps a layer-local offset from its centre (artboard px) to the artboard. */
    fun fromCenter(local: Offset, side: Float): Offset {
        val r = Math.toRadians(rotation.toDouble())
        return Offset(
            centerX * side + (local.x * cos(r) - local.y * sin(r)).toFloat(),
            centerY * side + (local.x * sin(r) + local.y * cos(r)).toFloat(),
        )
    }

    /** True if [p] lands on a visible pixel (or anywhere in the box when [boxOnly]). */
    fun hit(p: Offset, side: Float, boxOnly: Boolean = false): Boolean {
        val l = toLayer(p, side)
        val x = l.x.toInt()
        val y = l.y.toInt()
        if (l.x < 0 || l.y < 0 || x >= bitmap.width || y >= bitmap.height) return false
        return boxOnly || (bitmap.getPixel(x, y) ushr 24) > HIT_ALPHA
    }

    companion object {
        private const val HIT_ALPHA = 24
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
