package com.redraft.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import com.redraft.pdfcore.PdfRect
import com.redraft.pdfcore.PdfViewportTransform
import com.redraft.pdfcore.ViewportState
import com.redraft.pdfcore.selection.CharBox
import com.redraft.pdfcore.selection.TextFragment
import com.redraft.pdfcore.selection.TextLine
import com.redraft.pdfcore.selection.TextStyle
import org.junit.Rule
import org.junit.Test

/**
 * Regression guard for the selection UI (quick task 261120-fix-selection-and-apply).
 *
 * Commit 5d125ec drew an opaque white "in-place text field" over the selected line on
 * EVERY page whenever Edit mode was on. It hid the page text and the selection outline.
 * These tests pin the contract that survived: the overlay is drawn only on the page that
 * owns the selection, it announces itself, and nothing is painted over the page text.
 */
class SelectionOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val viewport = ViewportState(zoom = 1f, panX = 0f, panY = 0f)
    private val transform = PdfViewportTransform(
        pageWidthPt = 612f,
        pageHeightPt = 792f,
        rotationDegrees = 0,
        renderedWidthPx = 612,
        renderedHeightPx = 792,
    )

    private fun selectedLine(text: String = "Heading"): TextLine {
        val chars = text.mapIndexed { index, c ->
            CharBox(
                index = index,
                text = c.toString(),
                rect = PdfRect(
                    minX = 100f + index * 10f,
                    minY = 700f,
                    maxX = 110f + index * 10f,
                    maxY = 710f,
                ),
                fontSizePt = 10f,
            )
        }
        val fragment = TextFragment(chars, TextStyle(fontSizePt = 10f, baselineY = 700f))
        return TextLine(chars, PdfRect(100f, 700f, 170f, 710f), fragment)
    }

    @Test
    fun selection_outline_is_shown_on_the_owning_page_without_covering_the_text() {
        val state = SelectionOverlayState(
            selectedLine = selectedLine(),
            pageIndex = 0,
            editable = true,
        )

        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    // The page text behind the overlay must stay visible.
                    Text("Heading", modifier = Modifier.fillMaxSize())
                    SelectionOverlay(
                        pageIndex = 0,
                        state = state,
                        viewport = viewport,
                        transform = transform,
                    )
                }
            }
        }

        // The outline is drawn and announced.
        composeRule
            .onNodeWithContentDescription("Selected line: Heading", substring = true)
            .assertIsDisplayed()

        // The page text is still there — nothing opaque was painted over it.
        composeRule.onNodeWithText("Heading").assertIsDisplayed()

        // The broken in-place field is gone for good.
        composeRule
            .onAllNodesWithContentDescription("in-place text field")
            .assertCountEquals(0)
    }

    @Test
    fun nothing_is_drawn_on_a_page_that_does_not_own_the_selection() {
        val state = SelectionOverlayState(
            selectedLine = selectedLine(),
            pageIndex = 0,
            editable = true,
        )

        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SelectionOverlay(
                        pageIndex = 1, // different page
                        state = state,
                        viewport = viewport,
                        transform = transform,
                    )
                }
            }
        }

        composeRule
            .onAllNodesWithContentDescription("Selected line: Heading", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun no_selection_draws_nothing() {
        composeRule.setContent {
            MaterialTheme {
                SelectionOverlay(
                    pageIndex = 0,
                    state = null,
                    viewport = viewport,
                    transform = transform,
                )
            }
        }

        composeRule
            .onAllNodesWithContentDescription("Selected line", substring = true)
            .assertCountEquals(0)
    }
}
