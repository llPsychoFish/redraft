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
 * Screen tap → [HitResult] using the rules of D-06, D-07, D-08, D-09.
 *
 * - Coordinate conversion: screen (y-down) → PDF user space (y-up) via
 *   [PdfViewportTransform.screenToPdf] (D-02).
 * - Hit area: union of glyph boxes + 8 dp / 32 dp screen-pixel padding (D-08).
 * - Split at midpoint of adjacent baselines (D-08): implemented by expanding
 *   each line's hit area to exactly half the distance to the next line.
 * - Nearest-centre tiebreak: when a tap lands in the overlap of two hit areas
 *   the line whose centre is closest to the tap wins (D-09).
 * - Block grouping is NEVER consulted by this function (D-07).
 *
 * Returns `null` when the tap misses all hit areas.
 */
fun hitTest(
    tapScreen: PdfPoint,
    layout: TextPageLayout,
    viewport: ViewportState,
    transform: PdfViewportTransform,
    paddingHorizPx: Float = 8f,
    minHeightPx: Float = 32f,
): HitResult? {
    val tapPdf = transform.screenToPdf(
        ScreenPoint(tapScreen.x, tapScreen.y),
        viewport,
    )

    val lines = layout.reducedLines
    if (lines.isEmpty()) return null

    val pppx = transform.pdfUnitsPerPixel(viewport.zoom)

    // Build expanded hit areas for each line.
    // For each line i, the hit area top boundary is the midpoint between
    // line i's rect.maxY and line (i-1)'s rect.minY.
    // Similarly for bottom boundary vs. line (i+1).
    // Where no neighbour exists, use a fixed padding of minHeightPx / 2.
    val halfMinHeight = (minHeightPx * pppx) * 0.5f

    data class LineHitArea(val line: TextLine, val expanded: PdfRect)

    val hitAreas = List(lines.size) { i ->
        val r = lines[i].rect
        val padX = paddingHorizPx * pppx
        // Vertical midpoint between line i-1 and line i
        val topPad = if (i > 0) {
            val prevBottom = lines[i - 1].rect.maxY
            maxOf((r.minY - prevBottom) * 0.5f, halfMinHeight)
        } else halfMinHeight
        // Vertical midpoint between line i and line i+1
        val bottomPad = if (i < lines.lastIndex) {
            val nextTop = lines[i + 1].rect.minY
            maxOf((nextTop - r.maxY) * 0.5f, halfMinHeight)
        } else halfMinHeight

        val expanded = PdfRect(
            minX = r.minX - padX,
            minY = r.minY - topPad,
            maxX = r.maxX + padX,
            maxY = r.maxY + bottomPad,
        )
        LineHitArea(lines[i], expanded)
    }

    // Pick nearest-centre line whose hit area contains the tap (D-09).
    var best: LineHitArea? = null
    var bestDistSq = Float.MAX_VALUE

    for (ha in hitAreas) {
        if (ha.expanded.contains(tapPdf.x, tapPdf.y)) {
            val cx = (ha.line.rect.minX + ha.line.rect.maxX) * 0.5f
            val cy = (ha.line.rect.minY + ha.line.rect.maxY) * 0.5f
            val dx = tapPdf.x - cx
            val dy = tapPdf.y - cy
            val distSq = dx * dx + dy * dy
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                best = ha
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
