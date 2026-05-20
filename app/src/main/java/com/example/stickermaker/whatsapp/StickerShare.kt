package com.example.stickermaker.whatsapp

import android.content.Context
import android.graphics.Bitmap
import com.example.stickermaker.data.PackRepository
import com.example.stickermaker.data.Sticker
import com.example.stickermaker.data.StickerPack
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.editor.StickerExport
import com.example.stickermaker.editor.StickerMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sticker files handed to other apps (clipboard copy, keyboard insert), each carrying
 * WhatsApp's sticker metadata: its pack, "Sticker Maker" as publisher, and its emojis.
 * Written fresh to the cache per use, so the emojis are always current and saved pack files
 * stay untouched.
 */
object StickerShare {

    const val PUBLISHER = "Sticker Maker"
    private const val SINGLES_ID = "stickermaker-singles"
    private const val KEEP_FILES = 20

    /** A pack sticker, labelled with its pack and emojis. */
    suspend fun fromPack(context: Context, packs: PackRepository, pack: StickerPack, sticker: Sticker): File =
        withContext(Dispatchers.IO) {
            val bytes = packs.stickerFile(pack.id, sticker.id).readBytes()
            write(context, sticker.id, bytes, StickerMetadata.Info(pack.id, pack.name, pack.publisher, sticker.emojis))
        }

    /** A sticker that isn't in any pack (copied straight from the Save screen). */
    suspend fun unsaved(context: Context, sticker: Bitmap): File = withContext(Dispatchers.Default) {
        val bytes = StickerExport.encodeSticker(sticker)
        write(context, "single", bytes, StickerMetadata.Info(SINGLES_ID, PUBLISHER, PUBLISHER, emptyList()))
    }

    private fun write(context: Context, name: String, webp: ByteArray, info: StickerMetadata.Info): File {
        val px = WhatsAppLimits.STICKER_PX
        val labelled = StickerMetadata.attach(webp, StickerMetadata.exif(info), px, px)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Unique names: the clipboard may still point at an earlier file. Old ones are trimmed.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES - 1)?.forEach { it.delete() }
        return File(dir, "${name}_${System.currentTimeMillis()}.webp").apply { writeBytes(labelled) }
    }
}
