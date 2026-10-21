package com.redraft.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

// ThemeMode with exactly three cases: SYSTEM, LIGHT, DARK (D-02)
enum class ThemeMode {
    SYSTEM, LIGHT, DARK
}

// Pure, Android-free resolution function (D-01, D-04, D-05, UI-SPEC § Theme System Contract)
// Every Android input is a PARAMETER, not a call — so it's unit-testable
fun resolveColorScheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    amoled: Boolean,
    darkTheme: Boolean,
    dynamicAvailable: Boolean,
    lightScheme: androidx.compose.material3.ColorScheme,
    darkScheme: androidx.compose.material3.ColorScheme,
    dynamicLight: androidx.compose.material3.ColorScheme,
    dynamicDark: androidx.compose.material3.ColorScheme,
): androidx.compose.material3.ColorScheme {
    // Step 1: Resolve base scheme from themeMode
    val baseScheme = when (themeMode) {
        ThemeMode.SYSTEM -> if (darkTheme) darkScheme else lightScheme
        ThemeMode.LIGHT -> lightScheme
        ThemeMode.DARK -> darkScheme
    }

    // Step 2: Dynamic colour replaces the scheme entirely when available and enabled
    val dynamicScheme = if (dynamicColor && dynamicAvailable) {
        if (darkTheme) dynamicDark else dynamicLight
    } else null

    // Step 3: AMOLED overrides apply ONLY when the resolved scheme is dark AND not dynamic
    // (light + amoled = unchanged; dark + dynamic + amoled = unchanged dynamic; dark + static + amoled = AMOLED)
    val resolvedScheme = dynamicScheme ?: baseScheme
    val isDarkResolved = when {
        dynamicScheme != null -> darkTheme
        themeMode == ThemeMode.SYSTEM -> darkTheme
        themeMode == ThemeMode.DARK -> true
        else -> false
    }

    return if (amoled && isDarkResolved && dynamicScheme == null) {
        amoledColorScheme
    } else {
        resolvedScheme
    }
}

@Composable
fun RedraftTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    amoled: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val dynamicLight = if (dynamicAvailable) dynamicLightColorScheme(LocalContext.current) else lightColorScheme
    val dynamicDark = if (dynamicAvailable) dynamicDarkColorScheme(LocalContext.current) else darkColorScheme

    val colorScheme = resolveColorScheme(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        amoled = amoled,
        darkTheme = darkTheme,
        dynamicAvailable = dynamicAvailable,
        lightScheme = lightColorScheme,
        darkScheme = darkColorScheme,
        dynamicLight = dynamicLight,
        dynamicDark = dynamicDark,
    )

    // System bars handling (D-08) — on every scheme change
    val activity = LocalContext.current as Activity
    val insetsController = remember { WindowCompat.getInsetsController(activity.window, activity.window.decorView) }
    // Use the darkTheme parameter to determine system bar icons since it matches the resolved scheme
    val isLightScheme = !darkTheme

    // Apply system bar icon colours on every scheme change
    androidx.compose.runtime.SideEffect {
        insetsController.isAppearanceLightStatusBars = isLightScheme
        insetsController.isAppearanceLightNavigationBars = isLightScheme
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // API 26–28 only: set navigationBarColor to resolved background
            activity.window.navigationBarColor = colorScheme.background.value.toInt()
        }
        // On API 29+ do NOT set navigationBarColor
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RedraftTypography,
        content = content,
    )
}