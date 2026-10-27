package com.redraft.pdfcore.selection

import com.redraft.pdfcore.PdfRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the D-15 / D-16 / D-17 block grouping: deterministic blockId,
 * block-stop rules, new-block triggers, caps, and the guarantee that grouping
 * is metadata only (D-07).
 */
class BlockGroupingTest {

    // ─── Builders ────────────────────────────────────────────────────────────

    /**
     * One horizontal line whose box runs `[baseline, baseline + size]` in y-up
     * space (the reducer's stated convention: `minY` == baseline proxy), every
     * char `size/2` pt wide.
     */
    private fun line(
        index: Int,
        text: String,
        x: Float,
        baseline: Float,
        size: Float = 10f,
    ): TextLine {
        val charW = size * 0.5f
        val chars = text.mapIndexed { i, c ->
            CharBox(
                index = index + i,
                text = c.toString(),
                rect = PdfRect(x + i * charW, baseline, x + (i + 1) * charW, baseline + size),
                fontSizePt = size,
            )
        }
        return TextLine(
            chars = chars,
            rect = glyphUnion(chars),
            fragment = TextFragment(chars, TextStyle(fontSizePt = size, baselineY = baseline)),
        )
    }

    private fun blockIds(blocks: List<TextBlock>): List<String> = blocks.map { it.blockId }

    // ─── D-17: deterministic identity ────────────────────────────────────────

    @Test
    fun two_calls_over_the_same_input_produce_identical_block_ids() {
        val lines = listOf(
            line(0, "First paragraph line.", 100f, 700f),
            line(3, "Second line, same block.", 100f, 690f),
            line(9, "Second block by a big gap.", 100f, 650f),
        )
        val first = groupBlocks(lines, pageIndex = 4)
        val second = groupBlocks(lines, pageIndex = 4)
        assertEquals(blockIds(first), blockIds(second))
        assertTrue(first.first().blockId.startsWith("p4:b"))
    }

    @Test
    fun block_id_is_derived_from_page_index_and_first_char_index() {
        val lines = listOf(
            line(0, "Aaa.", 100f, 700f),
            line(4, "Bbb.", 100f, 690f),
        )
        val blocks = groupBlocks(lines, pageIndex = 7)
        assertEquals(listOf("p7:b0"), blockIds(blocks))
    }

    // ─── D-15: leading-gap block stop ────────────────────────────────────────

    @Test
    fun a_1_5x_line_height_whitespace_starts_a_new_block() {
        // Box height 10; row one box is [700..710], row two box is [675..685]:
        // the whitespace between them is exactly 1.5 × height → new block.
        val l1 = line(0, "Row one.", 100f, 700f)
        val l2 = line(3, "Row two far below.", 100f, 675f)
        assertEquals(2, groupBlocks(listOf(l1, l2)).size)
    }

    @Test
    fun a_1_4x_line_height_whitespace_does_not_split() {
        val l1 = line(0, "Row one.", 100f, 700f)
        val l2 = line(3, "Row two, close.", 100f, 676f) // whitespace 14 pt
        assertEquals(1, groupBlocks(listOf(l1, l2)).size)
    }

    // ─── D-16: list labels start new blocks ──────────────────────────────────

    @Test
    fun numeric_letter_and_roman_list_labels_each_start_a_new_block() {
        for (label in listOf("1.", "a)", "iii.")) {
            val l1 = line(0, "Intro paragraph text.", 100f, 700f)
            val l2 = line(6, label + " An item text.", 100f, 690f)
            val blocks = groupBlocks(listOf(l1, l2))
            assertEquals("label $label must start a new block", 2, blocks.size)
        }
    }

    @Test
    fun a_lead_bullet_glyph_starts_a_new_block() {
        val l1 = line(0, "Prev paragraph.", 100f, 700f)
        val l2 = line(5, "• Bullet item.", 100f, 690f)
        assertEquals(2, groupBlocks(listOf(l1, l2)).size)
    }

