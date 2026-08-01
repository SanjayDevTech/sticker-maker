package com.example.stickermaker.editor

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A looping move applied to a whole cut-out: what turns a still sticker into an animated one.
 * Every motion is periodic in t ∈ [0, 1), so the last frame flows back into the first.
 */
enum class StickerMotion(val label: String, val periodMs: Int) {
    None("Still", 0),
    Wiggle("Wiggle", 1000),
    Bounce("Bounce", 900),
    Pulse("Pulse", 1000),
    Jelly("Jelly", 900),
    Shake("Shake", 600),
    Hover("Hover", 1600),
    Swing("Swing", 1400),
    Spin("Spin", 1600),
    ;

    /** Frames in one loop at [FRAME_MS] each. */
    val frameCount: Int get() = if (this == None) 1 else max(2, (periodMs / FRAME_MS.toFloat()).roundToInt())

    /** The pose at loop position [t] (0..1). Offsets are fractions of the sticker's side. */
    fun pose(t: Float): Pose {
        val wave = sin(2 * PI * t).toFloat()
        return when (this) {
            None -> Pose.Rest
            // Rocks on its base, like a little dance.
            Wiggle -> Pose(rotation = 9f * wave, pivotY = 1f)
            // A hop: up and down along a sine arch, squashed flat at the moment it lands.
            Bounce -> {
                val air = sin(PI * t).toFloat()
                val squash = 0.14f * (1 - air).pow(6)
                Pose(dy = -0.09f * air, scaleX = 1 + squash, scaleY = 1 - squash, pivotY = 1f)
            }
            // A heartbeat: two quick beats, then rest.
            Pulse -> {
                val beat = { c: Float -> (1 - (abs(t - c) / 0.12f)).coerceAtLeast(0f).pow(2) }
                val s = 1 + 0.1f * max(beat(0.15f), 0.7f * beat(0.42f))
                Pose(scaleX = s, scaleY = s)
            }
            // Wobbles wide then tall, keeping its base on the ground.
            Jelly -> Pose(scaleX = 1 + 0.08f * wave, scaleY = 1 - 0.08f * wave, pivotY = 1f)
            Shake -> Pose(dx = 0.025f * sin(4 * PI * t).toFloat(), rotation = 3f * sin(2 * PI * t + PI / 2).toFloat())
            Hover -> Pose(dy = 0.035f * wave, rotation = 3f * cos(2 * PI * t).toFloat())
            // Hangs from its top edge like a sign.
            Swing -> Pose(rotation = 14f * wave, pivotY = 0f)
            Spin -> Pose(rotation = 360f * t)
        }
    }

    companion object {
        /** 25 fps: smooth, and WhatsApp's minimum frame time is 8 ms. */
        const val FRAME_MS = 40
    }
}

/**
 * A transform relative to the cut-out: scaled and rotated (degrees, clockwise) about a pivot on
 * its bounds' centre line ([pivotY]: 0 top, 0.5 middle, 1 bottom), then moved by [dx]/[dy].
 */
data class Pose(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val pivotY: Float = 0.5f,
) {
    companion object {
        val Rest = Pose()
    }
}

/** A 2D affine map: x' = a·x + c·y + tx, y' = b·x + d·y + ty (the layout Android's Matrix uses). */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun mapX(x: Float, y: Float) = a * x + c * y + tx
    fun mapY(x: Float, y: Float) = b * x + d * y + ty

    /** This map applied after [first]. */
    fun after(first: Affine) = Affine(
        a = a * first.a + c * first.b,
        b = b * first.a + d * first.b,
        c = a * first.c + c * first.d,
        d = b * first.c + d * first.d,
        tx = a * first.tx + c * first.ty + tx,
        ty = b * first.tx + d * first.ty + ty,
    )

    /** Android's Matrix.setValues order. */
    fun values() = floatArrayOf(a, c, tx, b, d, ty, 0f, 0f, 1f)

    companion object {
        val Identity = Affine()
        fun translate(x: Float, y: Float) = Affine(tx = x, ty = y)
        fun scale(sx: Float, sy: Float) = Affine(a = sx, d = sy)
        fun rotate(degrees: Float): Affine {
            val r = degrees * PI / 180
            val cs = cos(r).toFloat()
            val sn = sin(r).toFloat()
            return Affine(a = cs, b = sn, c = -sn, d = cs)
        }
    }
}

/**
 * How a still sticker (a [side]-px square) moves in each frame, already shrunk and centred so
 * that no frame ever leaves the square: the same maths draws the live preview and the file.
 */
class MotionPlan private constructor(
    val motion: StickerMotion,
    private val side: Float,
    private val pivotX: Float,
    private val top: Float,
    private val height: Float,
    private val fit: Affine,
) {
    /** The full transform at loop position [t], in the sticker's pixels. */
    fun at(t: Float): Affine = fit.after(local(motion, t, side, pivotX, top, height))

    fun frame(index: Int): Affine = at(index / motion.frameCount.toFloat())

    companion object {
        /**
         * Plans [motion] for a sticker whose visible pixels are [alpha] > 0 (row-major, [side]
         * square). The fit uses each row's outermost visible pixels: every corner of the shape's
         * convex hull is one of those, so the bounds stay exact under any rotation or scale.
         */
        fun of(motion: StickerMotion, alpha: (x: Int, y: Int) -> Boolean, side: Int, margin: Int): MotionPlan? {
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            var minX = side; var maxX = -1; var minY = side; var maxY = -1
            for (y in 0 until side) {
                var first = -1
                var last = -1
                for (x in 0 until side) if (alpha(x, y)) { if (first < 0) first = x; last = x }
                if (first < 0) continue
                // Pixel squares, not centres: add both outer corners of each extreme pixel.
                for (px in intArrayOf(first, last + 1)) for (py in intArrayOf(y, y + 1)) {
                    xs += px.toFloat(); ys += py.toFloat()
                }
                minX = min(minX, first); maxX = max(maxX, last + 1)
                minY = min(minY, y); maxY = max(maxY, y + 1)
            }
            if (maxX < 0) return null
            val s = side.toFloat()
            val pivotX = (minX + maxX) / 2f
            val top = minY.toFloat()
            val height = (maxY - minY).toFloat()

            // Bounds of every frame together.
            var l = Float.MAX_VALUE; var r = -Float.MAX_VALUE
            var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (i in 0 until motion.frameCount) {
                val m = local(motion, i / motion.frameCount.toFloat(), s, pivotX, top, height)
                for (k in xs.indices) {
                    val x = m.mapX(xs[k], ys[k]); val y = m.mapY(xs[k], ys[k])
                    l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
                }
            }
            val room = s - 2 * margin
            val k = min(1f, min(room / (r - l), room / (b - t)))
            // Shrink only if needed, and centre what moves: a hop ends up resting low, hopping up.
            val fit = Affine.translate(s / 2, s / 2)
                .after(Affine.scale(k, k))
                .after(Affine.translate(-(l + r) / 2, -(t + b) / 2))
            return MotionPlan(motion, s, pivotX, top, height, fit)
        }

        private fun local(motion: StickerMotion, t: Float, side: Float, pivotX: Float, top: Float, height: Float): Affine {
            val p = motion.pose(t)
            val pivotY = top + height * p.pivotY
            return Affine.translate(pivotX + p.dx * side, pivotY + p.dy * side)
                .after(Affine.rotate(p.rotation))
                .after(Affine.scale(p.scaleX, p.scaleY))
                .after(Affine.translate(-pivotX, -pivotY))
        }
    }
}
