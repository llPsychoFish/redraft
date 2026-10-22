package com.redraft.ui.settings

import com.redraft.data.AppSettings
import com.redraft.data.SettingsStore
import com.redraft.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Covers the Settings screen's two pure contracts (UI-01): every control writes through to the
 * store, and the Storage row renders the shared caches' live size in the contract's copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state

        var themeMode: ThemeMode? = null
        var dynamicColor: Boolean? = null
        var amoled: Boolean? = null
        var keepScreenOn: Boolean? = null
        var reopenAtLastPage: Boolean? = null

        override suspend fun setThemeMode(mode: ThemeMode) {
            themeMode = mode
            state.value = state.value.copy(themeMode = mode)
        }

        override suspend fun setDynamicColor(enabled: Boolean) {
            dynamicColor = enabled
            state.value = state.value.copy(dynamicColor = enabled)
        }

        override suspend fun setAmoled(enabled: Boolean) {
            amoled = enabled
            state.value = state.value.copy(amoled = enabled)
        }

        override suspend fun setKeepScreenOn(enabled: Boolean) {
            keepScreenOn = enabled
            state.value = state.value.copy(keepScreenOn = enabled)
        }

        override suspend fun setReopenAtLastPage(enabled: Boolean) {
            reopenAtLastPage = enabled
            state.value = state.value.copy(reopenAtLastPage = enabled)
        }
    }

    @Test
    fun `settings reflect the stored value`() = runTest {
        val store = FakeSettingsStore(
            AppSettings(themeMode = ThemeMode.DARK, amoled = true, keepScreenOn = true)
        )
        val viewModel = SettingsViewModel(store)

        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, viewModel.settings.value.themeMode)
        assertEquals(true, viewModel.settings.value.amoled)
        assertEquals(true, viewModel.settings.value.keepScreenOn)
    }

    @Test
    fun `every control writes through to the store`() = runTest {
        val store = FakeSettingsStore()
        val viewModel = SettingsViewModel(store)

        viewModel.setThemeMode(ThemeMode.LIGHT)
        viewModel.setDynamicColor(false)
        viewModel.setAmoled(true)
        viewModel.setKeepScreenOn(true)
        viewModel.setReopenAtLastPage(false)
        advanceUntilIdle()

        assertEquals(ThemeMode.LIGHT, store.themeMode)
        assertEquals(false, store.dynamicColor)
        assertEquals(true, store.amoled)
        assertEquals(true, store.keepScreenOn)
        assertEquals(false, store.reopenAtLastPage)
    }

    @Test
    fun `an unmeasured cache reports Checking instead of a fake zero`() {
        assertEquals("Checking…", formatCacheSize(null))
    }

    @Test
    fun `an emptied cache reports zero bytes`() {
        assertEquals("0 B", formatCacheSize(0L))
        assertEquals("0 B", formatCacheSize(-1L))
    }

    @Test
    fun `cache sizes render in the contract units`() {
        assertEquals("512 B", formatCacheSize(512L))
        assertEquals("1 KB", formatCacheSize(1024L))
        assertEquals("1 MB", formatCacheSize(1024L * 1024L))
        assertEquals("12.4 MB", formatCacheSize(13_002_342L))
        assertEquals("1 GB", formatCacheSize(1024L * 1024L * 1024L))
    }
}
