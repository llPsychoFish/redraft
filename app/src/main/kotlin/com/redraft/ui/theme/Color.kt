package com.redraft.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand colour tokens (D-03)
val BrandInk = Color(0xFF0B0C10)
val BrandPaper = Color(0xFFF2F2F4)
val BrandWhite = Color(0xFFFFFFFF)
val BrandAmber = Color(0xFFFFB000)

// Pre-blended opaque colours for light scheme (alpha blended over BrandPaper)
private val lightPrimaryContainer = Color(0xFFFFD87A)   // BrandAmber 0.18 over BrandPaper
private val lightSecondaryContainer = Color(0xFFE0E0E2) // BrandInk 0.18 over BrandPaper
private val lightTertiaryContainer = Color(0xFFFFD87A)  // BrandAmber 0.18 over BrandPaper
private val lightErrorContainer = Color(0xFFF7D7D7)     // #BA1A1A 0.12 over BrandPaper
private val lightSurfaceVariant = Color(0xFFEBEBED)     // BrandInk 0.08 over BrandPaper
private val lightOutline = Color(0xFF7A7A7C)            // Darker for 3:1 contrast on BrandPaper
private val lightOutlineVariant = Color(0xFF6A6A6C)     // Darker for 3:1 contrast on BrandPaper

// Pre-blended opaque colours for dark scheme (WCAG >= 4.5:1)
private val darkPrimaryContainer = Color(0xFF46330C)    // Dark amber, onPrimaryContainer = BrandPaper
private val darkSecondaryContainer = Color(0xFF2A2D3A)  // Dark grey-blue, onSecondaryContainer = BrandPaper
private val darkTertiaryContainer = Color(0xFF46330C)   // Dark amber, onTertiaryContainer = BrandPaper
private val darkErrorContainer = Color(0xFF8C1D1D)      // Dark red, onErrorContainer = BrandPaper
private val darkSurfaceVariant = Color(0xFF1E2028)      // Dark surface variant
private val darkOutline = Color(0xFF7A7D8A)             // Dark outline
private val darkOutlineVariant = Color(0xFF7A7D8A)      // Same as outline for 3:1 contrast on dark surface

// Light brand colour scheme — every role from UI-SPEC § Color, surfaceTint = Color.Transparent
val lightColorScheme = lightColorScheme(
    primary = BrandAmber,
    onPrimary = BrandInk,
    primaryContainer = lightPrimaryContainer,
    onPrimaryContainer = BrandInk,
    secondary = Color(0xFF6A6A6C), // BrandInk 0.6 opaque equivalent
    onSecondary = BrandWhite,
    secondaryContainer = lightSecondaryContainer,
    onSecondaryContainer = BrandInk,
    tertiary = Color(0xFFFF9A00), // BrandAmber 0.6 opaque equivalent
    onTertiary = BrandInk,
    tertiaryContainer = lightTertiaryContainer,
    onTertiaryContainer = BrandInk,
    error = Color(0xFFBA1A1A),
    onError = BrandWhite,
    errorContainer = lightErrorContainer,
    onErrorContainer = Color(0xFFBA1A1A),
    background = BrandPaper,
    onBackground = BrandInk,
    surface = BrandPaper,
    onSurface = BrandInk,
    surfaceVariant = lightSurfaceVariant,
    onSurfaceVariant = Color(0xFF6A6A6C), // BrandInk 0.6 opaque equivalent
    outline = lightOutline,
    outlineVariant = lightOutlineVariant,
    scrim = Color.Black,
    inverseSurface = BrandInk,
    inverseOnSurface = BrandPaper,
    inversePrimary = BrandAmber,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F9),
    surfaceContainer = Color(0xFFEBEBEE),
    surfaceContainerHigh = Color(0xFFE4E4E8),
    surfaceContainerHighest = Color(0xFFDDDDE2),
    surfaceDim = Color(0xFFFFFFFF),
    surfaceBright = Color(0xFFDDDDE2),
)

// Dark brand colour scheme — WCAG >= 4.5:1 for on-X/X pairs, surfaceTint = Color.Transparent
val darkColorScheme = darkColorScheme(
    primary = BrandAmber,
    onPrimary = BrandInk,
    primaryContainer = darkPrimaryContainer,
    onPrimaryContainer = BrandPaper,
    secondary = Color(0xFFB0B3C0), // BrandPaper 0.6 opaque equivalent
    onSecondary = BrandInk,
    secondaryContainer = darkSecondaryContainer,
    onSecondaryContainer = BrandPaper,
    tertiary = Color(0xFFFF9A00), // BrandAmber 0.6 opaque equivalent
    onTertiary = BrandInk,
    tertiaryContainer = darkTertiaryContainer,
    onTertiaryContainer = BrandPaper,
    error = Color(0xFFFFB4AB),
    onError = BrandInk,
    errorContainer = darkErrorContainer,
    onErrorContainer = BrandPaper,
    background = BrandInk,
    onBackground = BrandPaper,
    surface = BrandInk,
    onSurface = BrandPaper,
    surfaceVariant = darkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB0B3C0), // BrandPaper 0.6 opaque equivalent
    outline = darkOutline,
    outlineVariant = darkOutlineVariant,
    scrim = Color.Black,
    inverseSurface = BrandPaper,
    inverseOnSurface = BrandInk,
    inversePrimary = BrandAmber,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF090A0D),
    surfaceContainerLow = Color(0xFF12131A),
    surfaceContainer = Color(0xFF171821),
    surfaceContainerHigh = Color(0xFF1E202A),
    surfaceContainerHighest = Color(0xFF262833),
    surfaceDim = Color(0xFF090A0D),
    surfaceBright = Color(0xFF262833),
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