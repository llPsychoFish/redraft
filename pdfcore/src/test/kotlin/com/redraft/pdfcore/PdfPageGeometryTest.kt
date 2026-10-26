package com.redraft.pdfcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Pure-JVM contract for the geometry rule that fixes the carried `/Rotate` defect
 * (02-RESEARCH §2.3): sentinel fallback, min/max normalisation, rotation coercion
 * and the shared quarter-turn slot-sizing rule used by `PdfPageItem`.
 */
class PdfPageGeometryTest {

    // [left, top, right, bottom] — the binding's documented order (NativePage).
    private val letterCrop = floatArrayOf(0f, 792f, 612f, 0f)
    private val sentinel = floatArrayOf(-1f, -1f, -1f, -1f)

    @Test
    fun negativeRotationIndex_coerces_toZero_and_neverBecomes270() {
        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = letterCrop,
            mediaValues = letterCrop,
            rawRotation = -1,
        )

        assertEquals(0, geometry.rotationIndex)
        assertEquals(0, geometry.rotationDegrees)
    }

    @Test
    fun validRotationIndices_passThrough() {
        for (index in 0..3) {
            val geometry = PdfPageGeometry.build(
                pageIndex = index,
                cropValues = letterCrop,
                mediaValues = letterCrop,
                rawRotation = index,
            )
            assertEquals(index, geometry.rotationIndex)
            assertEquals(index * 90, geometry.rotationDegrees)
        }
    }

    @Test
    fun cropSentinel_falls_back_to_mediaBox() {
        val media = floatArrayOf(0f, 792f, 612f, 0f)

        val geometry = PdfPageGeometry.build(
            pageIndex = 7,
            cropValues = sentinel,
            mediaValues = media,
            rawRotation = 0,
        )

        assertEquals(612f, geometry.widthPt, 1e-3f)
        assertEquals(792f, geometry.heightPt, 1e-3f)
        assertEquals(0f, geometry.cropBox.minX, 1e-3f)
        assertEquals(0f, geometry.cropBox.minY, 1e-3f)
    }

    @Test
    fun bothBoxesSentinel_falls_back_to_letter() {
        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = sentinel,
            mediaValues = sentinel,
            rawRotation = 2,
        )

        assertEquals(612f, geometry.widthPt, 1e-3f)
        assertEquals(792f, geometry.heightPt, 1e-3f)
        assertEquals(180, geometry.rotationDegrees)
    }

    @Test
    fun reversedCropBox_normalises_toPositiveSize() {
        // Same rectangle as letterCrop but with both axes arriving reversed.
        val reversed = floatArrayOf(612f, 0f, 0f, 792f)

        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = reversed,
            mediaValues = null,
            rawRotation = 0,
        )

        assertTrue("width must be positive", geometry.widthPt > 0f)
        assertTrue("height must be positive", geometry.heightPt > 0f)
        assertEquals(612f, geometry.widthPt, 1e-3f)
        assertEquals(792f, geometry.heightPt, 1e-3f)
        assertEquals(0f, geometry.cropBox.minX, 1e-3f)
        assertEquals(0f, geometry.cropBox.minY, 1e-3f)
    }

    @Test
    fun cropBox_intersects_mediaBox() {
        val crop = floatArrayOf(0f, 792f, 700f, 0f) // wider than the media box
        val media = floatArrayOf(0f, 792f, 612f, 0f)

        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = crop,
            mediaValues = media,
            rawRotation = 0,
        )

        assertEquals(612f, geometry.widthPt, 1e-3f)
        assertEquals(792f, geometry.heightPt, 1e-3f)
    }

    @Test
    fun slotBaseSize_uprightPage_fillsViewportWidth() {
        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = letterCrop,
            mediaValues = letterCrop,
            rawRotation = 0,
        )

        val base = geometry.slotBaseSizePx(1080)

        assertEquals(1080, base.widthPx)
        assertEquals((1080f * 792f / 612f).roundToInt(), base.heightPx)
    }

    @Test
    fun slotBaseSize_quarterTurn_swapsSoDisplayFillsViewportWidth() {
        // 02-RESEARCH §2.3 worked example: 612×792 page, viewport 1080, /Rotate 90
        // → baseWidth 835, baseHeight 1080 (a landscape slot).
        val geometry = PdfPageGeometry.build(
            pageIndex = 0,
            cropValues = letterCrop,
            mediaValues = letterCrop,
            rawRotation = 1,
        )

        val base = geometry.slotBaseSizePx(1080)

        assertEquals(1080, base.heightPx)
        assertEquals((1080f * 612f / 792f).roundToInt(), base.widthPx)
        assertEquals(835, base.widthPx)
        assertEquals(90, geometry.rotationDegrees)
    }
}
