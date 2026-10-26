package com.redraft.pdfcore

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Axis-aligned rectangle in ABSOLUTE PDF user space (origin bottom-left, y up, points).
 *
 * Binding rect types never cross into this class: their field names are assigned
 * positionally and are not semantically trustworthy (02-RESEARCH §1.3), so every
 * value entering a [PdfRect] is normalised with per-axis min/max first.
 */
data class PdfRect(
    var minX: Float,
    var minY: Float,
    var maxX: Float,
    var maxY: Float,
) {
    val width: Float get() = maxX - minX
    val height: Float get() = maxY - minY

    /** True when the rect encloses no area (the result of a disjoint [intersect]). */
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    /**
     * Overlap of this box with [other] (mirrors PDFium's `bbox_ = CropBox ∩
     * MediaBox`, 02-RESEARCH §2.3 rule 1). A disjoint pair yields an EMPTY rect
     * (negative width/height) — never a swapped-in spurious box.
     */
    fun intersect(other: PdfRect): PdfRect = PdfRect(
        minX = max(minX, other.minX),
        minY = max(minY, other.minY),
        maxX = min(maxX, other.maxX),
        maxY = min(maxY, other.maxY),
    )

    /**
     * Inclusive overlap test: a zero-width box strictly inside [other] still
     * intersects it (zero-width spaces must survive extraction for gap maths),
     * while a box beyond [other]'s edges does not.
     */
    fun intersects(other: PdfRect): Boolean =
        minX <= other.maxX && maxX >= other.minX &&
            minY <= other.maxY && maxY >= other.minY

    /** Inclusive point-in-rectangle test. */
    fun contains(x: Float, y: Float): Boolean =
        x >= minX && x <= maxX && y >= minY && y <= maxY
}

/**
 * One page's UNROTATED CropBox geometry plus its coerced rotation index
 * (02-RESEARCH §2.3).
 *
 * This is the single place page geometry is derived. [PdfViewportTransform] consumes
 * these unrotated dimensions together with [rotationDegrees] and the crop origin, so
 * neither the renderer, the hit test nor the overlay ever applies a rotation step of
 * its own (D-02, D-14).
 */
data class PdfPageGeometry(
    val pageIndex: Int,
    /** Normalised, absolute PDF user space, UNROTATED. */
    val cropBox: PdfRect,
    /** 0..3 — the binding's raw index coerced, so -1 can never become 270°. */
    val rotationIndex: Int,
) {
    init {
        require(rotationIndex in 0..3) { "rotationIndex must be 0..3, was $rotationIndex" }
    }

    val rotationDegrees: Int get() = rotationIndex * 90

    /** Unrotated crop width in points. */
    val widthPt: Float get() = cropBox.width

    /** Unrotated crop height in points. */
    val heightPt: Float get() = cropBox.height

    /** Display size in points (swapped for quarter-turn pages). */
    val displayWidthPt: Float get() = if (rotationIndex % 2 == 1) heightPt else widthPt
    val displayHeightPt: Float get() = if (rotationIndex % 2 == 1) widthPt else heightPt

    /**
     * Base slot size in pixels at zoom 1 so the DISPLAYED page fills [viewportWidthPx]
     * at fit-width for every rotation (02-RESEARCH §2.3 rule 4). `PdfPageItem` feeds
     * this straight into `PdfViewportTransform` as renderedWidth/HeightPx — the single
     * quarter-turn aspect decision for layout.
     */
    fun slotBaseSizePx(viewportWidthPx: Int): RenderedSize {
        val quarterTurn = rotationIndex % 2 == 1
        val baseWidthPx = if (quarterTurn) {
            (viewportWidthPx * widthPt / heightPt).roundToInt()
        } else {
            viewportWidthPx
        }
        val baseHeightPx = if (quarterTurn) {
            viewportWidthPx
        } else {
            (viewportWidthPx * heightPt / widthPt).roundToInt()
        }
        return RenderedSize(
            widthPx = baseWidthPx.coerceAtLeast(1),
            heightPx = baseHeightPx.coerceAtLeast(1),
        )
    }

    companion object {
        /** Defensive last resort when a page reports no usable box (never observed in practice). */
        private val LETTER = PdfRect(0f, 0f, 612f, 792f)

        /**
         * Build geometry from raw binding values (02-RESEARCH §2.3 rules 1-2).
         *
         * @param cropValues four numbers in the binding's documented
         *   `[left, top, right, bottom]` order (`NativePage.getPageCropBox`);
         *   pairs are normalised with min/max, field names are not trusted.
         * @param mediaValues same shape for the MediaBox.
         * @param rawRotation the binding's `getPageRotation()`: -1 (error) .. 3.
         */
        fun build(
            pageIndex: Int,
            cropValues: FloatArray?,
            mediaValues: FloatArray?,
            rawRotation: Int,
        ): PdfPageGeometry {
            val media = mediaValues?.takeIfPresent()
            val crop = cropValues?.takeIfPresent()
            val box = when {
                crop != null && media != null -> crop.intersect(media).takeUnless { it.isEmpty } ?: crop
                crop != null -> crop
                media != null -> media
                else -> LETTER
            }
            return PdfPageGeometry(
                pageIndex = pageIndex,
                cropBox = box,
                rotationIndex = rawRotation.coerceIn(0, 3),
            )
        }

        /** The binding reports `(-1, -1, -1, -1)` for an absent box. */
        private fun FloatArray.takeIfPresent(): PdfRect? {
            if (size < 4) return null
            if (this[0] == -1f && this[1] == -1f && this[2] == -1f && this[3] == -1f) return null
            // Pair positions (0,2) as x and (1,3) as y, min/max per axis.
            val loX = min(this[0], this[2])
            val hiX = max(this[0], this[2])
            val loY = min(this[1], this[3])
            val hiY = max(this[1], this[3])
            return PdfRect(loX, loY, hiX, hiY)
        }
    }
}
