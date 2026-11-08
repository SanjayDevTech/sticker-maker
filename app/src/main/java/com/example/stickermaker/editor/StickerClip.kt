package com.example.stickermaker.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.example.stickermaker.data.Trim
import com.example.stickermaker.data.WhatsAppLimits
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * An animated sticker before encoding: its frames at sticker size, in order. The Save screen
 * plays one and encodes it the same way, whatever it's made of.
 */
interface StickerClip {
    /** A fresh set of frames (with its own decoding state), [side] px square. */
    fun frames(side: Int = WhatsAppLimits.STICKER_PX): StickerExport.FrameSet
}

/**
 * A GIF's or video's trimmed part, fitted into the square. Frames can be dropped to fit the
 * size limit. A video is busier than most GIFs, so it keeps at least [MIN_VIDEO_FPS] (lower
 * quality first) unless nothing at that rate fits.
 */
class GifStickerClip(val gif: Animation, val trim: Trim) : StickerClip {
    override fun frames(side: Int): StickerExport.FrameSet {
        val timeline = Timeline.of(gif.startMs, gif.durationMs, trim.startMs, trim.endMs, StickerMotion.FRAME_MS)
        val fitter = StickerExport.GifFitter(gif, side)
        fun set(timeline: Timeline): StickerExport.FrameSet {
            // Frames build on each other and are asked for in order: play forward with one cursor
            // (one decode per frame) instead of replaying from a checkpoint each time.
            var cursor: Animation.Cursor? = null
            val render = { k: Int ->
                val index = timeline.frames[k]
                val c = cursor?.takeIf { it.index <= index } ?: gif.cursor(index).also { cursor = it }
                while (c.index < index) c.advance()
                fitter.fit(c.canvas)
            }
            // Never below two frames: WhatsApp wants an animated sticker to animate.
            val fps = timeline.size * 1000f / timeline.durations.sum().coerceAtLeast(1)
            return StickerExport.FrameSet(timeline.durations, render, lastResort = gif is VideoFrames && fps < MIN_VIDEO_FPS) {
                timeline.thinned().takeIf { it.size in 2 until timeline.size }?.let(::set)
            }
        }
        return set(timeline)
    }
}

private const val MIN_VIDEO_FPS = 8

/** One layer of a combined sticker, frozen when the combine canvas is saved. */
class LayerSnapshot(
    /** The cut-out, or a GIF layer's first kept frame (the GIF's full size). */
    val still: Bitmap,
    /** Artboard px per layer px is size × side / still.width; see [Layer]. */
    val centerX: Float,
    val centerY: Float,
    val size: Float,
    val rotation: Float,
    val flipped: Boolean,
    val motion: StickerMotion,
    val gif: Animation?,
    val trim: Trim?,
    /** The cut-out with its outline, drawn instead of [still]; null for none. */
    val outlined: OutlinedBitmap? = null,
) {
    val gifLengthMs: Int get() = trim?.lengthMs ?: 0
}

/**
 * Layers drawn together over time. Not animated (no GIF, no motion) it's one still; animated,
 * every frame draws each layer at its own point in its loop, timed by a [Choreography].
 */
class Composition(val layers: List<LayerSnapshot>) : StickerClip {

    val choreography = Choreography(
        gifLengthsMs = layers.filter { it.gif != null }.map { it.gifLengthMs },
        motionPeriodsMs = layers.filter { it.gif == null && it.motion != StickerMotion.None }.map { it.motion.periodMs },
    )

    val animated: Boolean get() = choreography.animated

    /** The sticker at time 0 (the whole sticker when it doesn't move). */
    fun still(side: Int = WhatsAppLimits.STICKER_PX): Bitmap = Renderer(side).draw(0).copy(Bitmap.Config.ARGB_8888, false)

    override fun frames(side: Int): StickerExport.FrameSet = framesEvery(side, StickerMotion.FRAME_MS)

    /**
     * Frames every [frameMs]; too heavy, the grid coarsens (fewer frames, same timing). Past
     * [MAX_FRAME_MS] only as a last resort: a busy video layer may not fit 500 KB otherwise.
     */
    private fun framesEvery(side: Int, frameMs: Int): StickerExport.FrameSet {
        val (starts, durations) = choreography.frames(frameMs)
        val renderer = Renderer(side)
        return StickerExport.FrameSet(
            durations,
            { i ->
                val frame = renderer.draw(starts[i])
                IntArray(side * side).also { frame.getPixels(it, 0, side, 0, 0, side, side) }
            },
            lastResort = frameMs > MAX_FRAME_MS,
        ) {
            val next = frameMs * 3 / 2
            if (choreography.durationMs / next >= 2) framesEvery(side, next) else null
        }
    }

