package com.redraft.ui.support

/**
 * One external target: the copy shown to the reader and the `https` URL behind it.
 */
data class SupportLink(val label: String, val url: String)

/**
 * Every external URL the app can hand to another app lives here, so changing a link is a
 * one-line edit (D-20).
 *
 * Two rules hold for every entry:
 *
 * - **https only.** An entry with any other scheme is never launchable — [isAllowed] refuses it.
 * - **Allow-listed host only.** [allowedHosts] is the complete list of hosts this build will
 *   ever open. A new URL on a new host needs its host added here too; otherwise the row is
 *   treated as unconfigured rather than opened (T-01-05).
 *
 * Support entries are external links, never an in-app payment flow: there is no billing
 * library and no `INTERNET` permission anywhere in this app (D-19, D-36). Donations also never
 * gate a feature (D-21).
 */
object SupportLinks {

    // TODO(developer): fill these in before release (developer decision, see 01.1-04 user_setup).
    // Until a URL is set, its row is omitted from the UI — never greyed out, never an error.
    const val GITHUB_SPONSORS: String = ""
    const val KO_FI: String = ""
    const val BUY_ME_A_COFFEE: String = ""
    const val MOBILE_MONEY: String = ""

    // TODO(developer): point these at the real repository before release.
    const val SOURCE_CODE: String = ""
    const val REPORT_ISSUE: String = ""

    /**
     * The complete host allow-list (T-01-05). Subdomains of a listed host are accepted, so
     * `raw.githubusercontent.com` passes while `github.com.evil.example` does not.
     */
    val allowedHosts: Set<String> = setOf(
        "github.com",
        "ko-fi.com",
        "buymeacoffee.com",
    )

    /** Support-screen targets, in display order. Blank URLs are filtered out by the screen. */
    val supportLinks: List<SupportLink> = listOf(
        SupportLink("GitHub Sponsors", GITHUB_SPONSORS),
        SupportLink("Ko-fi", KO_FI),
        SupportLink("Buy Me a Coffee", BUY_ME_A_COFFEE),
        SupportLink("Mobile Money", MOBILE_MONEY),
    )

    /** About-screen link targets, in display order. */
    val aboutLinks: List<SupportLink> = listOf(
        SupportLink("Source code", SOURCE_CODE),
        SupportLink("Report an issue", REPORT_ISSUE),
    )

    /** The entries from [links] that are both configured and launchable, in order. */
    fun configured(links: List<SupportLink>): List<SupportLink> =
        links.filter { it.url.isNotBlank() && isAllowed(it.url) }

    /**
     * True only for an `https` URL whose host is in [allowedHosts].
     *
     * Parsing is deliberately strict: no userinfo trickery (`https://github.com@evil.example`),
     * no `intent://`, no `javascript:`, and no bare or relative paths.
     */
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        if (host.isEmpty()) return false
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }
}
