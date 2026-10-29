package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests on character boxes taken from a real PDF by PDFium.
 *
 * Synthetic boxes hid the bug: real PDFium boxes are INK boxes, so a quote, hyphen or '='
 * sits above the baseline and a 'g' or 'y' hangs below it.  The old reducer used the box
 * bottom as the baseline and shattered a 21-line page into 190 fragments.
 */
class RealPdfiumLineReductionTest {

    private val chars = realPdfiumChars()
    private val layout = TextPageLayout(0, chars)
    private fun text(line: TextLine) = line.chars.joinToString("") { it.text }.trim()

    private val viewportWidthPx = 720
    private val transform = PdfViewportTransform(
        pageWidthPt = 612f, pageHeightPt = 792f, rotationDegrees = 0,
        renderedWidthPx = viewportWidthPx, renderedHeightPx = (viewportWidthPx * 792f / 612f).toInt(),
    )

    @Test fun realPageReducesToOneLinePerVisualLine() {
        assertEquals(21, layout.reducedLines.size)
    }

    @Test fun lineWithQuotesAndEqualsSignStaysWhole() {
        val line = layout.reducedLines.first { text(it).startsWith("android:layout_height") }
        assertEquals("android:layout_height=\"match_parent\"", text(line))
    }

    @Test fun reductionDoesNotDependOnPdfiumLineBreakChars() {
        val noBreaks = chars.filter { it.text != "\r" && it.text != "\n" }
        assertEquals(21, TextPageLayout(0, noBreaks).reducedLines.size)
    }

    @Test fun tapOnTheMiddleOfAnyLineSelectsThatWholeLine() {
        val viewport = ViewportState(1f, 0f, 0f)
        for (line in layout.reducedLines) {
            val center = transform.pdfToScreen(
                PdfPoint((line.rect.minX + line.rect.maxX) / 2f, (line.rect.minY + line.rect.maxY) / 2f), viewport,
            )
            val hit = hitTest(PdfPoint(center.x, center.y), layout, viewport, transform)
            assertNotNull("tap on [${text(line)}] hit nothing", hit)
            assertEquals(text(line), text(hit!!.line))
        }
    }

    /**
     * The viewer draws a zoomed page centred in the viewport, then pans it.  The page's LEFT
     * EDGE on screen is (viewportWidth - zoom * viewportWidth) / 2 + panX.  The transform's
     * panX means "left edge", so the caller MUST pass the left edge, not the raw pan.
     */
    @Test fun zoomedTapSelectsTheLineWhenTheViewportReceivesThePageLeftEdge() {
        val zoom = 2f
        val line = layout.reducedLines.first { text(it).startsWith("android:layout_height") }
        val pageLeftPx = (viewportWidthPx - zoom * viewportWidthPx) / 2f // panX = 0
        val onScreenAtZeroLeft = transform.pdfToScreen(
            PdfPoint((line.rect.minX + line.rect.maxX) / 2f, (line.rect.minY + line.rect.maxY) / 2f),
            ViewportState(zoom, 0f, 0f),
        )
        val tap = PdfPoint(onScreenAtZeroLeft.x + pageLeftPx, onScreenAtZeroLeft.y)

        // What the app passed before the fix: the raw pan. It misses.
        assertNull(hitTest(tap, layout, ViewportState(zoom, 0f, 0f), transform))
        // With the page's real left edge it hits the right line.
        val hit = hitTest(tap, layout, ViewportState(zoom, pageLeftPx, 0f), transform)
        assertNotNull(hit)
        assertTrue(text(hit!!.line).startsWith("android:layout_height"))
    }
}
