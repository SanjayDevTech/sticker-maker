package com.example.stickermaker.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerMetadataTest {

    private val info = StickerMetadata.Info("pack1", "My \"best\" pack", "Sticker Maker", listOf("😂", "🔥"))

    /** A minimal simple-format WebP: RIFF header plus one odd-sized VP8L chunk. */
    private fun simpleWebp(): ByteArray {
        val data = byteArrayOf(0x2f, 1, 2, 3, 4) // odd: needs a pad byte
        val chunk = "VP8L".toByteArray() + le32(data.size) + data + byteArrayOf(0)
        val payload = "WEBP".toByteArray() + chunk
        return "RIFF".toByteArray() + le32(payload.size) + payload
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    @Test
    fun exifHasTheWhatsAppHeaderAndJson() {
        val exif = StickerMetadata.exif(info)
        val json = StickerMetadata.json(info).toByteArray(Charsets.UTF_8)
        assertArrayEquals(
            byteArrayOf(0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x41, 0x57, 0x07, 0x00),
            exif.copyOfRange(0, 14),
        )
        assertEquals(json.size, StickerMetadata.readLe32(exif, 14))
        assertEquals(22, StickerMetadata.readLe32(exif, 18))
        assertArrayEquals(json, exif.copyOfRange(22, exif.size))
    }

    @Test
    fun jsonIsEscapedAndCarriesEmojis() {
        val json = StickerMetadata.json(info)
        assertTrue(json, json.contains("\"sticker-pack-name\":\"My \\\"best\\\" pack\""))
        assertTrue(json, json.contains("\"sticker-pack-publisher\":\"Sticker Maker\""))
        assertTrue(json, json.contains("\"emojis\":[\"😂\",\"🔥\"]"))
    }

    @Test
    fun simpleWebpBecomesExtendedWithExifLast() {
        val exif = StickerMetadata.exif(info)
        val out = StickerMetadata.attach(simpleWebp(), exif, 512, 512)

        assertEquals("RIFF", String(out, 0, 4))
        assertEquals(out.size - 8, StickerMetadata.readLe32(out, 4))
        val chunks = StickerMetadata.chunks(out)
        assertEquals(listOf("VP8X", "VP8L", "EXIF"), chunks.map { it.fourcc })

        val vp8x = chunks[0].data
        assertEquals(0x08 or 0x10, vp8x[0].toInt()) // EXIF + alpha
        assertEquals(511, (vp8x[4].toInt() and 0xFF) or ((vp8x[5].toInt() and 0xFF) shl 8))
        assertEquals(511, (vp8x[7].toInt() and 0xFF) or ((vp8x[8].toInt() and 0xFF) shl 8))
        assertArrayEquals(byteArrayOf(0x2f, 1, 2, 3, 4), chunks[1].data)
        assertArrayEquals(exif, chunks[2].data)
    }

    @Test
    fun attachingTwiceReplacesTheOldExif() {
        val once = StickerMetadata.attach(simpleWebp(), StickerMetadata.exif(info), 512, 512)
        val newer = StickerMetadata.exif(info.copy(emojis = listOf("🙂")))
        val twice = StickerMetadata.attach(once, newer, 512, 512)
        val chunks = StickerMetadata.chunks(twice)
        assertEquals(listOf("VP8X", "VP8L", "EXIF"), chunks.map { it.fourcc })
        assertArrayEquals(newer, chunks[2].data)
    }
}
