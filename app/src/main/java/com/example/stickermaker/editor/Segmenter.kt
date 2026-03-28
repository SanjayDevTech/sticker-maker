package com.example.stickermaker.editor

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Finds the subject without AI, GrabCut-style: colour models (Gaussian mixtures) for subject
 * and background, plus a graph cut that prefers to cut where colours change sharply.
 * Re-learning the models from each cut and cutting again tightens the result.
 *
 * Runs on a downscaled copy (long side ≤ [WORK_PX]) for speed; the labels are scaled back up
 * smoothly and the user's marks are re-applied at full size.
 */
object Segmenter {

    const val WORK_PX = 400
    const val SEED_NONE: Byte = 0
    const val SEED_KEEP: Byte = 1
    const val SEED_REMOVE: Byte = 2

    private const val GAMMA = 50f
    private const val ITERATIONS = 4

    /**
     * @param pixels ARGB source, [w]×[h].
     * @param base the mask before Magic: erased pixels (0) are certain background.
     * @param seeds per pixel [SEED_KEEP]/[SEED_REMOVE]/[SEED_NONE]; keep marks are certain subject.
     * @return the new full-size mask (0 or 255), or null if there's no keep mark.
     */
    fun segment(
        pixels: IntArray, w: Int, h: Int,
        base: ByteArray, seeds: ByteArray,
        isActive: () -> Boolean = { true },
    ): ByteArray? {
        if (seeds.none { it == SEED_KEEP }) return null
        val work = Work.from(pixels, w, h, base, seeds)
        val labels = work.run(isActive)
        return upscale(labels, work.w, work.h, w, h, base, seeds)
    }

    /** Hard labels per work pixel. */
    private const val UNKNOWN: Byte = 0
    private const val HARD_FG: Byte = 1
    private const val HARD_BG: Byte = 2

