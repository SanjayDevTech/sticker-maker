package com.example.stickermaker.ui.screens.pack

import com.example.stickermaker.whatsapp.WhatsApp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.R
import com.example.stickermaker.appViewModel
import com.example.stickermaker.data.Sticker
import com.example.stickermaker.data.StickerPack
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.BrutalTextField
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.FileImage
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.SkeletonBlock
import com.example.stickermaker.ui.components.StickerBottomSheet
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.ToastHost
import com.example.stickermaker.ui.components.checkerboard
import com.example.stickermaker.ui.components.rememberToastState
import com.example.stickermaker.ui.components.showUndo
import com.example.stickermaker.ui.components.staggeredEntrance
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.launch

/** One pack: its stickers, their emojis, and the way into WhatsApp. */
@Composable
fun PackScreen(packId: String, onBack: () -> Unit, onAddStickers: () -> Unit) {
    val vm = appViewModel(key = packId) { PackViewModel(packId, it.packs) }
    val state by vm.state.collectAsStateWithLifecycle()
    val container = LocalAppContainer.current
    val toasts = rememberToastState()
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val gridState = rememberLazyGridState()
    val collapseAt = with(LocalDensity.current) { 72.dp.roundToPx() }
    val collapsed by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > collapseAt }
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var emojiFor by rememberSaveable { mutableStateOf<String?>(null) }

    val pack = state.pack
    // Deleted (here or elsewhere): nothing to show.
    LaunchedEffect(state.loading, pack == null) { if (!state.loading && pack == null) onBack() }
    if (pack == null) {
        Box(Modifier.fillMaxSize()) { SkeletonBlock(Modifier.fillMaxSize().padding(Dimens.ScreenGutter), Shapes.Large) }
        return
    }

    val context = LocalContext.current
    // Whether WhatsApp already has this pack; re-checked whenever the pack changes or we return.
    var whatsApp by remember { mutableStateOf<WhatsApp.Status?>(null) }
    var checks by remember { mutableIntStateOf(0) }
    LaunchedEffect(pack.id, pack.imageDataVersion, pack.canAddToWhatsApp, checks) {
        whatsApp = WhatsApp.status(context, pack.id)
    }
    val addToWhatsApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        checks++
        val error = result.data?.getStringExtra(WhatsApp.EXTRA_VALIDATION_ERROR)
        scope.launch {
            when {
                result.resultCode == Activity.RESULT_OK -> toasts.show("Added to WhatsApp!")
                // WhatsApp's own reason, word for word: the most useful thing to see if it happens.
                error != null -> toasts.show("WhatsApp said: $error")
                else -> toasts.show("Not added")
            }
        }
    }
    fun launchAddToWhatsApp() {
        when (val status = whatsApp) {
            WhatsApp.Status.NotInstalled -> scope.launch { toasts.show("WhatsApp isn't installed") }
            WhatsApp.Status.Added -> scope.launch { toasts.show("Already in WhatsApp. Changes show up there on their own.") }
            is WhatsApp.Status.NotAdded -> try {
                addToWhatsApp.launch(WhatsApp.addIntent(context, pack.id, pack.name, status.onlyTo))
            } catch (_: ActivityNotFoundException) {
                scope.launch { toasts.show("This WhatsApp can't take sticker packs") }
            }
            null -> Unit
        }
    }

    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = WhatsAppLimits.MAX_STICKERS),
    ) { uris ->
        if (uris.isNotEmpty()) {
            container.session.startNew(uris, targetPackId = packId)
            onAddStickers()
        }
    }

    fun remove(sticker: Sticker) {
        scope.launch {
            val (removed, index) = vm.remove(sticker.id) ?: return@launch
            if (toasts.showUndo("Sticker removed")) vm.restore(removed, index)
        }
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            StickerTopBar(
                title = pack.name,
                collapsed = collapsed,
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
                actions = {
                    // Leaving happens on its own once the pack is gone (see above); Home offers Undo.
                    IconTile(R.drawable.ic_delete, "Delete pack", { scope.launch { vm.deletePack() } }, color = Palette.Coral)
                },
            )
        },
        snackbarHost = { ToastHost(toasts) },
    ) { padding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Dimens.ScreenGutter,
                end = Dimens.ScreenGutter + Dimens.ShadowL,
                top = padding.calculateTopPadding() + Dimens.Space8,
                bottom = padding.calculateBottomPadding() + Dimens.Space48,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Header(
                    pack = pack,
                    onRename = { renaming = true },
                    inWhatsApp = whatsApp == WhatsApp.Status.Added,
                    onAddToWhatsApp = ::launchAddToWhatsApp,
                )
            }
            itemsIndexed(pack.stickers, key = { _, it -> it.id }, contentType = { _, _ -> "sticker" }) { index, sticker ->
                StickerTile(
                    packId = pack.id,
                    sticker = sticker,
                    onClick = { emojiFor = sticker.id },
                    onLongClick = {
                        WhatsApp.copySticker(context, container.packs.stickerFile(pack.id, sticker.id))
                        scope.launch { toasts.show("Copied. Paste it in a chat.") }
                    },
                    onRemove = { remove(sticker) },
                    modifier = Modifier.animateItem(fadeInSpec = null).staggeredEntrance(index),
                )
            }
            if (!pack.isFull) {
                item(key = "add", contentType = "add") {
                    BrutalSurface(
                        modifier = Modifier.aspectRatio(1f).padding(end = Dimens.ShadowS, bottom = Dimens.ShadowS),
                        color = colors.surface,
                        shadow = Dimens.ShadowS,
                        onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onClickLabel = "Add stickers",
                    ) {
                        Icon(painterResource(R.drawable.ic_add), null, Modifier.align(Alignment.Center).size(32.dp))
                    }
                }
            }
        }
    }

    if (renaming) {
        RenameSheet(
            name = pack.name,
            onDismiss = { renaming = false },
            onSave = {
                vm.rename(it)
                renaming = false
            },
        )
    }
    val emojiSticker = pack.stickers.firstOrNull { it.id == emojiFor }
    if (emojiSticker != null) {
        EmojiSheet(
            packId = pack.id,
            sticker = emojiSticker,
            onDismiss = { emojiFor = null },
            onDone = { emojis ->
                vm.setEmojis(emojiSticker.id, emojis)
                emojiFor = null
            },
            onCopy = {
                WhatsApp.copySticker(context, container.packs.stickerFile(pack.id, emojiSticker.id))
                emojiFor = null
                scope.launch { toasts.show("Copied. Long-press a chat box and Paste.") }
            },
        )
    }
}

