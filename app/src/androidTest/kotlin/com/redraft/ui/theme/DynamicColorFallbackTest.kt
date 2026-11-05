package com.redraft.ui.theme

import android.content.res.Resources
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #6: on a ROM that ships API 31 but not the Material You palette overlay, every
 * `android.R.color.system_*` lookup that material3 performs through `Resources.getColor(id, theme)`
 * throws `Resources.NotFoundException`. RedraftTheme wraps the whole UI, so the throw escaped the
 * first composition and the app died before drawing a frame.
 *
 * An emulator cannot be stripped of its overlay, so the failure is produced with the real
 * framework call on an id that cannot resolve — the same exception class, from the same method the
 * palette lookup uses.
 */
@RunWith(AndroidJUnit4::class)
class DynamicColorFallbackTest {

    @Test(expected = Resources.NotFoundException::class)
    fun unresolvablePaletteIdThrows_NotFoundException() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.resources.getColor(Resources.ID_NULL, context.theme)
    }

    @Test
    fun unreadablePalette_reportsUnavailableInsteadOfCrashing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val schemes = dynamicColorSchemesOrUnavailable(
            light = {
                context.resources.getColor(Resources.ID_NULL, context.theme)
                lightColorScheme
            },
            dark = { darkColorScheme },
        )
        assertNull(schemes)
    }

    @Test
    fun palettePresent_stillResolvesBothDynamicSchemes() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val schemes = dynamicColorSchemesOrUnavailable(
            light = { dynamicLightColorScheme(context) },
            dark = { dynamicDarkColorScheme(context) },
        )
        assertNotNull(schemes)
    }
}
