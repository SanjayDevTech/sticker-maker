package com.example.stickermaker.ui.screens.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stickermaker.data.PackBackup
import com.example.stickermaker.data.SettingsStore
import com.example.stickermaker.data.ThemeMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A pack backup or restore in progress. */
enum class BackupWork { BackingUp, Restoring }

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val backupWork: BackupWork? = null,
)

class SettingsViewModel(private val settings: SettingsStore, private val backup: PackBackup) : ViewModel() {

    private val work = MutableStateFlow<BackupWork?>(null)

    val state: StateFlow<SettingsUiState> = combine(settings.themeMode, work) { theme, w -> SettingsUiState(theme, w) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** One line to show as a toast after a backup or restore. */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun backUp(uri: Uri) = run(BackupWork.BackingUp) {
        val count = backup.export(uri)
        if (count == 1) "Backed up 1 pack" else "Backed up $count packs"
    }

    fun restore(uri: Uri) = run(BackupWork.Restoring) {
        val r = backup.import(uri)
        fun packs(n: Int) = if (n == 1) "1 pack" else "$n packs"
        val notes = buildList {
            if (r.alreadyHere > 0) add("${packs(r.alreadyHere)} already here")
            if (r.broken > 0) add("${packs(r.broken)} couldn't be read")
        }.joinToString(", ")
        when {
            r.added > 0 -> "Restored ${packs(r.added)}" + if (notes.isEmpty()) "" else " ($notes)"
            r.alreadyHere > 0 && r.broken == 0 -> "Nothing new: every pack in it is already here"
            r.broken > 0 -> "Restored nothing: $notes"
            else -> "That backup has no packs"
        }
    }

    private fun run(kind: BackupWork, block: suspend () -> String) {
        if (work.value != null) return
        work.value = kind
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: Exception) {
                Log.w(TAG, "Backup failed", e)
                if (kind == BackupWork.BackingUp) "Couldn't back up: ${e.message}" else "Couldn't restore: ${e.message}"
            } finally {
                work.value = null
            }
            _messages.emit(message)
        }
    }

    private companion object {
        const val TAG = "SettingsViewModel"
    }
}
