package com.redraft.ui.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EditorPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun panel_shows_the_field_and_cancel_dismisses_it() {
        var cancelled = false
        composeRule.setContent {
            MaterialTheme {
                EditorPanel(
                    state = EditorPanelState(
                        originalText = "Hello World",
                        draftText = "Hello World",
                        fontSizePt = 12f,
                        textColor = 0xFF000000.toInt(),
                    ),
                    onTextChange = {},
                    onApply = {},
                    onCancel = { cancelled = true },
                )
            }
        }

        composeRule.onNodeWithText("Edit Text").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Editing line: Hello World", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
    }
}
