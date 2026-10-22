package com.redraft.ui.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands [url] to another app with `ACTION_VIEW` (D-19). There is no in-app browser, no Custom
 * Tab dependency, and no billing library.
 *
 * Returns `false` when nothing on the device can open an `https` URL, or when [url] is not on
 * the [SupportLinks.allowedHosts] allow-list. Callers show the link-failure snackbar in that
 * case — the URL is never launched on a guess (T-01-05).
 */
internal fun openExternalLink(context: Context, url: String): Boolean {
    if (!SupportLinks.isAllowed(url)) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
