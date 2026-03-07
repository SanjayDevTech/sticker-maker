package com.example.stickermaker.editor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure pixel operations on an alpha mask (no Android types, so they're unit-tested on the JVM).
 *
 * Images are `IntArray` ARGB, row-major, `width * height`. Masks are `ByteArray` with the same
 * layout holding alpha 0..255 (read with `toInt() and 0xFF`): 0 = cut away, 255 = kept.
 */
object MaskOps {

    const val ERASED: Byte = 0
    const val KEPT: Byte = -1 // 255

    /** Inclusive-exclusive pixel rectangle; empty when right <= left. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty get() = right <= left || bottom <= top
        val width get() = right - left
        val height get() = bottom - top

        fun union(o: Box): Box = when {
            isEmpty -> o
            o.isEmpty -> this
            else -> Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
        }

        companion object {
            val EMPTY = Box(0, 0, 0, 0)
        }
    }

    /**
     * Sets a hard-edged disc of [radius] image pixels at ([cx], [cy]) to [value]. Radius < 1
     * means exactly one pixel, like an MS Paint pencil. Returns the touched box.
     */
    fun stamp(mask: ByteArray, width: Int, height: Int, cx: Float, cy: Float, radius: Float, value: Byte): Box {
        if (radius < 1f) {
            val x = cx.toInt()
            val y = cy.toInt()
            if (x !in 0 until width || y !in 0 until height) return Box.EMPTY
            mask[y * width + x] = value
            return Box(x, y, x + 1, y + 1)
        }
        val left = max(0, (cx - radius).toInt())
        val right = min(width, (cx + radius).toInt() + 1)
        val top = max(0, (cy - radius).toInt())
        val bottom = min(height, (cy + radius).toInt() + 1)
        if (right <= left || bottom <= top) return Box.EMPTY
        val r2 = radius * radius
        for (y in top until bottom) {
            val dy = y + 0.5f - cy
            val row = y * width
            for (x in left until right) {
                val dx = x + 0.5f - cx
                if (dx * dx + dy * dy <= r2) mask[row + x] = value
            }
        }
        return Box(left, top, right, bottom)
    }

    /** Stamps along the segment so fast strokes leave no gaps. */
    fun stampLine(
        mask: ByteArray, width: Int, height: Int,
        x0: Float, y0: Float, x1: Float, y1: Float,
        radius: Float, value: Byte,
    ): Box {
        val dx = x1 - x0
        val dy = y1 - y0
        val length = sqrt(dx * dx + dy * dy)
        val step = max(0.5f, radius * 0.35f)
        val steps = max(1, (length / step).toInt())
        var box = Box.EMPTY
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            box = box.union(stamp(mask, width, height, x0 + dx * t, y0 + dy * t, radius, value))
        }
        return box
    }

    /** Perceptual-ish RGB distance ("redmean"), squared. Range 0..~650k. */
    fun colorDistanceSq(a: Int, b: Int): Int {
        val r1 = (a shr 16) and 0xFF
        val g1 = (a shr 8) and 0xFF
        val b1 = a and 0xFF
        val r2 = (b shr 16) and 0xFF
        val g2 = (b shr 8) and 0xFF
        val b2 = b and 0xFF
        val rMean = (r1 + r2) shr 1
        val dr = r1 - r2
        val dg = g1 - g2
        val db = b1 - b2
        return (((512 + rMean) * dr * dr) shr 8) + 4 * dg * dg + (((767 - rMean) * db * db) shr 8)
    }

    private const val MAX_DISTANCE = 806f // sqrt of the largest colorDistanceSq

    /** Tolerance 0..1 (0 = only identical colours) as a squared-distance threshold. */
    fun toleranceToThreshold(tolerance: Float): Int {
        val d = tolerance.coerceIn(0f, 1f) * MAX_DISTANCE
        return (d * d).toInt()
    }

    /**
     * Pixels 4-connected to ([sx], [sy]) whose colour is within [tolerance] of the seed pixel.
     * Returns their indices (first [Region.count] entries of [Region.indices]) and bounds.
     * [scratch] lets callers reuse the work buffers between fills.
     */
    fun floodRegion(
        pixels: IntArray, width: Int, height: Int,
        sx: Int, sy: Int, tolerance: Float,
        scratch: Scratch = Scratch(width * height),
    ): Region {
        if (sx !in 0 until width || sy !in 0 until height) return Region(IntArray(0), 0, Box.EMPTY)
        val seed = pixels[sy * width + sx]
        val threshold = toleranceToThreshold(tolerance)
        val visited = scratch.visited
        visited.fill(false)
        val queue = scratch.queue
        var head = 0
        var tail = 0
        val start = sy * width + sx
        queue[tail++] = start
        visited[start] = true
        var minX = sx; var maxX = sx; var minY = sy; var maxY = sy
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            fun visit(n: Int) {
                if (!visited[n] && colorDistanceSq(seed, pixels[n]) <= threshold) {
                    visited[n] = true
                    queue[tail++] = n
                }
            }
            if (x > 0) visit(i - 1)
            if (x < width - 1) visit(i + 1)
            if (y > 0) visit(i - width)
            if (y < height - 1) visit(i + width)
        }
        return Region(queue.copyOf(tail), tail, Box(minX, minY, maxX + 1, maxY + 1))
    }

    class Scratch(size: Int) {
        val visited = BooleanArray(size)
        val queue = IntArray(size)
    }

    class Region(val indices: IntArray, val count: Int, val bounds: Box)

    fun apply(mask: ByteArray, region: Region, value: Byte) {
        for (k in 0 until region.count) mask[region.indices[k]] = value
    }

    /** Copies [box] of [src] (full-size mask) into a compact array. */
    fun copyBox(src: ByteArray, width: Int, box: Box): ByteArray {
        val out = ByteArray(box.width * box.height)
        for (y in box.top until box.bottom) {
            System.arraycopy(src, y * width + box.left, out, (y - box.top) * box.width, box.width)
        }
        return out
    }

    /** Writes a compact [patch] for [box] back into the full-size [dst]. */
    fun pasteBox(dst: ByteArray, width: Int, box: Box, patch: ByteArray) {
        for (y in box.top until box.bottom) {
            System.arraycopy(patch, (y - box.top) * box.width, dst, y * width + box.left, box.width)
        }
    }
}
