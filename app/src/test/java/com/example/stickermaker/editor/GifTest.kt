package com.example.stickermaker.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** Fixtures come from resources/gif/make_gifs.py: GIFs Pillow wrote, and the frames that went in. */
class GifTest {

    private fun bytes(name: String) = requireNotNull(javaClass.getResourceAsStream("/gif/$name")) { name }.readBytes()

    private fun expected(name: String, w: Int, h: Int, frames: Int): List<IntArray> =
        DataInputStream(GZIPInputStream(requireNotNull(javaClass.getResourceAsStream("/gif/$name.argb.gz")))).use { input ->
            List(frames) { IntArray(w * h) { input.readInt() } }
        }

    private fun assertFrames(name: String, w: Int, h: Int, frames: Int, order: List<Int>) {
        val gif = requireNotNull(Gif.parse(bytes("$name.gif")))
        assertEquals(w, gif.width)
        assertEquals(h, gif.height)
        assertEquals(frames, gif.frameCount)
        val want = expected(name, w, h, frames)
        for (i in order) assertArrayEquals("$name frame $i", want[i], gif.frame(i))
    }

    @Test
    fun transparentAnimationWithDisposalDecodesExactly() =
        assertFrames("moving", 40, 30, 20, (0 until 20).toList())

    @Test
    fun randomAccessMatchesPlayingInOrder() =
        // Backwards, then jumps across checkpoints, then repeats.
        assertFrames("moving", 40, 30, 20, (19 downTo 0).toList() + listOf(17, 3, 16, 15, 19, 0, 19))

    @Test
    fun fullPaletteNoiseAndRunsDecodeExactly() =
        assertFrames("noisy", 120, 90, 5, listOf(0, 1, 2, 3, 4, 2))

    @Test
    fun timingFollowsTheDelays() {
        val gif = requireNotNull(Gif.parse(bytes("moving.gif")))
        val delays = List(20) { 30 + 10 * (it % 4) }
        assertEquals(delays, List(20) { gif.delayMs(it) })
        assertEquals(delays.sum(), gif.durationMs)
        assertEquals(0, gif.frameAt(0))
        assertEquals(0, gif.frameAt(29))
        assertEquals(1, gif.frameAt(30))
        assertEquals(19, gif.frameAt(gif.durationMs + 500))
    }

    @Test
    fun notAGifIsRejected() {
        assertNull(Gif.parse(ByteArray(100)))
        assertNull(Gif.parse("GIF89a".toByteArray()))
    }

    @Test
    fun aTruncatedGifKeepsItsCompleteFrames() {
        val full = bytes("moving.gif")
        val gif = requireNotNull(Gif.parse(full.copyOf(full.size * 2 / 3)))
        val want = expected("moving", 40, 30, 20)
        assertEquals(true, gif.frameCount in 1 until 20)
        // Every frame but the last (possibly cut mid-data) is intact.
        for (i in 0 until gif.frameCount - 1) assertArrayEquals("frame $i", want[i], gif.frame(i))
    }
}

class TimelineTest {

    // Five frames of 100 ms: 0, 100, 200, 300, 400; 500 ms in all.
    private val even = intArrayOf(0, 100, 200, 300, 400)

    @Test
    fun theWholeClip() {
        val t = Timeline.of(even, 500, 0, 10_000, 40)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), t.frames)
        assertArrayEquals(intArrayOf(100, 100, 100, 100, 100), t.durations)
    }

    @Test
    fun aTrimClipsTheFramesAtItsEdges() {
        val t = Timeline.of(even, 500, 150, 360, 40)
        assertArrayEquals(intArrayOf(1, 2, 3), t.frames)
        assertArrayEquals(intArrayOf(50, 100, 60), t.durations)
    }

    @Test
    fun fastFramesFoldIntoTheMinimumFrameTime() {
        // 10 ms frames for 200 ms: one frame kept per 40 ms.
        val fast = IntArray(20) { it * 10 }
        val t = Timeline.of(fast, 200, 0, 200, 40)
        assertArrayEquals(intArrayOf(0, 4, 8, 12, 16), t.frames)
        assertArrayEquals(intArrayOf(40, 40, 40, 40, 40), t.durations)
    }

    @Test
    fun aTooShortLastFrameMergesIntoTheOneBefore() {
        val t = Timeline.of(even, 500, 0, 410, 40)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), t.frames)
        assertArrayEquals(intArrayOf(100, 100, 100, 110), t.durations)
    }

    @Test
    fun thinningDropsEveryThirdFrameAndKeepsTheTiming() {
        val t = Timeline.of(even, 500, 0, 500, 40).thinned()
        assertArrayEquals(intArrayOf(0, 1, 3, 4), t.frames)
        assertArrayEquals(intArrayOf(100, 200, 100, 100), t.durations)
        assertEquals(500, t.durations.sum())
    }
}

class GifCursorTest {
    @Test
    fun playingForwardMatchesRandomAccess() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/gif/moving.gif")).readBytes()
        val gif = requireNotNull(Gif.parse(bytes))
        val reference = requireNotNull(Gif.parse(bytes))
        for (from in listOf(0, 5, 16, 19)) {
            val cursor = gif.Cursor(from)
            do {
                assertArrayEquals("from $from at ${cursor.index}", reference.frame(cursor.index), cursor.canvas)
            } while (cursor.advance())
            assertEquals(19, cursor.index)
        }
    }
}
