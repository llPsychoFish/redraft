package com.redraft.release

import com.redraft.BuildConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the release contract (RELEASE-01…04). These are build-configuration assertions, not
 * runtime behaviour: they fail the JVM suite the moment someone weakens versioning, adds a 32-bit
 * ABI, grants `INTERNET`, or pastes a keystore password into the build script.
 *
 * The unit test's working directory is the `:app` module directory.
 */
class ReleaseConfigTest {

    private val buildScript = File("build.gradle.kts").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `versionName is semantic and matches the compiled BuildConfig`() {
        assertTrue(
            "versionName must be MAJOR.MINOR.PATCH, was ${BuildConfig.VERSION_NAME}",
            Regex("""\d+\.\d+\.\d+""").matches(BuildConfig.VERSION_NAME),
        )
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
        assertTrue("versionName in the script must be quoted", buildScript.contains("versionName = \"${BuildConfig.VERSION_NAME}\""))
    }

    @Test
    fun `versionCode has been incremented for this release`() {
        assertTrue(
            "versionCode must increase with every published build, was ${BuildConfig.VERSION_CODE}",
            BuildConfig.VERSION_CODE >= 2,
        )
    }

    @Test
    fun `only the two supported 64-bit ABIs ship`() {
        assertTrue(buildScript.contains("\"arm64-v8a\""))
        assertTrue(buildScript.contains("\"x86_64\""))
        assertFalse("32-bit ARM must not ship", buildScript.contains("armeabi-v7a"))
        assertFalse(
            "plain x86 must not ship",
            Regex("""["']x86["']""").containsMatchIn(buildScript),
        )
    }

    @Test
    fun `the release manifest grants no network permission and no tracking SDK`() {
        assertFalse(manifest.contains("android.permission.INTERNET"))
        listOf("firebase", "crashlytics", "billing", "analytics").forEach { sdk ->
            assertFalse("no $sdk dependency", buildScript.lowercase().contains(sdk))
        }
    }

    @Test
    fun `signing credentials are never committed`() {
        // The script must read the secrets, not embed them.
        assertTrue(buildScript.contains("local.properties"))
        assertFalse(
            "no literal store password in the build script",
            Regex("""storePassword\s*=\s*"(""|[^"]+)"""").containsMatchIn(buildScript),
        )
        val repositoryRoot = File("..")
        val keystores = repositoryRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "jks" || it.extension == "keystore") }
            .toList()
        assertTrue("no keystore may be checked out in the repository: $keystores", keystores.isEmpty())
    }
}
