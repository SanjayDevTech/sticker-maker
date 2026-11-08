package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A video's frames, decoded once on import and kept as JPEGs in [dir], so a video plays, trims
 * and encodes exactly like a [Gif]. Frames are at most [MAX_SIDE] px (the sticker's size: a
 * video only ever gets fitted into it or shown smaller) and at most one per [FRAME_MS], the
 * rate stickers play at. Sound is dropped.
 *
 * Decoding a video on the fly can't seek to a frame quickly (it decodes from the previous
 * key frame), so scrubbing and the combine canvas would stutter; a JPEG reads in a few ms.
 * Immutable after construction, so [frame] may run on several threads at once.
 */
class VideoFrames private constructor(
    private val dir: File,
    override val width: Int,
    override val height: Int,
    override val startMs: IntArray,
    override val durationMs: Int,
) : Animation {
    override val frameCount: Int get() = startMs.size

    /** Transparent if the frame is gone (the session was cleared while something still plays it). */
    override fun frame(i: Int): IntArray = IntArray(width * height).also { decode(i, null)?.getPixels(it, 0, width, 0, 0, width, height) }

    private fun decode(i: Int, reuse: Bitmap?): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inBitmap = reuse
        }
        return runCatching { BitmapFactory.decodeFile(frameFile(dir, i).path, options) }.getOrNull()
    }

    override fun cursor(from: Int): Animation.Cursor = object : Animation.Cursor {
        private var bitmap: Bitmap? = null
        override var index = from
            private set
        override val canvas = frame(from)

        override fun advance(): Boolean {
            if (index >= frameCount - 1) return false
            index++
            // A missing frame leaves the last one showing.
            decode(index, bitmap)?.let { bitmap = it; it.getPixels(canvas, 0, width, 0, 0, width, height) }
            return true
        }
    }

    companion object {
        /** Long side of the kept frames: the sticker's size. */
        const val MAX_SIDE = 512

        /** Longest stretch of a video that's kept; a sticker uses up to 10 s of it. */
        const val MAX_MS = 60_000

        /** Kept frames are at least this far apart (25 fps), the rate stickers play at. */
        private const val FRAME_MS = StickerMotion.FRAME_MS

        /** Decoded full-size frames held at once while importing (all decoders together). */
        private const val CHUNK_BYTES = 48 * 1024 * 1024

        /** Decoders run side by side while importing. */
        private const val MAX_DECODERS = 4
        private const val JPEG_QUALITY = 92
        private const val INDEX = "frames.txt"

        private fun frameFile(dir: File, i: Int) = File(dir, "%05d.jpg".format(i))

        /** Runs [block] on a retriever reading a fresh descriptor from [open], then releases both. */
        private fun <T> withRetriever(open: () -> ParcelFileDescriptor, block: (MediaMetadataRetriever) -> T): T =
            open().use { pfd ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(pfd.fileDescriptor)
                    block(retriever)
                } finally {
                    retriever.release()
                }
            }

        /** Shrinks [bitmap] to the kept size and writes it as frame [i]. */
        private fun write(bitmap: Bitmap, dir: File, i: Int) {
            val scale = minOf(1f, MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height))
            val w = max(1, (bitmap.width * scale).roundToInt())
            val h = max(1, (bitmap.height * scale).roundToInt())
            val scaled = if (bitmap.width == w && bitmap.height == h) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
            frameFile(dir, i).outputStream().buffered().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (scaled !== bitmap) scaled.recycle()
        }

        private val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }

        /**
         * Decodes the video [open] reads into [dir] (made empty first). Null if it has no
         * picture or no frame could be read. Blocks; takes a few seconds for a long video.
         */
        fun extract(open: () -> ParcelFileDescriptor, dir: File): VideoFrames? {
            val meta = withRetriever(open) { r ->
                fun key(k: Int) = r.extractMetadata(k)
                if (key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return@withRetriever null
                Meta(
                    durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: return@withRetriever null,
                    frameCount = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0,
                    pixels = (key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toLongOrNull() ?: 1920) *
                        (key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toLongOrNull() ?: 1080),
                )
            } ?: return null
            if (meta.durationMs <= 0) return null
            val keepMs = minOf(meta.durationMs, MAX_MS)
            dir.deleteRecursively()
            dir.mkdirs()

            val starts = runCatching { extractInOrder(open, dir, meta, keepMs) }.getOrNull()
                ?: run {
                    // No frame count, or the batch decode failed: seek to each moment instead
                    // (much slower, but any video a player can read works).
                    dir.listFiles()?.forEach(File::delete)
                    withRetriever(open) { r -> extractBySeeking(r, dir, keepMs) }
                }
            if (starts.size < 2) return null
            val first = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(frameFile(dir, 0).path, first)
            if (first.outWidth <= 0) return null
            File(dir, INDEX).writeText("${first.outWidth} ${first.outHeight} $keepMs\n" + starts.joinToString("\n"))
            return VideoFrames(dir, first.outWidth, first.outHeight, starts.toIntArray(), keepMs)
        }

        private class Meta(val durationMs: Int, val frameCount: Int, val pixels: Long)

        /**
         * Decodes frames in order, a chunk at a time, keeping one per [FRAME_MS] step. Frames are
         * assumed evenly spread over the video, which holds closely enough for phone videos.
         * The kept frames are split into runs decoded side by side, each by its own decoder
         * (one decoder alone runs at well under real time on slower phones). Null if it fails.
         */
        private fun extractInOrder(open: () -> ParcelFileDescriptor, dir: File, meta: Meta, keepMs: Int): List<Int>? {
            val count = meta.frameCount
            if (count < 2) return null
            val last = minOf(count, (count.toLong() * keepMs / meta.durationMs).toInt())
            // Which source frames to keep: the first at or after each step of the 40 ms grid.
            val keep = ArrayList<Int>()
            val starts = ArrayList<Int>()
            var next = 0
            for (i in 0 until last) {
                val at = (i.toLong() * meta.durationMs / count).toInt()
                if (at < next) continue
                keep += i
                starts += at
                while (next <= at) next += FRAME_MS
            }
            if (keep.size < 2) return null

            // A decoder starts at a key frame, so runs split only there (else each run would
            // first decode everything since the key frame before it).
            val wanted = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_DECODERS)
            val keyFrames = runCatching { keyFrames(open) }.getOrDefault(emptyList())
            val bounds = sortedSetOf(0, keep.size)
            for (wk in 1 until wanted) {
                val target = keep[keep.size * wk / wanted]
                val key = keyFrames.lastOrNull { it <= target } ?: continue
                bounds += keep.indexOfFirst { it >= key }.takeIf { it >= 0 } ?: continue
            }
            val runs = bounds.zipWithNext().filter { (a, b) -> b > a }
            val chunk = (CHUNK_BYTES / runs.size / (meta.pixels * 4)).toInt().coerceIn(1, 16)
            val failed = AtomicBoolean(false)
            // Shrinking and saving a frame costs about as much as decoding it, so it runs
            // beside the decoders; a cap on frames waiting keeps memory bounded.
            val writers = Executors.newFixedThreadPool(MAX_DECODERS)
            val waiting = Semaphore((CHUNK_BYTES / (meta.pixels * 4)).toInt().coerceIn(2, 16))
            val threads = runs.mapIndexed { wk, (from, to) ->
                // This run's share of the kept frames, by output position: [from, to).
                thread(name = "video-frames-$wk") {
                    runCatching {
                        withRetriever(open) { r ->
                            var out = from
                            var k = keep[from]
                            val end = keep[to - 1] + 1
                            while (k < end && !failed.get()) {
                                val n = minOf(chunk, end - k)
                                val frames = r.getFramesAtIndex(k, n, params)
                                frames.forEachIndexed { j, bitmap ->
                                    if (out < to && keep[out] == k + j) {
                                        val i = out++
                                        waiting.acquire()
                                        writers.execute {
                                            try {
                                                write(bitmap, dir, i)
                                            } catch (e: Exception) {
                                                failed.set(true)
                                            } finally {
                                                bitmap.recycle()
                                                waiting.release()
                                            }
                                        }
                                    } else {
                                        bitmap.recycle()
                                    }
                                }
                                k += n
                            }
                            check(out == to) { "Decoded $out of $to frames" }
                        }
                    }.onFailure { failed.set(true) }
                }
            }
            threads.forEach(Thread::join)
            writers.shutdown()
            writers.awaitTermination(1, TimeUnit.MINUTES)
            return if (failed.get()) null else starts
        }

        /**
         * Positions of the video's key frames, in decoding order (which matches display order
         * at key frames). Reads only the container, so it's quick.
         */
        private fun keyFrames(open: () -> ParcelFileDescriptor): List<Int> = open().use { pfd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                extractor.selectTrack(track)
                val keys = ArrayList<Int>()
                var i = 0
                while (extractor.sampleTrackIndex >= 0) {
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keys += i
                    i++
                    extractor.advance()
                }
                keys
            } finally {
                extractor.release()
            }
        }

        private fun extractBySeeking(r: MediaMetadataRetriever, dir: File, keepMs: Int): List<Int> {
            val starts = ArrayList<Int>()
            var at = 0
            while (at < keepMs) {
                r.getFrameAtTime(at * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, params)?.let {
                    write(it, dir, starts.size)
                    it.recycle()
                    starts += at
                }
                at += FRAME_MS
            }
            return starts
        }

        /** Frames [extract] wrote earlier, or null if they're gone. */
        fun open(dir: File): VideoFrames? = runCatching {
            val lines = File(dir, INDEX).readLines()
            val (w, h, ms) = lines.first().split(" ").map(String::toInt)
            VideoFrames(dir, w, h, lines.drop(1).map(String::toInt).toIntArray(), ms)
        }.getOrNull()
    }
}
