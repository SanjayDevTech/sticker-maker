package com.example.stickermaker.keyboard

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stickermaker.AppContainer
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.data.ThemeMode
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.FileImage
import com.example.stickermaker.ui.components.PackTray
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import com.example.stickermaker.ui.theme.StickerTheme
import java.io.File

/** The keyboard's panel: pack tabs on top, that pack's stickers below; tap to insert. */
@Composable
fun StickerKeyboard(container: AppContainer, onSticker: (File) -> Unit, onBackToKeyboard: () -> Unit) {
    val mode by container.settings.themeMode.collectAsStateWithLifecycle(ThemeMode.System)
    val dark = when (mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalAppContainer provides container) {
        StickerTheme(darkTheme = dark, fillScreen = false) {
            val colors = AppTheme.colors
            val packs by container.packs.packs.collectAsStateWithLifecycle()
            val list = packs.orEmpty().filter { it.stickers.isNotEmpty() }
            var selectedId by remember { mutableStateOf<String?>(null) }
            val selected = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()

            // A fixed height: the keyboard window never resizes (that's what made dragging it
            // sluggish), and the grid scrolls inside it.
            val height = with(LocalDensity.current) {
                (LocalContext.current.resources.displayMetrics.heightPixels * HEIGHT_FRACTION).toDp()
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(colors.canvas)
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier.padding(start = Dimens.Space12, end = Dimens.Space12, top = Dimens.Space8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
                ) {
                    // The familiar "back to typing" key: returns to the previous keyboard (e.g. FUTO).
                    BrutalSurface(
                        modifier = Modifier.size(width = 64.dp, height = 44.dp),
                        shape = Shapes.Small,
                        color = Palette.Yellow,
                        shadow = Dimens.ShadowS,
                        onClick = onBackToKeyboard,
                        onClickLabel = "Back to typing",
                    ) {
                        Text("ABC", style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.Center))
                    }
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
                        contentPadding = PaddingValues(end = Dimens.ShadowS, bottom = Dimens.ShadowS, top = Dimens.Space4),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(list, key = { it.id }, contentType = { "pack" }) { pack ->
                            val isSelected = pack.id == selected?.id
                            BrutalSurface(
                                modifier = Modifier.size(44.dp),
                                shape = Shapes.Small,
                                color = if (isSelected) Palette.Yellow else colors.surface,
                                borderWidth = if (isSelected) Dimens.BorderThick else Dimens.Border,
                                shadow = Dimens.ShadowS,
                                onClick = { selectedId = pack.id },
                                onClickLabel = pack.name,
                            ) {
                                PackTray(pack, Modifier.fillMaxSize().padding(4.dp))
                            }
                        }
                    }
                }
                if (selected == null) {
                    Box(Modifier.fillMaxSize().padding(Dimens.Space24), contentAlignment = Alignment.Center) {
                        Text(
                            "No stickers yet. Make some in Sticker Maker and they'll show up here.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.inkMuted,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(
                            start = Dimens.Space12,
                            end = Dimens.Space12 + Dimens.ShadowS,
                            top = Dimens.Space8,
                            bottom = Dimens.Space12,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
                        verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(selected.stickers, key = { it.id }, contentType = { "sticker" }) { sticker ->
                            val file = container.packs.stickerFile(selected.id, sticker.id)
                            BrutalSurface(
                                modifier = Modifier.aspectRatio(1f),
                                shape = Shapes.Medium,
                                color = colors.surface,
                                shadow = Dimens.ShadowS,
                                onClick = { onSticker(file) },
                                onClickLabel = "Insert sticker",
                            ) {
                                FileImage(
                                    file = file,
                                    maxPx = 192,
                                    contentScale = ContentScale.Fit,
                                    animated = selected.animated,
                                    modifier = Modifier.fillMaxSize().padding(4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val HEIGHT_FRACTION = 0.45f
