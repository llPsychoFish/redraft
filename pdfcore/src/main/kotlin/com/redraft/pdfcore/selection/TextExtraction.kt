package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfPageGeometry
import com.redraft.pdfcore.PdfRect
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * Beyond this a page is pathological: extraction returns [PageTextLayout.Failed]
 * instead of stalling the viewer on a malicious or broken document (02-RESEARCH §3.2).
 */
const val MAX_CHARS_PER_PAGE = 50_000

/**
 * Result of extracting one page as plain data: no native handles, no text-page
 * objects, no bitmaps, no Android types may cross this boundary (D-23, D-03).
 */
sealed interface PageTextLayout {
    data class Ready(val layout: TextPageLayout) : PageTextLayout
    data object Empty : PageTextLayout
    data object Failed : PageTextLayout
}

/** Everything the reducer needs from one page, as plain data. */
data class TextPageLayout(
    val pageIndex: Int,
    val chars: List<CharBox>,
)

/**
 * Normalise a binding rect: pairs (left, right) and (top, bottom) per axis with
 * min/max, so a rect whose top exceeds its bottom still yields positive height.
 * Binding field names are positionally assigned and never trusted (02-RESEARCH §1.3).
 */
internal fun normalizeCharRect(left: Float, top: Float, right: Float, bottom: Float): PdfRect =
    PdfRect(
        minX = minOf(left, right),
        minY = minOf(top, bottom),
        maxX = maxOf(left, right),
        maxY = maxOf(top, bottom),
    )

/**
 * Extract one page's text as plain data (02-RESEARCH §3).
 *
 * One page, one text page, both closed here — text page first — so no native
 * handle escapes (D-23). The full string is read once, then one box + size lookup
 * per character with `ensureActive()` every 256 chars, so a long page stays
 * cancellable and holds PDFium's global lock only one page at a time (§3.3).
 *
 * Every failure except coroutine cancellation becomes [PageTextLayout.Failed]: an
 * extraction failure is data, never a crash and never a message while loading (D-30).
 */
suspend fun pageTextLayout(
    document: PdfDocumentKt,
    index: Int,
    geometry: PdfPageGeometry,
): PageTextLayout = try {
    val opened = document.openPage(index) ?: error("PDFium could not open page $index")
    opened.use { page ->
        page.openTextPage().use { text ->
            val count = text.textPageCountChars()
            when {
                count > MAX_CHARS_PER_PAGE -> PageTextLayout.Failed
                count <= 0 -> PageTextLayout.Empty
                else -> {
                    val full = text.textPageGetText(0, count).orEmpty()
                    val chars = ArrayList<CharBox>(count)
                    for (i in 0 until count) {
                        if (i % 256 == 0) coroutineContext.ensureActive()
                        val binding = text.textPageGetCharBox(i) ?: continue
                        val rect = normalizeCharRect(
                            left = binding.left,
                            top = binding.top,
                            right = binding.right,
                            bottom = binding.bottom,
                        )
                        // Drop anything outside the CropBox: a hidden header off-page
                        // must never become selectable or draw outside the page (§5.5).
                        if (!rect.intersects(geometry.cropBox)) continue
                        chars.add(
                            CharBox(
                                index = i,
                                text = full.getOrNull(i)?.toString() ?: "",
                                rect = rect,
                                fontSizePt = text.getFontSize(i).toFloat(),
                            ),
                        )
                    }
                    PageTextLayout.Ready(TextPageLayout(geometry.pageIndex, chars))
                }
            }
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    PageTextLayout.Failed
}
