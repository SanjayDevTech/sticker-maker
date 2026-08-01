package com.example.stickermaker.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class StickerMotionTest {

    private val side = 512
    private val margin = 16

    /** A wide shape filling the margin box edge to edge: the hardest case for spins and hops. */
    private fun wide(x: Int, y: Int) = x in margin until side - margin && y in 180 until 330

    /** The transformed bounds of the shape's corners over every frame. */
    private fun bounds(plan: MotionPlan, motion: StickerMotion): FloatArray {
        val corners = listOf(margin to 180, side - margin to 180, margin to 330, side - margin to 330)
        var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until motion.frameCount) {
            val m = plan.frame(i)
            for ((x, y) in corners) {
                val px = m.mapX(x.toFloat(), y.toFloat()); val py = m.mapY(x.toFloat(), y.toFloat())
                l = min(l, px); r = max(r, px); t = min(t, py); b = max(b, py)
            }
        }
        return floatArrayOf(l, t, r, b)
    }

    @Test
    fun everyFrameOfEveryMotionStaysInsideTheMargin() {
        for (motion in StickerMotion.entries) {
            if (motion == StickerMotion.None) continue
            val plan = requireNotNull(MotionPlan.of(motion, ::wide, side, margin)) { "$motion has no plan" }
            val (l, t, r, b) = bounds(plan, motion).toList()
            val eps = 0.01f
            assertTrue("$motion left $l", l >= margin - eps)
            assertTrue("$motion top $t", t >= margin - eps)
            assertTrue("$motion right $r", r <= side - margin + eps)
            assertTrue("$motion bottom $b", b <= side - margin + eps)
        }
    }

    @Test
    fun loopsAreSeamless() {
        // The frame after the last is the first: pose(1) must equal pose(0).
        for (motion in StickerMotion.entries) {
            val a = motion.pose(0f)
            val z = motion.pose(1f)
            val angle = (z.rotation - a.rotation) % 360f
            assertEquals("$motion dx", a.dx, z.dx, 1e-4f)
            assertEquals("$motion dy", a.dy, z.dy, 1e-4f)
            assertEquals("$motion sx", a.scaleX, z.scaleX, 1e-4f)
            assertEquals("$motion sy", a.scaleY, z.scaleY, 1e-4f)
            assertEquals("$motion rotation", 0f, angle, 1e-3f)
        }
    }

    @Test
    fun aSmallShapeIsNotShrunkByAGentleMotion() {
        val plan = MotionPlan.of(StickerMotion.Wiggle, { x, y -> x in 200 until 312 && y in 200 until 312 }, side, margin)!!
        val m = plan.frame(0)
        assertEquals(1f, m.a, 1e-4f) // rest pose of a wiggle: no rotation, no shrink
        assertEquals(1f, m.d, 1e-4f)
    }

    @Test
    fun nothingVisibleHasNoPlan() {
        assertNull(MotionPlan.of(StickerMotion.Spin, { _, _ -> false }, side, margin))
    }

    @Test
    fun framesFitWhatsAppTiming() {
        for (motion in StickerMotion.entries) {
            assertTrue(motion.frameCount * StickerMotion.FRAME_MS <= 10_000)
        }
        assertTrue(StickerMotion.FRAME_MS >= 8)
    }
}
