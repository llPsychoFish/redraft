package com.redraft.pdfcore

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewportTransformTest {

    private val pageWidth = 612f
    private val pageHeight = 792f

    private fun transform(rotation: Int) = PdfViewportTransform(
        pageWidthPt = pageWidth,
        pageHeightPt = pageHeight,
        rotationDegrees = rotation,
        renderedWidthPx = 612,
        renderedHeightPx = 792,
    )

    private fun assertApprox(expected: Float, actual: Float, tolerance: Float = 1e-3f) {
        assertTrue(
            "expected $expected but was $actual",
            abs(expected - actual) <= tolerance,
        )
    }

    @Test
    fun roundTrip_screenToPdf_after_pdfToScreen() {
        val viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f)
        listOf(0, 90, 180, 270).forEach { rotation ->
            listOf(0.5f, 1f, 2.5f).forEach { zoom ->
                val transform = transform(rotation)
                val vp = viewport.copy(zoom = zoom, panX = 37f, panY = -19f)
                val pdf = PdfPoint(123.4f, 456.7f)

                val back = transform.screenToPdf(transform.pdfToScreen(pdf, vp), vp)

                assertApprox(pdf.x, back.x)
                assertApprox(pdf.y, back.y)
            }
        }
    }

    @Test
    fun fitWidthZoom_makes_rendered_width_match_viewport() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val transform = transform(rotation)
            val viewportWidthPx = 1080

            val zoom = transform.fitWidthZoom(viewportWidthPx)

            assertTrue(abs(transform.renderedSize(zoom).widthPx - viewportWidthPx) <= 1)
        }
    }

    @Test
    fun rotation90_swaps_rendered_dimensions() {
        val rotation0 = transform(0).renderedSize(1f)
        val rotation90 = transform(90).renderedSize(1f)

        assertEquals(rotation0.heightPx, rotation90.widthPx)
        assertEquals(rotation0.widthPx, rotation90.heightPx)
    }

    @Test
    fun screenTopLeft_maps_to_correct_pdf_corner_per_rotation() {
        val origin = ScreenPoint(0f, 0f)
        val viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f)

        // rotation 0: display top-left is the PDF's top-left (0, pageHeight)
        val r0 = transform(0).screenToPdf(origin, viewport)
        assertApprox(0f, r0.x)
        assertApprox(pageHeight, r0.y)

        // rotation 90: display top-left is the PDF's bottom-left (0, 0)
        val r90 = transform(90).screenToPdf(origin, viewport)
        assertApprox(0f, r90.x)
        assertApprox(0f, r90.y)

        // rotation 180: display top-left is the PDF's bottom-right (pageWidth, 0)
        val r180 = transform(180).screenToPdf(origin, viewport)
        assertApprox(pageWidth, r180.x)
        assertApprox(0f, r180.y)

        // rotation 270: display top-left is the PDF's top-right (pageWidth, pageHeight)
        val r270 = transform(270).screenToPdf(origin, viewport)
        assertApprox(pageWidth, r270.x)
        assertApprox(pageHeight, r270.y)
    }

    @Test
    fun pan_shifts_screen_coordinates() {
        val transform = transform(0)
        val pdf = PdfPoint(0f, pageHeight)

        val atOrigin = transform.pdfToScreen(pdf, ViewportState(1f, 0f, 0f))
        val panned = transform.pdfToScreen(pdf, ViewportState(1f, 50f, 25f))

        assertApprox(50f, panned.x - atOrigin.x)
        assertApprox(25f, panned.y - atOrigin.y)
    }

    @Test
    fun rotation_is_normalized_to_supported_multiples() {
        assertEquals(0, PdfViewportTransform.normalizeRotation(0))
        assertEquals(90, PdfViewportTransform.normalizeRotation(90))
        assertEquals(270, PdfViewportTransform.normalizeRotation(-90))
        assertEquals(0, PdfViewportTransform.normalizeRotation(360))
        assertEquals(90, PdfViewportTransform.normalizeRotation(449))
    }
}
