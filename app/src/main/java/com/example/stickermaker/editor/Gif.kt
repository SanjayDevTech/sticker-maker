package com.example.stickermaker.editor

/**
 * A GIF decoder with random access to its frames. Android plays GIFs (AnimatedImageDrawable)
 * but never hands out single frames, which trimming and re-encoding need.
 *
 * Parsing reads only the structure (frame rectangles, delays, where each frame's pixel data
 * sits); pixels are decoded on demand. Frames build on each other (disposal), so [frame] keeps
 * a full-canvas checkpoint every [CHECKPOINT_EVERY] frames and replays from the nearest one.
 * Immutable after construction, so [frame] may run on several threads at once.
 */
class Gif private constructor(
    private val bytes: ByteArray,
    val width: Int,
    val height: Int,
    private val frames: List<FrameInfo>,
    private val globalPalette: IntArray?,
) {
    val frameCount: Int get() = frames.size

    /** How long frame [i] shows, in ms (browser rules: 0–10 ms means 100 ms). */
    fun delayMs(i: Int): Int = frames[i].delayMs

    /** When frame [i] starts, in ms from the beginning. */
    val startMs: IntArray = IntArray(frames.size).also { starts ->
        var t = 0
        frames.forEachIndexed { i, f -> starts[i] = t; t += f.delayMs }
    }

    val durationMs: Int get() = if (frames.isEmpty()) 0 else startMs.last() + frames.last().delayMs

    /** The frame showing at [ms] (clamped to the GIF). */
    fun frameAt(ms: Int): Int {
        var lo = 0
        var hi = frames.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (startMs[mid] <= ms) lo = mid else hi = mid - 1
        }
        return lo
    }

    private class FrameInfo(
        val left: Int, val top: Int, val w: Int, val h: Int,
        val interlaced: Boolean,
        val palette: IntArray?,
        val transparent: Int,
        val disposal: Int,
        val delayMs: Int,
        val minCodeSize: Int,
        /** Offset of the first data sub-block (its length byte). */
        val dataOffset: Int,
    )

    /** The canvas as it stands after a frame is drawn, plus what disposal needs to undo it. */
    private class State(val index: Int, val canvas: IntArray, val beforeFrame: IntArray?)

    private val checkpoints = arrayOfNulls<State>((frames.size + CHECKPOINT_EVERY - 1) / CHECKPOINT_EVERY)

    /**
     * Frame [i] as full-canvas ARGB (not premultiplied; transparent where nothing was drawn).
     * The array is new and the caller's to keep.
     */
    fun frame(i: Int): IntArray {
        require(i in frames.indices)
        val slot = i / CHECKPOINT_EVERY
        // Reuse the nearest checkpoint at or before i, building any missing ones on the way.
        var state: State? = null
        for (s in slot downTo 0) {
            state = synchronized(checkpoints) { checkpoints[s] }
            if (state != null) break
        }
        var current = state?.let { State(it.index, it.canvas.copyOf(), it.beforeFrame?.copyOf()) }
        val pixels = IntArray(width * height)
        var k = current?.index?.plus(1) ?: 0
        while (k <= i) {
            current = draw(k, current, pixels)
            if (k % CHECKPOINT_EVERY == 0) {
                synchronized(checkpoints) {
                    if (checkpoints[k / CHECKPOINT_EVERY] == null) {
                        checkpoints[k / CHECKPOINT_EVERY] = State(k, current.canvas.copyOf(), current.beforeFrame?.copyOf())
                    }
                }
            }
            k++
        }
        return requireNotNull(current).canvas
    }

    /**
     * Plays forward from frame [from]: [canvas] is that frame, and [advance] draws the next in
     * one step (where [frame] may replay up to a checkpoint's worth). For playback; not
     * thread-safe, and [canvas] is overwritten by the next [advance].
     */
    inner class Cursor(from: Int) {
        private val scratch = IntArray(width * height)
        private var state = State(from, frame(from), null).let { s ->
            // frame() returns only the canvas; rebuild what disposal-to-previous needs.
            if (frames[from].disposal == DISPOSE_PREVIOUS && from > 0) State(from, s.canvas, frame(from - 1)) else s
        }

        val index: Int get() = state.index
        val canvas: IntArray get() = state.canvas

        /** Moves to the next frame; false at the last one. */
        fun advance(): Boolean {
            if (state.index >= frames.lastIndex) return false
            state = draw(state.index + 1, state, scratch)
            return true
        }
    }

    /** Applies the previous frame's disposal, then draws frame [k]. */
    private fun draw(k: Int, previous: State?, scratch: IntArray): State {
        val canvas = previous?.canvas ?: IntArray(width * height)
        if (previous != null) {
            val last = frames[previous.index]
            when (last.disposal) {
                // Restore to background: browsers clear to transparent, not the background colour.
                DISPOSE_BACKGROUND -> clearRect(canvas, last)
                DISPOSE_PREVIOUS -> previous.beforeFrame?.copyInto(canvas)
            }
        }
        val f = frames[k]
        val before = if (f.disposal == DISPOSE_PREVIOUS) canvas.copyOf() else null
        val palette = f.palette ?: globalPalette ?: return State(k, canvas, before)
        val indices = decodeIndices(f, scratch)
        for (row in 0 until f.h) {
            val y = f.top + rowAt(row, f.h, f.interlaced)
            if (y !in 0 until height) continue
            val src = row * f.w
            val dst = y * width
            for (col in 0 until f.w) {
                val x = f.left + col
                if (x !in 0 until width) continue
                val index = indices[src + col]
                if (index == f.transparent || index >= palette.size) continue
                canvas[dst + x] = palette[index]
            }
        }
        return State(k, canvas, before)
    }

    private fun clearRect(canvas: IntArray, f: FrameInfo) {
        for (y in maxOf(0, f.top) until minOf(height, f.top + f.h)) {
            canvas.fill(0, y * width + maxOf(0, f.left), y * width + minOf(width, f.left + f.w))
        }
    }

    /** Row [row] of the stored order, mapped to its place in the frame (interlaced: 4 passes). */
    private fun rowAt(row: Int, h: Int, interlaced: Boolean): Int {
        if (!interlaced) return row
        val pass1 = (h + 7) / 8
        val pass2 = (h + 3) / 8
        val pass3 = (h + 1) / 4
        return when {
            row < pass1 -> row * 8
            row < pass1 + pass2 -> (row - pass1) * 8 + 4
            row < pass1 + pass2 + pass3 -> (row - pass1 - pass2) * 4 + 2
            else -> (row - pass1 - pass2 - pass3) * 2 + 1
        }
    }

    /** LZW-decodes a frame's palette indices (row-major in stored order). Missing data stays 0. */
    private fun decodeIndices(f: FrameInfo, scratch: IntArray): IntArray {
        val count = f.w * f.h
        val out = if (scratch.size >= count) scratch.also { it.fill(0, 0, count) } else IntArray(count)
        val clear = 1 shl f.minCodeSize
        val end = clear + 1
        val prefix = ShortArray(MAX_CODES)
        val suffix = ByteArray(MAX_CODES)
        val stack = ByteArray(MAX_CODES + 1)
        var codeSize = f.minCodeSize + 1
        var next = clear + 2
        var old = -1
        var first = 0
        var written = 0

        // Bit reader across sub-blocks, inline: this loop runs once per code of every frame.
        val data = bytes
        var pos = f.dataOffset
        var blockLeft = 0
        var bits = 0
        var bitCount = 0

        while (written < count) {
            while (bitCount < codeSize) {
                if (blockLeft == 0) {
                    if (pos >= data.size) return out
                    blockLeft = data[pos++].toInt() and 0xFF
                    if (blockLeft == 0 || pos + blockLeft > data.size) return out
                }
                blockLeft--
                bits = bits or ((data[pos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            val code = bits and ((1 shl codeSize) - 1)
            bits = bits ushr codeSize
            bitCount -= codeSize

            if (code == clear) {
                codeSize = f.minCodeSize + 1
                next = clear + 2
                old = -1
                continue
            }
            if (code == end) break
            if (old == -1) {
                if (code >= clear) return out // corrupt: first code must be a literal
                out[written++] = code
                old = code
                first = code
                continue
            }
            var c = code
            var top = 0
            if (c >= next) {
                // The KwKwK case: the code being defined right now.
                stack[top++] = first.toByte()
                c = old
            }
            while (c > clear) {
                if (top >= stack.size) return out
                stack[top++] = suffix[c]
                c = prefix[c].toInt()
            }
            first = c and 0xFF
            stack[top++] = first.toByte()
            if (next < MAX_CODES) {
                prefix[next] = old.toShort()
                suffix[next] = first.toByte()
                next++
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            }
            while (top > 0 && written < count) out[written++] = stack[--top].toInt() and 0xFF
            old = code
        }
        return out
    }

    companion object {
        private const val MAX_CODES = 4096
        private const val CHECKPOINT_EVERY = 16
        private const val DISPOSE_BACKGROUND = 2
        private const val DISPOSE_PREVIOUS = 3

        /** True if [bytes] start like a GIF. */
        fun isGif(bytes: ByteArray) = bytes.size >= 6 &&
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()

        /** Parses a GIF's structure, or null if it isn't one or holds no frames. */
        fun parse(bytes: ByteArray): Gif? = runCatching { parseOrThrow(bytes) }.getOrNull()

        private fun parseOrThrow(bytes: ByteArray): Gif? {
            if (!isGif(bytes) || bytes.size < 13) return null
            var pos = 6
            fun u8() = bytes[pos++].toInt() and 0xFF
            fun u16() = u8() or (u8() shl 8)
            fun palette(size: Int) = IntArray(size) { 0xFF000000.toInt() or (u8() shl 16) or (u8() shl 8) or u8() }
            fun skipSubBlocks() {
                while (true) {
                    val n = u8()
                    if (n == 0) return
                    pos += n
                }
            }

            val width = u16()
            val height = u16()
            val packed = u8()
            pos += 2 // background colour index, pixel aspect ratio
            val global = if (packed and 0x80 != 0) palette(2 shl (packed and 7)) else null
            if (width <= 0 || height <= 0) return null

            val frames = ArrayList<FrameInfo>()
            var transparent = -1
            var disposal = 0
            var delayMs = 0
            // A truncated file (common for GIFs) keeps the frames read before the cut.
            try {
                readBlocks@ while (pos < bytes.size) {
                    when (u8()) {
                        0x21 -> {
                            val label = u8()
                            if (label == 0xF9) {
                                val size = u8()
                                val start = pos
                                val p = u8()
                                disposal = (p shr 2) and 7
                                val delay = u16() * 10
                                val t = u8()
                                transparent = if (p and 1 != 0) t else -1
                                delayMs = delay
                                pos = start + size
                                skipSubBlocks()
                            } else {
                                skipSubBlocks()
                            }
                        }
                        0x2C -> {
                            val left = u16()
                            val top = u16()
                            val w = u16()
                            val h = u16()
                            val p = u8()
                            val local = if (p and 0x80 != 0) palette(2 shl (p and 7)) else null
                            val minCodeSize = u8()
                            val dataOffset = pos
                            skipSubBlocks()
                            if (w > 0 && h > 0 && minCodeSize in 1..11) {
                                frames += FrameInfo(
                                    left, top, w, h,
                                    interlaced = p and 0x40 != 0,
                                    palette = local,
                                    transparent = transparent,
                                    disposal = disposal,
                                    delayMs = if (delayMs <= 10) 100 else delayMs,
                                    minCodeSize = minCodeSize,
                                    dataOffset = dataOffset,
                                )
                            }
                            // A graphic control extension applies to the next image only.
                            transparent = -1
                            disposal = 0
                            delayMs = 0
                        }
                        0x3B -> break@readBlocks
                        else -> break@readBlocks // unknown block: keep what's been read
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
                // Cut off mid-block.
            }
            if (frames.isEmpty()) return null
            return Gif(bytes, width, height, frames, global)
        }
    }
}

/**
 * Which frames play, and for how long each: the frames of a clip between two times, at most one
 * per [Timeline.of]'s minimum frame time (faster frames fold into the one before, so a 50 fps
 * GIF costs no more than 25 fps and looks the same on a phone).
 */
class Timeline(val frames: IntArray, val durations: IntArray) {
    val size: Int get() = frames.size

    /**
     * Two frames of every three, the dropped one's time added to the frame before: a third
     * less data, same timing. Gentler than halving, so the frame rate falls in small steps.
     */
    fun thinned(): Timeline {
        val kept = ArrayList<Int>()
        val time = ArrayList<Int>()
        for (k in 0 until size) {
            if (k % 3 == 2) {
                time[time.lastIndex] += durations[k]
            } else {
                kept += frames[k]
                time += durations[k]
            }
        }
        return Timeline(kept.toIntArray(), time.toIntArray())
    }

    companion object {
        /**
         * Frames starting at [startMs] (frame i begins at startMs[i]; the clip lasts
         * [totalMs]) that show within [from]..[to] ms, clipped to that window.
         */
        fun of(startMs: IntArray, totalMs: Int, from: Int, to: Int, minFrameMs: Int): Timeline {
            val end = minOf(to, totalMs)
            val kept = ArrayList<Int>()
            val keptStart = ArrayList<Int>()
            for (i in startMs.indices) {
                val begin = maxOf(startMs[i], from)
                val finish = minOf(startMs.getOrElse(i + 1) { totalMs }, end)
                if (finish <= begin) continue
                // Too soon after the last kept frame: it shows inside that frame's time instead.
                if (keptStart.isNotEmpty() && begin - keptStart.last() < minFrameMs) continue
                kept += i
                keptStart += begin
            }
            // The last frame must still last long enough on its own.
            if (kept.size > 1 && end - keptStart.last() < minFrameMs) {
                kept.removeAt(kept.lastIndex)
                keptStart.removeAt(keptStart.lastIndex)
            }
            val durations = IntArray(kept.size) { k -> (keptStart.getOrElse(k + 1) { end }) - keptStart[k] }
            return Timeline(kept.toIntArray(), durations)
        }
    }
}
