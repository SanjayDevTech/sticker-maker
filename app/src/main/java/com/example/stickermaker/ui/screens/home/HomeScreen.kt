package com.example.stickermaker.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.stickermaker.LocalAppContainer
import com.example.stickermaker.R
import com.example.stickermaker.data.WhatsAppLimits
import com.example.stickermaker.ui.components.BrutalSurface
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.EmptyState
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.SectionTitle
import com.example.stickermaker.ui.components.StickerBottomSheet
import com.example.stickermaker.ui.components.StickerCard
import com.example.stickermaker.ui.components.ToastHost
import com.example.stickermaker.ui.components.rememberToastState
import com.example.stickermaker.ui.components.StickerTag
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.rememberCollapsed
import com.example.stickermaker.ui.components.staggeredEntrance
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.launch

/** Placeholder home: the real content arrives with the app's purpose. */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenCreate: () -> Unit,
) {
    val listState = rememberLazyListState()
    val toasts = rememberToastState()
    val scope = rememberCoroutineScope()
    var showStartSheet by rememberSaveable { mutableStateOf(false) }
    val session = LocalAppContainer.current.session
    // System photo picker: no storage permission, multi-select up to a full pack.
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = WhatsAppLimits.MAX_STICKERS),
    ) { uris ->
        if (uris.isNotEmpty()) {
            session.startNew(uris)
            onOpenCreate()
        }
    }

    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Sticker Maker",
                collapsed = rememberCollapsed(listState),
                actions = {
                    IconTile(R.drawable.ic_settings, "Settings", onOpenSettings, color = Palette.Yellow)
                },
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
            verticalArrangement = Arrangement.spacedBy(Dimens.Space24),
        ) {
            item(key = "headline", contentType = "headline") {
                Box(Modifier.fillMaxWidth()) {
                    Text("Make it\nstick.", style = MaterialTheme.typography.displayLarge)
                    StickerTag(
                        "soon",
                        Palette.Pink,
                        rotation = 8f,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 18.dp, end = 12.dp),
                    )
                }
            }
            item(key = "hero", contentType = "card") {
                StickerCard(
                    modifier = Modifier.staggeredEntrance(0).fillMaxWidth(),
                    color = Palette.Lavender,
                    shadow = Dimens.ShadowL,
                    verticalSpacing = Dimens.Space16,
                ) {
                    Text("Your sticker studio", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "This is the foundation. The real thing lands in the next iteration.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ChunkyButton(
                        text = "Start something",
                        onClick = { showStartSheet = true },
                        icon = R.drawable.ic_auto_awesome,
                        size = ChunkySize.Hero,
                        squiggly = true,
                        wobble = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Dimens.Space8, end = Dimens.ShadowL, bottom = Dimens.ShadowL),
                    )
                }
            }
            item(key = "recent-title", contentType = "section-title") { SectionTitle("Recent", Modifier.staggeredEntrance(1)) }
            item(key = "recent-empty", contentType = "empty-state") {
                EmptyState(
                    icon = R.drawable.ic_star,
                    title = "Nothing here yet",
                    message = "Stickers you make will pile up here.",
                    modifier = Modifier.staggeredEntrance(2),
                )
            }
        }
    }

    if (showStartSheet) {
        StickerBottomSheet(
            onDismissRequest = { showStartSheet = false },
            title = "Start from…",
        ) {
            StartOption(R.drawable.ic_image, "Photos", "Pick one or more, cut them out", Palette.Mint) {
                showStartSheet = false
                pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            StartOption(R.drawable.ic_bolt, "Some text", "Loud words, big fonts", Palette.Yellow) {
                showStartSheet = false
                scope.launch { toasts.show("Text is coming soon") }
            }
            StartOption(R.drawable.ic_sentiment_satisfied, "A blank canvas", "Doodle it", Palette.Peach) {
                showStartSheet = false
                scope.launch { toasts.show("Canvas is coming soon") }
            }
        }
    }
}

@Composable
private fun StartOption(icon: Int, title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    BrutalSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = Dimens.ShadowM),
        shape = Shapes.Medium,
        color = color,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space16),
        ) {
            BrutalSurface(Modifier.size(44.dp), shape = Shapes.Small, color = AppTheme.colors.surface, shadow = 0.dp) {
                Icon(painterResource(icon), null, Modifier.align(Alignment.Center).size(24.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
