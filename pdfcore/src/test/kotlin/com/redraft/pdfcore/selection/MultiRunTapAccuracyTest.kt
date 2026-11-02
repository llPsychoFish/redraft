package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ViewportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tap accuracy on a Word-style page (real PDFium geometry, see [MultiRunPdfiumFixture]).
 *
 * The page is a list: a marker run ("1."), a bold label run and a justified body run all
 * share one visual line, and the body wraps onto a hanging-indent continuation line.
 */
class MultiRunTapAccuracyTest {

    private val chars = multiRunPdfiumChars()
    private val layout = TextPageLayout(0, chars)
    private val lines = layout.reducedLines

    private fun text(line: TextLine) = line.chars.joinToString("") { it.text }.trim()

    private val viewportWidthPx = 720
    private val transform = PdfViewportTransform(
        pageWidthPt = 612f, pageHeightPt = 792f, rotationDegrees = 0,
        renderedWidthPx = viewportWidthPx,
        renderedHeightPx = (viewportWidthPx * 792f / 612f).toInt(),
    )
    private val viewport = ViewportState(1f, 0f, 0f)

    /** A 1x-density stand-in: the app passes `8.dp.toPx()` / `32.dp.toPx()`. */
    private val tolerance = TapTolerance(horizontalPx = 8f, minLineHeightPx = 32f)

    /** Screen px of a PDF point. */
    private fun screenOf(x: Float, y: Float): PdfPoint {
        val s = transform.pdfToScreen(PdfPoint(x, y), viewport)
        return PdfPoint(s.x, s.y)
    }

    /** The body line of list item 1 — the long line the user actually means to tap. */
    private val activitiesLine = lines.first { text(it).startsWith("Activities:") }

    @Test fun markerAndBodyAreSeparateLines() {
        // Documents the shape the hit test has to cope with: the marker is its own line,
        // 12 pt to the LEFT of the body it belongs to visually.
        val marker = lines.first { text(it) == "1." }
        assertTrue(
            "marker rect ${marker.rect} must sit left of the body",
            marker.rect.maxX < activitiesLine.rect.minX,
        )
    }

    @Test fun tapOnTheStartOfTheBodyLineSelectsThatBodyLine() {
        // "Activities:" begins at x=90.1. A tap on its first letters is unambiguously on
        // the body line.
        val tap = screenOf(activitiesLine.rect.minX + 2f, (activitiesLine.rect.minY + activitiesLine.rect.maxY) / 2f)
        val hit = hitTest(tap, layout, viewport, transform, tolerance)
        assertEquals(
            "tap on the left end of the body line selected something else",
            text(activitiesLine),
            hit?.let { text(it.line) },
        )
    }

    @Test fun tapInTheLeftMarginDoesNotStealTheBodyLine() {
        // A tap at x=76 is on the "1." marker's own column, so the marker is the right
        // answer — but it must not win for a tap further right than the marker's box.
        val marker = lines.first { text(it) == "1." }
        val tap = screenOf(marker.rect.maxX + 6f, (activitiesLine.rect.minY + activitiesLine.rect.maxY) / 2f)
        val hit = hitTest(tap, layout, viewport, transform, tolerance)
        assertEquals(
            "the 6 pt-wide gap right of the marker belongs to the body line, not the marker",
            text(activitiesLine),
            hit?.let { text(it.line) },
        )
    }

    @Test fun tapBetweenTwoLinesSelectsTheNearerOne() {
        // The whitespace band between body line 1 and its continuation.
        val cont = lines.first { text(it).startsWith("screen of an email") }
        val midY = (activitiesLine.rect.minY + cont.rect.maxY) / 2f
        val tap = screenOf(200f, midY + 1f)
        val hit = hitTest(tap, layout, viewport, transform, tolerance)
        assertTrue("tap in the inter-line band hit nothing", hit != null)
        val chosen = text(hit!!.line)
        assertTrue(
            "tap just below body line 1 selected [$chosen]",
            chosen.startsWith("Activities:") || chosen.startsWith("screen of an email"),
        )
    }

    @Test fun everyVisualLineIsReachableAcrossItsWholeWidth() {
        for (line in lines.filter { it.rect.width > 40f }) {
            for (fraction in listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.95f)) {
                val x = line.rect.minX + line.rect.width * fraction
                val y = (line.rect.minY + line.rect.maxY) / 2f
                val hit = hitTest(screenOf(x, y), layout, viewport, transform, tolerance)
                assertEquals(
                    "tap at ${(fraction * 100).toInt()}% across [${text(line)}] selected ${hit?.let { text(it.line) }}",
                    text(line),
                    hit?.let { text(it.line) },
                )
            }
        }
    }

    @Test fun tapToleranceIsScreenPixelsSoItsReachIsAConstantPhysicalSize() {
        // The app hands hitTest `8.dp.toPx()`, NOT 8f: on a 2x phone that is 16 px, and
        // passing the raw dp silently halved the reach. So the tolerance is a SCREEN
        // distance and must behave like one — the same 12 px past the end of a line is
        // out of reach for an 8 px tolerance and in reach for a 16 px one at ANY zoom,
        // because zooming multiplies both the tap and the tolerance by the same factor.
        val cy = (activitiesLine.rect.minY + activitiesLine.rect.maxY) / 2f
        val narrow = TapTolerance(horizontalPx = 8f, minLineHeightPx = 32f)
        val wide = TapTolerance(horizontalPx = 16f, minLineHeightPx = 32f)

        for (zoom in listOf(1f, 2f)) {
            val pan = (viewportWidthPx - viewportWidthPx * zoom) / 2f
            val vp = ViewportState(zoom, pan, 0f)
            val edge = transform.pdfToScreen(PdfPoint(activitiesLine.rect.maxX, cy), vp)
            val tap = PdfPoint(edge.x + 12f, edge.y)
            assertNull(
                "zoom $zoom: a tap 12 px past the line is outside an 8 px reach",
                hitTest(tap, layout, vp, transform, narrow),
            )
            assertEquals(
                "zoom $zoom: 12 px is inside a 16 px reach",
                text(activitiesLine),
                hitTest(tap, layout, vp, transform, wide)?.let { text(it.line) },
            )
        }
    }
}
