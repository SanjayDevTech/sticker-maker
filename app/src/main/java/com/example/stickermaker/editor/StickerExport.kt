package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.example.stickermaker.data.WhatsAppLimits
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns cut-outs into files WhatsApp accepts. Everything here runs off the main thread. */
object StickerExport {

    /** Space around a single cut-out inside its 512 square (room for the outline too). */
    const val MARGIN_PX = 16

    /** The outline WhatsApp's own maker forces; here it's optional and off by default. */
    const val OUTLINE_PX = 8f

    /** A trimmed cut-out centred in a transparent 512 square, as large as the margin allows. */
    fun fit(cutout: Bitmap): Bitmap {
        val side = WhatsAppLimits.STICKER_PX
        val room = side - 2 * MARGIN_PX
        val scale = room.toFloat() / max(cutout.width, cutout.height)
        val w = max(1, (cutout.width * scale).roundToInt())
        val h = max(1, (cutout.height * scale).roundToInt())
        val scaled = smoothScale(cutout, w, h)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, ((side - w) / 2).toFloat(), ((side - h) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== cutout) scaled.recycle()
        return out
    }

    /** A copy of [sticker] (512 square) with a white outline. */
    fun withOutline(sticker: Bitmap): Bitmap {
        val w = sticker.width
        val h = sticker.height
        val pixels = IntArray(w * h)
        sticker.getPixels(pixels, 0, w, 0, 0, w, h)
        Outline.apply(pixels, w, h, OUTLINE_PX)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * WebP bytes under WhatsApp's 100 KB, best quality first:
     * 1. Lossless (pixel-exact) via libwebp, about half the size of Android's lossless. Cut-outs
     *    almost always fit.
     * 2. Otherwise lossy via libwebp's slow, careful settings at the highest quality that fits.
     * 3. If libwebp isn't available, Android's encoders, same order.
     */
    fun encodeSticker(sticker: Bitmap): ByteArray {
        val limit = WhatsAppLimits.STICKER_MAX_BYTES
        val lossless = WebpNative.lossless(sticker) ?: compress(sticker, Bitmap.CompressFormat.WEBP_LOSSLESS, 100)
        if (lossless.size <= limit) return lossless
        WebpNative.lossyWithin(sticker, limit)?.let { return it }
        var quality = 95
        while (true) {
            val bytes = compress(sticker, Bitmap.CompressFormat.WEBP_LOSSY, quality)
            if (bytes.size <= limit || quality <= MIN_QUALITY) return bytes
            quality -= 5
        }
    }

    /** The 96 px PNG tray icon WhatsApp shows for a pack. */
    fun encodeTray(sticker: Bitmap): ByteArray {
        val px = WhatsAppLimits.TRAY_PX
        val tray = smoothScale(sticker, px, px)
        return compress(tray, Bitmap.CompressFormat.PNG, 100).also { if (tray !== sticker) tray.recycle() }
    }

    /**
     * Shrinks by halving first, then one last bilinear step. A single bilinear step over a big
     * ratio only samples a few source pixels per output pixel and leaves jagged edges.
     */
    fun smoothScale(source: Bitmap, w: Int, h: Int): Bitmap {
        var current = source
        while (current.width / 2 >= w && current.height / 2 >= h) {
            val next = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
            if (current !== source) current.recycle()
            current = next
        }
        if (current.width == w && current.height == h) return current
        val out = Bitmap.createScaledBitmap(current, w, h, true)
        if (current !== source) current.recycle()
        return out
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream(64 * 1024).use { out ->
            bitmap.compress(format, quality, out)
            out.toByteArray()
        }

    private const val MIN_QUALITY = 30
}
