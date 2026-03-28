package com.example.stickermaker.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SegmenterTest {

    @Test
    fun maxFlowMatchesBruteForceMinCut() {
        val random = Random(3)
        repeat(40) {
            val w = 3
            val h = 3
            val n = w * h
            val graph = GridMaxFlow(w, h)
            val edges = Array(n) { FloatArray(8) }
            val src = FloatArray(n)
            val sink = FloatArray(n)
            for (p in 0 until n) {
                for (d in 0 until 8) {
                    if (graph.neighbor(p, d) < 0) continue
                    edges[p][d] = random.nextInt(0, 10).toFloat()
                    graph.setEdge(p, d, edges[p][d])
                }
                src[p] = random.nextInt(0, 12).toFloat()
                sink[p] = random.nextInt(0, 12).toFloat()
                graph.setTerminals(p, src[p], sink[p])
            }
            val flow = graph.maxFlow()

            var best = Double.MAX_VALUE
            for (bits in 0 until (1 shl n)) {
                var cut = 0.0
                for (p in 0 until n) {
                    val inS = bits shr p and 1 == 1
                    if (inS) cut += sink[p] else cut += src[p]
                    if (!inS) continue
                    for (d in 0 until 8) {
                        val q = graph.neighbor(p, d)
                        if (q >= 0 && (bits shr q and 1) == 0) cut += edges[p][d]
                    }
                }
                best = minOf(best, cut)
            }
            assertEquals(best, flow, 1e-3)

            // The reported cut must cost exactly the max flow.
            var cut = 0.0
            for (p in 0 until n) {
                if (graph.isSource(p)) cut += sink[p] else cut += src[p]
                if (!graph.isSource(p)) continue
                for (d in 0 until 8) {
                    val q = graph.neighbor(p, d)
                    if (q >= 0 && !graph.isSource(q)) cut += edges[p][d]
                }
            }
            assertEquals(flow, cut, 1e-3)
        }
    }

    private fun disk(w: Int, h: Int, cx: Int, cy: Int, r: Int, noise: Random): IntArray =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val inside = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
            val jitter = noise.nextInt(-12, 12)
            if (inside) argb(200 + jitter, 40 + jitter, 50) else argb(60, 110 + jitter, 210 + jitter)
        }

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    @Test
    fun findsTheSubjectFromOneScribble() {
        val w = 120
        val h = 90
        val pixels = disk(w, h, 60, 45, 25, Random(1))
        val base = ByteArray(w * h) { MaskOps.KEPT }
        val seeds = ByteArray(w * h)
        MaskOps.stampLine(seeds, w, h, 52f, 45f, 68f, 45f, 3f, Segmenter.SEED_KEEP)

        val mask = requireNotNull(Segmenter.segment(pixels, w, h, base, seeds))
        var wrong = 0
        for (i in 0 until w * h) {
            val x = i % w
            val y = i / w
            val inside = (x - 60) * (x - 60) + (y - 45) * (y - 45) <= 25 * 25
            if (inside != (mask[i] == MaskOps.KEPT)) wrong++
        }
        assertTrue("wrong pixels: $wrong", wrong < w * h / 100)
    }

    @Test
    fun earlierErasingStaysErasedAndMarksWin() {
        val w = 80
        val h = 60
        val pixels = disk(w, h, 40, 30, 20, Random(2))
        val base = ByteArray(w * h) { MaskOps.KEPT }
        // Erased by hand earlier: the disk's left part.
        for (y in 0 until h) for (x in 0 until 30) base[y * w + x] = MaskOps.ERASED
        val seeds = ByteArray(w * h)
        MaskOps.stamp(seeds, w, h, 45f, 30f, 3f, Segmenter.SEED_KEEP)
        MaskOps.stamp(seeds, w, h, 20f, 30f, 2f, Segmenter.SEED_KEEP) // inside the erased part

        val mask = requireNotNull(Segmenter.segment(pixels, w, h, base, seeds))
        assertEquals(MaskOps.ERASED, mask[30 * w + 25])
        assertEquals(MaskOps.KEPT, mask[30 * w + 20])
        assertEquals(MaskOps.KEPT, mask[30 * w + 50])
        assertEquals(MaskOps.ERASED, mask[2 * w + 78])
    }

    @Test
    fun needsAKeepMark() {
        val pixels = IntArray(16) { argb(1, 2, 3) }
        assertNull(Segmenter.segment(pixels, 4, 4, ByteArray(16) { MaskOps.KEPT }, ByteArray(16)))
    }
}
