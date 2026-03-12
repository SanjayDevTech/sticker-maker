package com.example.stickermaker.editor

import com.example.stickermaker.editor.MaskOps.Box

/**
 * Undo/redo for a mask as rectangular patches: each edit keeps only the before/after bytes of
 * the box it touched, not whole-mask copies, so a long history stays small. Oldest entries are
 * dropped past [maxBytes].
 */
class MaskHistory(private val width: Int, private val maxBytes: Int = 48 * 1024 * 1024) {

    private class Patch(val box: Box, val before: ByteArray, val after: ByteArray) {
        val bytes get() = before.size + after.size
    }

    private val undo = ArrayDeque<Patch>()
    private val redo = ArrayDeque<Patch>()
    private var bytes = 0

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    /**
     * Records an edit of [box]: [beforeFull] is a full-size copy of the mask taken before it.
     * Returns false (and records nothing) when the edit changed no pixel.
     */
    fun record(beforeFull: ByteArray, mask: ByteArray, box: Box): Boolean {
        if (box.isEmpty) return false
        val patch = Patch(box, MaskOps.copyBox(beforeFull, width, box), MaskOps.copyBox(mask, width, box))
        if (patch.before.contentEquals(patch.after)) return false
        undo.addLast(patch)
        bytes += patch.bytes
        redo.clear()
        while (bytes > maxBytes && undo.size > 1) bytes -= undo.removeFirst().bytes
        return true
    }

    /** Reverts the last edit into [mask]; returns the box to redraw, or null if nothing to undo. */
    fun undo(mask: ByteArray): Box? {
        val p = undo.removeLastOrNull() ?: return null
        MaskOps.pasteBox(mask, width, p.box, p.before)
        bytes -= p.bytes
        redo.addLast(p)
        return p.box
    }

    fun redo(mask: ByteArray): Box? {
        val p = redo.removeLastOrNull() ?: return null
        MaskOps.pasteBox(mask, width, p.box, p.after)
        undo.addLast(p)
        bytes += p.bytes
        return p.box
    }
}
