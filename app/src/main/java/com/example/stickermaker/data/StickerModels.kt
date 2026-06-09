package com.example.stickermaker.data

import kotlinx.serialization.Serializable

/** WhatsApp's rules for a pack it will accept (from the official sticker sample README). */
object WhatsAppLimits {
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val STICKER_PX = 512
    const val STICKER_MAX_BYTES = 100 * 1024
    const val TRAY_PX = 96
    const val TRAY_MAX_BYTES = 50 * 1024
    const val MAX_EMOJIS = 3
    const val MAX_NAME_CHARS = 128
}

@Serializable
data class Sticker(
    val id: String,
    /** 1–3 emojis WhatsApp uses to suggest the sticker. Required by WhatsApp. */
    val emojis: List<String> = listOf("🙂"),
    val updatedAt: Long,
)

@Serializable
data class StickerPack(
    /** Also the WhatsApp pack identifier: only [a-z0-9_-] so it's always valid. */
    val id: String,
    val name: String,
    val publisher: String,
    val stickers: List<Sticker> = emptyList(),
    /**
     * Bumped whenever stickers or the tray icon change, which is how WhatsApp learns a pack it
     * already has was updated (image_data_version).
     */
    val imageDataVersion: Int = 1,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isFull: Boolean get() = stickers.size >= WhatsAppLimits.MAX_STICKERS
    val canAddToWhatsApp: Boolean
        get() = stickers.size in WhatsAppLimits.MIN_STICKERS..WhatsAppLimits.MAX_STICKERS
    val stickersNeeded: Int get() = (WhatsAppLimits.MIN_STICKERS - stickers.size).coerceAtLeast(0)
}

@Serializable
internal data class PackIndex(
    val packs: List<StickerPack> = emptyList(),
    /**
     * Packs deleted in the app that WhatsApp may still have. WhatsApp keeps a pack it was given
     * and reads it through our provider, so we keep serving these (hidden in the app) until
     * WhatsApp no longer has them; otherwise it can neither use nor remove them.
     */
    val retired: List<StickerPack> = emptyList(),
)
