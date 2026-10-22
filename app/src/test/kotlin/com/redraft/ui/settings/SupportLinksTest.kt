package com.redraft.ui.settings

import com.redraft.ui.support.SupportLink
import com.redraft.ui.support.SupportLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards T-01-05 (untrusted external link): the app may only launch `https` URLs whose host is
 * on the compiled allow-list, and an unconfigured URL is omitted rather than opened.
 */
class SupportLinksTest {

    @Test
    fun `every configured link is https on an allow-listed host`() {
        val all = SupportLinks.supportLinks + SupportLinks.aboutLinks
        for (link in all) {
            if (link.url.isBlank()) continue
            assertTrue("${link.label} must use https: ${link.url}", link.url.startsWith("https://"))
            assertTrue("${link.label} host must be allow-listed: ${link.url}", SupportLinks.isAllowed(link.url))
        }
    }

    @Test
    fun `blank urls are omitted from the configured lists`() {
        val links = listOf(
            SupportLink("Unset", ""),
            SupportLink("Blank", "   "),
            SupportLink("Set", "https://github.com/redraft"),
        )
        assertEquals(listOf("Set"), SupportLinks.configured(links).map { it.label })
    }

    @Test
    fun `non-https schemes are refused`() {
        assertFalse(SupportLinks.isAllowed("http://github.com/redraft"))
        assertFalse(SupportLinks.isAllowed("javascript:alert(1)"))
        assertFalse(SupportLinks.isAllowed("intent://github.com#Intent;end"))
        assertFalse(SupportLinks.isAllowed("file:///etc/passwd"))
        assertFalse(SupportLinks.isAllowed("github.com/redraft"))
        assertFalse(SupportLinks.isAllowed(""))
    }

    @Test
    fun `hosts outside the allow-list are refused`() {
        assertFalse(SupportLinks.isAllowed("https://evil.example/donate"))
        // Suffix-confusion must not pass as a listed host.
        assertFalse(SupportLinks.isAllowed("https://github.com.evil.example/redraft"))
        // Userinfo must not masquerade as the host.
        assertFalse(SupportLinks.isAllowed("https://github.com@evil.example/redraft"))
    }

    @Test
    fun `allow-listed hosts and their subdomains are accepted`() {
        assertTrue(SupportLinks.isAllowed("https://github.com/redraft"))
        assertTrue(SupportLinks.isAllowed("https://gist.github.com/x"))
        assertTrue(SupportLinks.isAllowed("https://paypal.me/redraft"))
        assertTrue(SupportLinks.isAllowed("https://buymeacoffee.com/redraft"))
    }

    @Test
    fun `the configured about links keep display order`() {
        val labels = SupportLinks.aboutLinks.map { it.label }
        assertEquals(listOf("Source code", "Report an issue"), labels)
    }

    @Test
    fun `the configured support links keep display order`() {
        val labels = SupportLinks.supportLinks.map { it.label }
        assertEquals(
            listOf("GitHub Sponsors", "Buy Me a Coffee", "PayPal"),
            labels,
        )
    }
}
