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
import com.example.stickermaker.data.StickerPack

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
        METADATA_ALL -> metadata(uri, packs.packsBlocking())
        METADATA_ONE -> metadata(uri, packs.packsBlocking().filter { it.id == uri.lastPathSegment })
        STICKERS_OF_PACK -> stickers(uri, packs.packsBlocking().firstOrNull { it.id == uri.lastPathSegment })
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
                    0,
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
        val pack = packs.packsBlocking().firstOrNull { it.id == packId } ?: return null
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

        const val METADATA_ALL = 1
        const val METADATA_ONE = 2
        const val STICKERS_OF_PACK = 3
        const val ASSET = 4
    }
}
