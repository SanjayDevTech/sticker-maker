package com.example.stickermaker.ui.screens.create

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.R
import com.example.stickermaker.data.SessionImage
import com.example.stickermaker.data.SessionMode
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.EmptyState
import com.example.stickermaker.ui.components.FileImage
import com.example.stickermaker.ui.components.GifTag
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.dragLift
import com.example.stickermaker.ui.components.gridDragReorder
import com.example.stickermaker.ui.components.rememberGridDragState
import com.example.stickermaker.ui.components.SegmentedPicker
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

/** Step one of making stickers: see what was picked/shared, drop or add images, set the order. */
@Composable
fun ArrangeScreen(onBack: () -> Unit, onCutOut: () -> Unit) {
    val session = LocalAppContainer.current.session
    val images by session.images.collectAsStateWithLifecycle()
    val importing by session.importing.collectAsStateWithLifecycle()
    val mode by session.mode.collectAsStateWithLifecycle()
    val toasts = rememberToastState()
    val scope = rememberCoroutineScope()
    val colors = AppTheme.colors
    val gridState = rememberLazyGridState()
    val collapseAt = with(LocalDensity.current) { 72.dp.roundToPx() }
    val collapsed by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > collapseAt }
    }

    LaunchedEffect(session) {
        session.importFailures.collect { failed ->
            launch { toasts.show(if (failed == 1) "Couldn't open one image" else "Couldn't open $failed images") }
        }
    }

    val addMore = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = WhatsAppLimits.MAX_STICKERS),
    ) { uris -> session.add(uris) }
    fun launchAddMore() = addMore.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    val drag = rememberGridDragState(
        grid = gridState,
        isDraggable = { key -> key is String && session.images.value.any { it.id == key } },
        onMove = { from, to ->
            val list = session.images.value
            session.move(list.indexOfFirst { it.id == from }, list.indexOfFirst { it.id == to })
        },
    )

    fun remove(image: SessionImage, index: Int) {
        session.remove(image.id)
        scope.launch { if (toasts.showUndo("Image removed")) session.restore(image, index) }
    }

    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Your photos",
                collapsed = collapsed,
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
            )
        },
        snackbarHost = { ToastHost(toasts) },
        bottomBar = {
            if (images.isNotEmpty()) {
                ChunkyButton(
                    text = when {
                        images.size == 1 -> "Cut it out"
                        mode == SessionMode.Combine -> "Cut out & combine"
                        else -> "Cut them out"
                    },
                    onClick = onCutOut,
                    enabled = importing == 0,
                    icon = R.drawable.ic_auto_awesome,
                    size = ChunkySize.Large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = Dimens.ScreenGutter, end = Dimens.ScreenGutter + Dimens.ShadowM, bottom = 12.dp + Dimens.ShadowM),
                )
            }
        },
    ) { padding ->
        if (images.isEmpty() && importing == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = R.drawable.ic_image,
                    title = "No images",
                    message = "Pick a few photos to turn into stickers.",
                    action = { ChunkyButton("Pick photos", onClick = ::launchAddMore, icon = R.drawable.ic_add, size = ChunkySize.Medium) },
                )
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .gridDragReorder(drag),
            contentPadding = PaddingValues(
                start = Dimens.ScreenGutter,
                end = Dimens.ScreenGutter + Dimens.ShadowM,
                top = padding.calculateTopPadding() + Dimens.Space8,
                bottom = padding.calculateBottomPadding() + Dimens.Space24,
            ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.Space8), modifier = Modifier.padding(bottom = Dimens.Space8)) {
                    Text("Your photos", style = MaterialTheme.typography.displayMedium)
                    if (images.size > 1) {
                        SegmentedPicker(
                            options = SessionMode.entries,
                            selected = mode,
                            onSelect = { session.mode.value = it },
                            label = { if (it == SessionMode.Separate) "Separate stickers" else "Combine into one" },
                            modifier = Modifier.padding(top = Dimens.Space8, end = Dimens.ShadowS, bottom = Dimens.Space4),
                        )
                    }
                    Text(
                        when {
                            images.size <= 1 -> "Each image becomes one sticker. Long-press to drag."
                            mode == SessionMode.Combine ->
                                "All images become one sticker. This is the starting stack (1 at the back); you can restack in the editor."
                            else -> "Each image becomes its own sticker, in this order. Long-press to drag."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.inkMuted,
                    )
                }
            }
            itemsIndexed(images, key = { _, it -> it.id }, contentType = { _, _ -> "image" }) { index, image ->
                val dragging = drag.draggingKey == image.id
                ImageTile(
                    image = image,
                    position = index + 1,
                    onRemove = { remove(image, index) },
                    modifier = if (dragging) {
                        Modifier.dragLift(drag)
                    } else {
                        Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                    },
                )
            }
            items(importing) { SkeletonBlock(Modifier.aspectRatio(1f), Shapes.Medium) }
            if (images.size + importing < WhatsAppLimits.MAX_STICKERS) {
                item(key = "add", contentType = "add") {
                    BrutalSurface(
                        modifier = Modifier.aspectRatio(1f),
                        color = colors.surface,
                        shadow = Dimens.ShadowS,
                        onClick = ::launchAddMore,
                        onClickLabel = "Add more images",
                    ) {
                        Icon(painterResource(R.drawable.ic_add), null, Modifier.align(Alignment.Center).size(32.dp))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.items(count: Int, content: @Composable () -> Unit) {
    repeat(count) { i -> item(key = "importing-$i", contentType = "skeleton") { content() } }
}

@Composable
private fun ImageTile(
    image: SessionImage,
    position: Int,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.aspectRatio(1f)) {
        BrutalSurface(
            modifier = Modifier.fillMaxSize().padding(end = Dimens.ShadowS, bottom = Dimens.ShadowS),
            shape = Shapes.Medium,
            shadow = Dimens.ShadowS,
        ) {
            FileImage(
                file = image.file,
                modifier = Modifier.fillMaxSize().checkerboard(),
                contentScale = ContentScale.Fit,
                animated = image.isGif,
            )
        }
        StickerTag(
            "$position",
            Palette.Yellow,
            rotation = -6f,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        )
        if (image.isGif) GifTag(Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 8.dp))
        IconTile(
            R.drawable.ic_close,
            "Remove image $position",
            onRemove,
            size = 32.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 8.dp),
        )
    }
}
