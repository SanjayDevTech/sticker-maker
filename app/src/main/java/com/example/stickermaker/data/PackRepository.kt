package com.example.stickermaker.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Sticker packs on disk. One JSON index (`packs/index.json`) is the source of truth; each pack
 * has a folder with its finished stickers (`<id>.webp`), tray icon and editable sources.
 *
 *     files/packs/index.json
 *     files/packs/<packId>/<stickerId>.webp      512×512, what WhatsApp gets
 *     files/packs/<packId>/tray.png              96×96
 *     files/packs/<packId>/src/<stickerId>.jpg   source image, for re-editing
 *     files/packs/<packId>/src/<stickerId>.mask  its alpha mask
 *
 * Deletes only drop the pack/sticker from the index, so an Undo can put it straight back;
 * files no longer referenced are swept on the next cold start.
 *
 * Lives under filesDir, so Android Auto Backup carries packs across a reinstall.
 */
class PackRepository(context: Context) {

    private val root = File(context.filesDir, "packs")
    private val indexFile = File(root, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _packs = MutableStateFlow<List<StickerPack>?>(null)

    /** Null until the index has been read from disk. Newest-updated first. */
    val packs: StateFlow<List<StickerPack>?> = _packs.asStateFlow()

    init {
        scope.launch { mutex.withLock { load() } }
    }

    fun packDir(packId: String) = File(root, packId)
    fun stickerFile(packId: String, stickerId: String) = File(packDir(packId), "$stickerId.webp")
    fun trayFile(packId: String) = File(packDir(packId), "tray.png")
    fun sourceFile(packId: String, stickerId: String) = File(packDir(packId), "src/$stickerId.jpg")
    fun maskFile(packId: String, stickerId: String) = File(packDir(packId), "src/$stickerId.mask")

    fun pack(packId: String): StickerPack? = _packs.value?.firstOrNull { it.id == packId }

    /** Blocking read for the ContentProvider, which WhatsApp calls on a binder thread. */
    fun packsBlocking(): List<StickerPack> =
        _packs.value ?: kotlinx.coroutines.runBlocking { mutex.withLock { load() } }

    suspend fun createPack(name: String, publisher: String): StickerPack = edit { packs ->
        val now = System.currentTimeMillis()
        val pack = StickerPack(
            id = newId(),
            name = name.take(WhatsAppLimits.MAX_NAME_CHARS),
            publisher = publisher.take(WhatsAppLimits.MAX_NAME_CHARS),
            createdAt = now,
            updatedAt = now,
        )
        packDir(pack.id).mkdirs()
        (listOf(pack) + packs) to pack
    }

    suspend fun renamePack(packId: String, name: String) = updatePack(packId, bumpVersion = false) {
        it.copy(name = name.take(WhatsAppLimits.MAX_NAME_CHARS))
    }

    /** Removes from the index only; pass the returned pack to [restorePack] to undo. */
    suspend fun removePack(packId: String): StickerPack? = edit { packs ->
        val removed = packs.firstOrNull { it.id == packId }
        packs.filterNot { it.id == packId } to removed
    }

    suspend fun restorePack(pack: StickerPack) = edit { packs ->
        (packs.filterNot { it.id == pack.id } + pack).sortedByDescending { it.updatedAt } to Unit
    }

    /**
     * Registers a sticker whose files the caller has already written via [stickerFile],
     * [sourceFile] and [maskFile] (use [newId] for the id). Replaces it if the id exists.
     */
    suspend fun putSticker(packId: String, sticker: Sticker, index: Int? = null) =
        updatePack(packId, bumpVersion = true) { pack ->
            val without = pack.stickers.filterNot { it.id == sticker.id }
            val existingAt = pack.stickers.indexOfFirst { it.id == sticker.id }
            val at = (index ?: existingAt.takeIf { it >= 0 } ?: without.size).coerceIn(0, without.size)
            pack.copy(stickers = without.toMutableList().apply { add(at, sticker) })
        }

    suspend fun removeSticker(packId: String, stickerId: String) = updatePack(packId, bumpVersion = true) { pack ->
        pack.copy(stickers = pack.stickers.filterNot { it.id == stickerId })
    }

    suspend fun reorderStickers(packId: String, orderedIds: List<String>) = updatePack(packId, bumpVersion = true) { pack ->
        val byId = pack.stickers.associateBy { it.id }
        pack.copy(stickers = orderedIds.mapNotNull { byId[it] } + pack.stickers.filterNot { it.id in orderedIds })
    }

    suspend fun setEmojis(packId: String, stickerId: String, emojis: List<String>) = updatePack(packId, bumpVersion = false) { pack ->
        pack.copy(stickers = pack.stickers.map {
            if (it.id == stickerId) it.copy(emojis = emojis.take(WhatsAppLimits.MAX_EMOJIS).ifEmpty { listOf("🙂") }) else it
        })
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---- internals -------------------------------------------------------------------------

    private suspend fun updatePack(
        packId: String,
        bumpVersion: Boolean,
        transform: (StickerPack) -> StickerPack,
    ) = edit { packs ->
        val now = System.currentTimeMillis()
        val updated = packs.map { pack ->
            if (pack.id != packId) return@map pack
            transform(pack).let {
                it.copy(updatedAt = now, imageDataVersion = if (bumpVersion) it.imageDataVersion + 1 else it.imageDataVersion)
            }
        }
        updated.sortedByDescending { it.updatedAt } to Unit
    }

    /** Serialised read-modify-write of the index; the in-memory list updates after the disk write. */
    private suspend fun <T> edit(block: (List<StickerPack>) -> Pair<List<StickerPack>, T>): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val (next, result) = block(load())
                save(next)
                _packs.value = next
                result
            }
        }

    /** Caller holds [mutex]. */
    private fun load(): List<StickerPack> {
        _packs.value?.let { return it }
        val packs = runCatching { json.decodeFromString<PackIndex>(indexFile.readText()).packs }
            .getOrDefault(emptyList())
        sweepOrphans(packs)
        _packs.value = packs
        return packs
    }

    private fun save(packs: List<StickerPack>) {
        root.mkdirs()
        // Write-then-rename so a crash mid-write never leaves a truncated index.
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(json.encodeToString(PackIndex.serializer(), PackIndex(packs)))
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    /** Deletes folders/files of packs and stickers no longer in the index (old undo windows). */
    private fun sweepOrphans(packs: List<StickerPack>) {
        val byId = packs.associateBy { it.id }
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val pack = byId[dir.name]
            if (pack == null) {
                dir.deleteRecursively()
                return@forEach
            }
            val keep = pack.stickers.map { it.id }.toSet()
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val stickerId = file.name.substringBefore('.')
                if (stickerId != "tray" && stickerId !in keep) file.delete()
            }
        }
    }
}
