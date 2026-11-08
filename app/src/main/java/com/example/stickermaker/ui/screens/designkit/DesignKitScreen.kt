package com.example.stickermaker.ui.screens.designkit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.stickermaker.R
import com.example.stickermaker.ui.components.BrutalSlider
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.BrutalTextField
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.EmptyState
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.LoadRing
import com.example.stickermaker.ui.components.SectionTitle
import com.example.stickermaker.ui.components.SegmentedPicker
import com.example.stickermaker.ui.components.SkeletonBlock
import com.example.stickermaker.ui.components.StickerCard
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.ToastHost
import com.example.stickermaker.ui.components.dragLift
import com.example.stickermaker.ui.components.gridDragReorder
import com.example.stickermaker.ui.components.rememberCollapsed
import com.example.stickermaker.ui.components.rememberGridDragState
import com.example.stickermaker.ui.components.rememberToastState
import com.example.stickermaker.ui.components.sharedBlock
import com.example.stickermaker.ui.components.showUndo
import com.example.stickermaker.ui.components.staggeredEntrance
import com.example.stickermaker.ui.screens.settings.DESIGN_KIT_SHARED_KEY
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.LocalReduceMotion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class DemoItem(val id: Int, val name: String)

private val DemoItems = listOf("Grumpy cat", "Pizza slice", "Big NOPE", "Tiny cactus", "Hot take")
    .mapIndexed { i, name -> DemoItem(i, name) }

