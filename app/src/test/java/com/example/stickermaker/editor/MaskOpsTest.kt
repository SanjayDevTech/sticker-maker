package com.example.stickermaker.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskOpsTest {

    private fun kept(w: Int, h: Int) = ByteArray(w * h) { MaskOps.KEPT }
    private fun alpha(mask: ByteArray, i: Int) = mask[i].toInt() and 0xFF

    @Test
    fun onePixelBrushErasesExactlyOnePixel() {
        val mask = kept(10, 10)
        val box = MaskOps.stamp(mask, 10, 10, 4.5f, 6.5f, radius = 0.5f, value = MaskOps.ERASED)
        assertEquals(1, mask.count { it == MaskOps.ERASED })
        assertEquals(0, alpha(mask, 6 * 10 + 4))
        assertEquals(MaskOps.Box(4, 6, 5, 7), box)
    }

    @Test
    fun discStaysInsideBoundsAtEdges() {
        val mask = kept(8, 8)
        val box = MaskOps.stamp(mask, 8, 8, 0f, 0f, radius = 3f, value = MaskOps.ERASED)
        assertEquals(0, box.left)
        assertEquals(0, box.top)
        assertEquals(0, alpha(mask, 0))
        assertEquals(255, alpha(mask, 7 * 8 + 7))
    }

    @Test
    fun fastStrokeLeavesNoGaps() {
        val mask = kept(100, 10)
        MaskOps.stampLine(mask, 100, 10, 2f, 5f, 97f, 5f, radius = 2f, value = MaskOps.ERASED)
        for (x in 2..97) assertEquals("gap at x=$x", 0, alpha(mask, 5 * 100 + x))
    }

    @Test
    fun floodFillTakesConnectedSimilarColourOnly() {
        // Left half white, right half black, plus an isolated white pixel inside the black half.
        val w = 10
        val h = 4
        val px = IntArray(w * h) { i -> if (i % w < 5) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        px[2 * w + 8] = 0xFFFFFFFF.toInt()
        val region = MaskOps.floodRegion(px, w, h, 0, 0, tolerance = 0.1f)
        assertEquals(5 * h, region.count) // the island is not connected, so not included
        assertEquals(MaskOps.Box(0, 0, 5, 4), region.bounds)
    }

    @Test
    fun toleranceWidensTheFill() {
        val w = 3
        val px = intArrayOf(0xFF808080.toInt(), 0xFF888888.toInt(), 0xFFF0F0F0.toInt())
        assertEquals(1, MaskOps.floodRegion(px, w, 1, 0, 0, tolerance = 0f).count)
        assertEquals(2, MaskOps.floodRegion(px, w, 1, 0, 0, tolerance = 0.1f).count)
        assertEquals(3, MaskOps.floodRegion(px, w, 1, 0, 0, tolerance = 1f).count)
    }

    @Test
    fun historyUndoesAndRedoesPatches() {
        val w = 6
        val mask = kept(w, 6)
        val history = MaskHistory(w)
        val before = mask.copyOf()
        val box = MaskOps.stamp(mask, w, 6, 3f, 3f, 1.5f, MaskOps.ERASED)
        history.record(before, mask, box)
        assertTrue(history.canUndo)
        history.undo(mask)
        assertTrue(mask.all { it == MaskOps.KEPT })
        history.redo(mask)
        assertEquals(0, alpha(mask, 3 * w + 3))
        assertFalse(history.canRedo)
    }

    @Test
    fun noOpEditIsNotRecorded() {
        val mask = ByteArray(4) { MaskOps.ERASED }
        val history = MaskHistory(2)
        val before = mask.copyOf()
        val box = MaskOps.stamp(mask, 2, 2, 1f, 1f, 2f, MaskOps.ERASED)
        history.record(before, mask, box)
        assertFalse(history.canUndo)
    }
}
