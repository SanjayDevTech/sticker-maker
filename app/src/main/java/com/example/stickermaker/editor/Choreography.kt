package com.example.stickermaker.editor

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The timing of a combined sticker: one loop of [durationMs] in which every layer's own loop
 * (a GIF's trimmed part, a motion's period) repeats a whole number of times, so the sticker's
 * end flows straight back into its start.
 *
 * The longest GIF sets the length (a GIF is the content; motions are decoration); with no GIF,
 * the longest motion does. Each shorter loop is stretched or squeezed slightly so that it fits
 * a whole number of times: a 1 s wiggle in a 2.9 s GIF runs 3 times at 0.97 s.
 */
class Choreography(gifLengthsMs: List<Int>, motionPeriodsMs: List<Int>) {

    val durationMs: Int = (gifLengthsMs.maxOrNull() ?: motionPeriodsMs.maxOrNull() ?: 0)
        .coerceAtMost(com.example.stickermaker.data.WhatsAppLimits.ANIMATED_MAX_MS)

    val animated: Boolean get() = durationMs > 0

    /** How many times a loop of [lengthMs] runs in one sticker loop. */
    fun repeats(lengthMs: Int): Int = if (lengthMs <= 0) 1 else max(1, (durationMs.toFloat() / lengthMs).roundToInt())

    /** Where, in its own loop of [lengthMs], a layer is at sticker time [t] ms (0 until lengthMs). */
    fun localMs(lengthMs: Int, t: Int): Float {
        if (lengthMs <= 0 || durationMs <= 0) return 0f
        val span = durationMs.toFloat() / repeats(lengthMs)
        val within = (t.mod(durationMs)) % span
        return within / span * lengthMs
    }

    /** Frame start times on the [frameMs] grid, and how long each shows (the last takes the rest). */
    fun frames(frameMs: Int): Pair<IntArray, IntArray> {
        val count = max(1, durationMs / frameMs)
        val starts = IntArray(count) { it * frameMs }
        val durations = IntArray(count) { if (it < count - 1) frameMs else durationMs - starts[it] }
        return starts to durations
    }
}
