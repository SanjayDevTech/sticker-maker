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
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h) // unpremultiplied ARGB
        return runCatching { encodeLossless(argb, w, h, EFFORT) }
            .onFailure { Log.w("WebpNative", "libwebp encode failed", it) }
            .getOrNull()
    }

    /** 0 fast .. 9 smallest; see the class doc for why 6. */
    private const val EFFORT = 6

    @JvmStatic
    private external fun encodeLossless(argb: IntArray, width: Int, height: Int, level: Int): ByteArray?
}
