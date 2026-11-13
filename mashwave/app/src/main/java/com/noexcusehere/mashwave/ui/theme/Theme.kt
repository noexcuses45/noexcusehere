package com.noexcusehere.mashwave.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.noexcusehere.mashwave.R

// Mashwave is always dark: near-black "ink" with neon violet / pink / cyan accents.
val Ink = Color(0xFF07070C)
val Surface1 = Color(0xFF111119)
val Surface2 = Color(0xFF1A1A25)
val Surface3 = Color(0xFF262635)
val Outline = Color(0xFF32324A)

val Violet = Color(0xFF8B5CF6)
val Pink = Color(0xFFFF4D9D)
val Cyan = Color(0xFF22D3EE)
val Amber = Color(0xFFFFB648)

val TextHi = Color(0xFFF6F6FB)
val TextMid = Color(0xFFA9A9BD)
val TextLow = Color(0xFF6E6E85)

/** Deck colours in the mashup studio. */
val DeckA = Cyan
val DeckB = Pink

val BrandGradient = Brush.linearGradient(listOf(Violet, Color(0xFFD946EF), Pink))
val BrandGradientHorizontal = Brush.horizontalGradient(listOf(Violet, Pink))

@OptIn(ExperimentalTextApi::class)
private fun outfit(weight: Int) = Font(
    resId = R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Outfit = FontFamily(outfit(400), outfit(500), outfit(600), outfit(700), outfit(800))

private fun style(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = Outfit,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

private val MashwaveType = Typography(
    displaySmall = style(34, 800, 40, -0.5),
    headlineLarge = style(30, 800, 36, -0.5),
    headlineMedium = style(26, 700, 32, -0.3),
    headlineSmall = style(22, 700, 28, -0.2),
    titleLarge = style(20, 700, 26),
    titleMedium = style(16, 600, 22),
    titleSmall = style(14, 600, 20),
    bodyLarge = style(16, 400, 22),
    bodyMedium = style(14, 400, 20),
    bodySmall = style(12, 400, 16),
    labelLarge = style(14, 600, 20),
    labelMedium = style(12, 600, 16, 0.2),
    labelSmall = style(11, 600, 14, 0.6),
)

private val MashwaveColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2A78),
    onPrimaryContainer = Color(0xFFE9E0FF),
    secondary = Pink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF5C1F3F),
    onSecondaryContainer = Color(0xFFFFD9E8),
    tertiary = Cyan,
    onTertiary = Color(0xFF00323A),
    background = Ink,
    onBackground = TextHi,
    surface = Ink,
    onSurface = TextHi,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    outline = Outline,
    outlineVariant = Color(0xFF24243A),
    error = Color(0xFFFF6B6B),
    onError = Color.White,
    scrim = Color.Black,
)

@Composable
fun MashwaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MashwaveColors, typography = MashwaveType, content = content)
}
