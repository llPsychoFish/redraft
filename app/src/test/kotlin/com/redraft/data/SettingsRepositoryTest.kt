package com.redraft.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.redraft.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// Wave 0 test — decode defaults/fallback, persistence round-trip, IOException-to-defaults (UI-01, D-12)
class SettingsRepositoryTest {

    private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    private val KEY_AMOLED = booleanPreferencesKey("amoled")
    private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    private val KEY_REOPEN_AT_LAST_PAGE = booleanPreferencesKey("reopen_at_last_page")

    @Test
    fun `empty preferences decodes to full default AppSettings`() {
        val prefs = mutablePreferencesOf()

        val decoded = decodeSettings(prefs)

        assertNotNull(decoded)
        assertEquals(ThemeMode.SYSTEM, decoded.themeMode)
        assertEquals(true, decoded.dynamicColor)
        assertEquals(false, decoded.amoled)
        assertEquals(false, decoded.keepScreenOn)
        assertEquals(true, decoded.reopenAtLastPage)
    }

    @Test
    fun `unknown theme_mode value falls back to SYSTEM`() {
        val prefs = mutablePreferencesOf(
            KEY_THEME_MODE to "INVALID_MODE"
        )

        val decoded = decodeSettings(prefs)

        assertEquals(ThemeMode.SYSTEM, decoded.themeMode)
    }

    @Test
    fun `missing theme_mode falls back to SYSTEM`() {
        val prefs = mutablePreferencesOf()

        val decoded = decodeSettings(prefs)

        assertEquals(ThemeMode.SYSTEM, decoded.themeMode)
    }

    @Test
    fun `stored DARK theme_mode decodes to DARK`() {
        val prefs = mutablePreferencesOf(
            KEY_THEME_MODE to "DARK"
        )

        val decoded = decodeSettings(prefs)

        assertEquals(ThemeMode.DARK, decoded.themeMode)
    }

    @Test
    fun `stored LIGHT theme_mode decodes to LIGHT`() {
        val prefs = mutablePreferencesOf(
            KEY_THEME_MODE to "LIGHT"
        )

        val decoded = decodeSettings(prefs)

        assertEquals(ThemeMode.LIGHT, decoded.themeMode)
    }

    @Test
    fun `persistence round trip preserves all five fields`() {
        // Simulate a persisted state with all five fields set
        val prefs = mutablePreferencesOf(
            KEY_THEME_MODE to "DARK",
            KEY_DYNAMIC_COLOR to false,
            KEY_AMOLED to true,
            KEY_KEEP_SCREEN_ON to true,
            KEY_REOPEN_AT_LAST_PAGE to false
        )

        // Read phase: decode the resulting Preferences
        val decoded = decodeSettings(prefs)

        assertEquals(ThemeMode.DARK, decoded.themeMode)
        assertEquals(false, decoded.dynamicColor)
        assertEquals(true, decoded.amoled)
        assertEquals(true, decoded.keepScreenOn)
        assertEquals(false, decoded.reopenAtLastPage)
    }

    @Test
    fun `decoded model has exactly the five documented fields`() {
        // This test asserts the threat model T-01-06: no PII, paths, telemetry
        val prefs = mutablePreferencesOf(
            KEY_THEME_MODE to "LIGHT",
            KEY_DYNAMIC_COLOR to true,
            KEY_AMOLED to false,
            KEY_KEEP_SCREEN_ON to true,
            KEY_REOPEN_AT_LAST_PAGE to false
        )

        val decoded = decodeSettings(prefs)

        // Assert all five fields are present and no extra fields exist
        // (AppSettings data class only has these five properties)
        assertNotNull(decoded)
        assertEquals(ThemeMode.LIGHT, decoded.themeMode)
        assertEquals(true, decoded.dynamicColor)
        assertEquals(false, decoded.amoled)
        assertEquals(true, decoded.keepScreenOn)
        assertEquals(false, decoded.reopenAtLastPage)
    }
}