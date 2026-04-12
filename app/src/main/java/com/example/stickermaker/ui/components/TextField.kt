package com.example.stickermaker.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Motion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

/** One-line text input in an outlined block that turns yellow while focused. */
@Composable
fun BrutalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    maxLength: Int = Int.MAX_VALUE,
    onDone: () -> Unit = {},
) {
    val colors = AppTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill by animateColorAsState(if (focused) Palette.Yellow else colors.surface, Motion.effects(), label = "fieldFill")
    val textColor = if (focused) colors.onBlock else colors.ink
    BrutalSurface(
        modifier = modifier.fillMaxWidth().heightIn(min = Dimens.ButtonMedium),
        shape = Shapes.Medium,
        color = fill,
        shadow = Dimens.ShadowS,
    ) {
        Box(Modifier.align(Alignment.CenterStart).padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.titleMedium, color = colors.inkMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = { onValueChange(it.take(maxLength)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = textColor),
                cursorBrush = SolidColor(colors.ink),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
