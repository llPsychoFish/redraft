package com.redraft.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// Wave 0 test — system/light/dark resolution, dynamic fallback, AMOLED dark-only (UI-02)
class ThemeSchemeTest {

    private val lightScheme = lightColorScheme
    private val darkScheme = darkColorScheme
    private val dynamicLight = lightColorScheme.copy(primary = Color(0xFF123456))
    private val dynamicDark = darkColorScheme.copy(primary = Color(0xFF654321))

    @Test
    fun `system resolves light when darkTheme false`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = false,
            amoled = false,
            darkTheme = false,
            dynamicAvailable = false,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(lightScheme, resolved)
    }

    @Test
    fun `system resolves dark when darkTheme true`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = false,
            amoled = false,
            darkTheme = true,
            dynamicAvailable = false,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(darkScheme, resolved)
    }

    @Test
    fun `light mode resolves light scheme regardless of darkTheme`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.LIGHT,
            dynamicColor = false,
            amoled = false,
            darkTheme = true,
            dynamicAvailable = false,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(lightScheme, resolved)
    }

    @Test
    fun `dark mode resolves dark scheme regardless of darkTheme`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            amoled = false,
            darkTheme = false,
            dynamicAvailable = false,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(darkScheme, resolved)
    }

    @Test
    fun `dynamicColor true with dynamicAvailable true uses dynamic scheme`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = true,
            amoled = false,
            darkTheme = true,
            dynamicAvailable = true,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(dynamicDark, resolved)
    }

    @Test
    fun `dynamicColor true with dynamicAvailable false falls back to static scheme`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = true,
            amoled = false,
            darkTheme = true,
            dynamicAvailable = false,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(darkScheme, resolved)
    }

    @Test
    fun `palette overlay unavailable keeps brand scheme and amoled contract`() {
        // Issue #6: a ROM with no Material You palette overlay reports dynamic colour as
        // unavailable, so the brand scheme takes over — and the AMOLED override, which is skipped
        // whenever a dynamic scheme wins, has to come back into play.
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = true,
            amoled = true,
            darkTheme = true,
            dynamicAvailable = false,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = lightScheme,
            dynamicDark = darkScheme,
        )
        assertEquals(amoledColorScheme, resolved)
    }

    @Test
    fun `amoled true with dark static scheme applies amoled overrides`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.DARK,
            dynamicColor = false,
            amoled = true,
            darkTheme = true,
            dynamicAvailable = true,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(amoledColorScheme, resolved)
    }

    @Test
    fun `amoled true with light scheme does not apply amoled overrides`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.LIGHT,
            dynamicColor = false,
            amoled = true,
            darkTheme = false,
            dynamicAvailable = true,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(lightScheme, resolved)
    }

    @Test
    fun `amoled true with dynamic scheme does not apply amoled overrides`() {
        val resolved = resolveColorScheme(
            themeMode = ThemeMode.SYSTEM,
            dynamicColor = true,
            amoled = true,
            darkTheme = true,
            dynamicAvailable = true,
            lightScheme = lightScheme,
            darkScheme = darkScheme,
            dynamicLight = dynamicLight,
            dynamicDark = dynamicDark,
        )
        assertEquals(dynamicDark, resolved)
    }

    @Test
    fun `surfaceTint is transparent in all schemes`() {
        assertEquals(Color.Transparent, lightScheme.surfaceTint)
        assertEquals(Color.Transparent, darkScheme.surfaceTint)
        assertEquals(Color.Transparent, amoledColorScheme.surfaceTint)
    }

    @Test
    fun `themeMode has exactly three cases`() {
        val values = ThemeMode.values()
        assertEquals(3, values.size)
        assertEquals(ThemeMode.SYSTEM, values[0])
        assertEquals(ThemeMode.LIGHT, values[1])
        assertEquals(ThemeMode.DARK, values[2])
    }
}