package com.example.stickermaker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

private val SheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

/** Preferred over dialogs for choices and small forms. Thick ink rim, chunky handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
        shape = SheetShape,
        // The sheet chrome (fill, ink rim, handle) is drawn by the content below: a border
        // modifier on ModalBottomSheet itself lands on an internal full-screen layer.
        containerColor = Color.Transparent,
        contentColor = colors.ink,
        scrimColor = Palette.Ink.copy(alpha = 0.45f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .brutal(SheetShape, fill = colors.canvas, border = colors.ink, borderWidth = Dimens.BorderThick)
                .padding(horizontal = Dimens.ScreenGutter)
                .padding(bottom = Dimens.Space24)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Dimens.Space16),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 14.dp)
                    .size(width = 52.dp, height = 7.dp)
                    .background(colors.ink, Shapes.Pill),
            )
            if (title != null) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            content()
        }
    }
}
