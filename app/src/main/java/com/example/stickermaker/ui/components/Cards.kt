package com.example.stickermaker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

/** Padded brutal block holding a column. Clickable when [onClick] is set. */
@Composable
fun StickerCard(
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.surface,
    shape: Shape = Shapes.Large,
    shadow: Dp = Dimens.ShadowM,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    verticalSpacing: Dp = Dimens.Space12,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BrutalSurface(
        modifier = modifier,
        shape = shape,
        color = color,
        shadow = shadow,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            content = content,
        )
    }
}

/** Small tilted label slapped onto things: "NEW", "BETA", counts… */
@Composable
fun StickerTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    rotation: Float = -4f,
) {
    BrutalSurface(
        modifier = modifier.graphicsLayer { rotationZ = rotation },
        shape = Shapes.Pill,
        color = color,
        shadow = 2.dp,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** A tiny "GIF" (or "VIDEO") badge for thumbnails of animated images (a [StickerTag] is too wide there). */
@Composable
fun GifTag(modifier: Modifier = Modifier, video: Boolean = false) {
    BrutalSurface(
        modifier = modifier.padding(2.dp).graphicsLayer { rotationZ = -8f },
        shape = Shapes.Pill,
        color = Palette.Pink,
        shadow = 1.dp,
    ) {
        Text(if (video) "VIDEO" else "GIF", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}

/** Chunky section heading with an optional trailing slot. */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.padding(top = Dimens.Space8),
    )
}