    private class Work(
        val w: Int,
        val h: Int,
        val rgb: FloatArray,
        val hard: ByteArray,
        /** Pixels erased before Magic: certain background, but they don't teach the colour models. */
        val erased: BooleanArray,
    ) {
        val n = w * h

        fun run(isActive: () -> Boolean): BooleanArray {
            // Start like GrabCut with a rectangle: everything is maybe-subject except a thin
            // border band (a photo's edge is almost always background) and what's known.
            val band = max(2, min(w, h) / 50)
            val fg = BooleanArray(n) { p ->
                val x = p % w
                val y = p / w
                when (hard[p]) {
                    HARD_FG -> true
                    HARD_BG -> false
                    else -> x >= band && y >= band && x < w - band && y < h - band
                }
            }
            if (fg.none { !it }) {
                // Nothing to learn the background from: keep everything.
                return BooleanArray(n) { hard[it] != HARD_BG }
            }
            // Earlier erasing may have taken part of the subject too, so those colours must not
            // define "background". The border band still counts, erased or not.
            val train = BooleanArray(n) { p ->
                val x = p % w
                val y = p / w
                !erased[p] || x < band || y < band || x >= w - band || y >= h - band
            }
            val edges = smoothness()
            val fgModel = Gmm()
            val bgModel = Gmm()
            fgModel.init(rgb, fg, true, train)
            bgModel.init(rgb, fg, false, train)
            repeat(ITERATIONS) { iteration ->
                if (!isActive()) throw GridMaxFlow.MaxFlowCancelled()
                if (iteration > 0) {
                    fgModel.learn(rgb, fg, true, train)
                    bgModel.learn(rgb, fg, false, train)
                }
                val graph = GridMaxFlow(w, h)
                var maxLink = 0f
                for (p in 0 until n) {
                    var sum = 0f
                    for (d in 0 until 8) {
                        val c = edges[p * 8 + d]
                        graph.setEdge(p, d, c)
                        sum += c
                    }
                    maxLink = max(maxLink, sum)
                }
                val certain = maxLink + 1f
                for (p in 0 until n) {
                    when (hard[p]) {
                        HARD_FG -> graph.setTerminals(p, certain, 0f)
                        HARD_BG -> graph.setTerminals(p, 0f, certain)
                        else -> {
                            val r = rgb[p * 3]; val g = rgb[p * 3 + 1]; val b = rgb[p * 3 + 2]
                            // Cutting p from the source labels it background: costs -ln P(bg).
                            graph.setTerminals(p, bgModel.cost(r, g, b), fgModel.cost(r, g, b))
                        }
                    }
                }
                graph.maxFlow(isActive)
                var changed = 0
                for (p in 0 until n) {
                    val now = graph.isSource(p)
                    if (now != fg[p]) changed++
                    fg[p] = now
                }
                if (iteration > 0 && changed < n / 1000) return fg
            }
            return fg
        }

        /** Edge weights: high between similar colours (expensive to cut), low across edges. */
        private fun smoothness(): FloatArray {
            var sum = 0.0
            var count = 0
            for (p in 0 until n) {
                val x = p % w
                val y = p / w
                if (x + 1 < w) { sum += diff2(p, p + 1); count++ }
                if (y + 1 < h) { sum += diff2(p, p + w); count++ }
            }
            val beta = if (sum > 0) (count / (2 * sum)).toFloat() else 0f
            val out = FloatArray(n * 8)
            val diag = (1 / sqrt(2.0)).toFloat()
            for (p in 0 until n) {
                for (d in 0 until 8) {
                    val q = neighbor(p, d)
                    if (q < 0) continue
                    val dist = if (d >= 4) diag else 1f
                    out[p * 8 + d] = GAMMA * dist * exp(-beta * diff2(p, q))
                }
            }
            return out
        }

        private fun neighbor(p: Int, d: Int): Int {
            val x = p % w + GridMaxFlow.DX[d]
            val y = p / w + GridMaxFlow.DY[d]
            return if (x < 0 || y < 0 || x >= w || y >= h) -1 else y * w + x
        }

        private fun diff2(p: Int, q: Int): Float {
            val dr = rgb[p * 3] - rgb[q * 3]
            val dg = rgb[p * 3 + 1] - rgb[q * 3 + 1]
            val db = rgb[p * 3 + 2] - rgb[q * 3 + 2]
            return dr * dr + dg * dg + db * db
        }

        companion object {
            fun from(pixels: IntArray, w: Int, h: Int, base: ByteArray, seeds: ByteArray): Work {
                val scale = max(1f, max(w, h).toFloat() / WORK_PX)
                val ww = max(1, (w / scale).roundToInt())
                val wh = max(1, (h / scale).roundToInt())
                val rgb = FloatArray(ww * wh * 3)
                val hard = ByteArray(ww * wh)
                val erasedOnly = BooleanArray(ww * wh)
                for (y in 0 until wh) {
                    val y0 = y * h / wh
                    val y1 = max(y0 + 1, (y + 1) * h / wh)
                    for (x in 0 until ww) {
                        val x0 = x * w / ww
                        val x1 = max(x0 + 1, (x + 1) * w / ww)
                        var r = 0; var g = 0; var b = 0; var count = 0
                        var keep = false; var remove = false; var erased = 0
                        for (sy in y0 until y1) {
                            for (sx in x0 until x1) {
                                val i = sy * w + sx
                                val c = pixels[i]
                                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                                count++
                                when (seeds[i]) {
                                    SEED_KEEP -> keep = true
                                    SEED_REMOVE -> remove = true
                                }
                                if (base[i] == MaskOps.ERASED) erased++
                            }
                        }
                        val p = y * ww + x
                        rgb[p * 3] = r.toFloat() / count
                        rgb[p * 3 + 1] = g.toFloat() / count
                        rgb[p * 3 + 2] = b.toFloat() / count
                        hard[p] = when {
                            keep -> HARD_FG
                            remove || erased * 2 > count -> HARD_BG
                            else -> UNKNOWN
                        }
                        erasedOnly[p] = !keep && !remove && erased * 2 > count
                    }
                }
                return Work(ww, wh, rgb, hard, erasedOnly)
            }
        }
    }

