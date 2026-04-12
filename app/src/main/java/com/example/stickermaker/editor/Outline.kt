package com.example.stickermaker.editor

import kotlin.math.min

/**
 * The optional sticker outline, as pure maths: for each pixel, how far it is from the
 * sticker's opaque shape, turned into an anti-aliased band [radius] px wide.
 */
object Outline {

    private const val SOLID_ALPHA = 128
    private const val INF = Int.MAX_VALUE / 2

    // 3-4 chamfer: orthogonal steps cost 3, diagonal 4 (≈ 3·√2), so distance ≈ value / 3.
    private const val ORTHO = 3
    private const val DIAG = 4

    /**
     * Outline coverage 0..255 per pixel for an image whose alpha is [alpha] (0..255, [w]×[h]).
     * Pixels within [radius] of the shape are covered, with a one-pixel soft edge.
     */
    fun coverage(alpha: IntArray, w: Int, h: Int, radius: Float): IntArray {
        val d = IntArray(w * h) { if (alpha[it] >= SOLID_ALPHA) 0 else INF }
        // Forward pass: from the top-left neighbours.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                var v = d[i]
                if (v == 0) continue
                if (x > 0) v = min(v, d[i - 1] + ORTHO)
                if (y > 0) {
                    v = min(v, d[i - w] + ORTHO)
                    if (x > 0) v = min(v, d[i - w - 1] + DIAG)
                    if (x < w - 1) v = min(v, d[i - w + 1] + DIAG)
                }
                d[i] = v
            }
        }
        // Backward pass: from the bottom-right neighbours.
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                var v = d[i]
                if (v == 0) continue
                if (x < w - 1) v = min(v, d[i + 1] + ORTHO)
                if (y < h - 1) {
                    v = min(v, d[i + w] + ORTHO)
                    if (x < w - 1) v = min(v, d[i + w + 1] + DIAG)
                    if (x > 0) v = min(v, d[i + w - 1] + DIAG)
                }
                d[i] = v
            }
        }
        return IntArray(w * h) { i ->
            val distance = d[i] / ORTHO.toFloat()
            ((radius + 0.5f - distance).coerceIn(0f, 1f) * 255).toInt()
        }
    }

    /**
     * Puts [pixels] (ARGB, not premultiplied) over a solid [color] outline of [radius] px, in
     * place. Returns [pixels].
     */
    fun apply(pixels: IntArray, w: Int, h: Int, radius: Float, color: Int = 0xFFFFFF): IntArray {
        val alpha = IntArray(pixels.size) { pixels[it] ushr 24 }
        val cover = coverage(alpha, w, h, radius)
        val or = (color shr 16) and 0xFF
        val og = (color shr 8) and 0xFF
        val ob = color and 0xFF
        for (i in pixels.indices) {
            val ca = cover[i]
            if (ca == 0) continue
            val p = pixels[i]
            val sa = p ushr 24
            if (sa == 255) continue
            // Source-over: sticker pixel on top of the outline.
            val outA = sa + ca * (255 - sa) / 255
            if (outA == 0) continue
            fun channel(s: Int, o: Int) = (s * sa + o * ca * (255 - sa) / 255) / outA
            val r = channel((p shr 16) and 0xFF, or)
            val g = channel((p shr 8) and 0xFF, og)
            val b = channel(p and 0xFF, ob)
            pixels[i] = (outA shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }
}
