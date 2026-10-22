package com.redraft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.redraft.data.AppSettings
import com.redraft.data.SettingsRepository
import com.redraft.data.SettingsStore
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// SettingsViewModel with factory for DI (D-11, D-12)
class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    // Nullable StateFlow so the screen can distinguish "not yet loaded" from "loaded empty"
    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setThemeMode(mode: com.redraft.ui.theme.ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { store.setDynamicColor(enabled) }
    }

    fun setAmoled(enabled: Boolean) {
        viewModelScope.launch { store.setAmoled(enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { store.setKeepScreenOn(enabled) }
    }

    fun setReopenAtLastPage(enabled: Boolean) {
        viewModelScope.launch { store.setReopenAtLastPage(enabled) }
    }

    companion object {
        fun factory(context: android.content.Context): androidx.lifecycle.ViewModelProvider.Factory {
            val app = context.applicationContext
            return viewModelFactory {
                initializer {
                    SettingsViewModel(SettingsRepository(app))
                }
            }
        }
    }
}