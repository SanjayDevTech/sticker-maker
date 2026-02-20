package com.example.stickermaker.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File

/**
 * Small in-memory cache of downsampled bitmaps for grids and pack cards. No image library:
 * everything shown is a local file we wrote, so a sampled decode + LRU covers it.
 * Keys include the file's lastModified, so an edited sticker never shows a stale thumbnail.
 */
class ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun key(file: File, maxPx: Int) = "${file.path}@${file.lastModified()}#$maxPx"

    fun peek(file: File, maxPx: Int): Bitmap? = cache.get(key(file, maxPx))

    /** Blocking decode; call off the main thread. */
    fun load(file: File, maxPx: Int): Bitmap? {
        val key = key(file, maxPx)
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        cache.put(key, bitmap)
        return bitmap
    }
}
