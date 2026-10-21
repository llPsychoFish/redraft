package com.redraft.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand colour tokens (D-03)
val BrandInk = Color(0xFF0B0C10)
val BrandPaper = Color(0xFFF2F2F4)
val BrandWhite = Color(0xFFFFFFFF)
val BrandAmber = Color(0xFFFFB000)

// Light brand colour scheme — every role from UI-SPEC § Color, surfaceTint = Color.Transparent
val lightColorScheme = lightColorScheme(
    primary = BrandAmber,
    onPrimary = BrandInk,
    primaryContainer = BrandAmber.copy(alpha = 0.12f),
    onPrimaryContainer = BrandInk,
    secondary = BrandInk.copy(alpha = 0.6f),
    onSecondary = BrandWhite,
    secondaryContainer = BrandInk.copy(alpha = 0.12f),
    onSecondaryContainer = BrandInk,
    tertiary = BrandAmber.copy(alpha = 0.6f),
    onTertiary = BrandInk,
    tertiaryContainer = BrandAmber.copy(alpha = 0.12f),
    onTertiaryContainer = BrandInk,
    error = Color(0xFFBA1A1A),
    onError = BrandWhite,
    errorContainer = Color(0xFFBA1A1A).copy(alpha = 0.12f),
    onErrorContainer = Color(0xFFBA1A1A),
    background = BrandPaper,
    onBackground = BrandInk,
    surface = BrandPaper,
    onSurface = BrandInk,
    surfaceVariant = BrandInk.copy(alpha = 0.08f),
    onSurfaceVariant = BrandInk.copy(alpha = 0.6f),
    outline = BrandInk.copy(alpha = 0.38f),
    outlineVariant = BrandInk.copy(alpha = 0.18f),
    scrim = Color.Black,
    inverseSurface = BrandInk,
    inverseOnSurface = BrandPaper,
    inversePrimary = BrandAmber,
    surfaceTint = Color.Transparent,
)

// Dark brand colour scheme — every role from UI-SPEC § Color, surfaceTint = Color.Transparent
val darkColorScheme = darkColorScheme(
    primary = BrandAmber,
    onPrimary = BrandInk,
    primaryContainer = BrandAmber.copy(alpha = 0.24f),
    onPrimaryContainer = BrandInk,
    secondary = BrandPaper.copy(alpha = 0.6f),
    onSecondary = BrandInk,
    secondaryContainer = BrandInk.copy(alpha = 0.24f),
    onSecondaryContainer = BrandPaper,
    tertiary = BrandAmber.copy(alpha = 0.6f),
    onTertiary = BrandInk,
    tertiaryContainer = BrandAmber.copy(alpha = 0.24f),
    onTertiaryContainer = BrandInk,
    error = Color(0xFFFFB4AB),
    onError = BrandInk,
    errorContainer = Color(0xFFBA1A1A).copy(alpha = 0.24f),
    onErrorContainer = Color(0xFFFFB4AB),
    background = BrandInk,
    onBackground = BrandPaper,
    surface = BrandInk,
    onSurface = BrandPaper,
    surfaceVariant = BrandPaper.copy(alpha = 0.12f),
    onSurfaceVariant = BrandPaper.copy(alpha = 0.6f),
    outline = BrandPaper.copy(alpha = 0.38f),
    outlineVariant = BrandPaper.copy(alpha = 0.18f),
    scrim = Color.Black,
    inverseSurface = BrandPaper,
    inverseOnSurface = BrandInk,
    inversePrimary = BrandAmber,
    surfaceTint = Color.Transparent,
)

// AMOLED dark overrides — applied only when resolved scheme is dark and not dynamic
// Copy the base dark scheme and replace background, surface, surfaceContainerLowest,
// surfaceDim with #000000 and step the container ramp per UI-SPEC AMOLED table
val amoledColorScheme = darkColorScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainer = Color(0xFF0D0E14),
    surfaceContainerLow = Color(0xFF14151C),
    surfaceContainerHigh = Color(0xFF1C1E27),
    surfaceContainerHighest = Color(0xFF242630),
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF2E313D),
    surfaceTint = Color.Transparent,
)