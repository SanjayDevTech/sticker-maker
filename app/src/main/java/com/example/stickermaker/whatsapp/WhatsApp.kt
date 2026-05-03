package com.example.stickermaker.whatsapp

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Talking to WhatsApp (and WhatsApp Business), per WhatsApp's official sticker sample. */
object WhatsApp {

    const val CONSUMER = "com.whatsapp"
    const val BUSINESS = "com.whatsapp.w4b"
    private const val TAG = "WhatsApp"

    /** Extra WhatsApp puts on a cancelled result when the pack failed its checks. */
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    fun authority(context: Context) = "${context.packageName}.stickercontentprovider"

    sealed interface Status {
        /** Neither WhatsApp app is installed. */
        data object NotInstalled : Status

        /** Every installed WhatsApp app already has the pack. */
        data object Added : Status

        /** Still to add; [onlyTo] is set when just one installed app is missing it. */
        data class NotAdded(val onlyTo: String?) : Status
    }

    /** Asks each installed WhatsApp whether it already has [packId]. Runs off the main thread. */
    suspend fun status(context: Context, packId: String): Status = withContext(Dispatchers.IO) {
        val installed = listOf(CONSUMER, BUSINESS).filter { isInstalled(context, it) }
        if (installed.isEmpty()) return@withContext Status.NotInstalled
        val missing = installed.filterNot { isAdded(context, it, packId) }
        when (missing.size) {
            0 -> Status.Added
            1 -> Status.NotAdded(onlyTo = missing[0])
            else -> Status.NotAdded(onlyTo = null)
        }
    }

    /**
     * The intent that asks WhatsApp to add the pack. Aimed at one app when only that one needs
     * it; otherwise a chooser between the installed ones.
     */
    fun addIntent(context: Context, packId: String, packName: String, onlyTo: String?): Intent {
        val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK")
            .putExtra("sticker_pack_id", packId)
            .putExtra("sticker_pack_authority", authority(context))
            .putExtra("sticker_pack_name", packName)
        return if (onlyTo != null) intent.setPackage(onlyTo) else Intent.createChooser(intent, "Add to")
    }

    /**
     * Puts a sticker file on the clipboard labelled with WhatsApp's sticker type, so pasting it
     * into a chat may arrive as a sticker (the same type the keyboard route uses).
     */
    fun copySticker(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val clip = ClipData(ClipDescription("Sticker", arrayOf("image/webp.wasticker", "image/webp")), ClipData.Item(uri))
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isAdded(context: Context, pkg: String, packId: String): Boolean {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$pkg.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", authority(context))
            .appendQueryParameter("identifier", packId)
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow("result")) == 1
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't ask $pkg about $packId", e)
            false
        }
    }
}
