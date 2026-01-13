package com.example.stickermaker.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.stickermaker.R

/** Outfit, one variable font file (~110 KB) serving every weight. */
private fun outfit(weight: FontWeight) = Font(
    resId = R.font.outfit,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Outfit = FontFamily(
    outfit(FontWeight.Normal),
    outfit(FontWeight.Medium),
    outfit(FontWeight.SemiBold),
    outfit(FontWeight.Bold),
    outfit(FontWeight.ExtraBold),
    outfit(FontWeight.Black),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Outfit,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

/** Chunky scale: big tight headlines, roomy body. */
val StickerTypography = Typography(
    displayLarge = style(56, 58, FontWeight.Black, -0.03),
    displayMedium = style(44, 48, FontWeight.Black, -0.03),
    displaySmall = style(36, 40, FontWeight.ExtraBold, -0.02),
    headlineLarge = style(32, 36, FontWeight.ExtraBold, -0.02),
    headlineMedium = style(26, 30, FontWeight.ExtraBold, -0.01),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.01),
    titleLarge = style(20, 26, FontWeight.Bold),
    titleMedium = style(17, 22, FontWeight.SemiBold),
    titleSmall = style(15, 20, FontWeight.SemiBold),
    bodyLarge = style(17, 25, FontWeight.Normal),
    bodyMedium = style(15, 22, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(17, 20, FontWeight.Bold),
    labelMedium = style(14, 18, FontWeight.SemiBold),
    labelSmall = style(11, 14, FontWeight.ExtraBold, 0.08),
)
