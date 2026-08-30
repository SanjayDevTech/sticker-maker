package com.example.stickermaker.ui.screens.save

import com.example.stickermaker.whatsapp.WhatsApp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stickermaker.R
import com.example.stickermaker.appViewModel
import com.example.stickermaker.data.StickerPack
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.BrutalTextField
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.MotionImage
import com.example.stickermaker.ui.components.MotionPicker
import com.example.stickermaker.ui.components.ClipPlayer
import com.example.stickermaker.editor.StickerClip
import com.example.stickermaker.editor.MotionPlan
import com.example.stickermaker.editor.StickerExport
import com.example.stickermaker.editor.StickerMotion
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.stickermaker.ui.components.PackTray
import com.example.stickermaker.ui.components.SectionTitle
import com.example.stickermaker.ui.components.SegmentedPicker
import com.example.stickermaker.ui.components.SkeletonBlock
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.ToastHost
import com.example.stickermaker.ui.components.checkerboard
import com.example.stickermaker.ui.components.rememberCollapsed
import com.example.stickermaker.ui.components.rememberToastState
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.launch

/** Last step of making stickers: check them, pick the outline and the pack, save. */
@Composable
fun SaveScreen(onBack: () -> Unit, onSaved: (String) -> Unit) {
    val vm = appViewModel { SaveViewModel(it.session, it.packs) }
    val state by vm.state.collectAsStateWithLifecycle()
    val toasts = rememberToastState()
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Stickers whose copy is taking a while (moving ones still encoding).
    val copying = remember { mutableStateListOf<Int>() }

    fun copy(index: Int) {
        if (index in copying) return
        scope.launch {
            // Only show progress if it's slow: an instant copy shouldn't flash anything.
            val progress = launch {
                delay(COPY_PROGRESS_DELAY_MS)
                copying += index
            }
            val sticker = try {
                vm.stickerAt(index)
            } finally {
                progress.cancel()
                copying -= index
            }
            if (sticker == null) {
                toasts.show("Couldn't copy that one. Try again?")
                return@launch
            }
            WhatsApp.copySticker(context, sticker)
            toasts.show(if (state.count > 1) "Sticker ${index + 1} copied. Paste it in a chat." else "Copied. Paste it in a chat.")
        }
    }

    LaunchedEffect(vm) {
        launch { vm.saved.collect(onSaved) }
        vm.failed.collect { launch { toasts.show("Couldn't save. Try again?") } }
    }
    LaunchedEffect(state.skipped) {
        if (state.skipped > 0) {
            toasts.show(if (state.skipped == 1) "One image was fully erased, so it's left out" else "${state.skipped} fully erased images are left out")
        }
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Save",
                collapsed = rememberCollapsed(scroll),
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
            )
        },
        snackbarHost = { ToastHost(toasts) },
        bottomBar = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = Dimens.ScreenGutter, end = Dimens.ScreenGutter + Dimens.ShadowM, bottom = 12.dp + Dimens.ShadowM),
            ) {
                // One sticker: Copy sits next to Save. Several: each preview tile copies itself.
                if (state.count == 1) {
                    ChunkyButton(
                        text = if (0 in copying) "Copying…" else "Copy",
                        onClick = { copy(0) },
                        color = Palette.Sky,
                        size = ChunkySize.Large,
                        enabled = !state.loading && 0 !in copying,
                        modifier = Modifier.weight(0.4f),
                    )
                }
                ChunkyButton(
                    text = when {
                        state.saving -> "Saving…"
                        state.count <= 1 -> "Save"
                        else -> "Save ${state.count} stickers"
                    },
                    onClick = vm::save,
                    icon = R.drawable.ic_check,
                    size = ChunkySize.Large,
                    enabled = state.canSave,
                    modifier = Modifier.weight(0.6f),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(padding)
                .padding(start = Dimens.ScreenGutter, end = Dimens.ScreenGutter + Dimens.ShadowL, top = Dimens.Space8, bottom = Dimens.Space24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
        ) {
            Text("Save", style = MaterialTheme.typography.displayMedium)
            Previews(state.loading, state.previews, state.plans, state.clips, copying, onCopy = ::copy)
            Text(
                if (state.count > 1) {
                    "Tap a sticker to copy it, then paste it into a WhatsApp chat. No pack needed."
                } else {
                    "Copy, then paste into a WhatsApp chat: it arrives as a sticker. No pack needed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkMuted,
            )

            // Outline and motion are for cut-outs; GIFs play as they are.
            if (state.hasStills) {
                val hasClips = state.clips.isNotEmpty()
                SectionTitle("Outline", Modifier.padding(top = Dimens.Space8))
                SegmentedPicker(
                    options = listOf(false, true),
                    selected = state.outline,
                    onSelect = vm::setOutline,
                    label = { if (it) "White outline" else "No outline" },
                    modifier = Modifier.padding(end = Dimens.ShadowS),
                )
                Text(
                    if (hasClips) {
                        "WhatsApp's own maker forces a white border. Here it's your call (still stickers only; moving ones stay as they are)."
                    } else {
                        "WhatsApp's own maker forces a white border. Here it's your call."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )

                SectionTitle("Motion", Modifier.padding(top = Dimens.Space8))
                MotionPicker(
                    sample = state.previews.filterIndexed { i, _ -> i !in state.clips }.firstOrNull(),
                    selected = state.motion,
                    onSelect = vm::setMotion,
                    stillAllowed = !hasClips,
                )
                Text(
                    when {
                        hasClips -> "Some of these already move, so the still ones move too: WhatsApp keeps still and moving packs apart."
                        state.animated -> "Moving stickers go in animated packs: WhatsApp keeps still and moving packs apart."
                        else -> "Make it move: WhatsApp plays it on a loop."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkMuted,
                )
            }

            SectionTitle("Pack", Modifier.padding(top = Dimens.Space8))
            PackOption(
                selected = state.targetId == null,
                title = if (state.animated) "New animated pack" else "New pack",
                subtitle = "Start a fresh pack",
                color = Palette.Lime,
                onClick = { vm.setTarget(null) },
                thumbs = null,
            )
            AnimatedVisibility(
                visible = state.targetId == null,
                enter = fadeIn(Motion.effects()) + expandVertically(Motion.spatial()),
                exit = fadeOut(Motion.effects()) + shrinkVertically(Motion.spatialFast()),
            ) {
                BrutalTextField(
                    value = state.newName,
                    onValueChange = vm::setNewName,
                    placeholder = "Pack name",
                    maxLength = WhatsAppLimits.MAX_NAME_CHARS,
                    modifier = Modifier.padding(bottom = Dimens.Space4),
                )
            }
            state.packs.forEach { pack ->
                PackOption(
                    selected = state.targetId == pack.id,
                    title = pack.name,
                    subtitle = when {
                        pack.animated != state.animated -> if (pack.animated) "Animated pack: only moving stickers" else "Still pack: only still stickers"
                        !state.hasRoom(pack) -> "No room: a pack holds ${WhatsAppLimits.MAX_STICKERS}"
                        else -> "${pack.stickers.size} of ${WhatsAppLimits.MAX_STICKERS} stickers"
                    },
                    color = Palette.Sky,
                    enabled = state.accepts(pack),
                    onClick = { vm.setTarget(pack.id) },
                    thumbs = pack,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Previews(loading: Boolean, previews: List<ImageBitmap>, plans: List<MotionPlan?>, clips: Map<Int, StickerClip>, copying: List<Int>, onCopy: (Int) -> Unit) {
    val perRow = 3
    if (loading) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
            repeat(perRow) { SkeletonBlock(Modifier.weight(1f).aspectRatio(1f), Shapes.Medium) }
        }
        return
    }
    FlowRow(
        maxItemsInEachRow = perRow,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        previews.forEachIndexed { i, image ->
            BrutalSurface(
                modifier = Modifier.weight(1f).aspectRatio(1f),
                shape = Shapes.Medium,
                shadow = Dimens.ShadowS,
                onClick = { onCopy(i) },
                onClickLabel = "Copy sticker ${i + 1}",
            ) {
                val clip = clips[i]
                if (clip != null) {
                    ClipPlayer(clip, contentDescription = "Sticker ${i + 1}", modifier = Modifier.fillMaxSize().checkerboard(6.dp))
                } else {
                    MotionImage(
                        image,
                        plan = plans.getOrNull(i),
                        contentDescription = "Sticker ${i + 1}",
                        modifier = Modifier.fillMaxSize().checkerboard(6.dp),
                    )
                }
                // Fully qualified: inside FlowRow, the RowScope overload would win and not compile.
                androidx.compose.animation.AnimatedVisibility(
                    visible = i in copying,
                    enter = fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), initialScale = 0.6f),
                    exit = fadeOut(Motion.effects()),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    StickerTag("Copying…", Palette.Yellow, rotation = -4f)
                }
            }
        }
        // Keep the last row's tiles the same size as the others.
        repeat((perRow - previews.size % perRow) % perRow) { Box(Modifier.weight(1f)) }
    }
}

private const val COPY_PROGRESS_DELAY_MS = 400L

@Composable
private fun PackOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit,
    thumbs: StickerPack?,
    enabled: Boolean = true,
) {
    val colors = AppTheme.colors
    BrutalSurface(
        modifier = Modifier.fillMaxWidth().padding(end = Dimens.ShadowS),
        shape = Shapes.Medium,
        color = if (selected) color else colors.surface,
        borderWidth = if (selected) Dimens.BorderThick else Dimens.Border,
        shadow = if (selected) Dimens.ShadowM else Dimens.ShadowS,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            if (thumbs == null) {
                BrutalSurface(Modifier.size(48.dp), shape = Shapes.Small, color = colors.surface, shadow = 0.dp) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.align(Alignment.Center).size(24.dp))
                }
            } else {
                PackTray(thumbs, Modifier.size(48.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
            if (selected) StickerTag("✓", Palette.Yellow, rotation = 6f)
        }
    }
}
