package com.redraft.pdfcore.selection

import kotlin.math.abs

/**
 * Cap on lines per block (D-17). A run longer than this folds into two blocks.
 */
const val MAX_BLOCK_LINES = 40

/**
 * Cap on block height expressed in multiples of the block's own median line
 * height (D-17).
 */
const val MAX_BLOCK_HEIGHT_LINE_HEIGHTS = 12f

/**
 * One same-column paragraph block (D-15): metadata only.
 *
 * Blocks are never consulted by [hitTest] (D-07, D-16): they cannot change
 * what a tap selects. [blockId] is deterministic (D-17) — derived only from
 * the page index and the first character index of the block's first line, so
 * two reductions of the same fixture and two openings of the same document
 * produce identical ids. It is never random and never a content hash of
 * untrusted document bytes (threat T-02-05).
 */
data class TextBlock(
    /** `"p{pageIndex}:b{firstCharIndexOfBlock}"` (D-17). */
    val blockId: String,
    /** The block's lines, each carrying [TextLine.blockId] = [blockId]. */
    val lines: List<TextLine>,
)

/**
 * Group [lines] into same-column paragraph blocks (D-15, D-16, D-17) and
 * return the resulting [TextBlock]s with every line's [TextLine.blockId]
 * assigned.
 *
 * Lines are sorted deterministically first — `(-baselineY, minX)` for LTR
 * horizontal text, `(minX, -baselineY)` for vertical fragments — then folded
 * consecutively into a block while all of the below hold:
 *
 * - same size (±0.1 pt, the D-13 tolerance) and same direction (±15°);
 * - no font-size jump of 1.2× or more against the running block size;
 * - left edge equal within 0.5 em, or a hanging-indent continuation — i.e.
 *   the edge moved right by at most 2 em after a line that reached the
 *   right margin;
 * - leading gap strictly below 1.5 × line height (D-15; column and
 *   table-cell gaps start new blocks);
 * - no new-block trigger (D-16): a leading bullet glyph (including Symbol/
 *   Wingdings glyphs in the U+F020..U+F0FF range), a leading list label
 *   (`1.`, `a)`, `iii.`, `•`), or a short line followed by a style change.
 * - caps: at most [MAX_BLOCK_LINES] lines and at most
 *   [MAX_BLOCK_HEIGHT_LINE_HEIGHTS] × median line height per block (D-17).
 */
fun groupBlocks(lines: List<TextLine>, pageIndex: Int = 0): List<TextBlock> {
    if (lines.isEmpty()) return emptyList()

    val sorted = lines.sortedWith { a, b -> compareForFolding(a, b) }
    val maxRight = sorted.maxOf { it.rect.maxX }

    val folded = mutableListOf<MutableList<TextLine>>()
    var current: MutableList<TextLine>? = null

    for (line in sorted) {
        val block = current
        if (block == null || startsNewBlock(block, line, maxRight)) {
            current = mutableListOf(line)
            folded.add(current)
        } else {
            block.add(line)
        }
    }

    return folded.map { memberLines ->
        val id = "p${pageIndex}:b${memberLines.first().firstCharIndex}"
        val assigned = memberLines.map { it.copy(blockId = id) }
        TextBlock(blockId = id, lines = assigned)
    }
}

/**
 * Deterministic sort for the fold order: LTR horizontal lines order top-down
 * then left-to-right (`(-baselineY, minX)`); vertical fragments order
 * left-to-right then top-down (`(minX, -baselineY)`). Mixed orientations
 * interleave by their (minX, minY) footprint so vertical columns still appear
 * within their visual row; horizontal LTR text is the Phase 2 norm.
 * (The plan's vertical key reads "(minX, -baselineX)" — read as the baseline
 * proxy on the y axis, i.e. top-down.)
 */
private fun compareForFolding(a: TextLine, b: TextLine): Int {
    val aVertical = isVerticalFragment(a)
    val bVertical = isVerticalFragment(b)
    return when {
        aVertical || bVertical -> compareByFloat(lineBaselineOffset(a), lineBaselineOffset(b))
            .takeIf { it != 0 }
            ?: compareByFloat(a.rect.minX, b.rect.minX)

        else -> compareByFloat(-lineBaselineOffset(a), -lineBaselineOffset(b))
            .takeIf { it != 0 }
            ?: compareByFloat(a.rect.minX, b.rect.minX)
    }
}

private fun compareByFloat(a: Float, b: Float): Int {
    return if (a < b) -1 else if (a > b) 1 else 0
}

private fun isVerticalFragment(line: TextLine): Boolean =
    abs(line.fragment.style.directionY) > abs(line.fragment.style.directionX)

