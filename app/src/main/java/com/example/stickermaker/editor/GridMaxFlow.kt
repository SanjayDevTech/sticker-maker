package com.example.stickermaker.editor

/**
 * Minimum s-t cut on an 8-connected pixel grid (Boykov–Kolmogorov max-flow). Nodes are pixels;
 * the source side means "subject", the sink side "background".
 *
 * Usage: set every edge with [setEdge] (both directions are separate residuals), terminal
 * weights with [setTerminals], call [maxFlow], then read [isSource].
 */
class GridMaxFlow(val width: Int, val height: Int) {

    private val n = width * height

    /** Residual capacity of the edge from node p in direction d, at `p * 8 + d`. */
    private val cap = FloatArray(n * 8)

    /** Terminal residual: > 0 is left from the source to p, < 0 from p to the sink. */
    private val terminal = FloatArray(n)

    private val tree = ByteArray(n)

    /** Direction from a node to its parent, [TERMINAL] for roots, [NONE] when it has none. */
    private val parent = IntArray(n)

    private val queue = IntArray(n)
    private val queued = BooleanArray(n)
    private var head = 0
    private var size = 0

    private val orphans = IntArray(n)
    private var orphanCount = 0

    /** Bit d set when the neighbour in direction d exists; with [offset] avoids div/mod per edge. */
    private val valid = ByteArray(n).also { v ->
        for (p in 0 until n) {
            val x = p % width
            val y = p / width
            var bits = 0
            for (d in 0 until 8) {
                val nx = x + DX[d]
                val ny = y + DY[d]
                if (nx in 0 until width && ny in 0 until height) bits = bits or (1 shl d)
            }
            v[p] = bits.toByte()
        }
    }
    private val offset = IntArray(8) { DY[it] * width + DX[it] }

    // BK's bookkeeping for fast adoption: when a node's path to its terminal was last checked
    // ([stamp] == [time]) and how long it was.
    private val stamp = IntArray(n)
    private val dist = IntArray(n)
    private var time = 0

    var flow = 0.0
        private set

    fun setEdge(p: Int, d: Int, capacity: Float) {
        cap[p * 8 + d] = capacity
    }

    fun setTerminals(p: Int, source: Float, sink: Float) {
        terminal[p] = source - sink
        flow += minOf(source, sink)
    }

    fun isSource(p: Int) = tree[p] == S

    /** Neighbour of [p] in direction [d], or -1 off the grid. */
    fun neighbor(p: Int, d: Int): Int = if (valid[p].toInt() and (1 shl d) != 0) p + offset[d] else -1

    /** Runs to completion. [isActive] is polled now and then; returning false aborts. */
    fun maxFlow(isActive: () -> Boolean = { true }): Double {
        for (p in 0 until n) {
            when {
                terminal[p] > 0f -> { tree[p] = S; parent[p] = TERMINAL; dist[p] = 1; push(p) }
                terminal[p] < 0f -> { tree[p] = T; parent[p] = TERMINAL; dist[p] = 1; push(p) }
                else -> { tree[p] = FREE; parent[p] = NONE }
            }
        }
        var rounds = 0
        while (true) {
            if (++rounds % 4096 == 0 && !isActive()) throw MaxFlowCancelled()
            val path = grow() ?: break
            time++
            augment(path)
            adopt()
        }
        return flow
    }

    private fun push(p: Int) {
        if (queued[p]) return
        queued[p] = true
        queue[(head + size) % n] = p
        size++
    }

    private fun pop() {
        queued[queue[head]] = false
        head = (head + 1) % n
        size--
    }

    /** Grows both trees until they touch; returns the touching edge as `node * 8 + dir` (S to T). */
    private fun grow(): Int? {
        while (size > 0) {
            val p = queue[head]
            val tp = tree[p]
            if (tp == FREE) {
                pop()
                continue
            }
            for (d in 0 until 8) {
                val q = neighbor(p, d)
                if (q < 0) continue
                val c = if (tp == S) cap[p * 8 + d] else cap[q * 8 + OPP[d]]
                if (c <= 0f) continue
                val tq = tree[q]
                if (tq == FREE) {
                    tree[q] = tp
                    parent[q] = OPP[d]
                    stamp[q] = stamp[p]
                    dist[q] = dist[p] + 1
                    push(q)
                } else if (tq != tp) {
                    // p stays active: it may have more paths.
                    return if (tp == S) p * 8 + d else q * 8 + OPP[d]
                } else if (stamp[q] <= stamp[p] && dist[q] > dist[p]) {
                    // Same tree: hang q on p if that makes its path to the terminal shorter.
                    parent[q] = OPP[d]
                    stamp[q] = stamp[p]
                    dist[q] = dist[p] + 1
                }
            }
            pop()
        }
        return null
    }

