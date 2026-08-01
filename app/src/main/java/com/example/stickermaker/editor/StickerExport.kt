package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.example.stickermaker.data.WhatsAppLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    /** How [sticker] (a still, 512 square) moves with [motion], kept inside the margin; null when still or empty. */
    fun plan(sticker: Bitmap, motion: StickerMotion): MotionPlan? {
        if (motion == StickerMotion.None) return null
        val side = sticker.width
        val pixels = IntArray(side * side).also { sticker.getPixels(it, 0, side, 0, 0, side, side) }
        return MotionPlan.of(motion, { x, y -> pixels[y * side + x] ushr 24 != 0 }, side, MARGIN_PX)
    }

    /**
     * [sticker] (a still, 512 square) moving along [plan], as an animated WebP under WhatsApp's
     * 500 KB, or null if libwebp isn't available (Android has no animated WebP encoder).
     */
    suspend fun encodeAnimated(sticker: Bitmap, plan: MotionPlan): ByteArray? {
        val side = sticker.width
        // Called from several threads at once: each frame draws into its own bitmap.
        val render = { i: Int ->
            val frame = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply { setValues(plan.frame(i).values()) }
            Canvas(frame).drawBitmap(sticker, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            IntArray(side * side).also { frame.getPixels(it, 0, side, 0, 0, side, side); frame.recycle() }
        }
        return encodeFrames(side, plan.motion.frameCount, StickerMotion.FRAME_MS, render)
    }

    /**
     * Frames from [render] (unpremultiplied ARGB, called concurrently) as an animated WebP under
     * the limit. Each frame is encoded once as a full still, all cores at a time:
     * 1. Lossless, when the first frame shows the whole loop could fit.
     * 2. Otherwise lossy at the highest quality whose estimate (from three sample frames) fits,
     *    stepping down if the full encode still comes out too big.
     */
    suspend fun encodeFrames(side: Int, frameCount: Int, frameMs: Int, render: (Int) -> IntArray): ByteArray? = coroutineScope {
        val limit = WhatsAppLimits.ANIMATED_STICKER_MAX_BYTES
        suspend fun all(encode: (IntArray) -> ByteArray?): List<ByteArray>? =
            (0 until frameCount).map { i -> async(Dispatchers.Default) { encode(render(i)) } }.awaitAll()
                .takeIf { frames -> frames.none { it == null } }?.filterNotNull()

        val first = WebpNative.losslessFrame(render(0), side, side) ?: return@coroutineScope null
        // Frames differ, so leave headroom: a loop that only just fits on paper rarely does.
        if (first.size.toLong() * frameCount <= limit * 0.85) {
            val bytes = all { WebpNative.losslessFrame(it, side, side) }?.let { WebpNative.assemble(it, frameMs) }
            if (bytes != null && bytes.size <= limit) return@coroutineScope bytes
        }

        val samples = listOf(0, frameCount / 3, 2 * frameCount / 3).distinct().map(render)
        suspend fun estimate(q: Int): Long = samples
            .map { async(Dispatchers.Default) { WebpNative.lossyFrame(it, side, side, q)?.size ?: Int.MAX_VALUE } }
            .awaitAll().sumOf { it.toLong() } * frameCount / samples.size
        // Highest quality on the ladder whose estimate leaves a little headroom.
        var lo = 0
        var hi = ANIM_QUALITIES.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (estimate(ANIM_QUALITIES[mid]) <= limit * 0.92) hi = mid else lo = mid + 1
        }
        for (q in ANIM_QUALITIES.drop(lo)) {
            val bytes = all { WebpNative.lossyFrame(it, side, side, q) }?.let { WebpNative.assemble(it, frameMs) } ?: return@coroutineScope null
            if (bytes.size <= limit) return@coroutineScope bytes
        }
        null
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

    /** Lossy animation qualities, best first. */
    private val ANIM_QUALITIES = listOf(100, 95, 90, 85, 80, 75, 70, 60, 50, 40, 30, 20)
}
