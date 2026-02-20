package com.example.stickermaker

import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.stickermaker.data.ThemeMode
import com.example.stickermaker.ui.navigation.AppNavigation
import com.example.stickermaker.ui.theme.StickerTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /**
     * Gallery → Share → Sticker Maker. The read grant on shared URIs only lasts while this
     * activity is alive, so the session copies them right away.
     */
    private fun handleShare(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        val session = (application as StickerMakerApp).container.session
        session.add(uris)
        session.openRequested.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate so the splash window hands off cleanly.
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        val container = (application as StickerMakerApp).container
        // Fresh launch only: after a rotation/process restore the intent was already handled.
        if (savedInstanceState == null) handleShare(intent)
        // Null until DataStore answers. The splash stays up until then, so the first frame is
        // already in the right theme instead of flashing light → dark.
        val themeMode = container.settings.themeMode
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)
        splash.setKeepOnScreenCondition { themeMode.value == null }

        setContent {
            val mode by themeMode.collectAsStateWithLifecycle()
            val resolved = mode ?: return@setContent
            val dark = when (resolved) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Status/nav bar icon contrast must follow the *app* theme, not the system one,
            // or a forced Light theme on a dark phone gets white icons on cream.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                StickerTheme(darkTheme = dark) {
                    AppNavigation()
                }
            }
        }
    }
}
