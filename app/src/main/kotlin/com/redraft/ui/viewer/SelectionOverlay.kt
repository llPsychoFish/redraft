package com.redraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ViewportState
import com.redraft.pdfcore.selection.TextLine
import com.redraft.R
import kotlin.math.abs

// ─── Types ────────────────────────────────────────────────────────────────────

/**
 * The result of a text selection: the selected line and its paragraph block
 * metadata, both in PDF user space (D-06, D-07, D-15).
 *
 * @param selectedLine the line under the tap, or `null` when there is no
 *   selection (empty page or tap missed all hit areas).
 * @param blockId block metadata set by [com.redraft.pdfcore.selection.groupBlocks];
 *   `null` when [selectedLine] is `null` or before block grouping runs.
 * @param editable `true` when the selected line is editable (D-11).  Vertical
 *   and right-to-left lines are selectable but never editable.
 */
data class SelectionOverlayState(
    val selectedLine: TextLine? = null,
    val blockId: String? = null,
    val editable: Boolean = true,
)

// ─── Overlay ─────────────────────────────────────────────────────────────────

/**
 * Draws the selection rectangle for the currently selected line (D-24, D-26,
 * D-27).
 *
 * The overlay is invisible when [state] has no selected line, and it is drawn
 * only on the page that owns the selection — scrolling back to that page
 * redraws it in the same place, because every corner goes through the SAME
 * [transform] the page bitmap uses (D-02, D-26).
 *
 * Visual contract:
 * - **editable line**: a 2 dp outline in the theme's primary colour (brand
 *   amber) with a 12–15 % primary fill (D-26).
 * - **non-editable line** (vertical / right-to-left, D-11): a dashed grey
 *   outline with **no fill at all** — the tint would suggest the text can be
 *   edited, and it cannot.
 * - the stroke width is read in **screen pixels** (`2.dp.toPx()`), never
 *   multiplied by the zoom: the outline stays 2 dp at every zoom level (D-26).
 * - colours come from the theme tokens only — no hex literal in this file
 *   (D-27).
 * - the overlay takes **no pointer input**: it must never intercept a tap
 *   (D-26).
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

    val line = state.selectedLine
    val editable = state.editable && line.fragment.editable
    val lineText = line.chars.joinToString("") { it.text }
    val description = stringResource(R.string.cd_selected_line, lineText)

    // Theme tokens only (D-27): primary is the brand amber; `outline` is the
    // scheme's grey, which is legible on both light and dark pages.
    val primary = MaterialTheme.colorScheme.primary
    val nonEditableStroke = MaterialTheme.colorScheme.outline

    Canvas(
        modifier = modifier
            .fillMaxSize()
            // TalkBack announces the selected line when the selection changes,
            // without stealing focus (D-27). `semantics` consumes no touches.
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val path = selectionPath(line, transform, viewport)
        if (editable) {
            // Amber fill at 13 % (D-26's 12–15 % band) plus the 2 dp outline.
            drawPath(path, color = primary.copy(alpha = 0.13f))
            drawPath(path, color = primary, style = Stroke(width = 2.dp.toPx()))
        } else {
            // Dashed grey outline, NO fill (D-11).
            drawPath(
                path = path,
                color = nonEditableStroke,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }
    }
}

// ─── Geometry ────────────────────────────────────────────────────────────────

/**
 * The selection rectangle as a screen-space path.
 *
 * The corners are mapped through [transform] — the same conversion the page
 * bitmap uses — so the overlay cannot drift from the page during a pinch or a
 * pan (D-02, D-26).
 *
 * The box is oriented along the fragment's baseline direction: its extent
 * along the direction is the projection of the line's glyph union onto that
 * direction, and the perpendicular extent is the projection onto the normal.
 * For horizontal text this is exactly the glyph union; for a rotated fragment
 * it is the oriented box the decision requires (D-26).
 */
private fun selectionPath(
    line: TextLine,
    transform: PdfViewportTransform,
    viewport: ViewportState,
): Path {
    val rect = line.rect
    val dirX = line.fragment.style.directionX
    val dirY = line.fragment.style.directionY
    val along = abs(rect.width * dirX) + abs(rect.height * dirY)
    val across = abs(rect.width * -dirY) + abs(rect.height * dirX)

    val cx = (rect.minX + rect.maxX) * 0.5f
    val cy = (rect.minY + rect.maxY) * 0.5f
    val ax = dirX * along * 0.5f
    val ay = dirY * along * 0.5f
    val bx = -dirY * across * 0.5f
    val by = dirX * across * 0.5f

    val corners = listOf(
        PdfPoint(cx - ax - bx, cy - ay - by),
        PdfPoint(cx + ax - bx, cy + ay - by),
        PdfPoint(cx + ax + bx, cy + ay + by),
        PdfPoint(cx - ax + bx, cy - ay + by),
    )

    val path = Path()
    corners.forEachIndexed { index, corner ->
        val screen = transform.pdfToScreen(corner, viewport)
        if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
    }
    path.close()
    return path
}
