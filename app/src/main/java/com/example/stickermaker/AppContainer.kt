package com.example.stickermaker

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.stickermaker.data.SettingsStore

/**
 * Hand-rolled dependency container. Every dependency is lazy so app start only pays for what
 * the first screen touches. Add repositories / databases here as `by lazy` vals.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings: SettingsStore by lazy { SettingsStore(appContext) }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

/**
 * `val vm = appViewModel { SettingsViewModel(it.settings) }` — scoped to the current
 * navigation entry (Navigation 3's ViewModelStore decorator), built from the container.
 */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container) } },
    )
}
