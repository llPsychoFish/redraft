package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfPoint
import com.redraft.pdfcore.PdfRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ─── Types ───────────────────────────────────────────────────────────────────

/**
 * Visual style of a text fragment — a maximal sequence of characters sharing
 * the same font, colour, size and direction.
 *
 * [fontName] and [fillColor] are nullable: `FPDFText_GetFontInfo`,
 * `FPDFText_GetFillColor` and `FPDFText_GetCharAngle` are not bound in
 * pdfiumandroid 3.0.4-alpha01 (D-14).  Matching therefore uses only [fontSizePt]
 * and the derived baseline direction; nullable fields are ignored by equality.
 */
data class TextStyle(
    val fontName: String? = null,   // not bound in current binding
    val fillColor: Int? = null,      // not bound in current binding
    val fontSizePt: Float,
    /** Baseline start point in PDF user space (y-up coordinates). */
    val baselineY: Float,
    /**
     * Normalised text direction: (1, 0) = LTR horizontal, (-1, 0) = RTL,
     * (0, 1) = vertical top-to-bottom, (0, -1) = vertical bottom-to-top.
     * Not currently exposed by the binding — see above.
     */
    val directionX: Float = 1f,
    val directionY: Float = 0f,
)

/**
 * One maximal sequence of characters sharing the same [style].
 *
 * A fragment is the unit of gap measurement: gaps are measured **along the
 * baseline direction** of [style], so rotated text uses the same logic with no
 * special case (D-12, D-14).
 *
 * [editable] is `false` when the text direction is vertical or right-to-left
 * (D-11).  Because the binding does not expose direction (see [TextStyle]),
 * this field is always `true` in Phase 2; a future binding exposing the text
 * matrix will allow the real determination.
 */
data class TextFragment(
    val chars: List<CharBox>,
    val style: TextStyle,
    val editable: Boolean = true, // always true without direction from binding
) {
    init {
        require(chars.isNotEmpty()) { "A fragment must contain at least one char" }
    }
}

/**
 * One visual line: the union of glyph boxes across word gaps, with a stable
 * [blockId] for paragraph grouping (D-07, D-17).
 */
data class TextLine(
    val chars: List<CharBox>,
    /** Axis-aligned union of all char boxes in this line, y-up PDF space. */
    val rect: PdfRect,
    val fragment: TextFragment,
    /** Assigned by [groupBlocks]; `null` until [com.redraft.pdfcore.selection.groupBlocks] is called. */
    val blockId: String? = null,
) {
    /** Convenience: first char index in the original page. */
    val firstCharIndex: Int get() = chars.firstOrNull()?.index ?: -1
}

// ─── Fragmenting ─────────────────────────────────────────────────────────────

/**
 * Break [chars] into maximal [TextFragment] sequences: each fragment is
 * characters sharing the same style, where "same" means no style change
 * exceeds the thresholds below (D-13, D-14).
 *
 * Superscripts / subscripts are never merged into the base line because their
 * size difference (> 0.1 pt) always triggers a fragment boundary.
 */
fun fragmentChars(chars: List<CharBox>): List<TextFragment> {
    if (chars.isEmpty()) return emptyList()

    val fragments = mutableListOf<TextFragment>()
    var current = mutableListOf<CharBox>()

    fun emitFragment() {
        if (current.isNotEmpty()) {
            val style = deriveStyle(current)
            // Determine editable from geometric heuristics when direction is not available:
            // - Vertical text: glyphs are typically narrow/tall (width < height × 0.3)
            // - RTL is assumed horizontal; without direction matrix we cannot detect it
            // D-11: editable=false for vertical |u.y| > |u.x| and RTL (u.x < 0)
            // The binding does not expose direction, so all fragments default to editable=true.
            // This is a known limitation documented in TextStyle.
            val isVertical = detectVerticalFromGeometry(current)
            fragments.add(TextFragment(current.toList(), style, editable = !isVertical))
            current.clear()
        }
    }

    for (char in chars) {
        if (current.isNotEmpty()) {
            val prev = current.last()
            val prevStyle = deriveStyle(listOf(prev))
            val nextStyle = deriveStyle(listOf(char))
            if (styleDiffers(prevStyle, nextStyle)) {
                emitFragment()
            }
        }
        current.add(char)
    }
    emitFragment()
    return fragments
}