    private fun augment(edge: Int) {
        val s = edge / 8
        val d = edge % 8
        val t = neighbor(s, d)

        var b = cap[edge]
        var x = s
        while (parent[x] != TERMINAL) {
            val pd = parent[x]
            val par = neighbor(x, pd)
            b = minOf(b, cap[par * 8 + OPP[pd]])
            x = par
        }
        b = minOf(b, terminal[x])
        x = t
        while (parent[x] != TERMINAL) {
            val pd = parent[x]
            b = minOf(b, cap[x * 8 + pd])
            x = neighbor(x, pd)
        }
        b = minOf(b, -terminal[x])

        cap[edge] -= b
        cap[t * 8 + OPP[d]] += b
        x = s
        while (parent[x] != TERMINAL) {
            val pd = parent[x]
            val par = neighbor(x, pd)
            val e = par * 8 + OPP[pd]
            cap[e] -= b
            cap[x * 8 + pd] += b
            if (cap[e] <= 0f) orphan(x)
            x = par
        }
        terminal[x] -= b
        if (terminal[x] <= 0f) orphan(x)
        x = t
        while (parent[x] != TERMINAL) {
            val pd = parent[x]
            val par = neighbor(x, pd)
            val e = x * 8 + pd
            cap[e] -= b
            cap[par * 8 + OPP[pd]] += b
            if (cap[e] <= 0f) orphan(x)
            x = par
        }
        terminal[x] += b
        if (terminal[x] >= 0f) orphan(x)
        flow += b
    }

    private fun orphan(p: Int) {
        parent[p] = NONE
        orphans[orphanCount++] = p
    }

    private fun adopt() {
        while (orphanCount > 0) {
            val p = orphans[--orphanCount]
            val tp = tree[p]
            var bestDir = -1
            var bestDist = Int.MAX_VALUE
            for (d in 0 until 8) {
                val q = neighbor(p, d)
                if (q < 0 || tree[q] != tp) continue
                val c = if (tp == S) cap[q * 8 + OPP[d]] else cap[p * 8 + d]
                if (c <= 0f) continue
                val length = pathLength(q)
                if (length < bestDist) {
                    bestDist = length
                    bestDir = d
                }
            }
            if (bestDir >= 0) {
                parent[p] = bestDir
                stamp[p] = time
                dist[p] = bestDist + 1
                continue
            }
            for (d in 0 until 8) {
                val q = neighbor(p, d)
                if (q < 0 || tree[q] != tp) continue
                val c = if (tp == S) cap[q * 8 + OPP[d]] else cap[p * 8 + d]
                if (c > 0f) push(q)
                val qd = parent[q]
                if (qd >= 0 && qd != TERMINAL && neighbor(q, qd) == p) orphan(q)
            }
            tree[p] = FREE
        }
    }

    /**
     * Steps from [start] to its terminal, or [Int.MAX_VALUE] if the path is broken (it runs
     * into an orphan). Stamps the nodes it walked so later checks this round stop early.
     */
    private fun pathLength(start: Int): Int {
        var j = start
        var d = 0
        while (true) {
            if (stamp[j] == time) {
                d += dist[j]
                break
            }
            val pd = parent[j]
            d++
            if (pd == TERMINAL) {
                stamp[j] = time
                dist[j] = 1
                break
            }
            if (pd == NONE) return Int.MAX_VALUE
            j += offset[pd]
        }
        var k = start
        var length = d
        while (stamp[k] != time) {
            stamp[k] = time
            dist[k] = length--
            k += offset[parent[k]]
        }
        return d
    }

    class MaxFlowCancelled : java.util.concurrent.CancellationException("segmentation cancelled")

    companion object {
        private const val FREE: Byte = 0
        private const val S: Byte = 1
        private const val T: Byte = 2
        private const val TERMINAL = 8
        private const val NONE = -1

        // Right, left, down, up, then the diagonals; OPP pairs each with its reverse.
        val DX = intArrayOf(1, -1, 0, 0, 1, -1, 1, -1)
        val DY = intArrayOf(0, 0, 1, -1, 1, -1, -1, 1)
        val OPP = intArrayOf(1, 0, 3, 2, 5, 4, 7, 6)
    }
}