    @Test
    fun a_symbol_font_range_codepoint_starts_a_new_block() {
        // Symbol/Wingdings bullets surface as U+F020..U+F0FF private-use chars.
        val l1 = line(0, "Prev paragraph.", 100f, 700f)
        val chars = l2like(9)
        val l2 = chars
        assertEquals(0xF0B7.toChar(), l2.chars.first().text[0])
        assertEquals(2, groupBlocks(listOf(l1, l2)).size)
    }

    private fun l2like(index: Int): TextLine {
        val bulletChars = listOf(
            CharBox(
                index = index,
                text = 0xF0B7.toChar().toString(),
                rect = PdfRect(100f, 690f, 102f, 700f),
                fontSizePt = 10f,
            ),
            CharBox(
                index = index + 1,
                text = "Item text.",
                rect = PdfRect(112f, 690f, 162f, 700f),
                fontSizePt = 10f,
            ),
        )
        return TextLine(
            chars = bulletChars,
            rect = PdfRect(100f, 690f, 162f, 700f),
            fragment = TextFragment(
                bulletChars,
                TextStyle(fontSizePt = 10f, baselineY = 690f),
            ),
        )
    }

    // ─── D-16: hanging-indent continuation ───────────────────────────────────

    @Test
    fun hanging_indent_continuation_stays_in_its_list_item_block() {
        // The item line reaches the page's right margin (it is the widest
        // line here: maxX = 100 + 43 × 5 = 315), then a continuation indented
        // 15 pt (= 1.5 em of size 10, within the 2 em hanging-indent window).
        val l1 = line(0, "First item line reaching the right margin..", 100f, 700f)
        val continuation = line(43, "continuation text of the same item", 115f, 690f)
        assertEquals(1, groupBlocks(listOf(l1, continuation)).size)
    }

    @Test
    fun a_left_edge_change_without_a_margin_reached_line_starts_a_new_block() {
        // The previous line is narrow (does not reach the right margin), the
        // next line jumps 30 pt right — a column/table-cell gap, not a
        // hanging indent (edge advanced > 2 em with no margin line before it).
        val l1 = line(0, "Narrow.", 100f, 700f)
        val l2 = line(3, "Different column.", 130f, 690f)
        assertEquals(2, groupBlocks(listOf(l1, l2)).size)
    }

    // ─── D-17: caps ──────────────────────────────────────────────────────────

    @Test
    fun a_41_line_run_is_split_into_two_blocks_at_the_cap() {
        val lines = mutableListOf<TextLine>()
        var index = 0
        var baseline = 700f
        repeat(41) {
            lines.add(line(index, "Line $it of the run.", 100f, baseline))
            index += lines.last().chars.last().index + 1
            // Rows overlap heavily (baseline steps 2 pt against 10 pt boxes)
            // so the 40-line cap fires before the 12-height cap.
            baseline -= 2f
        }
        val blocks = groupBlocks(lines)
        // 40-line cap: the 41st line folds into a second block.
        assertEquals(listOf(40, 1), blocks.map { it.lines.size })
        assertTrue(blocks[0].blockId != blocks[1].blockId)
    }

    // ─── D-07: grouping is metadata only ─────────────────────────────────────

    @Test
    fun grouping_leaves_every_line_selection_rectangle_unchanged() {
        val lines = listOf(
            line(0, "One.", 100f, 700f),
            line(4, "Two.", 100f, 690f),
            line(8, "Three.", 100f, 650f),
        )
        val inputByKey = lines.associateBy { it.firstCharIndex }
        val out = groupBlocks(lines).flatMap { it.lines }
        assertEquals(lines.size, out.size)
        for (outLine in out) {
            val original = requireNotNull(inputByKey[outLine.firstCharIndex])
            assertEquals(original.rect.minX, outLine.rect.minX, 0f)
            assertEquals(original.rect.maxX, outLine.rect.maxX, 0f)
            assertEquals(original.rect.minY, outLine.rect.minY, 0f)
            assertEquals(original.rect.maxY, outLine.rect.maxY, 0f)
        }
        // Grouping output never influences hit resolution: HitTest.kt contains
        // no reference to groupBlocks or TextBlock (D-07) — that is asserted at
        // the module level, not by a unit test.
    }
}
