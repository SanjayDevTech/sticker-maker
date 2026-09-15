package com.example.stickermaker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.stickermaker.editor.StickerExport
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

    private companion object {
        /** WhatsApp requires a publisher name; packs made here are credited to the app. */
        const val PUBLISHER = "Sticker Maker"
    }

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

    /** Deleted in the app, possibly still in WhatsApp: served to WhatsApp, hidden here. */
    private var retired: List<StickerPack> = emptyList()

    /** Live and retired packs, for the provider: WhatsApp must still reach both. */
    fun packsForWhatsAppBlocking(): List<StickerPack> = packsBlocking() + retired

    val retiredPacks: List<StickerPack> get() = retired

    /** Forgets retired packs WhatsApp no longer has; their files go on the next cold start. */
    suspend fun purgeRetired(stillInWhatsApp: suspend (StickerPack) -> Boolean) {
        val gone = retired.filterNot { stillInWhatsApp(it) }.map { it.id }.toSet()
        if (gone.isEmpty()) return
        edit { packs ->
            retired = retired.filterNot { it.id in gone }
            packs to Unit
        }
    }

    suspend fun createPack(name: String, publisher: String, animated: Boolean = false): StickerPack = edit { packs ->
        val now = System.currentTimeMillis()
        val pack = StickerPack(
            id = newId(),
            name = name.take(WhatsAppLimits.MAX_NAME_CHARS),
            publisher = publisher.take(WhatsAppLimits.MAX_NAME_CHARS),
            animated = animated,
            createdAt = now,
            updatedAt = now,
        )
        packDir(pack.id).mkdirs()
        (listOf(pack) + packs) to pack
    }

    suspend fun renamePack(packId: String, name: String) = updatePack(packId, bumpVersion = false) {
        it.copy(name = name.take(WhatsAppLimits.MAX_NAME_CHARS))
    }

    /**
     * Hides a pack from the app; pass the returned pack to [restorePack] to undo. It stays
     * retired (still served to WhatsApp, files kept) until [purgeRetired] finds WhatsApp no
     * longer has it.
     */
    suspend fun removePack(packId: String): StickerPack? = edit { packs ->
        val removed = packs.firstOrNull { it.id == packId }
        if (removed != null) retired = retired.filterNot { it.id == packId } + removed
        packs.filterNot { it.id == packId } to removed
    }

    suspend fun restorePack(pack: StickerPack) = edit { packs ->
        retired = retired.filterNot { it.id == pack.id }
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

    suspend fun reorderStickers(packId: String, orderedIds: List<String>) {
        updatePack(packId, bumpVersion = true) { pack ->
            val byId = pack.stickers.associateBy { it.id }
            pack.copy(stickers = orderedIds.mapNotNull { byId[it] } + pack.stickers.filterNot { it.id in orderedIds })
        }
        refreshTray(packId)
    }

    suspend fun setEmojis(packId: String, stickerId: String, emojis: List<String>) = updatePack(packId, bumpVersion = false) { pack ->
        pack.copy(stickers = pack.stickers.map {
            if (it.id == stickerId) it.copy(emojis = emojis.take(WhatsAppLimits.MAX_EMOJIS).ifEmpty { listOf("🙂") }) else it
        })
    }

    /**
     * Writes finished sticker [files] (WebP bytes) into [packId], or into a new pack called
     * [newName] ([animated] or still), and returns the pack id. Files first, then the index.
     */
    suspend fun addStickers(packId: String?, newName: String?, animated: Boolean, files: List<ByteArray>): String {
        val id = packId ?: createPack(newName?.trim().orEmpty().ifEmpty { "My stickers" }, PUBLISHER, animated).id
        val added = withContext(Dispatchers.IO) {
            val written = ArrayList<Sticker>(files.size)
            try {
                files.forEach { bytes ->
                    val sticker = Sticker(id = newId(), updatedAt = System.currentTimeMillis())
                    writeAtomically(stickerFile(id, sticker.id), bytes)
                    written += sticker
                }
            } catch (e: Exception) {
                // Not in the index yet, so nothing refers to them: remove them now rather than
                // leaving them for the next cold start's sweep.
                written.forEach { stickerFile(id, it.id).delete() }
                throw e
            }
            written
        }
        updatePack(id, bumpVersion = true) { pack ->
            pack.copy(stickers = (pack.stickers + added).take(WhatsAppLimits.MAX_STICKERS))
        }
        refreshTray(id)
        return id
    }

    /** Removes a sticker from the index; returns it and its position for [restoreSticker]. */
    suspend fun removeSticker(packId: String, stickerId: String): Pair<Sticker, Int>? {
        val pack = pack(packId) ?: return null
        val index = pack.stickers.indexOfFirst { it.id == stickerId }
        if (index < 0) return null
        val sticker = pack.stickers[index]
        updatePack(packId, bumpVersion = true) { p -> p.copy(stickers = p.stickers.filterNot { it.id == stickerId }) }
        refreshTray(packId)
        return sticker to index
    }

    suspend fun restoreSticker(packId: String, sticker: Sticker, index: Int) {
        putSticker(packId, sticker, index)
        refreshTray(packId)
    }

    /** The tray icon follows the pack's first sticker (its first frame, if animated). */
    private suspend fun refreshTray(packId: String) = withContext(Dispatchers.Default) {
        val first = pack(packId)?.stickers?.firstOrNull() ?: return@withContext
        val bitmap = BitmapFactory.decodeFile(stickerFile(packId, first.id).path) ?: return@withContext
        writeAtomically(trayFile(packId), StickerExport.encodeTray(bitmap))
        bitmap.recycle()
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw java.io.IOException("Couldn't move ${tmp.name} into place")
            }
        } finally {
            // Written and moved, or failed: either way no .tmp is left behind.
            tmp.delete()
        }
    }

    /**
     * A pack just deleted from its own screen, for Home to offer Undo once it's back on screen.
     * Home clears it after showing the toast.
     */
    val recentlyRemoved = MutableStateFlow<RemovedPack?>(null)

    /** [inWhatsApp]: WhatsApp still has it, which Home's toast mentions. */
    data class RemovedPack(val pack: StickerPack, val inWhatsApp: Boolean)

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
        val index = runCatching { json.decodeFromString<PackIndex>(indexFile.readText()) }.getOrDefault(PackIndex())
        retired = index.retired
        sweepOrphans(index.packs + index.retired)
        _packs.value = index.packs
        return index.packs
    }

    private fun save(packs: List<StickerPack>) {
        root.mkdirs()
        // Write-then-rename so a crash mid-write never leaves a truncated index.
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(json.encodeToString(PackIndex.serializer(), PackIndex(packs, retired)))
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete()
            tmp.renameTo(indexFile)
        }
    }

    /** Deletes folders/files of packs and stickers no longer in the index (live or retired). */
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
                if (stickerId != "tray" && stickerId !in keep || file.name.endsWith(".tmp")) file.delete()
            }
        }
    }
}
