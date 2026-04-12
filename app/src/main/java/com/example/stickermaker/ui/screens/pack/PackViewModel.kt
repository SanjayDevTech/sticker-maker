package com.example.stickermaker.ui.screens.pack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stickermaker.data.PackRepository
import com.example.stickermaker.data.Sticker
import com.example.stickermaker.data.StickerPack
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PackUiState(
    val loading: Boolean = true,
    /** Null once loaded means the pack is gone (deleted). */
    val pack: StickerPack? = null,
)

class PackViewModel(private val packId: String, private val packs: PackRepository) : ViewModel() {

    val state: StateFlow<PackUiState> = packs.packs
        .map { list -> PackUiState(loading = list == null, pack = list?.firstOrNull { it.id == packId }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PackUiState(pack = packs.pack(packId)))

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { packs.renamePack(packId, trimmed) }
    }

    fun setEmojis(stickerId: String, emojis: List<String>) {
        viewModelScope.launch { packs.setEmojis(packId, stickerId, emojis) }
    }

    /** Removes a sticker now; returns what [restore] needs for the toast's Undo. */
    suspend fun remove(stickerId: String): Pair<Sticker, Int>? = packs.removeSticker(packId, stickerId)

    fun restore(sticker: Sticker, index: Int) {
        viewModelScope.launch { packs.restoreSticker(packId, sticker, index) }
    }

    /** Deletes the pack and hands it to Home, which offers Undo. */
    suspend fun deletePack() {
        packs.removePack(packId)?.let { packs.recentlyRemoved.value = it }
    }
}
