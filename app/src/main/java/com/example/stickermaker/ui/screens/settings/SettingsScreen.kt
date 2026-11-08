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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.R
import com.example.stickermaker.ui.components.ToastHost
import com.example.stickermaker.ui.components.ToastState
import com.example.stickermaker.ui.components.rememberToastState
import java.time.LocalDate
import kotlinx.coroutines.launch
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
    val viewModel = appViewModel { SettingsViewModel(it.settings, it.backup) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val toasts = rememberToastState()
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { launch { toasts.show(it) } }
    }
    SettingsContent(
        state = state,
        toasts = toasts,
        onBack = onBack,
        onThemeMode = viewModel::setThemeMode,
        onBackUp = viewModel::backUp,
        onRestore = viewModel::restore,
        onOpenDesignKit = onOpenDesignKit,
        onOpenLicenses = onOpenLicenses,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    toasts: ToastState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onBackUp: (Uri) -> Unit,
    onRestore: (Uri) -> Unit,
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
        snackbarHost = { ToastHost(toasts) },
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
            SectionTitle("Backup", Modifier.staggeredEntrance(2))
            BackupCard(state.backupWork, toasts, onBackUp, onRestore, Modifier.staggeredEntrance(2).fillMaxWidth())
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

/**
 * Saves every pack to a file the user picks and adds packs back from one. Restoring only
 * adds packs that aren't here (same id), so it's safe to run any time.
 */
@Composable
private fun BackupCard(
    work: BackupWork?,
    toasts: ToastState,
    onBackUp: (Uri) -> Unit,
    onRestore: (Uri) -> Unit,
    modifier: Modifier,
) {
    val packs by LocalAppContainer.current.packs.packs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onBackUp(uri)
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onRestore(uri)
    }
    StickerCard(modifier) {
        Text("Your packs, in one file", style = MaterialTheme.typography.titleMedium)
        Text(
            "Back up before reinstalling, then restore. Restoring only adds packs that aren't here " +
                "and never replaces or removes one. Packs WhatsApp still has show as added.",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.inkMuted,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            modifier = Modifier.padding(top = Dimens.Space4, end = Dimens.ShadowM, bottom = Dimens.ShadowM),
        ) {
            ChunkyButton(
                text = if (work == BackupWork.BackingUp) "Backing up…" else "Back up",
                onClick = {
                    if (packs.isNullOrEmpty()) {
                        scope.launch { toasts.show("No packs to back up yet") }
                    } else {
                        save.launch("sticker-packs-${LocalDate.now()}.zip")
                    }
                },
                icon = R.drawable.ic_file_save,
                size = ChunkySize.Medium,
                color = Palette.Lime,
                enabled = work == null,
            )
            ChunkyButton(
                text = if (work == BackupWork.Restoring) "Restoring…" else "Restore",
                onClick = { open.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                icon = R.drawable.ic_file_open,
                size = ChunkySize.Medium,
                color = Palette.Sky,
                enabled = work == null,
            )
        }
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
