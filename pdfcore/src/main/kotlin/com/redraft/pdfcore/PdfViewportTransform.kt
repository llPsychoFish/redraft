package com.redraft.pdfcore

import kotlin.math.abs
import kotlin.math.roundToInt

data class PdfPoint(val x: Float, val y: Float)

data class ScreenPoint(val x: Float, val y: Float)

data class ViewportState(val zoom: Float, val panX: Float, val panY: Float)

data class RenderedSize(val widthPx: Int, val heightPx: Int)

/**
 * Pure screen↔PDF coordinate transform (decision D-14). Reused verbatim by
 * Phase 2 hit-testing, so it must stay free of Android framework types and be
 * unit-testable as pure math.
 *
 * Coordinate systems (PITFALLS #4):
 * - **PDF user space**: origin bottom-left, y increases upward, units are
 *   points, and the page is un-rotated.
 * - **Screen space**: origin top-left, y increases downward, units are pixels.
 *
 * [rotationDegrees] is the page's intrinsic `/Rotate` applied clockwise for
 * display. The rotated (display) space also has a top-left origin and y-down,
 * so [pdfToScreen] composes rotation with the zoom/pan scale.
 *
 * [cropOriginX]/[cropOriginY] is the CropBox origin in absolute PDF user space:
 * char boxes and tap points arrive in absolute coordinates while display space
 * starts at the CropBox corner, so the origin is subtracted exactly once inside
 * [pdfToRotated] and added back exactly once inside [rotatedToPdf]
 * (02-RESEARCH §2.3). This class is therefore the ONLY place that knows about
 * rotation *and* CropBox offset — no renderer, hit test or overlay may add a
 * second step (D-02, D-14).
 */
class PdfViewportTransform(
    val pageWidthPt: Float,
    val pageHeightPt: Float,
    rotationDegrees: Int,
    val renderedWidthPx: Int,
    val renderedHeightPx: Int,
    val cropOriginX: Float = 0f,
    val cropOriginY: Float = 0f,
) {

    val rotationDegrees: Int = normalizeRotation(rotationDegrees)

    private val isQuarterTurn: Boolean
        get() = rotationDegrees == 90 || rotationDegrees == 270

    /** Base display width in px at zoom 1 (width/height swap under 90/270). */
    private val displayBaseWidthPx: Int
        get() = if (isQuarterTurn) renderedHeightPx else renderedWidthPx

    private val displayBaseHeightPx: Int
        get() = if (isQuarterTurn) renderedWidthPx else renderedHeightPx

    /** Rotated (display) page size in points. */
    private val rotatedPageWidthPt: Float
        get() = if (isQuarterTurn) pageHeightPt else pageWidthPt

    private val rotatedPageHeightPt: Float
        get() = if (isQuarterTurn) pageWidthPt else pageHeightPt

    internal fun scaleX(zoom: Float): Float = zoom * displayBaseWidthPx / rotatedPageWidthPt

    internal fun scaleY(zoom: Float): Float = zoom * displayBaseHeightPx / rotatedPageHeightPt

    /**
     * Points per screen pixel at [zoom] (the inverse of [scaleX]): how hit-area
     * padding expressed in dp converts to PDF units, so it shrinks in PDF space
     * as the user zooms in (D-08).
     */
    fun pdfUnitsPerPixel(zoom: Float): Float = rotatedPageWidthPt / (displayBaseWidthPx * zoom)

    /** Zoom that makes the displayed page exactly fill [viewportWidthPx]. */
    fun fitWidthZoom(viewportWidthPx: Int): Float =
        viewportWidthPx.toFloat() / displayBaseWidthPx

    fun renderedSize(zoom: Float): RenderedSize = RenderedSize(
        widthPx = (displayBaseWidthPx * zoom).roundToInt(),
        heightPx = (displayBaseHeightPx * zoom).roundToInt(),
    )

    fun pdfToScreen(pdf: PdfPoint, viewport: ViewportState): ScreenPoint {
        val rotated = pdfToRotated(pdf)
        return ScreenPoint(
            x = viewport.panX + rotated.x * scaleX(viewport.zoom),
            y = viewport.panY + rotated.y * scaleY(viewport.zoom),
        )
    }

    fun screenToPdf(screen: ScreenPoint, viewport: ViewportState): PdfPoint {
        val rotated = PdfPoint(
            x = (screen.x - viewport.panX) / scaleX(viewport.zoom),
            y = (screen.y - viewport.panY) / scaleY(viewport.zoom),
        )
        return rotatedToPdf(rotated)
    }

    private fun pdfToRotated(pdf: PdfPoint): PdfPoint {
        // Crop origin subtracted exactly once, BEFORE the rotation mapping.
        val x = pdf.x - cropOriginX
        val y = pdf.y - cropOriginY
        return when (rotationDegrees) {
            0 -> PdfPoint(x, pageHeightPt - y)
            90 -> PdfPoint(y, x)
            180 -> PdfPoint(pageWidthPt - x, y)
            270 -> PdfPoint(pageHeightPt - y, pageWidthPt - x)
            else -> error("Unsupported rotation $rotationDegrees")
        }
    }

    private fun rotatedToPdf(rotated: PdfPoint): PdfPoint {
        val pdf = when (rotationDegrees) {
            0 -> PdfPoint(rotated.x, pageHeightPt - rotated.y)
            90 -> PdfPoint(rotated.y, rotated.x)
            180 -> PdfPoint(pageWidthPt - rotated.x, rotated.y)
            270 -> PdfPoint(pageWidthPt - rotated.y, pageHeightPt - rotated.x)
            else -> error("Unsupported rotation $rotationDegrees")
        }
        // Crop origin added back exactly once, AFTER the inverse mapping.
        return PdfPoint(pdf.x + cropOriginX, pdf.y + cropOriginY)
    }

    companion object {
        private val SUPPORTED_ROTATIONS = intArrayOf(0, 90, 180, 270)

        fun normalizeRotation(degrees: Int): Int {
            val wrapped = ((degrees % 360) + 360) % 360
            return SUPPORTED_ROTATIONS.minByOrNull { abs(it - wrapped) } ?: 0
        }
    }
}
