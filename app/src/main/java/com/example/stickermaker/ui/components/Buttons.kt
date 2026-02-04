package com.example.stickermaker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

enum class ChunkySize(val height: Dp, val iconSize: Dp, val padding: Dp, val shadow: Dp) {
    Medium(Dimens.ButtonMedium, 22.dp, 20.dp, Dimens.ShadowS),
    Large(Dimens.ButtonLarge, 26.dp, 24.dp, Dimens.ShadowM),
    Hero(Dimens.ButtonHero, 30.dp, 28.dp, Dimens.ShadowL),
}

/**
 * Big, loud, tactile button. [squiggly] cuts the edge like a sticker; [wobble] makes that
 * edge crawl slowly (use for at most one hero action per screen).
 */
@Composable
fun ChunkyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Yellow,
    @DrawableRes icon: Int? = null,
    size: ChunkySize = ChunkySize.Large,
    squiggly: Boolean = false,
    wobble: Boolean = false,
    enabled: Boolean = true,
) {
    val textStyle: TextStyle = when (size) {
        ChunkySize.Medium -> MaterialTheme.typography.labelLarge
        ChunkySize.Large -> MaterialTheme.typography.titleLarge
        ChunkySize.Hero -> MaterialTheme.typography.headlineSmall
    }
    val shape: Shape = when {
        !squiggly -> Shapes.Medium
        wobble -> {
            val phase = rememberWobblePhase()
            remember(phase) { WobblingShape(SquigglyShape(), phase) }
        }
        else -> SquigglyShape()
    }
    BrutalSurface(
        modifier = modifier.defaultMinSize(minHeight = size.height),
        shape = shape,
        color = color,
        shadow = size.shadow,
        borderWidth = if (size == ChunkySize.Medium) Dimens.Border else Dimens.BorderThick,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = size.padding, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(size.iconSize))
            }
            Text(text, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Square icon button, like the back / menu tiles in the top bar. */
@Composable
fun IconTile(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.surface,
    size: Dp = Dimens.IconTile,
    enabled: Boolean = true,
) {
    BrutalSurface(
        modifier = modifier.size(size),
        shape = Shapes.Small,
        color = color,
        shadow = Dimens.ShadowS,
        onClick = onClick,
        onClickLabel = contentDescription,
        enabled = enabled,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier
                .align(Alignment.Center)
                .size(size * 0.5f),
        )
    }
}
