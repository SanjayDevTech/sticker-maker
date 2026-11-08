package com.example.stickermaker.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saves every pack to one zip the user keeps (Downloads, Drive…) and adds them back later,
 * e.g. after a reinstall or on a debug build. Packs keep their ids, which are also their
 * WhatsApp identifiers, so whether WhatsApp already has a restored pack is answered exactly as
 * before: the app asks WhatsApp each time, it never stores it.
 *
 *     backup.json                         [Contents]: format and the packs, as in the index
 *     packs/<packId>/<stickerId>.webp
 *     packs/<packId>/tray.png
 *     packs/<packId>/src/<stickerId>.jpg  (and .mask): sources for re-editing, when present
 *
 * Restoring only adds: a pack whose id the app already has is left alone, never replaced,
 * and nothing is removed.
 */
class PackBackup(context: Context, private val packs: PackRepository) {

    private val resolver = context.contentResolver
    private val staging = File(context.cacheDir, "pack-import")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Contents(val format: Int, val exportedAt: Long, val packs: List<StickerPack>)

    /** What a restore did. [broken]: packs in the file that couldn't be read back. */
    data class Restored(val added: Int, val alreadyHere: Int, val broken: Int)

    /** Writes every pack (not deleted ones) to [uri]; returns how many. */
    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = packs.packs.value ?: packs.packsBlocking()
        val out = resolver.openOutputStream(uri, "wt") ?: throw IOException("Can't write $uri")
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(INDEX))
            zip.write(json.encodeToString(Contents.serializer(), Contents(FORMAT, System.currentTimeMillis(), all)).toByteArray())
            zip.closeEntry()
            for (pack in all) {
                val files = pack.stickers.flatMap { s ->
                    listOf(packs.stickerFile(pack.id, s.id), packs.sourceFile(pack.id, s.id), packs.maskFile(pack.id, s.id))
                } + packs.trayFile(pack.id)
                for (file in files) {
                    if (!file.isFile) continue
                    zip.putNextEntry(ZipEntry("packs/${pack.id}/${file.relativeTo(packs.packDir(pack.id)).invariantSeparatorsPath}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        all.size
    }

    /** Adds the packs in the backup at [uri] that the app doesn't have yet. */
    suspend fun import(uri: Uri): Restored = withContext(Dispatchers.IO) {
        val dir = File(staging, UUID.randomUUID().toString())
        try {
            val contents = unpack(uri, dir)
            var added = 0
            var alreadyHere = 0
            var broken = 0
            val seen = HashSet<String>()
            for (raw in contents.packs) {
                if (!seen.add(raw.id)) continue
                val pack = check(raw, File(dir, "packs/${raw.id}"))
                when {
                    pack == null -> broken++
                    packs.addFromBackup(pack, File(dir, "packs/${pack.id}")) -> added++
                    else -> alreadyHere++
                }
            }
            Restored(added, alreadyHere, broken)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Extracts [uri] into [dir], keeping only entries a backup can contain (so no path can
     * escape [dir]), and reads its [Contents].
     */
    private fun unpack(uri: Uri, dir: File): Contents {
        dir.mkdirs()
        var contents: Contents? = null
        var total = 0L
        val input = resolver.openInputStream(uri) ?: throw IOException("Can't read $uri")
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name == INDEX) {
                    val text = zip.readBounded(MAX_INDEX_BYTES).decodeToString()
                    contents = json.decodeFromString(Contents.serializer(), text)
                    continue
                }
                if (!FILE.matches(name)) continue
                val file = File(dir, name)
                file.parentFile?.mkdirs()
                file.outputStream().buffered().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n
                        total += n
                        if (size > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) throw IOException("Backup too large")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        val read = contents ?: throw IOException("Not a Sticker Maker backup")
        if (read.format > FORMAT) throw IOException("Backup from a newer version (format ${read.format})")
        return read
    }

    /**
     * [pack] as it can be restored from [files], or null if it can't: an invalid id, or no
     * sticker images at all. Stickers whose image is missing are dropped.
     */
    private fun check(pack: StickerPack, files: File): StickerPack? {
        if (!ID.matches(pack.id)) return null
        val stickers = pack.stickers
            .filter { ID.matches(it.id) && File(files, "${it.id}.webp").isFile }
            .distinctBy { it.id }
            .take(WhatsAppLimits.MAX_STICKERS)
        if (stickers.isEmpty() && pack.stickers.isNotEmpty()) return null
        if (!files.isDirectory) files.mkdirs()
        return pack.copy(
            name = pack.name.take(WhatsAppLimits.MAX_NAME_CHARS),
            publisher = pack.publisher.take(WhatsAppLimits.MAX_NAME_CHARS),
            stickers = stickers,
        )
    }

    private fun ZipInputStream.readBounded(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.size() + n > max) throw IOException("Backup index too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val FORMAT = 1
        const val INDEX = "backup.json"

        /** Pack and sticker ids: what [PackRepository.newId] makes, and all WhatsApp allows. */
        val ID = Regex("[a-z0-9_-]{1,64}")

        /** The only files a backup holds. */
        val FILE = Regex("packs/[a-z0-9_-]{1,64}/([a-z0-9_-]{1,64}\\.webp|tray\\.png|src/[a-z0-9_-]{1,64}\\.(jpg|mask))")

        const val MAX_INDEX_BYTES = 8 * 1024 * 1024
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    }
}
