package com.example.stickermaker.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream

/**
 * The metadata WhatsApp reads from inside a sticker's WebP: an EXIF chunk holding a tiny TIFF
 * structure with one tag (0x5741, "WA") whose value is JSON naming the pack, the publisher and
 * the emojis. WhatsApp shows the pack and publisher when a received sticker is tapped, and uses
 * the emojis for sticker search. Layout as written by the wa-sticker-formatter library.
 *
 * Pure byte work (no Android types) so it's unit-tested on the JVM.
 */
object StickerMetadata {

    data class Info(val packId: String, val packName: String, val publisher: String, val emojis: List<String>)

    /** The JSON WhatsApp expects, with proper escaping. */
    fun json(info: Info): String = buildJsonObject {
        put("sticker-pack-id", JsonPrimitive(info.packId))
        put("sticker-pack-name", JsonPrimitive(info.packName))
        put("sticker-pack-publisher", JsonPrimitive(info.publisher))
        put("emojis", JsonArray(info.emojis.map(::JsonPrimitive)))
    }.toString()

    /** The EXIF payload: little-endian TIFF header, one IFD entry (tag 0x5741), then the JSON. */
    fun exif(info: Info): ByteArray {
        val jsonBytes = json(info).toByteArray(Charsets.UTF_8)
        val header = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00, // "II", 42, first IFD at 8
            0x01, 0x00, // one entry
            0x41, 0x57, 0x07, 0x00, // tag 0x5741 ("WA"), type UNDEFINED
            0x00, 0x00, 0x00, 0x00, // count: the JSON's byte length (filled below)
            0x16, 0x00, 0x00, 0x00, // value at offset 22, right after this header
        )
        writeLe32(header, 14, jsonBytes.size)
        return header + jsonBytes
    }

    /**
     * Returns [webp] with [exif] attached. A simple WebP (one VP8/VP8L chunk, as Android's
     * encoder writes) is first turned into the extended format, which is the only one that can
     * carry metadata: a VP8X header chunk with the EXIF flag, then the image, then EXIF.
     */
    fun attach(webp: ByteArray, exif: ByteArray, width: Int, height: Int): ByteArray {
        require(webp.size >= 12 && String(webp, 0, 4, Charsets.US_ASCII) == "RIFF" && String(webp, 8, 4, Charsets.US_ASCII) == "WEBP") {
            "Not a WebP file"
        }
        val chunks = chunks(webp)
        val existingHeader = chunks.firstOrNull { it.fourcc == "VP8X" }
        val image = chunks.filterNot { it.fourcc == "VP8X" || it.fourcc == "EXIF" }

        val flags = if (existingHeader != null) {
            existingHeader.data[0].toInt() or FLAG_EXIF
        } else {
            // VP8L can carry alpha (stickers do); a bare VP8 can't, so only VP8L sets the hint.
            FLAG_EXIF or (if (image.any { it.fourcc == "VP8L" }) FLAG_ALPHA else 0)
        }
        val header = ByteArray(10)
        header[0] = flags.toByte()
        writeLe24(header, 4, width - 1)
        writeLe24(header, 7, height - 1)

        val body = ByteArrayOutputStream(webp.size + exif.size + 32)
        body.write("WEBP".toByteArray(Charsets.US_ASCII))
        writeChunk(body, "VP8X", header)
        image.forEach { writeChunk(body, it.fourcc, it.data) }
        writeChunk(body, "EXIF", exif)
        val payload = body.toByteArray()

        val out = ByteArrayOutputStream(payload.size + 8)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    class Chunk(val fourcc: String, val data: ByteArray)

    /** The chunks after the RIFF/WEBP header, in order. */
    fun chunks(webp: ByteArray): List<Chunk> {
        val list = mutableListOf<Chunk>()
        var pos = 12
        while (pos + 8 <= webp.size) {
            val fourcc = String(webp, pos, 4, Charsets.US_ASCII)
            val size = readLe32(webp, pos + 4)
            val start = pos + 8
            if (size < 0 || start + size > webp.size) break
            list += Chunk(fourcc, webp.copyOfRange(start, start + size))
            pos = start + size + (size and 1) // chunks are padded to even sizes
        }
        return list
    }

    private fun writeChunk(out: ByteArrayOutputStream, fourcc: String, data: ByteArray) {
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(le32(data.size))
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
    }

    private fun le32(v: Int) = ByteArray(4).also { writeLe32(it, 0, v) }

    private fun writeLe32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun writeLe24(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 3) b[at + i] = (v ushr (8 * i)).toByte()
    }

    fun readLe32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private const val FLAG_EXIF = 0x08
    private const val FLAG_ALPHA = 0x10
}