/** Live gallery of the design system. Handy while designing real screens; delete when done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignKitScreen(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val toasts = rememberToastState()
    var toastCount by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf(*DemoItems.toTypedArray()) }
    var segment by rememberSaveable { mutableStateOf("Likes") }
    val colors = AppTheme.colors // read once, not per lazy item

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Design kit",
                collapsed = rememberCollapsed(listState, thresholdDp = 140),
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
            )
        },
        snackbarHost = { ToastHost(toasts) },
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
            item(key = "header", contentType = "demo-block") {
                // Same shared key as the Settings row: the pink block morphs into this header.
                BrutalSurface(
                    modifier = Modifier
                        .sharedBlock(DESIGN_KIT_SHARED_KEY)
                        .fillMaxWidth(),
                    shape = Shapes.Large,
                    color = Palette.Pink,
                    shadow = Dimens.ShadowL,
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(R.drawable.ic_palette), null, Modifier.size(40.dp))
                        Text("Design kit", style = MaterialTheme.typography.displaySmall)
                        Text("Every component, live.", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            item(key = "buttons-title", contentType = "section-title") { SectionTitle("Buttons", Modifier.staggeredEntrance(0)) }
            item(key = "buttons", contentType = "demo-block") {
                Column(
                    Modifier.staggeredEntrance(1),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
                ) {
                    ChunkyButton(
                        "Squiggly hero", onClick = {}, icon = R.drawable.ic_auto_awesome,
                        size = ChunkySize.Hero, squiggly = true, wobble = true,
                        color = Palette.Lime, modifier = Modifier.fillMaxWidth(),
                    )
                    ChunkyButton("Large", onClick = {}, icon = R.drawable.ic_add, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space16)) {
                        ChunkyButton("Medium", onClick = {}, size = ChunkySize.Medium, color = Palette.Sky)
                        ChunkyButton("Disabled", onClick = {}, size = ChunkySize.Medium, enabled = false)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                        IconTile(R.drawable.ic_favorite, "Like", {}, color = Palette.Pink)
                        IconTile(R.drawable.ic_star, "Star", {}, color = Palette.Orange)
                        IconTile(R.drawable.ic_bolt, "Boost", {}, color = Palette.Yellow)
                        IconTile(R.drawable.ic_close, "Close", {})
                    }
                }
            }

            item(key = "picker-title", contentType = "section-title") { SectionTitle("Picker", Modifier.staggeredEntrance(2)) }
            item(key = "picker", contentType = "demo-block") {
                SegmentedPicker(
                    options = listOf("Likes", "Matches", "Dates"),
                    selected = segment,
                    onSelect = { segment = it },
                    label = { it },
                    modifier = Modifier.staggeredEntrance(3),
                )
            }

            item(key = "slider-title", contentType = "section-title") { SectionTitle("Slider", Modifier.padding(top = Dimens.Space8)) }
            item(key = "slider", contentType = "demo-block") {
                var level by remember { mutableFloatStateOf(0.4f) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                    BrutalSlider(
                        value = level,
                        onValueChange = { level = it },
                        valueLabel = "${(level * 100).roundToInt()}%",
                        onDecrease = { level = (((level * 100).roundToInt() - 1) / 100f).coerceIn(0f, 1f) },
                        onIncrease = { level = (((level * 100).roundToInt() + 1) / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item(key = "drag-title", contentType = "section-title") { SectionTitle("Drag to reorder", Modifier.padding(top = Dimens.Space8)) }
            item(key = "drag", contentType = "demo-block") { DragDemo() }

            item(key = "field-title", contentType = "section-title") { SectionTitle("Text field", Modifier.padding(top = Dimens.Space8)) }
            item(key = "field", contentType = "demo-block") {
                var text by rememberSaveable { mutableStateOf("") }
                BrutalTextField(value = text, onValueChange = { text = it }, placeholder = "Name your pack", modifier = Modifier.padding(end = Dimens.ShadowS))
            }

            item(key = "blocks-title", contentType = "section-title") { SectionTitle("Blocks & tags", Modifier.staggeredEntrance(4)) }
            item(key = "blocks", contentType = "demo-block") {
                FlowRow(
                    modifier = Modifier.staggeredEntrance(5),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
                    maxItemsInEachRow = 3,
                ) {
                    colors.blocks.forEachIndexed { i, color ->
                        StickerCard(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = Dimens.ShadowS, bottom = Dimens.ShadowS),
                            color = color,
                            shape = Shapes.Medium,
                            shadow = Dimens.ShadowS,
                            contentPadding = PaddingValues(14.dp),
                            onClick = {},
                        ) {
                            Text("#${i + 1}", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            item(key = "tags", contentType = "demo-block") {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                    StickerTag("new", Palette.Yellow)
                    StickerTag("hot", Palette.Coral, rotation = 3f)
                    StickerTag("beta", Palette.Mint, rotation = -2f)
                }
            }

            item(key = "toasts-title", contentType = "section-title") { SectionTitle("Toasts", Modifier.padding(top = Dimens.Space8)) }
            item(key = "toasts", contentType = "demo-block") {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
                    Text(
                        "Swipe left or right to clear. Three count down at once; the next one peeks out from behind, the rest wait hidden.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkMuted,
                    )
                    ChunkyButton(
                        "Pop a toast",
                        onClick = {
                            val n = ++toastCount
                            scope.launch { toasts.show("Toast #$n — swipe me away") }
                        },
                        icon = R.drawable.ic_bolt,
                        size = ChunkySize.Medium,
                        color = Palette.Yellow,
                    )
                }
            }

            item(key = "undo-title", contentType = "section-title") { SectionTitle("Remove + undo", Modifier.padding(top = Dimens.Space8)) }
            items(items, key = { it.id }, contentType = { "demo-row" }) { item ->
                BrutalSurface(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth(),
                    color = colors.block(item.id),
                    shadow = Dimens.ShadowS,
                ) {
                    Row(
                        Modifier.padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconTile(
                            R.drawable.ic_delete,
                            "Remove ${item.name}",
                            onClick = {
                                val index = items.indexOf(item)
                                items.remove(item)
                                scope.launch {
                                    if (toasts.showUndo("${item.name} removed")) {
                                        items.add(index.coerceAtMost(items.size), item)
                                    }
                                }
                            },
                            size = 40.dp,
                        )
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "undo-empty", contentType = "demo-block") {
                    ChunkyButton(
                        "Bring them back",
                        onClick = { items.addAll(DemoItems) },
                        icon = R.drawable.ic_undo,
                        size = ChunkySize.Medium,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item(key = "loading-title", contentType = "section-title") { SectionTitle("Loading", Modifier.padding(top = Dimens.Space8)) }
            item(key = "loading", contentType = "demo-block") {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(72.dp))
                    // A skeleton with progress (e.g. a video loading), filling over and over.
                    val reduceMotion = LocalReduceMotion.current
                    val demoProgress = if (reduceMotion) {
                        remember { mutableStateOf<Float?>(0.6f) }
                    } else {
                        val t = rememberInfiniteTransition(label = "load").animateFloat(
                            0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "progress",
                        )
                        remember(t) { derivedStateOf<Float?> { t.value } }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                        SkeletonBlock(Modifier.weight(1f).height(110.dp), Shapes.Large)
                        SkeletonBlock(Modifier.weight(1f).height(110.dp), Shapes.Large, demoProgress)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12), verticalAlignment = Alignment.CenterVertically) {
                        LoadRing({ 0.25f })
                        LoadRing({ 0.6f })
                        LoadRing({ 1f })
                        LoadRing({ 0.4f }, size = 28.dp)
                    }
                }
            }

            item(key = "empty-title", contentType = "section-title") { SectionTitle("Empty state", Modifier.padding(top = Dimens.Space8)) }
            item(key = "empty", contentType = "demo-block") {
                EmptyState(
                    icon = R.drawable.ic_image,
                    title = "No stickers yet",
                    message = "Make one and it shows up here.",
                    color = Palette.Peach,
                    action = {
                        ChunkyButton("Make one", onClick = {}, icon = R.drawable.ic_add, size = ChunkySize.Medium)
                    },
                )
            }
        }
    }
}

private val DragColors = listOf(
    Palette.Pink, Palette.Yellow, Palette.Lime, Palette.Sky,
    Palette.Lavender, Palette.Peach, Palette.Mint, Palette.Coral,
)

/** The Arrange screen's long-press drag, on a fixed 4 × 2 grid of numbered tiles. */
@Composable
private fun DragDemo() {
    val tiles = remember { mutableStateListOf(*DragColors.indices.toList().toTypedArray()) }
    val grid = rememberLazyGridState()
    val drag = rememberGridDragState(
        grid = grid,
        isDraggable = { true },
        onMove = { from, to -> tiles.add(tiles.indexOf(to as Int), tiles.removeAt(tiles.indexOf(from as Int))) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space8)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A lazy grid inside the LazyColumn needs a fixed height: two rows of square tiles.
            val tile = (maxWidth - Dimens.Space12 * (DRAG_COLUMNS - 1)) / DRAG_COLUMNS
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Fixed(DRAG_COLUMNS),
                userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
                verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
                modifier = Modifier
                    .height(tile * 2 + Dimens.Space12)
                    .gridDragReorder(drag),
            ) {
                gridItems(tiles, key = { it }, contentType = { "tile" }) { id ->
                    BrutalSurface(
                        modifier = (if (drag.draggingKey == id) Modifier.dragLift(drag) else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                            .aspectRatio(1f)
                            .padding(end = Dimens.ShadowS, bottom = Dimens.ShadowS),
                        shape = Shapes.Medium,
                        color = DragColors[id],
                        shadow = Dimens.ShadowS,
                    ) {
                        Text("${id + 1}", style = MaterialTheme.typography.headlineMedium, color = AppTheme.colors.onBlock, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
        Text("Long-press a tile, then drag it.", style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.inkMuted)
    }
}

private const val DRAG_COLUMNS = 4
