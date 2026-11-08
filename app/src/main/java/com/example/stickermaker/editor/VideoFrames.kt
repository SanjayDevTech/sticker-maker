package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
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
 *
 * Frames may still be loading after [extract] returns: [loaded] says which stretches are
 * ready, and anything that plays or saves frames stays inside one. [focus] loads a stretch
 * next (what the trim bar shows). The frame list itself is fixed from the start, so [frame]
 * may run on several threads at once.
 */
class VideoFrames private constructor(
    private val dir: File,
    override val width: Int,
    override val height: Int,
    override val startMs: IntArray,
    override val durationMs: Int,
    finished: Boolean = true,
) : Animation {
    override val frameCount: Int get() = startMs.size

    /**
     * The stretches of the video that are ready, in order and never touching, and whether
     * loading has stopped. Usually one growing from the start; [focus] on a part further in
     * starts another there.
     */
    data class Loaded(val spans: List<Span>, val finished: Boolean) {
        /** Frames from [fromMs] up to [toMs] are all ready. */
        data class Span(val fromMs: Int, val toMs: Int)

        /** The ready stretch [ms] falls in, or null. */
        fun spanAt(ms: Int): Span? = spans.firstOrNull { ms >= it.fromMs && ms < it.toMs }

        /** How far it's ready from the very start. */
        val readyMs: Int get() = spans.firstOrNull()?.takeIf { it.fromMs == 0 }?.toMs ?: 0

        /** How much of the video is ready, wherever it is. */
        val totalMs: Int get() = spans.sumOf { it.toMs - it.fromMs }

        /** Whether every frame in [fromMs, toMs) is ready. */
        fun covers(fromMs: Int, toMs: Int): Boolean = spanAt(fromMs)?.let { toMs <= it.toMs } == true
    }

    private val _loaded = MutableStateFlow(Loaded(if (finished) listOf(Loaded.Span(0, durationMs)) else emptyList(), finished))
    val loaded: StateFlow<Loaded> = _loaded.asStateFlow()

    /** Frames written or given up on. */
    private val done = BooleanArray(frameCount) { finished }

    @Volatile
    private var cancelled = false

    /** The stretch to load next, in ms, or null to load in order. */
    @Volatile
    private var focus: IntRange? = null

    /**
     * Where this video sits among the session's images. Videos load the rest of their frames
     * one after another in this order (after the one open in the editor), so the next one
     * you'll edit is the next one done.
     */
    @Volatile
    var order: Int = 0

    /**
     * An HDR (HLG) video this phone's decoder couldn't convert to normal colours. It's shown
     * unconverted, which looks a little flat rather than wrong.
     */
    @Volatile
    var hdrUnconverted: Boolean = false
        private set

    /** No decoder on this phone can play the video's format ([mime], its profile or size). */
    class UnsupportedVideo(val mime: String) : IOException("No decoder on this phone for $mime")

    /** Stops loading (the session is over); frames already written stay readable until deleted. */
    fun close() {
        cancelled = true
    }

    /**
     * Loads [fromMs] to [toMs] before the rest (what the trim bar shows right now), and this
     * video before others still loading in the background. Null: back to loading in order.
     */
    fun focus(fromMs: Int, toMs: Int) {
        focus = fromMs..toMs
    }

    fun unfocus() {
        focus = null
    }

    /** Frame [i] is written, or won't be (it then shows the one before). */
    private fun markDone(i: Int) = synchronized(done) {
        if (done[i]) return@synchronized
        done[i] = true
        val spans = ArrayList<Loaded.Span>()
        var k = 0
        while (k < frameCount) {
            if (!done[k]) {
                k++
                continue
            }
            val from = k
            while (k < frameCount && done[k]) k++
            spans += Loaded.Span(startMs[from], if (k < frameCount) startMs[k] else durationMs)
        }
        _loaded.value = Loaded(spans, false)
    }

    private fun finish() = synchronized(done) {
        _loaded.value = _loaded.value.copy(finished = true)
    }

    /**
     * Transparent if the frame is gone (the session was cleared while something still plays
     * it). A frame the decoder skipped shows the one before.
     */
    override fun frame(i: Int): IntArray = IntArray(width * height).also { decode(i, null)?.getPixels(it, 0, width, 0, 0, width, height) }

    private fun decode(i: Int, reuse: Bitmap?): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inBitmap = reuse
        }
        for (k in i downTo maxOf(0, i - LOOK_BACK)) {
            val file = frameFile(dir, k)
            if (!file.exists()) continue
            return runCatching { BitmapFactory.decodeFile(file.path, options) }.getOrNull()
        }
        return null
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

    /**
     * Decodes [frames] with [MediaCodec] onto the GPU ([FrameGrabber]), keeping the frames at
     * [keepPts]; decoded frames that aren't kept never leave the decoder. Goes in order, except
     * that it jumps (from the key frame before) to the start of the video first, then to a
     * [focus]ed stretch. Saving runs on a pool beside it. Owns [extractor] and [pfd].
     */
    private class GpuLoader(
        private val frames: VideoFrames,
        private val dir: File,
        private val extractor: MediaExtractor,
        private val pfd: ParcelFileDescriptor,
        private val format: MediaFormat,
        private val mime: String,
        /** The decoder [decoderFor] picked, or null to let Android choose by [mime]. */
        private val decoder: String?,
        /** The video's colour transfer (HDR: HLG or PQ), 0 if unknown. */
        private val transfer: Int,
        private val keepPts: LongArray,
        private val keys: LongArray,
        private val endUs: Long,
        private val rotation: Int,
        private val shrink: Float,
        private val headFrames: Int,
        private val needMs: Int,
        private val head: CountDownLatch,
    ) {
        private val n = keepPts.size

        /** Frames handed to a writer or given up on. Loader thread only. */
        private val claimed = BooleanArray(n)
        private var firstUnclaimed = 0
        @Volatile
        private var level = Turns.HEAD

        /**
         * Higher goes first: the level, then (for the rest of a video) the one open in the
         * editor, then list order.
         */
        fun priority(): Long {
            val open = if (level == Turns.REST && frames.focus != null) 1L else 0L
            return level * 1_000_000_000L + open * 100_000_000L + (99_999_999L - frames.order.coerceIn(0, 99_999_999))
        }

        private val writes = AtomicBoolean(true)
        private val firstWritten = AtomicBoolean(false)
        private val writers = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, MAX_DECODERS))
        private val pool = ArrayBlockingQueue<Bitmap>(GPU_QUEUE)

        private lateinit var codec: MediaCodec
        private lateinit var grabber: FrameGrabber
        private val info = MediaCodec.BufferInfo()

        /** The kept frame the decoder reaches next. */
        private var out = 0
        private var inputDone = false
        private var outputDone = false
        private var lastOutput = 0L

        fun run() {
            try {
                repeat(GPU_QUEUE) { pool.put(Bitmap.createBitmap(frames.width, frames.height, Bitmap.Config.ARGB_8888)) }
                grabber = FrameGrabber(frames.width, frames.height, rotation, shrink)
                codec = if (decoder != null) MediaCodec.createByCodecName(decoder) else MediaCodec.createDecoderByType(mime)
                codec.configure(format, grabber.surface, null, 0)
                if (isHdr(transfer)) {
                    val converts = codec.inputFormat.intOr(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, 0) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO
                    if (!converts) {
                        // HLG still looks fine unconverted, just a little flat: keep the fast
                        // path. PQ would look washed out, so it goes to the slower retriever,
                        // which converts.
                        check(transfer == MediaFormat.COLOR_TRANSFER_HLG) { "This phone can't turn PQ HDR into SDR" }
                        codec.reset()
                        format.removeKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
                        codec.configure(format, grabber.surface, null, 0)
                        frames.hdrUnconverted = true
                    }
                }
                codec.start()
                lastOutput = SystemClock.uptimeMillis()
                while (!frames.cancelled && writes.get()) {
                    val wanted = wanted()
                    if (wanted < 0) break
                    if (outputDone || wanted < out || worthJumping(wanted)) jumpTo(wanted)
                    Turns.take(this) { step() }
                }
                writers.shutdown()
                writers.awaitTermination(1, TimeUnit.MINUTES)
                if (!frames.cancelled && writes.get()) {
                    File(dir, INDEX).writeText("${frames.width} ${frames.height} ${frames.durationMs}\n" + frames.startMs.joinToString("\n"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Decoding stopped", e)
            } finally {
                Turns.leave(this)
                writers.shutdown()
                writers.awaitTermination(1, TimeUnit.MINUTES)
                if (::codec.isInitialized) {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
                if (::grabber.isInitialized) runCatching { grabber.close() }
                extractor.release()
                pfd.close()
                frames.finish()
                head.countDown()
            }
        }

        /**
         * The frame to load next (-1: all done): the start first, then what's focused, then in
         * order. Sets this loader's turn [level] to match.
         */
        private fun wanted(): Int {
            while (firstUnclaimed < n && claimed[firstUnclaimed]) firstUnclaimed++
            val (next, newLevel) = when {
                firstUnclaimed < headFrames -> firstUnclaimed to Turns.HEAD
                else -> focused().let { if (it >= 0) it to Turns.FOCUS else firstUnclaimed to Turns.REST }
            }
            level = newLevel
            return if (next < n) next else -1
        }

        /** The first frame of the focused stretch still to load, or -1. */
        private fun focused(): Int {
            val focus = frames.focus ?: return -1
            var i = frames.frameAt(focus.first)
            while (i < n && frames.startMs[i] < focus.last) {
                if (!claimed[i]) return i
                i++
            }
            return -1
        }

        /** The last key frame at or before [us]. */
        private fun keyBefore(us: Long): Long {
            var lo = 0
            var hi = keys.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (keys[mid] <= us) lo = mid else hi = mid - 1
            }
            return keys[lo]
        }

        /** Whether starting over from the key frame before [wanted] beats decoding up to it. */
        private fun worthJumping(wanted: Int): Boolean {
            if (out >= n) return true
            return keyBefore(keepPts[wanted]) - keepPts[out] > JUMP_US
        }

        private fun jumpTo(wanted: Int) {
            val key = keyBefore(keepPts[wanted])
            codec.flush()
            extractor.seekTo(key, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            inputDone = false
            outputDone = false
            out = 0
            while (out < n && keepPts[out] < key) out++
            lastOutput = SystemClock.uptimeMillis()
        }

        /** Feeds the decoder one sample and takes one frame out, if it has one. */
        private fun step() {
            if (!inputDone) {
                val slot = codec.dequeueInputBuffer(2_000)
                if (slot >= 0) {
                    val size = extractor.readSampleData(codec.getInputBuffer(slot)!!, 0)
                    val t = extractor.sampleTime
                    if (size < 0 || t > endUs) {
                        codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(slot, 0, size, t, 0)
                        extractor.advance()
                    }
                }
            }
            val slot = codec.dequeueOutputBuffer(info, 2_000)
            if (slot < 0) {
                check(SystemClock.uptimeMillis() - lastOutput < STALL_MS) { "Decoder stalled" }
                return
            }
            lastOutput = SystemClock.uptimeMillis()
            val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            val pts = info.presentationTimeUs
            // Frames the decoder went past without producing show the one before.
            if (!end) while (out < n && keepPts[out] < pts) skip(out++)
            val match = out < n && keepPts[out] == pts
            val keep = match && !claimed[out]
            codec.releaseOutputBuffer(slot, keep)
            if (keep) save(out)
            if (match) out++
            if (end) {
                outputDone = true
                // Nothing more comes from this run: whatever it should have reached is lost.
                for (i in out until n) if (!claimed[i]) skip(i)
            }
        }

        private fun skip(i: Int) {
            if (claimed[i]) return
            claimed[i] = true
            ready(i)
        }

        private fun save(i: Int) {
            claimed[i] = true
            val pixels = grabber.grab()
            val bitmap = pool.take()
            bitmap.copyPixelsFromBuffer(pixels)
            writers.execute {
                try {
                    frameFile(dir, i).outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                    if (i == 0) firstWritten.set(true)
                    ready(i)
                } catch (e: Exception) {
                    // The session was cleared, or the disk is full.
                    writes.set(false)
                } finally {
                    pool.put(bitmap)
                }
            }
        }

        private fun ready(i: Int) {
            frames.markDone(i)
            if (firstWritten.get() && frames.loaded.value.readyMs >= needMs) head.countDown()
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

        /** How far back [frame] looks for a frame the decoder skipped. */
        private const val LOOK_BACK = 25

        /** Frames waiting to be saved while decoding on the GPU (each a small bitmap). */
        private const val GPU_QUEUE = 12

        /** Jump ahead (from a key frame) rather than decode through more than this. */
        private const val JUMP_US = 1_500_000L

        private const val SEEK_MISSES_AT_START = 5
        private const val SEEK_MISSES_LATER = 50

        /** A decoder that produces nothing for this long is stuck. */
        private const val STALL_MS = 10_000L
        private const val TAG = "VideoFrames"

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
         * picture or no frame could be read.
         *
         * Blocks until the first [headMs] are ready; the rest keeps loading in the background
         * (see [loaded]). Decoding goes through the GPU ([FrameGrabber]); if that fails before
         * the start is ready, the slower [MediaMetadataRetriever] path decodes it all instead.
         * [onLoading] gets the frames as soon as they start loading, to watch [loaded], and
         * null if that attempt is dropped for the fallback.
         */
        fun extract(
            open: () -> ParcelFileDescriptor,
            dir: File,
            headMs: Int = Int.MAX_VALUE,
            onLoading: (VideoFrames?) -> Unit = {},
        ): VideoFrames? {
            val meta = withRetriever(open) { r ->
                fun key(k: Int) = r.extractMetadata(k)
                if (key(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return@withRetriever null
                Meta(
                    durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: return@withRetriever null,
                    frameCount = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0,
                    width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                    rotation = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                )
            } ?: return null
            if (meta.durationMs <= 0) return null
            val keepMs = minOf(meta.durationMs, MAX_MS)
            dir.deleteRecursively()
            dir.mkdirs()

            runCatching { decodeOnGpu(open, dir, meta, keepMs, headMs, onLoading) }
                .onFailure {
                    // Nothing on this phone can decode it: the retriever would only fail slower.
                    if (it is UnsupportedVideo) {
                        onLoading(null)
                        dir.deleteRecursively()
                        throw it
                    }
                }
                .onFailure { Log.w(TAG, "GPU decoding failed; using the retriever", it) }
                .onFailure { onLoading(null) }
                .getOrNull()
                ?.let { return it }
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

        private class Meta(val durationMs: Int, val frameCount: Int, val width: Int, val height: Int, val rotation: Int) {
            val pixels: Long get() = (if (width > 0) width else 1920).toLong() * (if (height > 0) height else 1080)
        }

        /**
         * Decodes with [MediaCodec] onto the GPU ([FrameGrabber]), one frame per [FRAME_MS]
         * step by the frames' real timestamps; decoded frames that aren't kept never leave the
         * decoder. Saving runs on a pool beside it. Returns once frames up to [headMs] are
         * written and keeps going in the background; throws if it fails before that.
         */
        private fun decodeOnGpu(
            open: () -> ParcelFileDescriptor,
            dir: File,
            meta: Meta,
            keepMs: Int,
            headMs: Int,
            onLoading: (VideoFrames?) -> Unit,
        ): VideoFrames {
            val pfd = open()
            val extractor = MediaExtractor()
            var handedOver = false
            try {
                extractor.setDataSource(pfd.fileDescriptor)
                val track = (0 until extractor.trackCount).first {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                }
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                // Which decoder can play it. None at all: say so now, rather than after the
                // retriever has spent a minute failing too.
                val decoder = probeFormat(format)?.let { probe ->
                    MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(probe) ?: throw UnsupportedVideo(mime)
                }

                // Every frame's timestamp, from the container alone (quick). Decoding order
                // isn't display order, so read a little past the end and sort.
                val keepUs = keepMs * 1000L
                val times = ArrayList<Long>()
                val keys = ArrayList<Long>()
                val first = extractor.sampleTime
                while (extractor.sampleTrackIndex >= 0) {
                    val t = extractor.sampleTime
                    if (t - first > keepUs + 2_000_000) break
                    times += t
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) keys += t
                    extractor.advance()
                }
                times.sort()
                keys.sort()
                if (keys.isEmpty()) keys += first
                val base = times.firstOrNull() ?: error("No frames")
                val keepPts = ArrayList<Long>()
                val starts = ArrayList<Int>()
                var next = 0
                for (t in times) {
                    val at = ((t - base) / 1000).toInt()
                    if (at >= keepMs) break
                    if (at < next) continue
                    keepPts += t
                    starts += at
                    while (next <= at) next += FRAME_MS
                }
                check(keepPts.size >= 2) { "Under 2 frames" }
                extractor.seekTo(first, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val rotation = meta.rotation.takeIf { it != 0 } ?: format.intOr(MediaFormat.KEY_ROTATION, 0)
                val srcW = meta.width.takeIf { it > 0 } ?: format.getInteger(MediaFormat.KEY_WIDTH)
                val srcH = meta.height.takeIf { it > 0 } ?: format.getInteger(MediaFormat.KEY_HEIGHT)
                val turned = rotation % 180 != 0
                val shownW = if (turned) srcH else srcW
                val shownH = if (turned) srcW else srcH
                val scale = minOf(1f, MAX_SIDE.toFloat() / max(shownW, shownH))
                val outW = max(1, (shownW * scale).roundToInt())
                val outH = max(1, (shownH * scale).roundToInt())
                // We turn frames upright ourselves; the decoder mustn't as well.
                format.setInteger(MediaFormat.KEY_ROTATION, 0)
                val transfer = format.intOr(MediaFormat.KEY_COLOR_TRANSFER, 0)
                // HDR would come out washed out on an 8-bit surface: ask for SDR (API 31).
                if (isHdr(transfer)) format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)

                val frames = VideoFrames(dir, outW, outH, starts.toIntArray(), keepMs, finished = false)
                onLoading(frames)
                val needMs = minOf(headMs, keepMs)
                val head = CountDownLatch(1)
                handedOver = true
                val loader = GpuLoader(
                    frames, dir, extractor, pfd, format, mime, decoder, transfer,
                    keepPts = keepPts.toLongArray(),
                    keys = keys.toLongArray(),
                    endUs = base + keepUs + 1_000_000,
                    rotation = rotation,
                    shrink = max(shownW, shownH).toFloat() / max(outW, outH),
                    headFrames = starts.count { it < needMs },
                    needMs = needMs,
                    head = head,
                )
                Turns.enter(loader)
                thread(name = "video-frames") { loader.run() }
                head.await()
                val loaded = frames.loaded.value
                if (loaded.readyMs < needMs || !frameFile(dir, 0).exists()) {
                    frames.close()
                    error("Stopped at ${loaded.readyMs} ms")
                }
                return frames
            } finally {
                if (!handedOver) {
                    extractor.release()
                    pfd.close()
                }
            }
        }

        private fun MediaFormat.intOr(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default

        private fun isHdr(transfer: Int) = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG

        /**
         * What a decoder must handle for [track]: codec, size and profile (which is where
         * 10-bit lives). Not the level: containers often overstate it. Null if the track
         * doesn't say enough to ask.
         */
        private fun probeFormat(track: MediaFormat): MediaFormat? {
            val mime = track.getString(MediaFormat.KEY_MIME) ?: return null
            if (!track.containsKey(MediaFormat.KEY_WIDTH) || !track.containsKey(MediaFormat.KEY_HEIGHT)) return null
            return MediaFormat.createVideoFormat(mime, track.getInteger(MediaFormat.KEY_WIDTH), track.getInteger(MediaFormat.KEY_HEIGHT)).apply {
                if (track.containsKey(MediaFormat.KEY_PROFILE)) setInteger(MediaFormat.KEY_PROFILE, track.getInteger(MediaFormat.KEY_PROFILE))
            }
        }

        /**
         * Lets one video decode at a time, a frame per turn, always the most urgent
         * ([GpuLoader.priority]): every video's start (a batch opens once all are ready), then
         * the part a trim bar shows, then the rest of the video open in the editor, then the
         * other videos one after another in list order. Several decoders and GL contexts at
         * once were far slower in total than one after another (four 10 s clips: 15 s against
         * about 5 s), and finishing videos in turn beats all of them finishing late together.
         */
        private object Turns {
            const val REST = 0
            const val FOCUS = 1
            const val HEAD = 2

            private val lock = Object()
            private var busy = false
            private val loaders = HashSet<GpuLoader>()

            fun enter(loader: GpuLoader) = synchronized(lock) { loaders += loader }

            fun leave(loader: GpuLoader) = synchronized(lock) {
                loaders -= loader
                lock.notifyAll()
            }

            fun <T> take(me: GpuLoader, block: () -> T): T {
                synchronized(lock) {
                    while (busy || loaders.any { it !== me && it.priority() > me.priority() }) {
                        // Priorities also change outside turns (focus, order): look again soon.
                        lock.wait(WAIT_MS)
                    }
                    busy = true
                }
                try {
                    return block()
                } finally {
                    synchronized(lock) {
                        busy = false
                        lock.notifyAll()
                    }
                }
            }

            private const val WAIT_MS = 50L
        }

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

        /**
         * Gives up after [SEEK_MISSES_AT_START] misses before any frame worked (it can't decode
         * this), or [SEEK_MISSES_LATER] in a row later (the rest is unreadable; keep what's there).
         */
        private fun extractBySeeking(r: MediaMetadataRetriever, dir: File, keepMs: Int): List<Int> {
            val starts = ArrayList<Int>()
            var at = 0
            var misses = 0
            while (at < keepMs) {
                val frame = r.getFrameAtTime(at * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, params)
                if (frame != null) {
                    write(frame, dir, starts.size)
                    frame.recycle()
                    starts += at
                    misses = 0
                } else if (++misses >= if (starts.isEmpty()) SEEK_MISSES_AT_START else SEEK_MISSES_LATER) {
                    break
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
