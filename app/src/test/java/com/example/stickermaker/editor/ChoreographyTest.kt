package com.example.stickermaker.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoreographyTest {

    @Test
    fun theLongestGifSetsTheLengthEvenOverALongerMotion() {
        val c = Choreography(gifLengthsMs = listOf(2900, 1200), motionPeriodsMs = listOf(4000))
        assertEquals(2900, c.durationMs)
    }

    @Test
    fun withoutGifsTheLongestMotionSetsIt() {
        val c = Choreography(emptyList(), listOf(1000, 1600))
        assertEquals(1600, c.durationMs)
        assertTrue(c.animated)
    }

    @Test
    fun nothingMovingIsStill() {
        assertFalse(Choreography(emptyList(), emptyList()).animated)
    }

    @Test
    fun shorterLoopsRepeatAWholeNumberOfTimes() {
        val c = Choreography(listOf(2900), listOf(1000))
        assertEquals(3, c.repeats(1000)) // 2.9 s holds three 0.97 s wiggles
        // The loop restarts exactly where the sticker does.
        assertEquals(0f, c.localMs(1000, 0), 1e-3f)
        assertEquals(0f, c.localMs(1000, 2900), 1e-3f)
        // A third of the way through the sticker, the wiggle is starting its second run.
        assertEquals(0f, c.localMs(1000, 2900 / 3 + 1), 2f)
        // The longest GIF itself runs at its own speed.
        assertEquals(1450f, c.localMs(2900, 1450), 1e-3f)
    }

    @Test
    fun framesCoverTheLoopExactly() {
        val c = Choreography(listOf(2940), emptyList())
        val (starts, durations) = c.frames(40)
        assertEquals(73, starts.size)
        assertEquals(2940, durations.sum())
        assertTrue(durations.all { it >= 40 })
        assertArrayEquals(intArrayOf(0, 40, 80), starts.copyOf(3))
    }

    @Test
    fun neverLongerThanWhatsAppAllows() {
        assertEquals(10_000, Choreography(listOf(14_000), emptyList()).durationMs)
    }
}
