package com.example.stickermaker.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTest {

    private val w = 40
    private val h = 40

    /** An opaque red 10×10 square in the middle of a transparent 40×40 image. */
    private fun square(): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if (x in 15 until 25 && y in 15 until 25) 0xFFFF0000.toInt() else 0
    }

    @Test
    fun outlineCoversABandOfTheRadius() {
        val alpha = square().map { it ushr 24 }.toIntArray()
        val cover = Outline.coverage(alpha, w, h, 4f)
        assertEquals(255, cover[20 * w + 20]) // inside
        assertEquals(255, cover[20 * w + 12]) // 3 px left of the shape
        assertEquals(0, cover[20 * w + 5]) // 10 px away
        assertEquals(0, cover[0]) // corner
    }

    @Test
    fun stickerStaysOnTopAndOutlineIsWhite() {
        val pixels = Outline.apply(square(), w, h, 4f)
        assertEquals(0xFFFF0000.toInt(), pixels[20 * w + 20])
        assertEquals(0xFFFFFFFF.toInt(), pixels[20 * w + 12])
        assertEquals(0, pixels[0])
    }

    @Test
    fun edgeIsSoft() {
        val alpha = square().map { it ushr 24 }.toIntArray()
        val cover = Outline.coverage(alpha, w, h, 4.5f)
        // Around the corners the distance isn't whole pixels, so the band edge is partial
        // coverage rather than a hard step.
        val partial = cover.count { it in 1..254 }
        assertTrue("expected a soft edge", partial > 8)
    }
}
