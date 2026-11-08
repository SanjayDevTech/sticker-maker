package com.example.stickermaker.ui.screens.settings

import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.ChunkyButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import android.view.inputmethod.InputMethodManager
import android.provider.Settings
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import com.example.stickermaker.R
import com.example.stickermaker.appViewModel
import com.example.stickermaker.data.ThemeMode
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.SectionTitle
import com.example.stickermaker.ui.components.SegmentedPicker
import com.example.stickermaker.ui.components.StickerCard
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.rememberCollapsed
import com.example.stickermaker.ui.components.rememberVersionName
import com.example.stickermaker.ui.components.sharedBlock
import com.example.stickermaker.ui.components.staggeredEntrance
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

const val DESIGN_KIT_SHARED_KEY = "design-kit-block"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDesignKit: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val viewModel = appViewModel { SettingsViewModel(it.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onThemeMode = viewModel::setThemeMode,
        onOpenDesignKit = onOpenDesignKit,
        onOpenLicenses = onOpenLicenses,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onOpenDesignKit: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    // Small, fixed content: a scrolling Column, not a lazy list (Compose guidance for ~1–10 items).
    val scrollState = rememberScrollState()
    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Settings",
                collapsed = rememberCollapsed(scrollState),
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = Dimens.ScreenGutter,
                    end = Dimens.ScreenGutter + Dimens.ShadowL,
                    top = padding.calculateTopPadding() + Dimens.Space8,
                    bottom = padding.calculateBottomPadding() + Dimens.Space48,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space20),
        ) {
            Text("Settings", style = MaterialTheme.typography.displayMedium)
            SectionTitle("Appearance", Modifier.staggeredEntrance(0))
            StickerCard(Modifier.staggeredEntrance(1).fillMaxWidth()) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Dark is pure black, easy on OLED.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTheme.colors.inkMuted,
                )
                SegmentedPicker(
                    options = ThemeMode.entries,
                    selected = state.themeMode,
                    onSelect = onThemeMode,
                    label = { it.name },
                    icon = {
                        when (it) {
                            ThemeMode.System -> R.drawable.ic_contrast
                            ThemeMode.Light -> R.drawable.ic_light_mode
                            ThemeMode.Dark -> R.drawable.ic_dark_mode
                        }
                    },
                    modifier = Modifier.padding(top = Dimens.Space4, end = Dimens.ShadowS),
                )
            }
            SectionTitle("Sticker keyboard", Modifier.staggeredEntrance(2))
            StickerKeyboardCard(Modifier.staggeredEntrance(2).fillMaxWidth())
            SectionTitle("Under the hood", Modifier.staggeredEntrance(2))
            BrutalSurface(
                modifier = Modifier
                    .staggeredEntrance(3)
                    .sharedBlock(DESIGN_KIT_SHARED_KEY)
                    .fillMaxWidth(),
                shape = Shapes.Large,
                color = Palette.Pink,
                onClick = onOpenDesignKit,
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Space16),
                ) {
                    Icon(painterResource(R.drawable.ic_palette), null, Modifier.size(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Design kit", style = MaterialTheme.typography.titleLarge)
                        Text("Every component, live.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            SectionTitle("About", Modifier.staggeredEntrance(4))
            AboutCard(onOpenLicenses, Modifier.staggeredEntrance(4).fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().padding(top = Dimens.Space24),
                horizontalArrangement = Arrangement.Center,
            ) {
                StickerTag("v${rememberVersionName()}", Palette.Lime, rotation = 3f)
            }
        }
    }
}

/**
 * Whether the sticker keyboard is turned on, with a way to Android's keyboard settings.
 * Re-checked each time the screen resumes (the user comes back from those settings).
 */
@Composable
private fun StickerKeyboardCard(modifier: Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
        onPauseOrDispose {}
    }
    StickerCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
            Text("Send single stickers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StickerTag(if (enabled) "On" else "Off", if (enabled) Palette.Lime else Palette.Peach, rotation = 3f)
        }
        Text(
            "WhatsApp only takes packs of 3 or more from other apps. The sticker keyboard sends any " +
                "one sticker as a real sticker: in a chat, switch to it, tap a sticker, then switch back.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.inkMuted,
        )
        ChunkyButton(
            text = if (enabled) "Keyboard settings" else "Turn it on",
            onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            size = ChunkySize.Medium,
            color = Palette.Sky,
            modifier = Modifier.padding(top = Dimens.Space4, end = Dimens.ShadowM, bottom = Dimens.ShadowM),
        )
    }
}

/** Who made it, where the source lives, and the licences it ships under. */
@Composable
private fun AboutCard(onOpenLicenses: () -> Unit, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    StickerCard(modifier) {
        Text("Made by Sticker Maker contributors", style = MaterialTheme.typography.titleMedium)
        Text(
            "Free and open source under the MIT License.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.inkMuted,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            modifier = Modifier.padding(top = Dimens.Space4, end = Dimens.ShadowM, bottom = Dimens.ShadowM),
        ) {
            ChunkyButton(
                text = "Source",
                onClick = { uriHandler.openUri(SOURCE_URL) },
                icon = R.drawable.ic_code,
                size = ChunkySize.Medium,
                color = Palette.Lime,
            )
            ChunkyButton(
                text = "Licences",
                onClick = onOpenLicenses,
                size = ChunkySize.Medium,
                color = Palette.Sky,
            )
        }
    }
}

private const val SOURCE_URL = "https://github.com/SanjayDevTech/sticker-maker"
