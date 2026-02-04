package com.example.stickermaker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

/**
 * Single-select row in one brutal tray. The selected block is a separate outlined slab that
 * slides (with a little overshoot) to the chosen option.
 */
@Composable
fun <T> SegmentedPicker(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> Int)? = null,
    selectedColor: Color = Palette.Orange,
) {
    val colors = AppTheme.colors
    val inset = 5.dp
    BrutalSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp),
        shape = Shapes.Medium,
        color = colors.surface,
        shadow = Dimens.ShadowS,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(inset),
        ) {
            val segment = maxWidth / options.size
            val index = options.indexOf(selected).coerceAtLeast(0)
            val indicatorX by animateDpAsState(segment * index, Motion.spatialBouncy(), label = "indicator")

            Box(
                modifier = Modifier
                    .offset { IntOffset(indicatorX.roundToPx(), 0) }
                    .width(segment)
                    .fillMaxHeight()
                    .brutal(Shapes.Small, fill = selectedColor, border = colors.ink),
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
                options.forEach { option ->
                    val isSelected = option == selected
                    val textColor = animateColorAsState(
                        if (isSelected) colors.onBlock else colors.ink,
                        Motion.effects(),
                        label = "segmentText",
                    )
                    Row(
                        modifier = Modifier
                            .width(segment)
                            .fillMaxHeight()
                            .selectable(
                                selected = isSelected,
                                onClick = { if (!isSelected) onSelect(option) },
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The animated colour is read only while drawing (icon) and via
                        // BasicText's ColorProducer (label): the switch never recomposes.
                        if (icon != null) {
                            val painter = painterResource(icon(option))
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .drawBehind {
                                        with(painter) { draw(size, colorFilter = ColorFilter.tint(textColor.value)) }
                                    },
                            )
                        }
                        BasicText(label(option), style = MaterialTheme.typography.labelMedium, color = { textColor.value })
                    }
                }
            }
        }
    }
}
