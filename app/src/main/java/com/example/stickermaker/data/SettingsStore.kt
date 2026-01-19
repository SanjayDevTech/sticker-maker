package com.example.stickermaker.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val ThemeMode = stringPreferencesKey("theme_mode")
    }

    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data
        .map { prefs ->
            prefs[Keys.ThemeMode]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.System
        }
        .distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[Keys.ThemeMode] = mode.name }
    }
}
