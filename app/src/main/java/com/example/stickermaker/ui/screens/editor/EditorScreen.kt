package com.example.stickermaker.ui.screens.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stickermaker.R
import com.example.stickermaker.appViewModel
import com.example.stickermaker.data.SessionImage
import com.example.stickermaker.data.SessionMode
import com.example.stickermaker.ui.components.BrutalSlider
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.EmptyState
import com.example.stickermaker.ui.components.FileImage
import com.example.stickermaker.ui.components.GifTag
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.SegmentedPicker
import com.example.stickermaker.ui.components.SkeletonBlock
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.ToastHost
import com.example.stickermaker.ui.components.checkerboard
import com.example.stickermaker.ui.components.rememberToastState
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Cut each picked image out: brush or colour-fill away the background, restore mistakes. */
@Composable
fun EditorScreen(onBack: () -> Unit, onCombine: () -> Unit, onSave: () -> Unit) {
    val vm = appViewModel { EditorViewModel(it.session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val view = remember(state.index) { CanvasView() }
    val toasts = rememberToastState()
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val count = state.images.size
    // GIFs: the moment a trim edge is being dragged to (the player holds it), and where it plays.
    var scrubMs by remember { mutableStateOf<Int?>(null) }
    val playhead = remember { mutableIntStateOf(0) }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            StickerTopBar(
                title = if (count > 1) "Cut out ${state.index + 1}/$count" else "Cut out",
                collapsed = true,
                navigation = {
                    IconTile(R.drawable.ic_arrow_back, "Back", {
                        vm.saveCurrent()
                        onBack()
                    })
                },
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
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            val gif = state.gif
            val trim = state.trim
            if (state.isGif) {
                BrutalSurface(Modifier.weight(1f).fillMaxWidth(), shape = Shapes.Large, color = Palette.Lavender, shadow = Dimens.ShadowM) {
                    when {
                        gif != null && trim != null -> GifPlayer(gif, trim, scrubMs, playhead, Modifier.fillMaxSize().padding(Dimens.Space16))
                        state.failed -> CantOpen(Modifier.align(Alignment.Center))
                        else -> SkeletonBlock(Modifier.fillMaxSize().padding(Dimens.Space24), Shapes.Medium)
                    }
                }
                if (gif != null && trim != null) TrimPanel(gif, trim, vm::setTrim, { scrubMs = it }, playhead)
            } else {
                Stage(
                    state = state,
                    view = view,
                    vm = vm,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                ToolPanel(state, vm, view)
            }
            if (count > 1) Filmstrip(state.images, state.index, vm::select)
            if (count == 1) {
                // One image: save it auto-fitted, or arrange it first (size, turn, flip, motion).
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                    ChunkyButton(
                        text = "Arrange",
                        onClick = {
                            vm.saveCurrent()
                            vm.useMode(SessionMode.Combine)
                            onCombine()
                        },
                        icon = R.drawable.ic_open_with,
                        size = ChunkySize.Large,
                        color = Palette.Sky,
                        enabled = state.ready,
                    )
                    ChunkyButton(
                        text = "Save sticker",
                        onClick = {
                            vm.saveCurrent()
                            vm.useMode(SessionMode.Separate)
                            onSave()
                        },
                        icon = R.drawable.ic_check,
                        size = ChunkySize.Large,
                        enabled = state.ready,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                ChunkyButton(
                    text = when {
                        !state.isLast -> "Next image"
                        state.mode == SessionMode.Combine -> "Combine"
                        else -> "Save stickers"
                    },
                    onClick = {
                        if (!state.isLast) {
                            vm.next()
                        } else {
                            vm.saveCurrent()
                            if (state.mode == SessionMode.Combine) onCombine() else onSave()
                        }
                    },
                    icon = if (state.isLast) R.drawable.ic_check else R.drawable.ic_arrow_forward,
                    size = ChunkySize.Large,
                    enabled = state.ready,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Stage(state: EditorUiState, view: CanvasView, vm: EditorViewModel, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val moved by remember(view) { derivedStateOf { view.isMoved } }
    val zoomLabel by remember(view) { derivedStateOf { "${view.zoom.roundToInt()}×" } }
    BrutalSurface(modifier = modifier, shape = Shapes.Large, color = Palette.Lavender, shadow = Dimens.ShadowM) {
        val engine = state.engine
        when {
            engine != null -> {
                EditorCanvas(
                    engine = engine,
                    view = view,
                    tool = state.tool,
                    erase = state.erase,
                    brushPx = if (state.tool == EditTool.Magic) state.markerPx else state.brushPx,
                    onBeginStroke = vm::beginStroke,
                    onExtendStroke = vm::extendStroke,
                    onEndStroke = vm::endStroke,
                    onCancelStroke = vm::cancelStroke,
                    onFill = vm::fill,
                    modifier = Modifier.fillMaxSize(),
                )
                AnimatedVisibility(
                    visible = moved,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Dimens.Space12),
                    enter = fadeIn(Motion.effects()) + scaleIn(Motion.spatialBouncy()),
                    exit = fadeOut(Motion.effects()) + scaleOut(Motion.spatialFast()),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space8), verticalAlignment = Alignment.CenterVertically) {
                        StickerTag(zoomLabel, Palette.Yellow, rotation = -4f)
                        IconTile(R.drawable.ic_fit_screen, "Fit to screen", { scope.launch { view.reset() } }, size = 40.dp)
                    }
                }
                CanvasMessage(
                    message = canvasMessage(state),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(Dimens.Space12),
                )
            }
            state.failed -> CantOpen(Modifier.align(Alignment.Center))
            else -> SkeletonBlock(Modifier.fillMaxSize().padding(Dimens.Space24), Shapes.Medium)
        }
    }
}

@Composable
private fun CantOpen(modifier: Modifier) = EmptyState(
    icon = R.drawable.ic_image,
    title = "Can't open this one",
    message = "The image couldn't be read. Go back and remove it.",
    modifier = modifier,
)

/** One tag at the bottom of the canvas: what's happening now, or how to start. */
private data class Message(val text: String, val color: Color)

@Composable
private fun canvasMessage(state: EditorUiState): Message? {
    val surface = AppTheme.colors.surface
    return when {
        state.magicBusy -> Message("Finding the subject…", Palette.Sky)
        state.tool == EditTool.Magic && state.magicMarks == 0 ->
            Message(if (state.erase) "Switch to Keep and scribble on the subject" else "Scribble over the subject", surface)
        state.tool == EditTool.Magic && !state.touched -> null
        state.tool == EditTool.Auto && state.adjustingFill -> Message("Match is adjusting your last fill", Palette.Lime)
        state.touched -> null
        state.tool == EditTool.Brush ->
            Message(if (state.erase) "Drag to erase · pinch to zoom" else "Drag to restore · pinch to zoom", surface)
        else -> Message(if (state.erase) "Tap a colour to erase it" else "Tap a colour to restore it", surface)
    }
}

@Composable
private fun CanvasMessage(message: Message?, modifier: Modifier) {
    AnimatedContent(
        targetState = message,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(Motion.effects()) + scaleIn(Motion.spatialBouncy(), initialScale = 0.8f)) togetherWith
                (fadeOut(Motion.effects()) + scaleOut(Motion.spatialFast(), targetScale = 0.8f))
        },
        contentAlignment = Alignment.BottomCenter,
        label = "canvasMessage",
    ) { m ->
        if (m != null) StickerTag(m.text, m.color, rotation = -2f)
    }
}

@Composable
private fun ToolPanel(state: EditorUiState, vm: EditorViewModel, view: CanvasView) {
    val magic = state.tool == EditTool.Magic
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
        SegmentedPicker(
            options = EditTool.entries,
            selected = state.tool,
            onSelect = vm::setTool,
            label = {
                when (it) {
                    EditTool.Brush -> "Brush"
                    EditTool.Auto -> "Auto"
                    EditTool.Magic -> "Magic"
                }
            },
            icon = {
                when (it) {
                    EditTool.Brush -> R.drawable.ic_brush
                    EditTool.Auto -> R.drawable.ic_format_color_fill
                    EditTool.Magic -> R.drawable.ic_auto_awesome
                }
            },
            selectedColor = Palette.Yellow,
        )
        // Every tool has an inverse. For Magic it decides what a scribble marks.
        SegmentedPicker(
            options = listOf(true, false),
            selected = state.erase,
            onSelect = vm::setErase,
            label = {
                when {
                    magic -> if (it) "Remove" else "Keep"
                    else -> if (it) "Erase" else "Restore"
                }
            },
            icon = { if (it) R.drawable.ic_ink_eraser else R.drawable.ic_healing },
            selectedColor = when {
                state.erase -> Palette.Coral
                magic -> Palette.Lime
                else -> Palette.Mint
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
            val sized = state.tool != EditTool.Auto
            Text(
                if (sized) "Size" else "Match",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(48.dp),
            )
            val valueText = when (state.tool) {
                EditTool.Brush -> "${state.brushPx} px"
                EditTool.Magic -> "${state.markerPx} px"
                EditTool.Auto -> "${(state.tolerance * 100).roundToInt()}%"
            }
            BrutalSlider(
                value = when (state.tool) {
                    EditTool.Brush -> state.brush
                    EditTool.Magic -> state.marker
                    EditTool.Auto -> state.tolerance
                },
                onValueChange = if (sized) vm::setBrush else vm::setTolerance,
                valueLabel = valueText,
                onDecrease = { if (sized) vm.nudgeSize(-1) else vm.nudgeTolerance(-1) },
                onIncrease = { if (sized) vm.nudgeSize(1) else vm.nudgeTolerance(1) },
                color = when (state.tool) {
                    EditTool.Brush -> Palette.Pink
                    EditTool.Magic -> Palette.Sky
                    EditTool.Auto -> Palette.Lime
                },
                onActiveChange = { view.previewBrush = it && sized },
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(52.dp),
            )
        }
    }
}

@Composable
private fun Filmstrip(images: List<SessionImage>, selected: Int, onSelect: (Int) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { listState.animateScrollToItem((selected - 2).coerceAtLeast(0)) }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Space8),
        contentPadding = PaddingValues(end = Dimens.ShadowS, bottom = Dimens.ShadowS, top = Dimens.Space4),
    ) {
        itemsIndexed(images, key = { _, it -> it.id }, contentType = { _, _ -> "thumb" }) { i, image ->
            val isSelected = i == selected
            BrutalSurface(
                modifier = Modifier.size(56.dp),
                shape = Shapes.Small,
                color = if (isSelected) Palette.Yellow else AppTheme.colors.surface,
                borderWidth = if (isSelected) Dimens.BorderThick else Dimens.Border,
                shadow = Dimens.ShadowS,
                onClick = { onSelect(i) },
                onClickLabel = "Edit image ${i + 1}",
            ) {
                Box(Modifier.fillMaxSize().padding(if (isSelected) 5.dp else 0.dp)) {
                    FileImage(
                        file = image.file,
                        maxPx = 160,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().checkerboard(6.dp),
                    )
                }
                if (image.isGif) GifTag(Modifier.align(Alignment.BottomEnd))
            }
        }
    }
}