/** True when a new block starts with [line] at the end of [block] (D-15, D-16). */
private fun startsNewBlock(
    block: List<TextLine>,
    line: TextLine,
    maxRight: Float,
): Boolean {
    // Caps (D-17): line count and block height.
    if (block.size >= MAX_BLOCK_LINES) return true
    if (isBlockHeightCapped(block)) return true

    val last = block.last()
    val size = line.fragment.style.fontSizePt
    if (size <= 0f) return false

    // Style change: size and direction within the D-13 fragment tolerance.
    if (abs(last.fragment.style.fontSizePt - size) > 0.1f) return true
    if (!sameDirection(last.fragment.style, line.fragment.style)) return true

    // Font-size jump ≥ 1.2× the running block size starts a new block (D-16).
    val runningSize = block.first().fragment.style.fontSizePt
    if (runningSize > 0f) {
        val ratio = size / runningSize
        if (ratio >= 1.2f || ratio <= 1f / 1.2f) return true
    }

    // Leading gap: at or above 1.5 × line height the block ends (D-15).
    // For horizontal LTR text (folded top-down, y-up) the whitespace above the
    // candidate line is `last baseline − line top`; for vertical fragments the
    // whitespace precedes along the fragment direction (below is +y).
    val vertical = isVerticalFragment(line)
    val lineHeight = lineAscent(line)
    val leadingGap = if (vertical) line.rect.minX - last.rect.maxX
    else lineBaselineOffset(last) - line.rect.maxY
    if (lineHeight > 0f && leadingGap >= 1.5f * lineHeight) return true

    // Column / table-cell gap: the left edge must either hold (≤ 0.5 em),
    // or advance as a first-line indent (≤ 2 em) after a line that reached
    // the right margin (hanging-indent continuation, D-15/D-16).
    val deltaLeft = line.rect.minX - last.rect.minX
    val atMargin = last.rect.maxX >= maxRight - size * 0.5f
    if (abs(deltaLeft) > size * 0.5f) {
        val hangingIndent = deltaLeft > 0f && deltaLeft <= size * 2f && atMargin
        if (!hangingIndent) return true
    }

    // New-block triggers (D-16).
    val text = line.chars.joinToString("") { it.text }
    if (startsAsListLabel(text)) return true
    if (startsAsBulletGlyph(line)) return true

    // Short line followed by a style change starts a new block (D-16).
    val widest = block.maxOf { it.rect.width }
    if (last.rect.width < widest * 0.5f) {
        val subtleStyleChange = abs(last.fragment.style.fontSizePt - size) > 0.01f ||
            !sameDirection(last.fragment.style, line.fragment.style)
        if (subtleStyleChange) return true
    }

    return false
}

/** Block height cap: total height exceeds 12 × the median member line height. */
private fun isBlockHeightCapped(block: List<TextLine>): Boolean {
    if (block.size < 2) return false
    val heights = block.map { lineAscent(it) }.sorted()
    val median = heights[heights.size / 2]
    if (median <= 0f) return false
    val top = block.maxOf { lineBaselineOffset(it) }
    val bottom = block.minOf { lineBaselineOffset(it) }
    return (top - bottom) >= MAX_BLOCK_HEIGHT_LINE_HEIGHTS * median
}

/** Direction match within the ±15° fragment tolerance (D-13). */
private fun sameDirection(a: TextStyle, b: TextStyle): Boolean {
    val dot = a.directionX * b.directionX + a.directionY * b.directionY
    val lenA = kotlin.math.sqrt(a.directionX * a.directionX + a.directionY * a.directionY)
    val lenB = kotlin.math.sqrt(b.directionX * b.directionX + b.directionY * b.directionY)
    return dot / kotlin.math.max(lenA * lenB, 1e-6f) >= 0.965926f // cos(15°) ≈ 0.966
}

/**
 * Leading list label (D-16): `1.`, `a)`, `iii.` followed by a dot or closing
 * bracket — digits, a single letter, or 2+ Roman numeral letters.
 */
private fun startsAsListLabel(text: String): Boolean {
    val t = text.trimStart()

    // digits "1.", "12.", "365."
    var i = 0
    while (i < t.length && i < 4 && t[i].isDigit()) i++
    if (i > 0 && i < t.length && (t[i] == '.' || t[i] == ')')) return true

    // single letter "a)", "A."
    if (t.length > 1 && t[0].isLetter() && (t[1] == '.' || t[1] == ')')) return true

    // Roman numerals "iii.", "xv)", "I." (2+ letters then a delimiter)
    return isRomanNumeral(t)
}

/** True when [text] starts with 2+ Roman numeral letters (`iii.`, `xv)`). */
private fun isRomanNumeral(text: String): Boolean {
    var i = 0
    while (i < text.length && text[i].lowercaseChar() in "ivxlcdm") i++
    return i >= 2 && i < text.length && (text[i] == '.' || text[i] == ')')
}

/**
 * Leading bullet glyph (D-16): the common Unicode bullets, plus Symbol and
 * Wingdings bullets that surface as U+F020..U+F0FF private-use code points.
 */
private fun startsAsBulletGlyph(line: TextLine): Boolean {
    val first = line.chars.firstOrNull() ?: return false
    val gc = first.text.firstOrNull() ?: return false
    if (gc.code in 0xF020..0xF0FF) return true
    return gc in "•●◦‣·■▪○◦❖-"
}