/** Derive the [TextStyle] for a single-char or multi-char fragment. */
internal fun deriveStyle(sampleChars: List<CharBox>): TextStyle {
    val first = sampleChars.first()
    // Use the first char's font size as the fragment's reference size (D-14).
    // The binding does not expose fontName / fillColor / direction (D-14).
    return TextStyle(
        fontName = null,
        fillColor = null,
        fontSizePt = first.fontSizePt,
        baselineY = first.rect.minY,  // minY == baseline for a properly normalised char rect
        directionX = 1f,  // not exposed; default to LTR horizontal
        directionY = 0f,
    )
}

/** True when two styles differ by more than the acceptance thresholds (D-13). */
private fun styleDiffers(a: TextStyle, b: TextStyle): Boolean {
    // Size difference: 0.1 pt threshold
    if (abs(a.fontSizePt - b.fontSizePt) > 0.1f) return true

    // Baseline difference: max(0.75, 0.15 * size) pt
    val baselineThreshold = maxOf(0.75f, 0.15f * a.fontSizePt)
    if (abs(a.baselineY - b.baselineY) > baselineThreshold) return true

    // Direction change > ~15°: use dot-product threshold on normalised direction
    val dot = a.directionX * b.directionX + a.directionY * b.directionY
    if (dot < 0.965926f) return true // cos(15°) ≈ 0.966

    return false
}

/**
 * Infer vertical text from bounding-box geometry when direction is not exposed.
 * A vertical fragment typically has height > width × 3.
 */
private fun detectVerticalFromGeometry(chars: List<CharBox>): Boolean {
    if (chars.isEmpty()) return false
    val union = chars.first().rect
    for (c in chars) {
        val r = c.rect
        union.minX = min(union.minX, r.minX)
        union.minY = min(union.minY, r.minY)
        union.maxX = max(union.maxX, r.maxX)
        union.maxY = max(union.maxY, r.maxY)
    }
    val w = union.width
    val h = union.height
    return h > 0f && w > 0f && h > w * 3f
}

// ─── Gap splitting ────────────────────────────────────────────────────────────

/**
 * Split each [fragment] into one or more [TextLine] objects using the gap
 * rules from D-12:
 *
 * - Split when `gap > 2 × sizeOfLeftChar`
 * - Also split when `gap ≥ 1 × sizeOfLeftChar` **and**
 *   `gap > 2.5 × medianWordGap` (median computed over gaps in
 *   `(0.05 × size, 1.0 × size)` before any splitting)
 *
 * Characters with `width < 0.01` contribute to the accumulated gap but are
 * excluded from the glyph union, so trailing spaces never widen the hit area
 * (D-12, D-13).
 */
fun splitFragmentIntoLines(fragment: TextFragment): List<TextLine> {
    val chars = fragment.chars
    if (chars.isEmpty()) return emptyList()

    // Compute medianWordGap once per fragment before any splits (D-12).
    val medianGap = medianWordGap(chars)

    val lines = mutableListOf<TextLine>()
    var lineChars = mutableListOf<CharBox>()

    for (i in chars.indices) {
        val char = chars[i]

        if (lineChars.isNotEmpty()) {
            val prev = lineChars.last()
            val size = prev.fontSizePt
            // Measure gap along the baseline direction of the fragment
            val gap = gapAlongBaseline(prev, char, fragment.style)
            val gapInEm = gap / size

            // Rule 1: 2 em split
            val splitsAt2Em = gapInEm > 2f
            // Rule 2: 1 em AND > 2.5× median split (D-12)
            val splitsAtMedian = gapInEm >= 1f && gapInEm > 2.5f * (medianGap / size)

            if (splitsAt2Em || splitsAtMedian) {
                lines.add(makeLine(lineChars, fragment))
                lineChars = mutableListOf()
            }
        }

        lineChars.add(char)
    }

    if (lineChars.isNotEmpty()) {
        lines.add(makeLine(lineChars, fragment))
    }

    return lines.ifEmpty { listOf(makeLine(chars, fragment)) }
}

