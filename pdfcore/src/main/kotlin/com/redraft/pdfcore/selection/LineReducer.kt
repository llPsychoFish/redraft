package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfRect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
 * - a derived direction change greater than about 15 degrees.
 *
 * The baseline is deliberately NOT part of this test.  PDFium reports each
 * glyph's own ink box, so a descender's (`g`, `p`, `y`) box bottom sits about
 * 0.2 em below the real baseline; comparing box bottoms between neighbours
 * would cut `Right column row one` into `Ri` / `g` / `ht column row one`.
 * Baselines are therefore clustered per fragment in [splitFragmentIntoLines],
 * which tolerates descender boxes and still splits superscripts (D-13
 * refinement).
 *
 * Whitespace-only and zero-width characters never break a fragment: they carry
 * no glyph, they only contribute to the gap between two glyphs (D-12).
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
        if (current.isNotEmpty() && !isGapOnlyChar(char)) {
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
    // Whitespace carries no style of its own — PDFium reports a size of 1 pt
    // for synthesized spaces — so the reference char is the first glyph.
    val first = sampleChars.firstOrNull { !isGapOnlyChar(it) } ?: sampleChars.first()
    // Use the first glyph's font size as the fragment's reference size (D-14).
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

// ─── Gap splitting (D-12) ─────────────────────────────────────────────────────

/**
 * How far below the baseline a glyph box may hang before it is treated as a
 * different line rather than as a descender.
 *
 * PDFium reports the font's ascender/descender box for most glyphs but the
 * glyph's own ink box for descenders, so a `g` hangs about 0.22 em below the
 * baseline of its line.  0.3 em covers the deepest descenders in the common
 * Latin faces while staying far below any real leading.
 */
internal const val DESCENDER_ALLOWANCE_EM = 0.3f

/**
 * True for characters that carry no glyph of their own: whitespace and
 * zero-width boxes.  They contribute to the accumulated gap between two glyphs
 * but are excluded from the glyph union (D-12, D-13).
 */
internal fun isGapOnlyChar(char: CharBox): Boolean =
    char.rect.width < 0.01f || char.text.isBlank()

/**
 * The character's offset perpendicular to the baseline, used as the baseline
 * proxy: the box bottom for horizontal text, the box left edge for vertical
 * text (whose baseline runs along +y).
 */
internal fun baselineProxy(char: CharBox, style: TextStyle): Float =
    if (abs(style.directionY) > abs(style.directionX)) char.rect.minX else char.rect.minY

/**
 * Group [chars] into baseline clusters and return the cluster id per char.
 *
 * PDFium reports **ink boxes**, not baselines, so the box bottom is only the
 * baseline for glyphs that sit on it:
 *
 * - anchor glyphs (letters, digits — ink at least 0.4 em tall) report the
 *   baseline as their box bottom; their mode is the line's baseline,
 * - descenders (`g`, `p`, `y`) and brackets (`(`, `)`) hang about 0.2 em below
 *   it and form a weaker cluster that belongs to that line,
 * - short glyphs (quotes, `=`, `-`, `_`, `.`) ride 0.1–0.45 em above the
 *   baseline of the line they belong to.
 *
 * A weaker cluster within the descender allowance of the next baseline is
 * therefore folded into that line, while a cluster of equal support stays
 * separate — that is what keeps a superscript off its base line (D-13).
 */
internal fun assignBaselineClusters(chars: List<CharBox>, style: TextStyle): IntArray {
    val clusters = IntArray(chars.size)
    if (chars.isEmpty()) return clusters

    val size = chars.first().fontSizePt
    val vertical = abs(style.directionY) > abs(style.directionX)
    val descenderAllowancePt = DESCENDER_ALLOWANCE_EM * size
    val aboveThresholdPt = maxOf(0.75f, 0.15f * size)
    val anchorThreshold = 0.4f * size
    val groupTol = 0.15f * size
    val raisedAllowancePt = 0.6f * size

    // Offset perpendicular to the baseline direction.  For horizontal text this
    // is the box bottom (the baseline of a glyph that sits on it); for 90°
    // text it is the box left edge.
    fun offset(c: CharBox): Float = baselineProxy(c, style)

    // Ink extent perpendicular to the baseline direction.
    fun extent(c: CharBox): Float = if (vertical) c.rect.width else c.rect.height

    // Anchor glyphs reach far enough that their offset marks the baseline.
    // Quotes, '=', '-', '_' and '.' are short and never do.
    val anchorIdx = chars.indices.filter { extent(chars[it]) >= anchorThreshold }

    // Cluster anchor offsets (single linkage over 0.15 em).
    val groups = mutableListOf<MutableList<Int>>()
    var previousOffset = Float.NEGATIVE_INFINITY
    for (i in anchorIdx.sortedBy { offset(chars[it]) }) {
        val o = offset(chars[i])
        if (groups.isEmpty() || o - previousOffset > groupTol) groups.add(mutableListOf())
        groups.last().add(i)
        previousOffset = o
    }
    if (groups.isEmpty()) return clusters

    // One candidate baseline per group: the offset most of its members share.
    class Baseline(val value: Float, val support: Int, val members: MutableList<Int>)

    val candidates = groups.map { group ->
        val counts = group.groupingBy { offset(chars[it]) }.eachCount()
        val top = counts.maxByOrNull { it.value }!!
        Baseline(top.key, top.value, group.toMutableList())
    }.sortedBy { it.value }

    // A weaker group sitting below the next baseline by no more than the
    // descender allowance is that line's descenders, not a line of its own.
    // A stronger group is a real line; equal support means neither wins, so a
    // superscript never absorbs its base line (D-13).
    val lines = mutableListOf<Baseline>()
    for (candidate in candidates) {
        if (lines.isEmpty()) {
            lines.add(candidate)
            continue
        }
        val last = lines.last()
        val gap = candidate.value - last.value
        val sameLine = gap <= 0.05f * size
        val descenderGroup =
            gap > 0f && gap <= descenderAllowancePt && last.support < candidate.support
        if (sameLine || descenderGroup) {
            val value = if (sameLine && last.support >= candidate.support) last.value else candidate.value
            val support = maxOf(last.support, candidate.support)
            lines[lines.lastIndex] =
                Baseline(value, support, last.members.apply { addAll(candidate.members) })
        } else {
            lines.add(candidate)
        }
    }

    // Anchors belong to the baseline they formed; the short glyphs are placed
    // afterwards on the line they belong to.
    val assignments = IntArray(chars.size) { -1 }
    for ((j, line) in lines.withIndex()) {
        for (member in line.members) assignments[member] = j
    }
    for (i in chars.indices) {
        if (assignments[i] >= 0) continue
        val o = offset(chars[i])
        val short = extent(chars[i]) < anchorThreshold
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for ((j, line) in lines.withIndex()) {
            val baseline = line.value
            // Sits on the baseline, or hangs below it the way a descender does.
            val anchored =
                o >= baseline - descenderAllowancePt && o <= baseline + aboveThresholdPt
            // Rides above the baseline: a quote or an '=' sign.
            val raised = short && o > baseline && o - baseline <= raisedAllowancePt
            if (!anchored && !raised) continue
            val distance = if (raised) o - baseline else abs(o - baseline)
            if (distance < bestDistance) {
                bestDistance = distance
                best = j
            }
        }
        if (best < 0) {
            for ((j, line) in lines.withIndex()) {
                val distance = abs(o - line.value)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = j
                }
            }
        }
        assignments[i] = best
    }
    return assignments
}