@Composable
private fun Header(pack: StickerPack, inWhatsApp: Boolean, onRename: () -> Unit, onAddToWhatsApp: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        modifier = Modifier.padding(bottom = Dimens.Space8),
    ) {
        BrutalSurface(
            shape = Shapes.Small,
            color = colors.canvas,
            borderWidth = 0.dp,
            shadow = 0.dp,
            onClick = onRename,
            onClickLabel = "Rename pack",
        ) {
            Text(pack.name, style = MaterialTheme.typography.displayMedium, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space8), verticalAlignment = Alignment.CenterVertically) {
            StickerTag("${pack.stickers.size} / ${WhatsAppLimits.MAX_STICKERS}", Palette.Yellow, rotation = -3f)
            if (inWhatsApp) {
                StickerTag("In WhatsApp", Palette.Mint, rotation = 2f)
            } else if (pack.canAddToWhatsApp) {
                StickerTag("Ready for WhatsApp", Palette.Lime, rotation = 2f)
            } else {
                StickerTag(
                    if (pack.stickersNeeded == 1) "Needs 1 more" else "Needs ${pack.stickersNeeded} more",
                    Palette.Peach,
                    rotation = 2f,
                )
            }
        }
        ChunkyButton(
            text = when {
                inWhatsApp -> "In WhatsApp"
                pack.canAddToWhatsApp -> "Add to WhatsApp"
                else -> "Add ${pack.stickersNeeded} more for WhatsApp"
            },
            onClick = onAddToWhatsApp,
            icon = R.drawable.ic_check,
            color = Palette.Lime,
            size = ChunkySize.Large,
            squiggly = pack.canAddToWhatsApp && !inWhatsApp,
            enabled = pack.canAddToWhatsApp,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.Space4, bottom = Dimens.ShadowM, end = Dimens.ShadowS),
        )
        Text(
            "Long-press a sticker to copy it, then paste into any WhatsApp chat. Tap one for its emojis; tap the name to rename.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkMuted,
        )
    }
}

