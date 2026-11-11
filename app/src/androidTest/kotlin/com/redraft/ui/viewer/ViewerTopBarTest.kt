package com.redraft.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ViewerTopBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val longTitle = "A very long research paper title that must ellipsize.pdf"

    @Test
    fun edit_mode_keeps_the_title_counter_and_all_actions_separate() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    ViewerTopBar(
                        title = longTitle,
                        pageIndex = 0,
                        pageCount = 6,
                        zoom = 2f,
                        editMode = true,
                        hasSelection = true,
                        canEditSelection = true,
                        onBack = {},
                        onFitWidth = {},
                        onOpenOverview = {},
                        onToggleEditMode = {},
                        onEditText = {},
                        onNothingSelected = {},
                        onCannotEdit = {},
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("1 / 6").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit selected text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Edit mode").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Page overview").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Fit width").assertIsDisplayed()

        val back = composeRule.onNodeWithContentDescription("Back").fetchSemanticsNode().boundsInRoot
        val counter = composeRule.onNodeWithText("1 / 6").fetchSemanticsNode().boundsInRoot
        val editText = composeRule
            .onNodeWithContentDescription("Edit selected text")
            .fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText(longTitle, substring = true)
            .fetchSemanticsNode().boundsInRoot

        assertFalse("the back arrow must not overlap the page counter", back.overlaps(counter))
        assertFalse("the Edit-text action must not overlap the back arrow", back.overlaps(editText))
        assertFalse("the title must not overlap the back arrow", title.overlaps(back))
        assertTrue("the title must keep a real width", title.width > 0f)
    }
}
