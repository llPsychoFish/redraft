package com.redraft.pdfcore.selection

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
    val fillColor: Int? = null,     // not bound in current binding
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
 * [editable] defaults from the fragment's direction (D-11): vertical
 * (`|u.y| > |u.x|`) and right-to-left (`u.x < 0`) lines are selectable but
 * never editable — Phase 3 must refuse to edit them.
 */
data class TextFragment(
    val chars: List<CharBox>,
    val style: TextStyle,
    val editable: Boolean = defaultEditable(style),
) {
    init {
        require(chars.isNotEmpty()) { "A fragment must contain at least one char" }
    }
}

/** D-11: pieces the binding cannot report as LTR horizontal default to non-editable. */
internal fun defaultEditable(style: TextStyle): Boolean =
    !(abs(style.directionY) > abs(style.directionX) || style.directionX < 0f)

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
 * exceeds the thresholds below (D-13, D-14):
 *
 * - size difference greater than 0.1 pt,
 * - baseline difference greater than `max(0.75, 0.15 × size)` pt,
 * - a derived direction change greater than about 15 degrees.
 *
 * Superscripts / subscripts are never merged into the base line because their
 * baseline difference (> 0.15 em) always triggers a fragment boundary.
 */
fun fragmentChars(chars: List<CharBox>): List<TextFragment> {
    if (chars.isEmpty()) return emptyList()

    val fragments = mutableListOf<TextFragment>()
    var current = mutableListOf<CharBox>()

    fun emitFragment() {
        if (current.isNotEmpty()) {
            val style = deriveStyle(current)
            // The binding does not expose the text direction, so [style]'s
            // direction is the LTR default; fall back to a geometric heuristic
            // for vertical pieces (D-11, D-14).
            val verticalHeuristic = detectVerticalFromGeometry(current)
            fragments.add(
                TextFragment(
                    current.toList(),
                    style,
                    editable = defaultEditable(style) && !verticalHeuristic,
                ),
            )
            current = mutableListOf()
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
 *
 * Reads the boxes only — it must never mutate input rects (the first char's
 * rect is reused by later gap maths and by the line's glyph union).
 */
internal fun detectVerticalFromGeometry(chars: List<CharBox>): Boolean {
    if (chars.isEmpty()) return false
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (c in chars) {
        val r = c.rect
        minX = min(minX, r.minX)
        minY = min(minY, r.minY)
        maxX = max(maxX, r.maxX)
        maxY = max(maxY, r.maxY)
    }
    val w = maxX - minX
    val h = maxY - minY
    return h > 0f && w > 0f && h > w * 3f
}

// ─── Baseline-direction projection (D-12) ────────────────────────────────────

/**
 * Projection of an axis-aligned char box onto the fragment's baseline unit
 * direction `u`: `[uStart, uEnd]` is the char's extent along the direction of
 * travel, so a gap is simply `next.uStart − prev.uEnd` — rotated text needs no
 * special case (D-12).
 */
internal fun projectOntoU(rect: PdfRect, style: TextStyle): Pair<Float, Float> {
    val cx = (rect.minX + rect.maxX) * 0.5f
    val cy = (rect.minY + rect.maxY) * 0.5f
    val proj = cx * style.directionX + cy * style.directionY
    // Axis-aligned extent widened by the axis-aligned box: the worst-case
    // projection span of the box onto u (exact for u along an axis, an upper
    // bound for skewed directions, which Phase 2 never sees).
    val span = abs(style.directionX) * rect.width + abs(style.directionY) * rect.height
    return (proj - span * 0.5f) to (proj + span * 0.5f)
}

// ─── Gap splitting ────────────────────────────────────────────────────────────

/**
 * Split each [fragment] into one or more [TextLine] objects using the gap
 * rules from D-12:
 *
 * - Split when `gap > 2 × sizeOfLeftChar`.
 * - Also split when the gap exceeds **2.5 × the median word gap** of the
 *   fragment, computed once over gaps in `(0.05 × size, 1.0 × size)` BEFORE
 *   splitting — tight table gutters under 2 em (e.g. 0.8 em) split this way,
 *   while evenly stretched justified gaps stay near the median and survive.
 * - A gap of at least 1 em with no word-gap sample at all also splits.
 *
 * Characters with `width < 0.01` contribute to the accumulated gap but are
 * excluded from the glyph union, so trailing spaces never widen the hit area
 * (D-12, D-13).
 */
fun splitFragmentIntoLines(fragment: TextFragment): List<TextLine> {
    val chars = fragment.chars
    if (chars.isEmpty()) return emptyList()

    val style = fragment.style
    // Median word gap once per fragment, before any splits (D-12).
    val medianGapPt = medianWordGap(chars, style)

    val lines = mutableListOf<TextLine>()
    var lineChars = mutableListOf<CharBox>()

    for (i in chars.indices) {
        val char = chars[i]

        if (lineChars.isNotEmpty()) {
            val prev = lineChars.last()
            val gapPt = gapAlongU(prev, char, style)
            val size = prev.fontSizePt
            if (splitsAtGap(gapPt, size, medianGapPt)) {
                lines.add(makeLine(lineChars, fragment))
                lineChars = mutableListOf()
            }
        }

        lineChars.add(char)
    }

    if (lineChars.isNotEmpty()) {
        lines.add(makeLine(lineChars, fragment))
    }

    return lines
}

/**
 * D-12 gap decision, All thresholds are expressed in em of the LEFT char's
 * font size, exactly as decided.
 */
internal fun splitsAtGap(gapPt: Float, sizePt: Float, medianGapPt: Float): Boolean {
    if (sizePt <= 0f) return false
    val gapEm = gapPt / sizePt
    val medianEm = medianGapPt / sizePt

    // Rule 1: any gap wider than 2 em of the left run's font size splits.
    if (gapEm > 2f) return true

    // Rule 2: a gap more than 2.5× the fragment's median word gap splits —
    // this is what separates tight table gutters (sub-1-em) from cells while
    // evenly stretched justified gaps (which sit near the median) stay whole.
    if (medianEm > 0f && gapEm > 2.5f * medianEm) return true

    // Rule 3 (D-12's "at least 1 em" clause, degenerate case): a gap of at
    // least 1 em in a fragment that produced no word-gap sample splits.
    if (medianEm <= 0f && gapEm >= 1f) return true

    return false
}

/**
 * Gap between two adjacent chars, measured along the fragment's baseline
 * direction (D-12).  For horizontal text (u = (1, 0)) this is the x-difference;
 * for 90° text (u = (0, 1)) this is the y-difference.
 */
internal fun gapAlongU(prev: CharBox, next: CharBox, style: TextStyle): Float {
    val (_, prevEnd) = projectOntoU(prev.rect, style)
    val (nextStart, _) = projectOntoU(next.rect, style)
    return nextStart - prevEnd
}

/**
 * Median of gaps that are between 0.05 × size and 1.0 × size of the reference
 * character, measured along the baseline direction (D-12).  Computing this
 * before splitting ensures the median represents the natural word-gap
 * distribution of the whole fragment.
 */
internal fun medianWordGap(chars: List<CharBox>, style: TextStyle): Float {
    if (chars.size < 2) return 0f
    val refSize = chars.first().fontSizePt
    val lo = 0.05f * refSize
    val hi = 1.0f * refSize
    val eligibleGaps = mutableListOf<Float>()

    for (i in 0 until chars.size - 1) {
        val gap = gapAlongU(chars[i], chars[i + 1], style)
        if (gap >= lo && gap <= hi) eligibleGaps.add(gap)
    }

    return if (eligibleGaps.isEmpty()) 0f
    else eligibleGaps.sorted()[eligibleGaps.size / 2]
}

/** Kept for compatibility with earlier call sites/tests. */
internal fun medianWordGap(chars: List<CharBox>): Float =
    medianWordGap(chars, TextStyle(fontSizePt = chars.firstOrNull()?.fontSizePt ?: 1f, baselineY = 0f))

// ─── Subset-prefix stripper (D-13) ───────────────────────────────────────────

/**
 * Strip the six-letter subset prefix from a font name:
 * `ABCDEF+TimesNewRoman` → `TimesNewRoman` (D-13).  Font names are not bound
 * in the current binding (D-14); this is ready for when a future binding
 * exposes `FPDFText_GetFontInfo`.
 */
fun stripSubsetPrefix(fontName: String): String {
    val plus = fontName.indexOf('+')
    return if (plus == 6 && plus < fontName.length - 1) fontName.substring(plus + 1) else fontName
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Union of glyph boxes, zero-width chars excluded from the union but counted in gap. */
internal fun glyphUnion(chars: List<CharBox>): PdfRect {
    val result = PdfRect(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
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
