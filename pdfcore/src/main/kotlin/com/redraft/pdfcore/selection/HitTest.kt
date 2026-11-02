package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ScreenPoint
import com.redraft.pdfcore.ViewportState

/**
 * Hit-test result: the line selected by a tap, plus metadata needed by the
 * Compose overlay (D-06, D-15).
 *
 * [charIndex] is the index of the first character of the selected line in the
 * page's flat char list — used by [TextExtraction.getText] to slice the raw
 * text range for display/editing.
 */
data class HitResult(
    val line: TextLine,
    /** First char index of the line in the page's original char list. */
    val charIndex: Int,
    /** Baseline endpoint in PDF user space — used to draw the caret. */
    val caretStartPdf: PdfPoint,
    val caretEndPdf: PdfPoint,
)

/**
 * How far a tap may sit from a line's box and still select it, in SCREEN PIXELS.
 *
 * Pixels, never dp: this module has no display density, and a tap tolerance has to
 * be a constant *physical* size, so the caller converts `8.dp.toPx()` where a
 * `Density` is in scope. Handing dp to [hitTest] silently shrinks the tolerance to
 * a fraction of its intent on a high-density phone.
 *
 * [minLineHeightPx] only matters for a line with no neighbour to bound against —
 * everywhere else the band is cut at the midpoint between two lines, which is the
 * tighter and correct bound.
 */
data class TapTolerance(
    val horizontalPx: Float,
    val minLineHeightPx: Float,
)

/**
 * Screen tap → [HitResult] using the rules of D-06, D-07, D-08, D-09.
 *
 * - Coordinate conversion: screen (y-down) → PDF user space (y-up) via
 *   [PdfViewportTransform.screenToPdf] (D-02).
 * - Hit area (D-08): the line's glyph union widened by [TapTolerance.horizontalPx]
 *   at each end, and vertically bounded by the midpoint to the nearest line that
 *   OVERLAPS it in x — so bands tile the page and never reach past halfway into
 *   a neighbour.
 * - Choice (D-09): among the lines whose band contains the tap, the one whose
 *   own box is nearest wins. Distance is measured to the line's RECT, never to
 *   its centre — a centre is meaningless between a 5 pt list marker and the
 *   450 pt line it sits in front of.
 * - Block grouping is NEVER consulted by this function (D-07).
 *
 * Returns `null` when the tap misses all hit areas.
 */
fun hitTest(
    tapScreen: PdfPoint,
    layout: TextPageLayout,
    viewport: ViewportState,
    transform: PdfViewportTransform,
    tolerance: TapTolerance,
): HitResult? {
    val tapPdf = transform.screenToPdf(
        ScreenPoint(tapScreen.x, tapScreen.y),
        viewport,
    )

    val lines = layout.reducedLines
    if (lines.isEmpty()) return null

    val pppx = transform.pdfUnitsPerPixel(viewport.zoom)
    val padX = tolerance.horizontalPx * pppx
    val maxPadY = (tolerance.minLineHeightPx * pppx) * 0.5f

    // Index sorted by vertical centre, bottom-most first (PDF y is up).
    val byY = lines.indices.sortedBy {
        val r = lines[it].rect
        (r.minY + r.maxY) * 0.5f
    }
    val positionInY = IntArray(lines.size)
    byY.forEachIndexed { slot, lineIdx -> positionInY[lineIdx] = slot }

    data class Band(val line: TextLine, val minY: Float, val maxY: Float)

    // For each line, the nearest overlapping line above and below. Two lines
    // overlap only if their x ranges meet, so a two-column page bounds each
    // column against itself and a tap never crosses the gutter.
    val bands = Array(lines.size) { i ->
        val r = lines[i].rect
        val centreY = (r.minY + r.maxY) * 0.5f
        val me = positionInY[i]

        var topPad = maxPadY
        var slot = me + 1
        while (slot < byY.size) {
            val other = lines[byY[slot]].rect
            val gap = other.minY - r.maxY
            if (gap > maxPadY * 2f) break
            if (rectsOverlapInX(r, other)) {
                topPad = ((if (gap > 0f) (r.maxY + other.minY) * 0.5f else centreY) - r.maxY)
                    .coerceIn(0f, maxPadY)
                break
            }
            slot++
        }

        var bottomPad = maxPadY
        slot = me - 1
        while (slot >= 0) {
            val other = lines[byY[slot]].rect
            val gap = r.minY - other.maxY
            if (gap > maxPadY * 2f) break
            if (rectsOverlapInX(r, other)) {
                bottomPad = (r.minY - (if (gap > 0f) (r.minY + other.maxY) * 0.5f else centreY))
                    .coerceIn(0f, maxPadY)
                break
            }
            slot--
        }

        Band(lines[i], r.minY - bottomPad, r.maxY + topPad)
    }

    // Among the bands the tap falls in, take the line whose box is nearest.
    var best: Band? = null
    var bestDx = Float.MAX_VALUE
    var bestWidth = -Float.MAX_VALUE

    for (band in bands) {
        if (tapPdf.y < band.minY || tapPdf.y > band.maxY) continue
        val r = band.line.rect
        val dx = when {
            tapPdf.x < r.minX -> r.minX - tapPdf.x
            tapPdf.x > r.maxX -> tapPdf.x - r.maxX
            else -> 0f
        }
        // A tap equidistant from a short marker and the long line beside it is
        // meant for the line, so width breaks the tie.
        if (dx < bestDx || (dx == bestDx && r.width > bestWidth)) {
            // The horizontal tolerance still gates the tap: outside every box by
            // more than padX there is nothing to select.
            if (dx <= padX) {
                bestDx = dx
                bestWidth = r.width
                best = band
            }
        }
    }

    val line = best?.line ?: return null
    val firstIdx = line.chars.firstOrNull()?.index ?: 0
    val lastChar = line.chars.lastOrNull()

    // Caret endpoints: baseline left and right of the line's rect
    val baselineY = lineBaselineOffset(line)
    val caretStart = PdfPoint(line.rect.minX, baselineY)
    val caretEnd = PdfPoint(
        lastChar?.rect?.maxX ?: line.rect.maxX,
        baselineY,
    )

    return HitResult(
        line = line,
        charIndex = firstIdx,
        caretStartPdf = caretStart,
        caretEndPdf = caretEnd,
    )
}

private fun rectsOverlapInX(a: PdfRect, b: PdfRect): Boolean =
    a.minX <= b.maxX && b.minX <= a.maxX