@Composable
private fun StickerTile(
    packId: String,
    sticker: Sticker,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val packs = LocalAppContainer.current.packs
    Box(modifier.aspectRatio(1f)) {
        BrutalSurface(
            modifier = Modifier.fillMaxSize().padding(end = Dimens.ShadowS, bottom = Dimens.ShadowS),
            shape = Shapes.Medium,
            shadow = Dimens.ShadowS,
            onClick = onClick,
            onClickLabel = "Set emojis",
            onLongClick = onLongClick,
            onLongClickLabel = "Copy sticker",
        ) {
            FileImage(
                file = packs.stickerFile(packId, sticker.id),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().checkerboard(),
            )
        }
        StickerTag(
            sticker.emojis.joinToString(""),
            AppTheme.colors.surface,
            rotation = -6f,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 8.dp),
        )
        IconTile(
            R.drawable.ic_close,
            "Remove sticker",
            onRemove,
            size = 32.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp),
        )
    }
}

@Composable
private fun RenameSheet(name: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(name) }
    StickerBottomSheet(onDismissRequest = onDismiss, title = "Rename pack") {
        BrutalTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = "Pack name",
            maxLength = WhatsAppLimits.MAX_NAME_CHARS,
            onDone = { onSave(value) },
            modifier = Modifier.padding(end = Dimens.ShadowS),
        )
        ChunkyButton(
            text = "Save name",
            onClick = { onSave(value) },
            enabled = value.isNotBlank(),
            size = ChunkySize.Medium,
            modifier = Modifier.fillMaxWidth().padding(end = Dimens.ShadowM, top = Dimens.Space8),
        )
    }
}

/** Emojis people most often reach for in stickers; WhatsApp needs 1–3 per sticker. */
private val EMOJIS = listOf(
    "😂", "❤️", "😍", "🥰", "😎", "😭", "😡", "👍",
    "🙏", "🎉", "🔥", "😴", "🤔", "😱", "🥳", "😜",
    "🙂", "😅", "🤣", "💀", "👀", "💯", "🤝", "✨",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiSheet(
    packId: String,
    sticker: Sticker,
    onDismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
    onCopy: () -> Unit,
) {
    val packs = LocalAppContainer.current.packs
    val chosen = remember(sticker.id) { mutableStateListOf<String>().apply { addAll(sticker.emojis) } }
    StickerBottomSheet(onDismissRequest = onDismiss, title = "Emojis") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space16)) {
            BrutalSurface(Modifier.size(96.dp), shape = Shapes.Medium, shadow = Dimens.ShadowS) {
                FileImage(
                    file = packs.stickerFile(packId, sticker.id),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().checkerboard(6.dp),
                )
            }
            Text(
                "Pick up to ${WhatsAppLimits.MAX_EMOJIS}. WhatsApp suggests this sticker when you type them.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTheme.colors.inkMuted,
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(
            maxItemsInEachRow = 6,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space8),
            modifier = Modifier.padding(top = Dimens.Space8, end = Dimens.ShadowS),
        ) {
            EMOJIS.forEach { emoji ->
                val on = emoji in chosen
                BrutalSurface(
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    shape = Shapes.Small,
                    color = if (on) Palette.Yellow else AppTheme.colors.surface,
                    borderWidth = if (on) Dimens.BorderThick else Dimens.Border,
                    shadow = Dimens.ShadowS,
                    onClick = {
                        when {
                            on -> if (chosen.size > 1) chosen.remove(emoji)
                            chosen.size < WhatsAppLimits.MAX_EMOJIS -> chosen.add(emoji)
                            else -> {
                                chosen.removeAt(0)
                                chosen.add(emoji)
                            }
                        }
                    },
                    onClickLabel = emoji,
                ) {
                    Text(emoji, fontSize = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            modifier = Modifier.padding(end = Dimens.ShadowM, top = Dimens.Space8),
        ) {
            ChunkyButton(
                text = "Copy",
                onClick = onCopy,
                color = Palette.Sky,
                size = ChunkySize.Medium,
                modifier = Modifier.weight(1f),
            )
            ChunkyButton(
                text = "Done",
                onClick = { onDone(chosen.toList()) },
                icon = R.drawable.ic_check,
                size = ChunkySize.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
