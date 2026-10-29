package com.redraft.pdfcore

import com.redraft.pdfcore.selection.normalizeCharRect
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
    fun disjointBoxes_intersect_isEmpty_notSwappedIntoSpuriousOverlap() {
        val inside = PdfRect(0f, 0f, 612f, 792f)
        val outside = PdfRect(700f, 0f, 800f, 792f)

        val overlap = inside.intersect(outside)

        assertTrue("disjoint intersection must be empty", overlap.isEmpty)
        assertTrue("box beyond the crop must not intersect", !outside.intersects(inside))
        assertTrue("box inside the crop intersects", inside.intersects(inside))
        // A zero-width box strictly inside must still intersect (gap maths needs it).
        val zeroWidth = PdfRect(100f, 100f, 100f, 120f)
        assertTrue("zero-width box inside must intersect", zeroWidth.intersects(inside))
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
    fun normalizeCharRect_topGreaterThanBottom_yieldsPositiveHeight() {
        // PDFium char boxes are y-up: top arrives greater than bottom (§1.3).
        val rect = normalizeCharRect(left = 0f, top = 300f, right = 50f, bottom = 100f)

        assertTrue("height must be positive", rect.height > 0f)
        assertEquals(200f, rect.height, 1e-3f)
        assertEquals(50f, rect.width, 1e-3f)
    }

    @Test
    fun charBoxOutsideCrop_failsTheExtractorsIntersectsFilter() {
        // Exactly the predicate TextExtraction uses: outside boxes are skipped.
        val crop = PdfRect(0f, 0f, 612f, 792f)
        val outsideCrop = normalizeCharRect(-100f, 900f, -50f, 850f)
        val insideCrop = normalizeCharRect(10f, 50f, 40f, 80f)

        assertTrue("box outside the crop must be dropped", !outsideCrop.intersects(crop))
        assertTrue("box inside the crop must be kept", insideCrop.intersects(crop))
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
