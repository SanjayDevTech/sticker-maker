package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.example.stickermaker.data.WhatsAppLimits
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
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
        // May be called from several threads at once: each frame draws into its own bitmap.
        val render = { i: Int ->
            val frame = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply { setValues(plan.frame(i).values()) }
            Canvas(frame).drawBitmap(sticker, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            IntArray(side * side).also { frame.getPixels(it, 0, side, 0, 0, side, side); frame.recycle() }
        }
        // A motion keeps every frame: dropping some would make it stutter.
        return encodeFrames(side, FrameSet(IntArray(plan.motion.frameCount) { StickerMotion.FRAME_MS }, render))
    }

    /**
     * [gif] between [startMs] and [endMs] as an animated sticker: each frame fitted into the
     * 512 square (transparent around it), at most 25 fps. When even fair quality won't fit in
     * 500 KB, every other frame goes (timing kept) rather than the picture turning to mush.
     */
    suspend fun encodeGif(gif: Gif, startMs: Int, endMs: Int): ByteArray? {
        val side = WhatsAppLimits.STICKER_PX
        val fitter = GifFitter(gif, side)
        fun frames(timeline: Timeline): FrameSet {
            // Frames build on each other, and passes ask for them in order: play forward with one
            // cursor (one decode per frame) instead of replaying from a checkpoint each time.
            var cursor: Gif.Cursor? = null
            val render = { k: Int ->
                val index = timeline.frames[k]
                val c = cursor?.takeIf { it.index <= index } ?: gif.Cursor(index).also { cursor = it }
                while (c.index < index) c.advance()
                fitter.fit(c.canvas)
            }
            // Never below two frames: WhatsApp wants an animated sticker to animate.
            return FrameSet(timeline.durations, render) { timeline.thinned().takeIf { it.size in 2 until timeline.size }?.let(::frames) }
        }
        val timeline = Timeline.of(gif.startMs, gif.durationMs, startMs, endMs, StickerMotion.FRAME_MS)
        if (timeline.size == 0) return null
        return try {
            encodeFrames(side, frames(timeline))
        } finally {
            fitter.recycle()
        }
    }

    /** GIF frame [index] scaled to fit a transparent [side] square (up or down), as ARGB. */
    fun fitGifFrame(gif: Gif, index: Int, side: Int): IntArray =
        GifFitter(gif, side).let { f -> f.fit(gif.frame(index)).also { f.recycle() } }

    /**
     * Fits GIF frames into a transparent square, reusing its bitmaps from frame to frame (one
     * caller at a time). Big shrinks halve first, like [smoothScale], so edges stay smooth.
     */
    private class GifFitter(private val gif: Gif, private val side: Int) {
        private val source = Bitmap.createBitmap(gif.width, gif.height, Bitmap.Config.ARGB_8888)
        private val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(out)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val scale = side.toFloat() / max(gif.width, gif.height)
        private val w = max(1, (gif.width * scale).roundToInt())
        private val h = max(1, (gif.height * scale).roundToInt())
        private val dst = android.graphics.RectF(((side - w) / 2).toFloat(), ((side - h) / 2).toFloat(), ((side - w) / 2 + w).toFloat(), ((side - h) / 2 + h).toFloat())

        fun fit(pixels: IntArray): IntArray {
            source.setPixels(pixels, 0, gif.width, 0, 0, gif.width, gif.height)
            out.eraseColor(0)
            val shrunk = if (scale < 0.5f) smoothScale(source, w, h) else source
            canvas.drawBitmap(shrunk, null, dst, paint)
            if (shrunk !== source) shrunk.recycle()
            return IntArray(side * side).also { out.getPixels(it, 0, side, 0, 0, side, side) }
        }

        fun recycle() {
            source.recycle()
            out.recycle()
        }
    }

    /**
     * Frames to animate: frame i shows [durations] ms. [render] gives its unpremultiplied ARGB;
     * it's called from one coroutine at a time, in order during full passes (cheap for sources
     * that play forward), so it may reuse buffers. [fewer] is the same clip with fewer frames,
     * for sources that may drop some (null: can't).
     */
    class FrameSet(val durations: IntArray, val render: (Int) -> IntArray, val fewer: () -> FrameSet? = { null }) {
        val size: Int get() = durations.size
    }

    /**
     * [frames] as an animated WebP under WhatsApp's 500 KB. Each frame is encoded once as a
     * full still, all cores at a time, and the settings come from three sample frames before
     * the one full pass, so a failed pass is rare:
     * 1. Lossless, when the first frame shows the whole loop could fit.
     * 2. Otherwise lossy: per-frame sizes from the samples give, for each frame count on offer,
     *    the highest quality that fits. The most frames that still reach [FAIR_QUALITY] win
     *    (fewer frames look better than mush); failing that, the best quality any count gets.
     */
    suspend fun encodeFrames(side: Int, frames: FrameSet): ByteArray? = coroutineScope {
        val limit = WhatsAppLimits.ANIMATED_STICKER_MAX_BYTES

        // One full pass: rendered in order on this coroutine, encoded in parallel, with only a
        // few frames in flight so memory stays flat however long the clip is.
        suspend fun pass(set: FrameSet, encode: (IntArray) -> ByteArray?): ByteArray? {
            val gate = Semaphore(PARALLEL)
            val parts = ArrayList<Deferred<ByteArray?>>(set.size)
            for (i in 0 until set.size) {
                gate.acquire()
                val pixels = set.render(i)
                parts += async(Dispatchers.Default) { try { encode(pixels) } finally { gate.release() } }
            }
            val encoded = parts.awaitAll()
            if (encoded.any { it == null }) return null
            return WebpNative.assemble(encoded.filterNotNull(), set.durations)
        }

        // Lossless frames rarely come under ~8 KB, so long clips can skip even trying.
        if (frames.size * MIN_LOSSLESS_FRAME_BYTES <= limit) {
            val first = WebpNative.losslessFrame(frames.render(0), side, side) ?: return@coroutineScope null
            // Frames differ, so leave headroom: a loop that only just fits on paper rarely does.
            if (first.size.toLong() * frames.size <= limit * 0.85) {
                pass(frames) { WebpNative.losslessFrame(it, side, side) }?.let { if (it.size <= limit) return@coroutineScope it }
            }
        }

        // Average bytes per frame at each quality, from three samples. Size grows with frame
        // count, not with which frames, so this one sampling serves every count below.
        val samples = listOf(0, frames.size / 3, 2 * frames.size / 3).distinct().map(frames.render)
        val perFrame = HashMap<Int, Long>()
        suspend fun bytesPerFrame(q: Int): Long = perFrame.getOrPut(q) {
            samples.map { async(Dispatchers.Default) { WebpNative.lossyFrame(it, side, side, q)?.size ?: Int.MAX_VALUE } }
                .awaitAll().sumOf { it.toLong() } / samples.size
        }
        /** Index on the ladder of the best quality whose estimate fits [count] frames, or -1. */
        suspend fun bestFor(count: Int): Int {
            val budget = limit * 0.92 / count
            var lo = 0
            var hi = ANIM_QUALITIES.lastIndex
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (bytesPerFrame(ANIM_QUALITIES[mid]) <= budget) hi = mid else lo = mid + 1
            }
            return if (bytesPerFrame(ANIM_QUALITIES[lo]) <= budget) lo else -1
        }

        // Frame counts on offer, most first; pick the most that still look fair.
        var choice: Pair<FrameSet, Int>? = null
        var set: FrameSet? = frames
        while (set != null) {
            val q = bestFor(set.size)
            if (q >= 0 && (choice == null || q < choice.second)) choice = set to q
            if (q >= 0 && ANIM_QUALITIES[q] >= FAIR_QUALITY) break
            set = set.fewer()
        }
        val (chosen, start) = choice ?: return@coroutineScope null
        // The estimate rarely misses; if it does, step quality down on the same frames.
        for (q in ANIM_QUALITIES.drop(start)) {
            val bytes = pass(chosen) { WebpNative.lossyFrame(it, side, side, q) } ?: return@coroutineScope null
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

    /** Below this, dropping frames looks better than lowering quality further. */
    private const val FAIR_QUALITY = 50

    /** About the smallest a lossless 512 frame gets; more frames than fit at this skip lossless. */
    private const val MIN_LOSSLESS_FRAME_BYTES = 8 * 1024

    /** Frames encoding at once: every core busy, memory bounded. */
    private val PARALLEL = Runtime.getRuntime().availableProcessors()

    /** Lossy animation qualities, best first. */
    private val ANIM_QUALITIES = listOf(100, 95, 90, 85, 80, 75, 70, 60, 50, 40, 30, 20)
}
