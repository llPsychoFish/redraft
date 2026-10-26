package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfRect

/**
 * Block grouping: assigns a stable [blockId] to each line based on proximity
 * and indentation rules (D-07).
 *
 * This module is purely for metadata enrichment — it does NOT affect hit-testing,
 * caret placement, or any other Phase-2 acceptance criterion.
 */

/**
 * Assign a stable [TextLine.blockId] to each line in [lines] using the block
 * grouping rules of D-07:
 *
 * 1. **Paragraph grouping**: if two consecutive lines have an overlap on the x-axis
 *    that is ≥ 60 % of the narrower line's width, they belong to the same block.
 * 2. **First-line indentation**: a line whose left indent exceeds the right indent
 *    of the preceding line by more than 2 em (of its own font size) starts a new block.
 * 3. **Orphan rule**: a line standing alone between two blocks starts a new block.
 *
 * The blockId format is `"block-{number}"` where the number is 1-based and
 * increases monotonically across the page (stable within a session but not
 * guaranteed across renders).
 */
fun groupBlocks(lines: List<TextLine>): List<TextLine> {
    if (lines.isEmpty()) return emptyList()

    val result = mutableListOf<TextLine>()
    var blockNumber = 1

    for ((index, line) in lines.withIndex()) {
        val prev = lines.getOrNull(index - 1)

        val newBlock = when {
            // First line always starts block 1
            index == 0 -> true
            // Paragraph continuation: sufficient x-overlap with prev line (D-07)
            prev != null && xOverlapFrac(line.rect, prev.rect) >= 0.6f -> false
            // Indentation rule: left indent of this line exceeds right indent of
            // prev line by > 2 em → new block (D-07)
            prev != null && ({
                val em = line.chars.firstOrNull()?.fontSizePt ?: 10f
                val leftIndent = line.rect.minX - prev.rect.minX
                val rightIndent = prev.rect.maxX - line.rect.maxX
                leftIndent - rightIndent > 2f * em
            })() -> true
            // Orphan rule: isolated line between two blocks (D-07)
            prev != null && result.lastOrNull()?.blockId != null -> true
            else -> false
        }

        val blockId = if (newBlock) {
            (blockNumber++).toString().let { "block-$it" }
        } else {
            result.lastOrNull()?.blockId
        }

        result.add(line.copy(blockId = blockId))
    }

    return result
}

/**
 * Fraction of the narrower line's width that overlaps on the x-axis with the wider line.
 * Returns 0 when the lines do not overlap.
 */
private fun xOverlapFrac(a: PdfRect, b: PdfRect): Float {
    val overlapLeft = maxOf(a.minX, b.minX)
    val overlapRight = minOf(a.maxX, b.maxX)
    if (overlapRight <= overlapLeft) return 0f
    val overlap = overlapRight - overlapLeft
    val minWidth = minOf(a.width, b.width)
    return if (minWidth > 0f) overlap / minWidth else 0f
}
