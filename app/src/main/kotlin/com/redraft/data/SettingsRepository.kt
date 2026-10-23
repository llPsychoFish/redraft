package com.redraft.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.emptyPreferences
import com.redraft.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.SupervisorJob
import java.io.IOException

// Single-delegate DataStore for settings (D-09, D-10)
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
private val KEY_AMOLED = booleanPreferencesKey("amoled")
private val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
private val KEY_REOPEN_AT_LAST_PAGE = booleanPreferencesKey("reopen_at_last_page")

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setAmoled(enabled: Boolean)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setReopenAtLastPage(enabled: Boolean)
}

// Pure, Android-free decode function — unit-testable (D-12)
fun decodeSettings(preferences: Preferences): AppSettings {
    val themeModeName = preferences[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name
    val themeMode = try {
        ThemeMode.valueOf(themeModeName)
    } catch (e: IllegalArgumentException) {
        ThemeMode.SYSTEM
    }
    return AppSettings(
        themeMode = themeMode,
        dynamicColor = preferences[KEY_DYNAMIC_COLOR] ?: true,
        amoled = preferences[KEY_AMOLED] ?: false,
        keepScreenOn = preferences[KEY_KEEP_SCREEN_ON] ?: false,
        reopenAtLastPage = preferences[KEY_REOPEN_AT_LAST_PAGE] ?: true,
    )
}

class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences -> decodeSettings(preferences) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[KEY_THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setAmoled(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_AMOLED] = enabled }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setReopenAtLastPage(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_REOPEN_AT_LAST_PAGE] = enabled }
    }
}