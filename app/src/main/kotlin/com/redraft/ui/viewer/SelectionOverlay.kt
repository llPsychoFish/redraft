package com.redraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Constraints
import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ViewportState
import com.redraft.pdfcore.selection.TextLine
import com.redraft.R

// ─── Types ────────────────────────────────────────────────────────────────────

/**
 * The result of a text selection: the selected line and its paragraph block
 * metadata, both in PDF user space (D-06, D-07, D-15).
 *
 * @param selectedLine the line under the tap, or `null` when there is no
 *   selection (empty page or tap missed all hit areas).
 * @param blockId block metadata set by [com.redraft.pdfcore.selection.groupBlocks];
 *   `null` when [selectedLine] is `null` or before block grouping runs.
 * @param editable `true` when the selected line is editable (D-11).
 *   Always `true` until a binding exposes text direction.
 */
data class SelectionOverlayState(
    val selectedLine: TextLine? = null,
    val blockId: String? = null,
    val editable: Boolean = true,
)

// ─── Overlay ─────────────────────────────────────────────────────────────────

/**
 * Draws the caret and selection handles for the currently selected line (D-15).
 *
 * The overlay is invisible when [state] has no [SelectionState.selectedLine].
 * Only the caret is drawn in Phase 2; handles and drag tracking are planned
 * for the edit phase.
 *
 * @param pageIndex used to show the caret only on the selected page.
 * @param viewport used to compute the pixel scale for padding.
 * @param transform maps PDF user-space points to screen pixels (y-flip applied).
 */
@Composable
fun SelectionOverlay(
    pageIndex: Int,
    visiblePageIndex: Int,
    state: SelectionOverlayState?,
    viewport: ViewportState,
    transform: PdfViewportTransform,
    modifier: Modifier = Modifier,
) {
    // Show only on the page that owns the selection.
    if (state == null || state.selectedLine == null || pageIndex != visiblePageIndex) return

    val caretColor = colorResource(R.color.selection_caret)
    val line = state.selectedLine

    Canvas(modifier = modifier.fillMaxSize()) {
        drawCaret(
            line = line,
            viewport = viewport,
            transform = transform,
            caretColor = caretColor,
        )
    }
}

// ─── Caret drawing ───────────────────────────────────────────────────────────

private fun DrawScope.drawCaret(
    line: TextLine,
    viewport: ViewportState,
    transform: PdfViewportTransform,
    caretColor: Color,
) {
    val pxPerPdfUnit = transform.pdfUnitsPerPixel(viewport.zoom)

    // Baseline y in PDF space → screen y (y is flipped)
    val baselinePdfY = line.rect.minY
    val baselineScreenY = transform.pdfToScreen(PdfPoint(0f, baselinePdfY), viewport).y

    val caretWidthPx = (1.5f * pxPerPdfUnit).coerceAtLeast(1.5f)
    val caretHeightPx = (line.rect.height + 2f * pxPerPdfUnit)

    val leftScreenX = transform.pdfToScreen(PdfPoint(line.rect.minX, baselinePdfY), viewport).x

    // Vertical caret: x = left edge, y = baseline - height, full height of the line
    drawRect(
        color = caretColor,
        topLeft = Offset(leftScreenX - caretWidthPx / 2f, baselineScreenY - caretHeightPx),
        size = androidx.compose.ui.geometry.Size(caretWidthPx, caretHeightPx),
    )
}

/** Parse "#rrggbb" or "#aarrggbb" strings to Compose Color. */
private fun parseColor(hex: String): Color {
    return try {
        val argb = when (hex.length) {
            7 -> 0xff000000.toInt() or hex.substring(1).toInt(16)          // "#rrggbb"
            9 -> hex.substring(1).toInt(16)                                  // "#aarrggbb"
            else -> 0xff000000.toInt()
        }
        Color(argb)
    } catch (_: Exception) {
        Color.Blue  // fallback: visible on light and dark backgrounds
    }
}

// ─── Empty/no-text placeholder ───────────────────────────────────────────────

/** The string shown when tapping a page that contains no extractable text. */
fun noTextString(): String = "Tap text to select it."
