package com.example.stickermaker.ui.screens.combine

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stickermaker.R
import com.example.stickermaker.appViewModel
import com.example.stickermaker.editor.Layer
import com.example.stickermaker.editor.StickerExport
import com.example.stickermaker.editor.StickerMotion
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.EmptyState
import com.example.stickermaker.ui.components.GifTag
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.MotionPicker
import com.example.stickermaker.ui.components.SkeletonBlock
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.ToastHost
import com.example.stickermaker.ui.components.checkerboard
import com.example.stickermaker.ui.components.rememberToastState
import com.example.stickermaker.ui.components.showUndo
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.launch

/** Compose the cut-outs into one sticker: place, size, turn and restack the layers. */
@Composable
fun CombineScreen(onBack: () -> Unit, onSave: () -> Unit) {
    val vm = appViewModel { CombineViewModel(it.session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toasts = rememberToastState()
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val playback = rememberPlayback(state.layers)

    LaunchedEffect(state.empty) {
        val empty = state.empty
        if (empty.isNotEmpty()) {
            launch {
                toasts.show(
                    if (empty.size == 1) "Image ${empty[0]} is fully erased, so it's left out"
                    else "Images ${empty.joinToString()} are fully erased, so they're left out",
                )
            }
        }
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Combine",
                collapsed = true,
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
                actions = {
                    IconTile(R.drawable.ic_undo, "Undo", vm::undo, enabled = state.canUndo)
                    IconTile(R.drawable.ic_redo, "Redo", vm::redo, enabled = state.canRedo)
                },
            )
        },
        snackbarHost = { ToastHost(toasts) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .navigationBarsPadding()
                .padding(start = Dimens.ScreenGutter, end = Dimens.ScreenGutter + Dimens.ShadowM, bottom = Dimens.Space12 + Dimens.ShadowM),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
            ) {
                BrutalSurface(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    shape = Shapes.Small,
                    color = colors.surface,
                    shadow = Dimens.ShadowL,
                    borderWidth = Dimens.BorderThick,
                ) {
                    when {
                        state.loading -> SkeletonBlock(Modifier.fillMaxSize().padding(Dimens.Space24), Shapes.Medium)
                        state.layers.isEmpty() -> EmptyState(
                            icon = R.drawable.ic_image,
                            title = "Nothing to combine",
                            message = "Every image is fully erased. Go back and restore some.",
                            modifier = Modifier.align(Alignment.Center),
                        )
                        else -> CombineCanvas(
                            layers = state.layers,
                            playback = playback,
                            selected = state.selected,
                            onSelect = vm::select,
                            onGestureStart = vm::beginGesture,
                            onGestureEnd = vm::endGesture,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Text(
                    "Tap a layer to pick it. Drag to move; pinch or pull the yellow corner to size and turn. Cut-outs can move too.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
                if (state.layers.isNotEmpty()) {
                    LayerStrip(
                        state = state,
                        vm = vm,
                        onDelete = { layer ->
                            val index = vm.delete(layer.id)
                            if (index >= 0) {
                                scope.launch {
                                    if (toasts.showUndo("Layer ${layer.number} removed")) vm.undoDelete(layer.id, index)
                                }
                            }
                        },
                    )
                    LayerMotion(state.selected, vm::setMotion)
                }
            }
            ChunkyButton(
                text = "Save sticker",
                onClick = { vm.finish(onSave) },
                icon = R.drawable.ic_check,
                size = ChunkySize.Large,
                enabled = state.layers.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The stack, front first, with the restack and flip actions for the picked layer. */
@Composable
private fun LayerStrip(state: CombineUiState, vm: CombineViewModel, onDelete: (Layer) -> Unit) {
    val canDelete = state.layers.size > 1
    val selectedIndex = state.selectedIndex
    val hasSelection = selectedIndex >= 0
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
            Text("Layers", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconTile(
                R.drawable.ic_flip_to_front, "Bring forward", { vm.restack(up = true) },
                enabled = hasSelection && selectedIndex < state.layers.lastIndex, color = Palette.Sky,
            )
            IconTile(
                R.drawable.ic_flip_to_back, "Send backward", { vm.restack(up = false) },
                enabled = hasSelection && selectedIndex > 0, color = Palette.Sky,
            )
            IconTile(R.drawable.ic_flip, "Flip", vm::flip, enabled = hasSelection, color = Palette.Peach)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
            contentPadding = PaddingValues(end = Dimens.ShadowS, bottom = Dimens.ShadowS, top = Dimens.Space4),
        ) {
            // Front of the stack first, like a layers panel.
            items(state.layers.asReversed(), key = { it.id }, contentType = { "layer" }) { layer ->
                LayerThumb(
                    layer = layer,
                    selected = layer.id == state.selectedId,
                    onClick = { vm.select(layer.id) },
                    onDelete = if (canDelete) ({ onDelete(layer) }) else null,
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                )
            }
        }
    }
}

/** How the picked layer moves: a motion picker for a cut-out; a GIF just plays. */
@Composable
private fun LayerMotion(layer: Layer?, onSelect: (StickerMotion) -> Unit) {
    if (layer == null) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
        Text("Motion · layer ${layer.number}", style = MaterialTheme.typography.titleLarge)
        if (layer.isGif) {
            Text(
                "A GIF plays its trimmed part. Shorter loops repeat to fill the longest GIF.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.inkMuted,
            )
        } else {
            // The picker previews motions on a sticker-shaped square of this layer.
            val sample by produceState<ImageBitmap?>(null, layer) {
                value = withContext(Dispatchers.Default) { StickerExport.fit(layer.bitmap).asImageBitmap() }
            }
            MotionPicker(sample = sample, selected = layer.motion, onSelect = onSelect, stillAllowed = true)
        }
    }
}

@Composable
private fun LayerThumb(layer: Layer, selected: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?, modifier: Modifier) {
    // Extra room on the top-end for the delete tile, which overhangs the thumbnail.
    Box(modifier.size(76.dp)) {
        BrutalSurface(
            modifier = Modifier.size(64.dp).align(Alignment.BottomStart),
            shape = Shapes.Small,
            color = if (selected) Palette.Yellow else AppTheme.colors.surface,
            borderWidth = if (selected) Dimens.BorderThick else Dimens.Border,
            shadow = Dimens.ShadowS,
            onClick = onClick,
            onClickLabel = "Pick layer ${layer.number}",
        ) {
            Image(
                layer.image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(if (selected) 5.dp else 0.dp).checkerboard(5.dp),
                contentScale = ContentScale.Fit,
            )
        }
        StickerTag("${layer.number}", Palette.Lavender, rotation = -6f, modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp))
        if (layer.isGif) GifTag(Modifier.align(Alignment.BottomEnd).padding(end = Dimens.ShadowS + 4.dp, bottom = 4.dp))
        if (onDelete != null) {
            IconTile(
                R.drawable.ic_close,
                "Remove layer ${layer.number}",
                onDelete,
                size = 28.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = Dimens.ShadowS),
            )
        }
    }
}