/**
 * Split each [fragment] into one or more [TextLine] objects.
 *
 * Two rules decide the boundaries:
 *
 * - **Baseline** (D-13): chars are grouped into baseline clusters first, so
 *   descenders stay on their line while superscripts and the next row start a
 *   new one.
 * - **Gap** (D-12): within a baseline, a gap wider than `2 × sizeOfLeftChar`,
 *   or one wider than `2.5 × the median word gap` of the fragment, splits the
 *   line — tight table gutters under 2 em (e.g. 0.8 em) split this way, while
 *   evenly stretched justified gaps stay near the median and survive.
 *
 * Characters with `width < 0.01` contribute to the accumulated gap but are
 * excluded from the glyph union, so trailing spaces never widen the hit area
 * (D-12, D-13).  A fragment that holds no glyph at all yields no line.
 */
fun splitFragmentIntoLines(fragment: TextFragment): List<TextLine> {
    val chars = fragment.chars
    if (chars.isEmpty()) return emptyList()

    val style = fragment.style
    val ink = chars.filterNot { isGapOnlyChar(it) }
    if (ink.isEmpty()) return emptyList()

    // Median word gap once per fragment, before any splits (D-12).
    val medianGapPt = medianWordGap(chars, style)

    val inkClusters = assignBaselineClusters(ink, style)
    val clusterOfChar = HashMap<Int, Int>(ink.size)
    ink.forEachIndexed { index, char -> clusterOfChar[char.index] = inkClusters[index] }

    val lines = mutableListOf<TextLine>()
    var lineChars = mutableListOf<CharBox>()
    var lastGlyph: CharBox? = null
    var currentCluster = -1

    for (char in chars) {
        if (isGapOnlyChar(char)) {
            // Whitespace rides along with the line it follows; it contributes
            // to the gap but never to the union.
            if (lineChars.isNotEmpty()) lineChars.add(char)
            continue
        }

        val cluster = clusterOfChar[char.index] ?: currentCluster
        if (lineChars.isNotEmpty() && cluster != currentCluster) {
            lines.add(makeLine(lineChars, fragment))
            lineChars = mutableListOf()
            lastGlyph = null
        }
        currentCluster = cluster

        val previous = lastGlyph
        if (lineChars.isNotEmpty() && previous != null) {
            val gapPt = gapAlongU(previous, char, style)
            if (splitsAtGap(gapPt, previous.fontSizePt, medianGapPt)) {
                lines.add(makeLine(lineChars, fragment))
                lineChars = mutableListOf()
            }
        }

        lineChars.add(char)
        lastGlyph = char
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
 * Median of the fragment's **word gaps**, measured along the baseline
 * direction (D-12).
 *
 * A gap is a *word gap* when a whitespace character (a literal space or a
 * PDFium-synthesised separator) sits between the two glyphs it separates.
 * Only those gaps are sampled: sampling every gap would let the many small
 * LETTER gaps (0.06–0.15 em in real PDFium output) dominate the median and
 * turn D-12's "2.5× the median word gap" rule into "split at every space".
 *
 * The sample is further restricted to the word-gap window `(0.05 × size,
 * 1.0 × size]`, so a column gutter or a table cell gap (several em wide)
 * never becomes the median.  The median is computed before any split, over
 * the whole fragment.
 *
 * When the fragment holds no whitespace at all there is no word-gap
 * distribution to speak of; the window sample of all gaps is used instead
 * (the legacy behaviour, and what D-12's degenerate case assumes).
 */
internal fun medianWordGap(chars: List<CharBox>, style: TextStyle): Float {
    if (chars.size < 2) return 0f
    val refSize = style.fontSizePt.takeIf { it > 0f }
        ?: chars.firstOrNull { !isGapOnlyChar(it) }?.fontSizePt
        ?: chars.first().fontSizePt
    val lo = 0.05f * refSize
    val hi = 1.0f * refSize
    val wordGaps = mutableListOf<Float>()
    val allGaps = mutableListOf<Float>()

    var previousGlyph: CharBox? = null
    var whitespaceBetween = false
    for (char in chars) {
        if (isGapOnlyChar(char)) {
            // A whitespace (or zero-width) character marks the boundary
            // between two words — it carries no glyph of its own.
            if (previousGlyph != null) whitespaceBetween = true
            continue
        }
        val previous = previousGlyph
        if (previous != null) {
            val gap = gapAlongU(previous, char, style)
            if (gap >= lo && gap <= hi) {
                allGaps.add(gap)
                if (whitespaceBetween) wordGaps.add(gap)
            }
        }
        previousGlyph = char
        whitespaceBetween = false
    }

    val sample = if (wordGaps.isNotEmpty()) wordGaps else allGaps
    return if (sample.isEmpty()) 0f
    else sample.sorted()[sample.size / 2]
}

/** Kept for compatibility with earlier call sites/tests. */
internal fun medianWordGap(chars: List<CharBox>): Float {
    val size = chars.firstOrNull { !isGapOnlyChar(it) }?.fontSizePt
        ?: chars.firstOrNull()?.fontSizePt
        ?: 1f
    return medianWordGap(chars, TextStyle(fontSizePt = size, baselineY = 0f))
}

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

/**
 * The line's baseline offset: the highest box offset among its glyphs.
 *
 * A descender box hangs below (or left of) the baseline, so the maximum offset
 * is the baseline itself — the same convention the baseline clustering uses.
 */
internal fun lineBaselineOffset(line: TextLine): Float =
    if (abs(line.fragment.style.directionY) > abs(line.fragment.style.directionX)) {
        line.chars.maxOf { it.rect.minX }
    } else {
        line.chars.maxOf { it.rect.minY }
    }

/**
 * The line's own height above its baseline — the ascent, with the descender
 * depth excluded, so a line containing `g` measures the same as its neighbours.
 */
internal fun lineAscent(line: TextLine): Float =
    if (abs(line.fragment.style.directionY) > abs(line.fragment.style.directionX)) {
        line.rect.maxX - lineBaselineOffset(line)
    } else {
        line.rect.maxY - lineBaselineOffset(line)
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
