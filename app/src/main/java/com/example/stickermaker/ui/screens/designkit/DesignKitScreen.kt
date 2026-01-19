package com.example.stickermaker.ui.screens.designkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.EmptyState
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.SectionTitle
import com.example.stickermaker.ui.components.SegmentedPicker
import com.example.stickermaker.ui.components.SkeletonBlock
import com.example.stickermaker.ui.components.StickerCard
import com.example.stickermaker.ui.components.StickerSnackbarHost
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.rememberCollapsed
import com.example.stickermaker.ui.components.sharedBlock
import com.example.stickermaker.ui.components.showUndo
import com.example.stickermaker.ui.components.staggeredEntrance
import com.example.stickermaker.ui.screens.settings.DESIGN_KIT_SHARED_KEY
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.launch

private data class DemoItem(val id: Int, val name: String)

private val DemoItems = listOf("Grumpy cat", "Pizza slice", "Big NOPE", "Tiny cactus", "Hot take")
    .mapIndexed { i, name -> DemoItem(i, name) }

/** Live gallery of the design system. Handy while designing real screens; delete when done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DesignKitScreen(onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf(*DemoItems.toTypedArray()) }
    var segment by rememberSaveable { mutableStateOf("Likes") }

    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Design kit",
                collapsed = rememberCollapsed(listState, thresholdDp = 140),
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
            )
        },
        snackbarHost = { StickerSnackbarHost(snackbar) },
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
            item(key = "header") {
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

            item(key = "buttons-title") { SectionTitle("Buttons", Modifier.staggeredEntrance(0)) }
            item(key = "buttons") {
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

            item(key = "picker-title") { SectionTitle("Picker", Modifier.staggeredEntrance(2)) }
            item(key = "picker") {
                SegmentedPicker(
                    options = listOf("Likes", "Matches", "Dates"),
                    selected = segment,
                    onSelect = { segment = it },
                    label = { it },
                    modifier = Modifier.staggeredEntrance(3),
                )
            }

            item(key = "blocks-title") { SectionTitle("Blocks & tags", Modifier.staggeredEntrance(4)) }
            item(key = "blocks") {
                FlowRow(
                    modifier = Modifier.staggeredEntrance(5),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
                    maxItemsInEachRow = 3,
                ) {
                    AppTheme.colors.blocks.forEachIndexed { i, color ->
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
            item(key = "tags") {
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                    StickerTag("new", Palette.Yellow)
                    StickerTag("hot", Palette.Coral, rotation = 3f)
                    StickerTag("beta", Palette.Mint, rotation = -2f)
                }
            }

            item(key = "undo-title") { SectionTitle("Remove + undo", Modifier.padding(top = Dimens.Space8)) }
            items(items, key = { it.id }) { item ->
                BrutalSurface(
                    modifier = Modifier
                        .animateItem()
                        .fillMaxWidth(),
                    color = AppTheme.colors.block(item.id),
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
                                    if (snackbar.showUndo("${item.name} removed")) {
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
                item(key = "undo-empty") {
                    ChunkyButton(
                        "Bring them back",
                        onClick = { items.addAll(DemoItems) },
                        icon = R.drawable.ic_undo,
                        size = ChunkySize.Medium,
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            item(key = "loading-title") { SectionTitle("Loading", Modifier.padding(top = Dimens.Space8)) }
            item(key = "loading") {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(72.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Space12)) {
                        SkeletonBlock(Modifier.weight(1f).height(110.dp), Shapes.Large)
                        SkeletonBlock(Modifier.weight(1f).height(110.dp), Shapes.Large)
                    }
                }
            }

            item(key = "empty-title") { SectionTitle("Empty state", Modifier.padding(top = Dimens.Space8)) }
            item(key = "empty") {
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
