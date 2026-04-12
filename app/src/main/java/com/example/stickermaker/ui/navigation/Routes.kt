package com.example.stickermaker.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data object DesignKitRoute : NavKey
@Serializable data object ArrangeRoute : NavKey
@Serializable data object EditorRoute : NavKey
@Serializable data object CombineRoute : NavKey
@Serializable data object SaveRoute : NavKey
@Serializable data class PackRoute(val packId: String) : NavKey
