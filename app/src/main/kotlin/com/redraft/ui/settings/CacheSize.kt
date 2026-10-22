package com.redraft.ui.settings

import java.util.Locale

/**
 * Copy for the Storage section's cache-size line (01.1-UI-SPEC § Settings).
 *
 * `null` means the measurement has not landed yet, so the row says `Checking…` rather than
 * claiming an empty cache. Every other value renders as a human size, with `0 B` for an
 * emptied cache.
 */
fun formatCacheSize(bytes: Long?): String {
    if (bytes == null) return "Checking…"
    if (bytes <= 0L) return "0 B"
    if (bytes < 1024L) return "$bytes B"

    val kib = bytes / 1024.0
    if (kib < 1024.0) return decimal(kib) + " KB"

    val mib = kib / 1024.0
    if (mib < 1024.0) return decimal(mib) + " MB"

    val gib = mib / 1024.0
    return decimal(gib) + " GB"
}

private fun decimal(value: Double): String {
    val rendered = String.format(Locale.US, "%.1f", value)
    return if (rendered.endsWith(".0")) rendered.dropLast(2) else rendered
}