    /** Bilinear upscale of the labels, then the full-size truths: marks and earlier erasing. */
    private fun upscale(
        labels: BooleanArray, ww: Int, wh: Int, w: Int, h: Int,
        base: ByteArray, seeds: ByteArray,
    ): ByteArray {
        val out = ByteArray(w * h)
        val sx = ww.toFloat() / w
        val sy = wh.toFloat() / h
        fun at(x: Int, y: Int) = if (labels[y.coerceIn(0, wh - 1) * ww + x.coerceIn(0, ww - 1)]) 1f else 0f
        for (y in 0 until h) {
            val v = (y + 0.5f) * sy - 0.5f
            val y0 = kotlin.math.floor(v).toInt()
            val fy = v - y0
            for (x in 0 until w) {
                val i = y * w + x
                val keep = when {
                    seeds[i] == SEED_KEEP -> true
                    seeds[i] == SEED_REMOVE || base[i] == MaskOps.ERASED -> false
                    else -> {
                        val u = (x + 0.5f) * sx - 0.5f
                        val x0 = kotlin.math.floor(u).toInt()
                        val fx = u - x0
                        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
                        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
                        top * (1 - fy) + bottom * fy >= 0.5f
                    }
                }
                out[i] = if (keep) MaskOps.KEPT else MaskOps.ERASED
            }
        }
        return out
    }

    /** Gaussian mixture over RGB with full covariances, as in GrabCut. */
    private class Gmm(val k: Int = 5) {
        private val weight = FloatArray(k)
        private val mean = FloatArray(k * 3)
        private val inv = FloatArray(k * 9)
        private val coef = FloatArray(k)

        /** First fit: k-means on (a sample of) the pixels whose label is [fg]. */
        fun init(rgb: FloatArray, labels: BooleanArray, fg: Boolean, train: BooleanArray) {
            val members = labels.indices.filter { labels[it] == fg && train[it] }
            if (members.isEmpty()) return
            val sample = if (members.size > 20_000) members.shuffled(Random(7)).take(20_000) else members
            val centers = FloatArray(k * 3)
            val random = Random(11)
            for (c in 0 until k) {
                val p = sample[random.nextInt(sample.size)]
                for (j in 0 until 3) centers[c * 3 + j] = rgb[p * 3 + j]
            }
            val assign = IntArray(sample.size)
            repeat(6) {
                sample.forEachIndexed { i, p ->
                    var best = 0
                    var bestD = Float.MAX_VALUE
                    for (c in 0 until k) {
                        val dr = rgb[p * 3] - centers[c * 3]
                        val dg = rgb[p * 3 + 1] - centers[c * 3 + 1]
                        val db = rgb[p * 3 + 2] - centers[c * 3 + 2]
                        val d = dr * dr + dg * dg + db * db
                        if (d < bestD) { bestD = d; best = c }
                    }
                    assign[i] = best
                }
                val sums = FloatArray(k * 3)
                val counts = IntArray(k)
                sample.forEachIndexed { i, p ->
                    val c = assign[i]
                    counts[c]++
                    for (j in 0 until 3) sums[c * 3 + j] += rgb[p * 3 + j]
                }
                for (c in 0 until k) if (counts[c] > 0) for (j in 0 until 3) centers[c * 3 + j] = sums[c * 3 + j] / counts[c]
            }
            fit(rgb, sample.toIntArray(), assign)
        }

        /** Later fits: each pixel joins its most likely component, then parameters are re-estimated. */
        fun learn(rgb: FloatArray, labels: BooleanArray, fg: Boolean, train: BooleanArray) {
            val members = labels.indices.filter { labels[it] == fg && train[it] }.toIntArray()
            if (members.isEmpty()) return
            val assign = IntArray(members.size) { i ->
                val p = members[i]
                var best = 0
                var bestV = -1f
                for (c in 0 until k) {
                    val v = component(c, rgb[p * 3], rgb[p * 3 + 1], rgb[p * 3 + 2])
                    if (v > bestV) { bestV = v; best = c }
                }
                best
            }
            fit(rgb, members, assign)
        }

