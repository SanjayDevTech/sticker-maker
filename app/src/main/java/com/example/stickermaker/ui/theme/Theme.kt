package com.example.stickermaker.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

val LocalStickerColors = staticCompositionLocalOf { LightStickerColors }

/** `AppTheme.colors.ink` etc. — the custom tokens Material's ColorScheme has no slot for. */
object AppTheme {
    val colors: StickerColors
        @Composable @ReadOnlyComposable get() = LocalStickerColors.current
}

/** Material components we still use (sheets, snackbars, text fields) pick these up. */
private fun StickerColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = Palette.Pink,
        onPrimary = onBlock,
        primaryContainer = Palette.Lavender,
        onPrimaryContainer = onBlock,
        secondary = Palette.Yellow,
        onSecondary = onBlock,
        secondaryContainer = Palette.Lime,
        onSecondaryContainer = onBlock,
        tertiary = Palette.Mint,
        onTertiary = onBlock,
        error = Palette.Coral,
        onError = onBlock,
        background = canvas,
        onBackground = ink,
        surface = canvas,
        onSurface = ink,
        surfaceVariant = surface,
        onSurfaceVariant = inkMuted,
        surfaceContainerLowest = canvas,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface,
        outline = ink,
        outlineVariant = inkMuted,
        inverseSurface = ink,
        inverseOnSurface = canvas,
        scrim = Palette.Ink,
    )
}

@Composable
fun StickerTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkStickerColors else LightStickerColors
    CompositionLocalProvider(
        LocalStickerColors provides colors,
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialExpressiveTheme(
            colorScheme = remember(colors) { colors.toMaterial() },
            typography = StickerTypography,
            motionScheme = MotionScheme.expressive(),
        ) {
            // Opaque root so screen transitions never reveal the bare window behind them.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = colors.canvas,
                contentColor = colors.ink,
                content = content,
            )
        }
    }
}
