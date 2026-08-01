package com.example.stickermaker.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.stickermaker.StickerMakerApp
import com.example.stickermaker.data.PackRepository
import com.example.stickermaker.data.Sticker
import com.example.stickermaker.data.StickerPack
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.editor.StickerExport
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File

/**
 * What WhatsApp reads a pack from, following the contract in WhatsApp's official sample app
 * (github.com/WhatsApp/stickers): the same paths, column names and MIME types. WhatsApp holds
 * `com.whatsapp.sticker.READ`, which the manifest requires.
 *
 *     content://<authority>/metadata                       every pack
 *     content://<authority>/metadata/<packId>              one pack
 *     content://<authority>/stickers/<packId>              its stickers
 *     content://<authority>/stickers_asset/<packId>/<file> a sticker .webp or the tray .png
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var matcher: UriMatcher

    // The container is lazy; nothing is touched until WhatsApp actually asks.
    private val packs: PackRepository
        get() = (requireNotNull(context).applicationContext as StickerMakerApp).container.packs

    override fun onCreate(): Boolean {
        val authority = WhatsApp.authority(requireNotNull(context))
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, METADATA, METADATA_ALL)
            addURI(authority, "$METADATA/*", METADATA_ONE)
            addURI(authority, "$STICKERS/*", STICKERS_OF_PACK)
            addURI(authority, "$STICKERS_ASSET/*/*", ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = when (matcher.match(uri)) {
        METADATA_ALL -> metadata(uri, packs.packsForWhatsAppBlocking())
        METADATA_ONE -> metadata(uri, listOf(findOrStub(uri.lastPathSegment)))
        STICKERS_OF_PACK -> stickers(uri, findOrStub(uri.lastPathSegment))
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    private fun metadata(uri: Uri, list: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier",
                "sticker_pack_name",
                "sticker_pack_publisher",
                "sticker_pack_icon",
                "android_play_store_link",
                "ios_app_download_link",
                "sticker_pack_publisher_email",
                "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website",
                "sticker_pack_license_agreement_website",
                "image_data_version",
                "whatsapp_will_not_cache_stickers",
                "animated_sticker_pack",
            ),
        )
        // Only packs WhatsApp would accept; a half-made pack would fail its validation.
        list.filter { it.canAddToWhatsApp }.forEach { pack ->
            cursor.addRow(
                arrayOf<Any>(
                    pack.id, pack.name, pack.publisher, TRAY_FILE,
                    "", "", "", "", "", "",
                    pack.imageDataVersion.toString(),
                    0,
                    if (pack.animated) 1 else 0,
                ),
            )
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    private fun stickers(uri: Uri, pack: StickerPack?): Cursor {
        val cursor = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        pack?.stickers?.forEach { sticker ->
            cursor.addRow(arrayOf<Any>("${sticker.id}.webp", sticker.emojis.joinToString(","), "Sticker"))
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != ASSET) return null
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val packId = segments[1]
        val fileName = segments[2]
        val known = packs.packsForWhatsAppBlocking().firstOrNull { it.id == packId }
        if (known == null) return stubAsset(fileName)
        val pack = known
        // Serve only files that belong to the pack: no path tricks into other folders.
        val file = when {
            fileName == TRAY_FILE -> packs.trayFile(packId)
            pack.stickers.any { "${it.id}.webp" == fileName } -> packs.stickerFile(packId, fileName.removeSuffix(".webp"))
            else -> return null
        }
        if (!file.exists()) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    /**
     * A pack WhatsApp asks for that we no longer know (deleted before deleted packs were kept
     * around): answer with a valid "Deleted pack" stand-in, so WhatsApp has something it can
     * show and the user can remove it there.
     */
    private fun findOrStub(packId: String?): StickerPack {
        packs.packsForWhatsAppBlocking().firstOrNull { it.id == packId }?.let { return it }
        val now = System.currentTimeMillis()
        return StickerPack(
            id = packId.orEmpty(),
            name = "Deleted pack",
            publisher = "Sticker Maker",
            stickers = List(WhatsAppLimits.MIN_STICKERS) { Sticker(id = "$STUB_PREFIX$it", updatedAt = now) },
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun stubAsset(fileName: String): AssetFileDescriptor? {
        val ctx = requireNotNull(context)
        val isTray = fileName == TRAY_FILE
        if (!isTray && !fileName.startsWith(STUB_PREFIX)) return null
        val file = File(ctx.cacheDir, "stub/${if (isTray) "tray.png" else "sticker.webp"}")
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val side = if (isTray) WhatsAppLimits.TRAY_PX else WhatsAppLimits.STICKER_PX
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.color = 0xFFD4BBFF.toInt() // Palette.Lavender
                drawCircle(side / 2f, side / 2f, side * 0.4f, paint)
                paint.color = 0xFF141414.toInt()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = side * 0.05f
                drawCircle(side / 2f, side / 2f, side * 0.4f, paint)
                drawLine(side * 0.36f, side * 0.36f, side * 0.64f, side * 0.64f, paint)
                drawLine(side * 0.64f, side * 0.36f, side * 0.36f, side * 0.64f, paint)
            }
            file.writeBytes(if (isTray) StickerExport.encodeTray(bitmap) else StickerExport.encodeSticker(bitmap))
        }
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String {
        val authority = WhatsApp.authority(requireNotNull(context))
        return when (matcher.match(uri)) {
            METADATA_ALL -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
            METADATA_ONE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
            STICKERS_OF_PACK -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
            ASSET -> if (uri.lastPathSegment == TRAY_FILE) "image/png" else "image/webp"
            else -> throw IllegalArgumentException("Unknown URI: $uri")
        }
    }

    // Read-only.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"
        const val TRAY_FILE = "tray.png"
        const val STUB_PREFIX = "deleted_"

        const val METADATA_ALL = 1
        const val METADATA_ONE = 2
        const val STICKERS_OF_PACK = 3
        const val ASSET = 4
    }
}
