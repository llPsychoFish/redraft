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

    /** The transform a PdfPageItem would build for [rotation] at [viewportWidthPx]. */
    private fun slotTransform(
        rotation: Int,
        viewportWidthPx: Int,
        cropOriginX: Float = 0f,
        cropOriginY: Float = 0f,
    ): PdfViewportTransform {
        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = floatArrayOf(0f, pageHeight, pageWidth, 0f), // [left, top, right, bottom]
            mediaValues = floatArrayOf(0f, pageHeight, pageWidth, 0f),
            rawRotation = rotation / 90,
        )
        val base = geometry.slotBaseSizePx(viewportWidthPx)
        return PdfViewportTransform(
            pageWidthPt = geometry.widthPt,
            pageHeightPt = geometry.heightPt,
            rotationDegrees = geometry.rotationDegrees,
            renderedWidthPx = base.widthPx,
            renderedHeightPx = base.heightPx,
            cropOriginX = cropOriginX,
            cropOriginY = cropOriginY,
        )
    }

    @Test
    fun displayedRectCorners_mapTo_theFourPageCorners_perRotation() {
        val viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f)
        val pageCorners = listOf(
            PdfPoint(0f, 0f),
            PdfPoint(pageWidth, 0f),
            PdfPoint(pageWidth, pageHeight),
            PdfPoint(0f, pageHeight),
        )

        listOf(0, 90, 180, 270).forEach { rotation ->
            val t = transform(rotation)
            val size = t.renderedSize(1f)
            val screenCorners = listOf(
                ScreenPoint(0f, 0f),
                ScreenPoint(size.widthPx.toFloat(), 0f),
                ScreenPoint(size.widthPx.toFloat(), size.heightPx.toFloat()),
                ScreenPoint(0f, size.heightPx.toFloat()),
            )
            val mapped = screenCorners.map { t.screenToPdf(it, viewport) }

            pageCorners.forEach { expected ->
                assertTrue(
                    "rotation $rotation: expected corner $expected among $mapped",
                    mapped.any {
                        abs(it.x - expected.x) <= 0.5f && abs(it.y - expected.y) <= 0.5f
                    },
                )
            }
        }
    }

    @Test
    fun slotBuiltTransform_fillsViewport_atEveryRotation() {
        val viewportWidthPx = 1080

        listOf(0, 90, 180, 270).forEach { rotation ->
            val t = slotTransform(rotation, viewportWidthPx)

            assertEquals(
                "rotation $rotation: fitWidthZoom",
                1f,
                t.fitWidthZoom(viewportWidthPx),
                1e-3f,
            )
            assertEquals(
                "rotation $rotation: rendered width",
                viewportWidthPx,
                t.renderedSize(1f).widthPx,
            )
            // Integer slot rounding leaves at most half a point of scale skew.
            assertEquals(
                "rotation $rotation: scaleX == scaleY",
                t.scaleX(1f),
                t.scaleY(1f),
                0.01f,
            )
            assertEquals(
                "rotation $rotation: scaleX == scaleY at 2x",
                t.scaleX(2f),
                t.scaleY(2f),
                0.02f,
            )
        }
    }

    @Test
    fun cropOrigin_mapsTo_displayBottomLeft_atRotation0() {
        // /CropBox [36 36 576 756] → 540x720 crop starting at (36, 36).
        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = floatArrayOf(36f, 756f, 576f, 36f), // [left, top, right, bottom]
            mediaValues = null,
            rawRotation = 0,
        )
        assertEquals(540f, geometry.widthPt, 1e-3f)
        assertEquals(720f, geometry.heightPt, 1e-3f)

        val base = geometry.slotBaseSizePx(1080)
        val transform = PdfViewportTransform(
            pageWidthPt = geometry.widthPt,
            pageHeightPt = geometry.heightPt,
            rotationDegrees = geometry.rotationDegrees,
            renderedWidthPx = base.widthPx,
            renderedHeightPx = base.heightPx,
            cropOriginX = geometry.cropBox.minX,
            cropOriginY = geometry.cropBox.minY,
        )
        val viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f)
        val renderedHeight = transform.renderedSize(1f).heightPx.toFloat()

        // The crop origin maps to the display bottom-left (y-down → bottom = max y).
        val bottomLeft = transform.pdfToScreen(PdfPoint(36f, 36f), viewport)
        assertApprox(0f, bottomLeft.x, 0.5f)
        assertApprox(renderedHeight, bottomLeft.y, 0.5f)

        // …and the inverse maps the display bottom-left back to the crop origin.
        val back = transform.screenToPdf(ScreenPoint(0f, renderedHeight), viewport)
        assertApprox(36f, back.x, 0.5f)
        assertApprox(36f, back.y, 0.5f)
    }

    @Test
    fun cropOrigin_roundTrips_insetPoint_allRotations() {
        val viewport = ViewportState(zoom = 2f, panX = 13f, panY = -7f)
        val pdf = PdfPoint(100f, 200f)

        listOf(0, 90, 180, 270).forEach { rotation ->
            val transform = slotTransform(rotation, viewportWidthPx = 1080, cropOriginX = 36f, cropOriginY = 36f)
            val back = transform.screenToPdf(transform.pdfToScreen(pdf, viewport), viewport)
            assertApprox(pdf.x, back.x)
            assertApprox(pdf.y, back.y)
        }
    }
}
