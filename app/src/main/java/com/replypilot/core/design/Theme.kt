package com.replypilot.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.replypilot.core.datastore.ThemeMode

// Brand: deep teal ("pilot") with a warm amber accent ("paper plane").
private val Teal10 = Color(0xFF00201F)
private val Teal30 = Color(0xFF0F4C5C)
private val Teal40 = Color(0xFF1F6475)
private val Teal80 = Color(0xFF8ED0DF)
private val Teal90 = Color(0xFFC4ECF4)
private val Amber40 = Color(0xFFB0621C)
private val Amber80 = Color(0xFFFFB87A)
private val Amber90 = Color(0xFFFFDCC2)

private val LightColors = lightColorScheme(
    primary = Teal30, onPrimary = Color.White, primaryContainer = Teal90, onPrimaryContainer = Teal10,
    secondary = Amber40, onSecondary = Color.White, secondaryContainer = Amber90, onSecondaryContainer = Color(0xFF331200),
    tertiary = Color(0xFF5B5891), tertiaryContainer = Color(0xFFE3DFFF), onTertiaryContainer = Color(0xFF17134A),
    background = Color(0xFFF7FAF9), onBackground = Color(0xFF171D1D), surface = Color(0xFFF7FAF9), onSurface = Color(0xFF171D1D),
    surfaceVariant = Color(0xFFDBE5E5), onSurfaceVariant = Color(0xFF3F4949), outline = Color(0xFF6F7979), outlineVariant = Color(0xFFBEC8C9),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F5F4), surfaceContainer = Color(0xFFEBF0EF),
    surfaceContainerHigh = Color(0xFFE5EAE9), surfaceContainerHighest = Color(0xFFDFE4E3),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Teal80, onPrimary = Color(0xFF003640), primaryContainer = Teal40, onPrimaryContainer = Teal90,
    secondary = Amber80, onSecondary = Color(0xFF4F2500), secondaryContainer = Color(0xFF6E3A0C), onSecondaryContainer = Amber90,
    tertiary = Color(0xFFC5C0FF), tertiaryContainer = Color(0xFF433F77), onTertiaryContainer = Color(0xFFE3DFFF),
    background = Color(0xFF0F1515), onBackground = Color(0xFFDEE4E3), surface = Color(0xFF0F1515), onSurface = Color(0xFFDEE4E3),
    surfaceVariant = Color(0xFF3F4949), onSurfaceVariant = Color(0xFFBEC8C9), outline = Color(0xFF889393), outlineVariant = Color(0xFF3F4949),
    surfaceContainerLowest = Color(0xFF0A0F0F), surfaceContainerLow = Color(0xFF171D1D), surfaceContainer = Color(0xFF1B2121),
    surfaceContainerHigh = Color(0xFF252B2B), surfaceContainerHighest = Color(0xFF303636),
    error = Color(0xFFF2B8B5), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
)

/** Semantic colours for the states the product must always distinguish visually. */
@Immutable
data class StatusColors(
    val scheduled: Color,
    val autoReply: Color,
    val paused: Color,
    val failed: Color,
    val simulated: Color,
    val review: Color,
    val success: Color,
    val onStatus: Color,
)

private val LightStatus = StatusColors(
    scheduled = Color(0xFF1F6475), autoReply = Color(0xFF5B5891), paused = Color(0xFF6F7979), failed = Color(0xFFB3261E),
    simulated = Color(0xFF9A5B00), review = Color(0xFFB0621C), success = Color(0xFF2E7D4F), onStatus = Color.White,
)
private val DarkStatus = StatusColors(
    scheduled = Color(0xFF8ED0DF), autoReply = Color(0xFFC5C0FF), paused = Color(0xFFBEC8C9), failed = Color(0xFFF2B8B5),
    simulated = Color(0xFFFFCC80), review = Color(0xFFFFB87A), success = Color(0xFF8FD8A8), onStatus = Color(0xFF0F1515),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Large tabular figures for dashboard metrics. */
val MetricTextStyle = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)

@Composable
fun ReplyPilotTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}
