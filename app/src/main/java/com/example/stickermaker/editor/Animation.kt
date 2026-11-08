package com.example.stickermaker.editor

/**
 * Frames that play over time, with random access: a [Gif] or a video's frames ([VideoFrames]).
 * Both go through the same trim, combine and save steps; nothing downstream cares which.
 */
interface Animation {
    val width: Int
    val height: Int
    val frameCount: Int

    /** When each frame starts, in ms from the beginning. */
    val startMs: IntArray
    val durationMs: Int

    /** The frame showing at [ms] (clamped to the animation). */
    fun frameAt(ms: Int): Int {
        var lo = 0
        var hi = frameCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (startMs[mid] <= ms) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Frame [i] as ARGB (not premultiplied), [width] × [height]. New, and the caller's to keep. */
    fun frame(i: Int): IntArray

    /** Plays forward from frame [from], one step per frame. Not thread-safe. */
    fun cursor(from: Int): Cursor

    interface Cursor {
        val index: Int

        /** The current frame; overwritten by the next [advance]. */
        val canvas: IntArray

        /** Moves to the next frame; false at the last one. */
        fun advance(): Boolean
    }
}