        private fun fit(rgb: FloatArray, members: IntArray, assign: IntArray) {
            val count = IntArray(k)
            val sum = DoubleArray(k * 3)
            val prod = DoubleArray(k * 9)
            members.forEachIndexed { i, p ->
                val c = assign[i]
                count[c]++
                for (a in 0 until 3) {
                    val va = rgb[p * 3 + a].toDouble()
                    sum[c * 3 + a] += va
                    for (b in 0 until 3) prod[c * 9 + a * 3 + b] += va * rgb[p * 3 + b]
                }
            }
            val total = max(1, members.size)
            for (c in 0 until k) {
                if (count[c] == 0) {
                    weight[c] = 0f
                    coef[c] = 0f
                    continue
                }
                weight[c] = count[c].toFloat() / total
                val m = DoubleArray(3) { sum[c * 3 + it] / count[c] }
                for (a in 0 until 3) mean[c * 3 + a] = m[a].toFloat()
                val cov = DoubleArray(9) { prod[c * 9 + it] / count[c] - m[it / 3] * m[it % 3] }
                // A little noise on the diagonal keeps flat-colour components invertible.
                for (a in 0 until 3) cov[a * 4] += REGULARIZE
                val det = cov[0] * (cov[4] * cov[8] - cov[5] * cov[7]) -
                    cov[1] * (cov[3] * cov[8] - cov[5] * cov[6]) +
                    cov[2] * (cov[3] * cov[7] - cov[4] * cov[6])
                val invDet = 1.0 / det
                inv[c * 9] = ((cov[4] * cov[8] - cov[5] * cov[7]) * invDet).toFloat()
                inv[c * 9 + 1] = ((cov[2] * cov[7] - cov[1] * cov[8]) * invDet).toFloat()
                inv[c * 9 + 2] = ((cov[1] * cov[5] - cov[2] * cov[4]) * invDet).toFloat()
                inv[c * 9 + 3] = ((cov[5] * cov[6] - cov[3] * cov[8]) * invDet).toFloat()
                inv[c * 9 + 4] = ((cov[0] * cov[8] - cov[2] * cov[6]) * invDet).toFloat()
                inv[c * 9 + 5] = ((cov[2] * cov[3] - cov[0] * cov[5]) * invDet).toFloat()
                inv[c * 9 + 6] = ((cov[3] * cov[7] - cov[4] * cov[6]) * invDet).toFloat()
                inv[c * 9 + 7] = ((cov[1] * cov[6] - cov[0] * cov[7]) * invDet).toFloat()
                inv[c * 9 + 8] = ((cov[0] * cov[4] - cov[1] * cov[3]) * invDet).toFloat()
                coef[c] = (weight[c] / sqrt(TWO_PI_CUBED * det)).toFloat()
            }
        }

        private fun component(c: Int, r: Float, g: Float, b: Float): Float {
            if (coef[c] == 0f) return 0f
            val dr = r - mean[c * 3]
            val dg = g - mean[c * 3 + 1]
            val db = b - mean[c * 3 + 2]
            val o = c * 9
            val m = dr * (dr * inv[o] + dg * inv[o + 3] + db * inv[o + 6]) +
                dg * (dr * inv[o + 1] + dg * inv[o + 4] + db * inv[o + 7]) +
                db * (dr * inv[o + 2] + dg * inv[o + 5] + db * inv[o + 8])
            return coef[c] * exp(-0.5f * m)
        }

        /** -ln P(colour); capped so one odd colour can't dominate the cut. */
        fun cost(r: Float, g: Float, b: Float): Float {
            var sum = 0f
            for (c in 0 until k) sum += component(c, r, g, b)
            return (-ln(max(sum, 1e-30f))).coerceIn(0f, MAX_COST)
        }

        companion object {
            private const val REGULARIZE = 1.0
            private const val TWO_PI_CUBED = 248.05021344239853 // (2π)^3
            private const val MAX_COST = 60f
        }
    }
}
