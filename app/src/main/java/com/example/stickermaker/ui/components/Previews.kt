package com.example.stickermaker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.stickermaker.R
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.StickerTheme

@Composable
private fun ComponentSampler() {
    var selected by remember { mutableStateOf("Two") }
    Column(
        Modifier.padding(20.dp).padding(end = Dimens.ShadowL),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ChunkyButton("Squiggly", onClick = {}, squiggly = true, size = ChunkySize.Hero, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChunkyButton("Medium", onClick = {}, size = ChunkySize.Medium, color = Palette.Sky)
            IconTile(R.drawable.ic_favorite, "Like", {}, color = Palette.Pink)
            StickerTag("new", Palette.Yellow)
        }
        SegmentedPicker(listOf("One", "Two", "Three"), selected, { selected = it }, { it })
        StickerCard(Modifier.fillMaxWidth(), color = Palette.Lavender) {
            Text("Sticker card", style = MaterialTheme.typography.headlineSmall)
            Text("Body copy on a pastel block.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Light", showBackground = true, widthDp = 380)
@Composable
private fun ComponentsLightPreview() = StickerTheme(darkTheme = false) { ComponentSampler() }

@Preview(name = "Dark", showBackground = true, widthDp = 380)
@Composable
private fun ComponentsDarkPreview() = StickerTheme(darkTheme = true) { ComponentSampler() }

@Preview(name = "Empty state", showBackground = true, widthDp = 380)
@Composable
private fun EmptyStatePreview() = StickerTheme(darkTheme = false) {
    EmptyState(R.drawable.ic_star, "Nothing here yet", "Stickers you make will pile up here.")
}
