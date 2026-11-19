package com.noexcusehere.mashwave.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The same colours as the phone app.
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
val BrandGradient = Brush.horizontalGradient(listOf(Violet, Pink))

/** A file that ships inside the app (the font, the icon). */
fun bundled(name: String): ByteArray? =
    runCatching { Thread.currentThread().contextClassLoader.getResourceAsStream(name)?.use { it.readBytes() } }.getOrNull()

@OptIn(ExperimentalTextApi::class)
private val Outfit: FontFamily = run {
    val bytes = bundled("font/outfit.ttf") ?: return@run FontFamily.Default
    runCatching {
        FontFamily(
            listOf(400, 500, 600, 700).map { weight ->
                Font(
                    identity = "outfit-$weight",
                    data = bytes,
                    weight = FontWeight(weight),
                    style = FontStyle.Normal,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
                )
            },
        )
    }.getOrDefault(FontFamily.Default)
}

private fun style(size: Int, weight: Int, line: Int) = TextStyle(
    fontFamily = Outfit,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
)

private val DeskType = Typography(
    displaySmall = style(30, 700, 36),
    headlineMedium = style(24, 700, 30),
    headlineSmall = style(20, 600, 26),
    titleLarge = style(18, 600, 24),
    titleMedium = style(15, 600, 20),
    titleSmall = style(13, 600, 18),
    bodyLarge = style(15, 400, 22),
    bodyMedium = style(14, 400, 20),
    bodySmall = style(12, 400, 16),
    labelLarge = style(14, 600, 18),
    labelMedium = style(12, 500, 16),
    labelSmall = style(11, 500, 14),
)

private val DeskColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B2A78),
    onPrimaryContainer = Color(0xFFE9E0FF),
    secondary = Pink,
    onSecondary = Color.White,
    tertiary = Cyan,
    background = Ink,
    onBackground = TextHi,
    surface = Surface1,
    onSurface = TextHi,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    outline = Outline,
    error = Color(0xFFFF6B6B),
)

@Composable
fun DeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DeskColors, typography = DeskType) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 200,
                unhoverColor = Color.White.copy(alpha = 0.14f),
                hoverColor = Color.White.copy(alpha = 0.34f),
            ),
            content = content,
        )
    }
}
