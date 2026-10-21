package com.redraft.data

import com.redraft.ui.theme.ThemeMode

// AppSettings model with defaults matching UI-SPEC (D-10)
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val amoled: Boolean = false,
    val keepScreenOn: Boolean = false,
    val reopenAtLastPage: Boolean = true,
)