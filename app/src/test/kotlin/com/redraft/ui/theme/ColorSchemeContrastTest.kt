package com.redraft.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates WCAG 2.1 contrast ratios for the three colour schemes.
 * Light/Dark/AMOLED must meet >= 4.5:1 for on-X / X pairs, >= 3:1 for outline roles.
 */
class ColorSchemeContrastTest {

    @Test
    fun `light scheme meets WCAG AA contrast for on-X pairs`() {
        val scheme = lightColorScheme
        val pairs = listOf(
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onSecondaryContainer to scheme.secondaryContainer,
            scheme.onTertiary to scheme.tertiary,
            scheme.onTertiaryContainer to scheme.tertiaryContainer,
            scheme.onError to scheme.error,
            scheme.onErrorContainer to scheme.errorContainer,
            scheme.onBackground to scheme.background,
            scheme.onSurface to scheme.surface,
            scheme.onSurfaceVariant to scheme.surfaceVariant,
        )
        pairs.forEach { (on, base) ->
            assertTrue(
                "Light: ${colorToHex(on)} on ${colorToHex(base)} contrast ${contrastRatio(on, base)} < 4.5",
                contrastRatio(on, base) >= 4.5
            )
        }
    }

    @Test
    fun `dark scheme meets WCAG AA contrast for on-X pairs`() {
        val scheme = darkColorScheme
        val pairs = listOf(
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onSecondaryContainer to scheme.secondaryContainer,
            scheme.onTertiary to scheme.tertiary,
            scheme.onTertiaryContainer to scheme.tertiaryContainer,
            scheme.onError to scheme.error,
            scheme.onErrorContainer to scheme.errorContainer,
            scheme.onBackground to scheme.background,
            scheme.onSurface to scheme.surface,
            scheme.onSurfaceVariant to scheme.surfaceVariant,
        )
        pairs.forEach { (on, base) ->
            assertTrue(
                "Dark: ${colorToHex(on)} on ${colorToHex(base)} contrast ${contrastRatio(on, base)} < 4.5",
                contrastRatio(on, base) >= 4.5
            )
        }
    }

    @Test
    fun `amoled scheme meets WCAG AA contrast for on-X pairs`() {
        val scheme = amoledColorScheme
        val pairs = listOf(
            scheme.onPrimary to scheme.primary,
            scheme.onPrimaryContainer to scheme.primaryContainer,
            scheme.onSecondary to scheme.secondary,
            scheme.onSecondaryContainer to scheme.secondaryContainer,
            scheme.onTertiary to scheme.tertiary,
            scheme.onTertiaryContainer to scheme.tertiaryContainer,
            scheme.onError to scheme.error,
            scheme.onErrorContainer to scheme.errorContainer,
            scheme.onBackground to scheme.background,
            scheme.onSurface to scheme.surface,
            scheme.onSurfaceVariant to scheme.surfaceVariant,
        )
        pairs.forEach { (on, base) ->
            assertTrue(
                "AMOLED: ${colorToHex(on)} on ${colorToHex(base)} contrast ${contrastRatio(on, base)} < 4.5",
                contrastRatio(on, base) >= 4.5
            )
        }
    }

    @Test
    fun `all schemes meet WCAG AA for outline roles (3 to 1)`() {
        listOf("Light" to lightColorScheme, "Dark" to darkColorScheme, "AMOLED" to amoledColorScheme).forEach { (name, scheme) ->
            val outlinePairs = listOf(
                scheme.outline to scheme.surface,
                scheme.outlineVariant to scheme.surface,
            )
            outlinePairs.forEach { (on, base) ->
                assertTrue(
                    "$name: ${colorToHex(on)} on ${colorToHex(base)} contrast ${contrastRatio(on, base)} < 3.0",
                    contrastRatio(on, base) >= 3.0
                )
            }
        }
    }

    private fun colorToHex(color: Color): String {
        // Color.value is a packed float, use the component values
        val r = (color.red * 255).toInt()
        val g = (color.green * 255).toInt()
        val b = (color.blue * 255).toInt()
        val a = (color.alpha * 255).toInt()
        return String.format("#%02X%02X%02X%02X", a, r, g, b)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val fgLum = relativeLuminance(foreground)
        val bgLum = relativeLuminance(background)
        val lighter = max(fgLum, bgLum)
        val darker = min(fgLum, bgLum)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        val r = srgbToLinear(color.red)
        val g = srgbToLinear(color.green)
        val b = srgbToLinear(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun srgbToLinear(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.04045) {
            c / 12.92
        } else {
            Math.pow((c + 0.055) / 1.055, 2.4)
        }
    }
}