    /**
     * Draws the composition at any time; reuses its bitmaps, so one caller at a time. Asked in
     * time order (as a pass does), each GIF layer plays forward one decode per frame.
     */
    private inner class Renderer(private val side: Int) {
        private val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(out)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val matrix = Matrix()

        // Per layer: a pre-shrunk still (big shrinks look jagged with bilinear alone), or the
        // GIF's cursor and a bitmap its frames are copied into.
        private val stills = layers.map { layer ->
            if (layer.gif != null) return@map null
            val source = layer.outlined?.bitmap ?: layer.still
            // Drawn width of the source (outline padding included).
            val drawn = layer.size * side * source.width / layer.still.width
            if (drawn < source.width / 2f) {
                StickerExport.smoothScale(source, max(1, drawn.roundToInt()), max(1, (drawn * source.height / source.width).roundToInt()))
            } else {
                source
            }
        }
        private val cursors = arrayOfNulls<Animation.Cursor>(layers.size)
        private val gifFrames = layers.map { l -> l.gif?.let { Bitmap.createBitmap(it.width, it.height, Bitmap.Config.ARGB_8888) } }

        fun draw(t: Int): Bitmap {
            out.eraseColor(0)
            layers.forEachIndexed { i, layer ->
                val gif = layer.gif
                val bitmap = if (gif != null) {
                    val local = choreography.localMs(layer.gifLengthMs, t).toInt()
                    val index = gif.frameAt(requireNotNull(layer.trim).startMs + local)
                    val c = cursors[i]?.takeIf { it.index <= index } ?: gif.cursor(index).also { cursors[i] = it }
                    while (c.index < index) c.advance()
                    requireNotNull(gifFrames[i]).also { it.setPixels(c.canvas, 0, gif.width, 0, 0, gif.width, gif.height) }
                } else {
                    requireNotNull(stills[i])
                }
                layerMatrix(layer, side.toFloat(), t, matrix)
                // The outlined copy is padded: shift onto the cut-out's pixel grid.
                val outlined = layer.outlined.takeIf { gif == null }
                outlined?.let { matrix.preTranslate(-it.pad.toFloat(), -it.pad.toFloat()) }
                // Drawing a pre-shrunk copy: scale back up to the source's pixel grid.
                val source = outlined?.bitmap ?: layer.still
                if (bitmap.width != source.width) {
                    matrix.preScale(source.width.toFloat() / bitmap.width, source.height.toFloat() / bitmap.height)
                }
                canvas.drawBitmap(bitmap, matrix, paint)
            }
            return out
        }
    }

    /** Where [layer] draws at time [t] on an artboard [side] px wide: placement, then its motion. */
    fun layerMatrix(layer: LayerSnapshot, side: Float, t: Int, into: Matrix) {
        placement(layer.still.width, layer.still.height, layer.centerX, layer.centerY, layer.size, layer.rotation, layer.flipped, side, into)
        if (layer.gif == null && layer.motion != StickerMotion.None) {
            val local = choreography.localMs(layer.motion.periodMs, t) / layer.motion.periodMs
            applyMotion(layer.motion.pose(local), layer.still.width, layer.still.height, side, into)
        }
    }

    companion object {
        /** The coarsest frame grid a combined sticker drops to (8 fps). */
        private const val MAX_FRAME_MS = 125

        /** A layer's placement: its pixels centred on (cx, cy) of the artboard, sized, turned, flipped. */
        fun placement(w: Int, h: Int, cx: Float, cy: Float, size: Float, rotation: Float, flipped: Boolean, side: Float, into: Matrix) {
            val s = size * side / w
            into.reset()
            into.postTranslate(-w / 2f, -h / 2f)
            into.postScale(if (flipped) -s else s, s)
            into.postRotate(rotation)
            into.postTranslate(cx * side, cy * side)
        }

        /**
         * Adds a motion [pose] to a layer's [matrix]: scaled and turned about its pivot in the
         * layer's own frame (so a swing hangs from the layer's top however it's turned), then
         * moved in artboard terms.
         */
        fun applyMotion(pose: Pose, w: Int, h: Int, side: Float, matrix: Matrix) {
            val px = w / 2f
            val py = h * pose.pivotY
            matrix.preTranslate(px, py)
            matrix.preRotate(pose.rotation)
            matrix.preScale(pose.scaleX, pose.scaleY)
            matrix.preTranslate(-px, -py)
            matrix.postTranslate(pose.dx * side, pose.dy * side)
        }
    }
}
