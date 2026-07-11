package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.util.Log

/**
 * libwebp's lossless encoder (src/main/cpp). About half the size of Android's built-in lossless
 * WebP for pixel-identical output. Measured on a Pixel 9 with a real sticker: Android 27,958 B;
 * libwebp level 6: 12,884 B in ~50 ms; level 9: 12,582 B but ~1.3 s, so level 6 it is.
 * Null means "not available": callers fall back to Android's encoder.
 */
internal object WebpNative {

    private val loaded: Boolean = try {
        System.loadLibrary("stickerwebp")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w("WebpNative", "libwebp unavailable, using Android's encoder", e)
        false
    }

    /** Lossless WebP of [bitmap], or null. Call off the main thread. */
    fun lossless(bitmap: Bitmap): ByteArray? {
        if (!loaded) return null
        val argb = pixels(bitmap)
        return runCatching { encodeLossless(argb, bitmap.width, bitmap.height, EFFORT) }
            .onFailure { Log.w("WebpNative", "libwebp encode failed", it) }
            .getOrNull()
    }

    /**
     * The highest-quality lossy WebP of [bitmap] that fits in [maxBytes], or null. libwebp with
     * sharp YUV and exact alpha keeps measurably more detail than Android's encoder: on a busy
     * photo sticker 49.3 dB PSNR / 0.998 SSIM vs 45.3 / 0.997, in ~70 ms on a Pixel 9 (battery
     * saver on). Tries quality 100 first (usually fits), then binary-searches whole steps.
     */
    fun lossyWithin(bitmap: Bitmap, maxBytes: Int): ByteArray? {
        if (!loaded) return null
        val argb = pixels(bitmap)
        fun encode(q: Int) = runCatching { encodeLossy(argb, bitmap.width, bitmap.height, q.toFloat()) }
            .onFailure { Log.w("WebpNative", "libwebp lossy encode failed", it) }
            .getOrNull()
        val best = encode(100) ?: return null
        if (best.size <= maxBytes) return best
        var lo = 0
        var hi = 99
        var fit: ByteArray? = null
        while (lo <= hi) {
            val q = (lo + hi) / 2
            val bytes = encode(q) ?: return null
            if (bytes.size <= maxBytes) {
                fit = bytes
                lo = q + 1
            } else {
                hi = q - 1
            }
        }
        return fit
    }

    private fun pixels(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        return IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) } // unpremultiplied ARGB
    }

    /** 0 fast .. 9 smallest; see the class doc for why 6. */
    private const val EFFORT = 6

    @JvmStatic
    private external fun encodeLossless(argb: IntArray, width: Int, height: Int, level: Int): ByteArray?

    @JvmStatic
    private external fun encodeLossy(argb: IntArray, width: Int, height: Int, quality: Float): ByteArray?
}
