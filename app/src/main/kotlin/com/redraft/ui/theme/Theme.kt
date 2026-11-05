package com.redraft.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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

/**
 * The system's dynamic light/dark schemes, or null when they cannot be read.
 *
 * material3 resolves every `android.R.color.system_accent1_*`/`system_neutral*_*` entry with
 * `Resources.getColor(id, theme)` and no fallback, so a ROM that ships API 31 but not the Material
 * You palette overlay makes the lookup throw [Resources.NotFoundException]. One UI is the case
 * behind issue #6. RedraftTheme wraps the whole UI, so the throw escapes the first composition and
 * kills the process before a frame is drawn — the app "crashes when trying to open", and no setting
 * could save it, because both schemes used to be read before the flag was even consulted.
 */
fun dynamicColorSchemesOrUnavailable(
    light: () -> ColorScheme,
    dark: () -> ColorScheme,
): Pair<ColorScheme, ColorScheme>? =
    try {
        light() to dark()
    } catch (_: Resources.NotFoundException) {
        null
    }

/** Safely finds an Activity in the context hierarchy, returns null if not found (e.g., previews, tests). */
fun findActivity(context: Context): Activity? {
    var current: Context = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
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

    // Read the palette only when dynamic colour is asked for, and treat an unreadable palette as
    // "not available" so the brand schemes — and the AMOLED contract that depends on them — take over
    val context = LocalContext.current
    val dynamicSchemes = if (dynamicColor && dynamicAvailable) {
        dynamicColorSchemesOrUnavailable(
            light = { dynamicLightColorScheme(context) },
            dark = { dynamicDarkColorScheme(context) },
        )
    } else null

    val colorScheme = resolveColorScheme(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        amoled = amoled,
        darkTheme = darkTheme,
        dynamicAvailable = dynamicSchemes != null,
        lightScheme = lightColorScheme,
        darkScheme = darkColorScheme,
        dynamicLight = dynamicSchemes?.first ?: lightColorScheme,
        dynamicDark = dynamicSchemes?.second ?: darkColorScheme,
    )

    // System bars handling (D-08) — on every scheme change
    val activity = findActivity(context)
    val insetsController = remember(activity) {
        activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView) }
    }
    // Use the darkTheme parameter to determine system bar icons since it matches the resolved scheme
    val isLightScheme = !darkTheme

    // Apply system bar icon colours on every scheme change
    androidx.compose.runtime.SideEffect {
        insetsController?.apply {
            isAppearanceLightStatusBars = isLightScheme
            isAppearanceLightNavigationBars = isLightScheme
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // API 26–28 only: set navigationBarColor to resolved background
                activity?.window?.navigationBarColor = colorScheme.background.toArgb()
            }
            // On API 29+ do NOT set navigationBarColor
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RedraftTypography,
        content = content,
    )
}