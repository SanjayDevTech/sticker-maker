package com.example.stickermaker.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.stickermaker.ui.theme.AppTheme
import com.example.stickermaker.ui.theme.Dimens
import com.example.stickermaker.ui.theme.LocalReduceMotion
import com.example.stickermaker.ui.theme.Palette
import com.example.stickermaker.ui.theme.Shapes

/**
 * Designed empty state: a wobbling sticker-cut tile with an icon, a headline, one line of
 * help and (optionally) the action that fills the space.
 */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    color: Color = Palette.Mint,
    action: (@Composable () -> Unit)? = null,
) {
    val phase by rememberWobblePhase(periodMillis = 4000)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.Space24, horizontal = Dimens.Space16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.Space12),
    ) {
        BrutalSurface(
            modifier = Modifier
                .size(104.dp)
                .graphicsLayer { rotationZ = -6f },
            shape = SquigglyShape(cornerRadius = 30.dp, amplitude = 3.dp, wavelength = 18.dp, phase = phase),
            color = color,
            shadow = Dimens.ShadowM,
            borderWidth = Dimens.BorderThick,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Dimens.Space8),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        if (action != null) Box(Modifier.padding(top = Dimens.Space8)) { action() }
    }
}

/**
 * Placeholder block shown while content loads, instead of a spinner. Outlined like the real
 * thing so the layout doesn't jump; the fill breathes unless animations are off.
 */
@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = Shapes.Medium,
) {
    val colors = AppTheme.colors
    val reduceMotion = LocalReduceMotion.current
    val pulse = if (reduceMotion) {
        0.5f
    } else {
        val value by rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "pulse",
        )
        value
    }
    Box(
        modifier
            .brutal(shape, fill = colors.surface, border = colors.ink.copy(alpha = 0.25f))
            .drawBehind { drawRect(colors.ink.copy(alpha = 0.04f + 0.06f * pulse)) },
    )
}

/** Snackbar host that renders [StickerSnackbar]s. */
@Composable
fun StickerSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> StickerSnackbar(data) }
}

@Composable
fun StickerSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    BrutalSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Dimens.ScreenGutter, end = Dimens.ScreenGutter + Dimens.ShadowM, bottom = 12.dp),
        shape = Shapes.Medium,
        color = Palette.Yellow,
        shadow = Dimens.ShadowM,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.Space12),
        ) {
            Text(
                data.visuals.message,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            val action = data.visuals.actionLabel
            if (action != null) {
                ChunkyButton(
                    text = action,
                    onClick = { data.performAction() },
                    color = AppTheme.colors.surface,
                    size = ChunkySize.Medium,
                    modifier = Modifier.padding(end = Dimens.ShadowS, bottom = Dimens.ShadowS),
                )
            }
        }
    }
}

/**
 * Prefer this over confirm dialogs: do the destructive thing immediately, then offer Undo.
 * Returns true if the user tapped Undo.
 */
suspend fun SnackbarHostState.showUndo(message: String): Boolean {
    currentSnackbarData?.dismiss()
    return showSnackbar(
        message = message,
        actionLabel = "Undo",
        duration = SnackbarDuration.Long,
    ) == SnackbarResult.ActionPerformed
}

/** versionName from the installed package (BuildConfig stays disabled). */
@Composable
fun rememberVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
}
