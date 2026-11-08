package com.example.stickermaker.ui.screens.settings

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.stickermaker.R
import com.example.stickermaker.ui.components.ChunkyButton
import com.example.stickermaker.ui.components.ChunkySize
import com.example.stickermaker.ui.components.IconTile
import com.example.stickermaker.ui.components.SkeletonBlock
import com.example.stickermaker.ui.components.StickerBottomSheet
import com.example.stickermaker.ui.components.StickerCard
import com.example.stickermaker.ui.components.StickerTopBar
import com.example.stickermaker.ui.components.rememberCollapsed
import com.example.stickermaker.ui.components.staggeredEntrance
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Something the app is built from or ships, with the licence that lets it. */
private class Credit(val name: String, val what: String, val license: String, @RawRes val text: Int)

private val credits = listOf(
    Credit("Sticker Maker", "This app. © 2026 Sticker Maker contributors.", "MIT License", R.raw.license_mit),
    Credit("libwebp", "Encodes every sticker. © 2010 Google Inc.", "BSD 3-Clause, plus a patent grant", R.raw.license_libwebp),
    Credit("Outfit", "The typeface. © 2021 The Outfit Project Authors.", "SIL Open Font License 1.1", R.raw.license_outfit_ofl),
    Credit("Material Symbols", "The icons. © Google LLC.", "Apache License 2.0", R.raw.license_apache_2_0),
    Credit("AndroidX and Jetpack Compose", "The UI, navigation and storage. © The Android Open Source Project.", "Apache License 2.0", R.raw.license_apache_2_0),
    Credit("Kotlin, coroutines and serialization", "The language and its libraries. © JetBrains s.r.o. and contributors.", "Apache License 2.0", R.raw.license_apache_2_0),
)

/** Every licence the app owes a notice to, each readable in full. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    var reading by rememberSaveable { mutableStateOf<Int?>(null) }
    Scaffold(
        containerColor = AppTheme.colors.canvas,
        topBar = {
            StickerTopBar(
                title = "Licences",
                collapsed = rememberCollapsed(scrollState),
                navigation = { IconTile(R.drawable.ic_arrow_back, "Back", onBack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = Dimens.ScreenGutter,
                    end = Dimens.ScreenGutter + Dimens.ShadowL,
                    top = padding.calculateTopPadding() + Dimens.Space8,
                    bottom = padding.calculateBottomPadding() + Dimens.Space48,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space20),
        ) {
            Text("Licences", style = MaterialTheme.typography.displayMedium)
            Text(
                "Sticker Maker is free and open source, built on the work below.",
                style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.inkMuted,
            )
            credits.forEachIndexed { i, credit ->
                StickerCard(Modifier.staggeredEntrance(i).fillMaxWidth()) {
                    Text(credit.name, style = MaterialTheme.typography.titleLarge)
                    Text(credit.what, style = MaterialTheme.typography.bodyMedium, color = AppTheme.colors.inkMuted)
                    Text(credit.license, style = MaterialTheme.typography.titleSmall)
                    ChunkyButton(
                        text = "Read it",
                        onClick = { reading = i },
                        size = ChunkySize.Medium,
                        color = Palette.Sky,
                        modifier = Modifier.padding(top = Dimens.Space4, end = Dimens.ShadowM, bottom = Dimens.ShadowM),
                    )
                }
            }
        }
    }
    reading?.let { i -> LicenseSheet(credits[i], onDismiss = { reading = null }) }
}

@Composable
private fun LicenseSheet(credit: Credit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null, credit.text) {
        value = withContext(Dispatchers.IO) {
            context.resources.openRawResource(credit.text).bufferedReader().use { it.readText() }
        }
    }
    StickerBottomSheet(onDismissRequest = onDismiss, title = credit.name) {
        Text(credit.license, style = MaterialTheme.typography.titleMedium)
        val body = text
        if (body == null) {
            SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), Shapes.Medium)
        } else {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            )
        }
    }
}
