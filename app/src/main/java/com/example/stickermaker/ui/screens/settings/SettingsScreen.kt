package com.example.stickermaker.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
) {
    val viewModel = appViewModel { SettingsViewModel(it.settings) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        onBack = onBack,
        onThemeMode = viewModel::setThemeMode,
        onOpenDesignKit = onOpenDesignKit,
    )
}

@Composable
private fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onOpenDesignKit: () -> Unit,
) {
    val listState = rememberLazyListState()
    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Settings",
                collapsed = rememberCollapsed(listState),
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.ScreenGutter,
                end = Dimens.ScreenGutter + Dimens.ShadowL,
                top = padding.calculateTopPadding() + Dimens.Space8,
                bottom = padding.calculateBottomPadding() + Dimens.Space48,
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space20),
        ) {
            item(key = "headline") {
                Text("Settings", style = MaterialTheme.typography.displayMedium)
            }
            item(key = "appearance-title") { SectionTitle("Appearance", Modifier.staggeredEntrance(0)) }
            item(key = "appearance") {
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
            }
            item(key = "kit-title") { SectionTitle("Under the hood", Modifier.staggeredEntrance(2)) }
            item(key = "kit") {
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
            }
            item(key = "version") {
                Row(
                    Modifier.fillMaxWidth().padding(top = Dimens.Space24),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StickerTag("v${rememberVersionName()}", Palette.Lime, rotation = 3f)
                }
            }
        }
    }
}