/**
 * Gap between two adjacent chars, measured along the fragment's baseline
 * direction (D-12).  For horizontal text (u = (1, 0)) this is the x-difference;
 * for 90° vertical text (u = (0, 1)) this is the y-difference.
 */
private fun gapAlongBaseline(prev: CharBox, next: CharBox, style: TextStyle): Float {
    // The baseline direction determines the gap axis.
    // horizontal (|dx| > |dy|): gap = next.minX - prev.maxX  (dx > 0 for LTR)
    // vertical   (|dy| > |dx|): gap = next.minY - prev.maxY  (dy > 0 for vertical top-down)
    return when {
        abs(style.directionX) >= abs(style.directionY) -> {
            // Predominantly horizontal: gap along x
            next.rect.minX - prev.rect.maxX
        }
        else -> {
            // Predominantly vertical: gap along y
            next.rect.minY - prev.rect.maxY
        }
    }
}

/**
 * Median of gaps that are between 0.05 × size and 1.0 × size of the reference
 * character (D-12).  Computing this before splitting ensures the median
 * represents the natural word-gap distribution of the whole fragment.
 */
internal fun medianWordGap(chars: List<CharBox>): Float {
    if (chars.size < 2) return 0f
    val refSize = chars.first().fontSizePt
    val lo = 0.05f * refSize
    val hi = 1.0f * refSize
    val eligibleGaps = mutableListOf<Float>()

    for (i in 0 until chars.size - 1) {
        val gap = chars[i + 1].rect.minX - chars[i].rect.maxX
        if (gap >= lo && gap <= hi) eligibleGaps.add(gap)
    }

    return if (eligibleGaps.isEmpty()) 0f
    else eligibleGaps.sorted()[eligibleGaps.size / 2]
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Union of glyph boxes, zero-width chars excluded from the union but counted in gap. */
internal fun glyphUnion(chars: List<CharBox>): PdfRect {
    val result = PdfRect(Float.MAX_VALUE, Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_VALUE)
    var hasReal = false
    for (c in chars) {
        if (c.rect.width < 0.01f) continue  // zero-width: contribute gap, not union
        hasReal = true
        result.minX = minOf(result.minX, c.rect.minX)
        result.minY = minOf(result.minY, c.rect.minY)
        result.maxX = maxOf(result.maxX, c.rect.maxX)
        result.maxY = maxOf(result.maxY, c.rect.maxY)
    }
    if (!hasReal) {
        // All zero-width chars: use bounding box of the zero-width chars themselves
        for (c in chars) {
            result.minX = minOf(result.minX, c.rect.minX)
            result.minY = minOf(result.minY, c.rect.minY)
            result.maxX = maxOf(result.maxX, c.rect.maxX)
            result.maxY = maxOf(result.maxY, c.rect.maxY)
        }
    }
    return result
}

private fun makeLine(chars: List<CharBox>, fragment: TextFragment): TextLine {
    require(chars.isNotEmpty()) { "A line must contain at least one char" }
    return TextLine(
        chars = chars,
        rect = glyphUnion(chars),
        fragment = fragment,
    )
}

// ─── Pipeline ─────────────────────────────────────────────────────────────────

/**
 * Full pipeline: chars → fragments → lines.
 * Returns lines with null blockId; call [groupBlocks] to assign blockId.
 */
fun reduceCharsToLines(chars: List<CharBox>): List<TextLine> {
    val fragments = fragmentChars(chars)
    return fragments.flatMap { splitFragmentIntoLines(it) }
}

// hitTest lives in HitTest.kt (returns HitResult? — richer than TextLine?).