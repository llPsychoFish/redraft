package com.redraft.ui.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
                    onFormatChange = {},
                    onApply = {},
                    onCancel = { cancelled = true },
                )
            }
        }

        composeRule.onNodeWithText("Edit Text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Document text colour").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Editing line: Hello World", substring = true)
            .assertIsDisplayed()

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.runOnIdle { assertTrue(cancelled) }
    }

    @Test
    fun typing_updates_the_draft_text() {
        var latestDraft = ""
        composeRule.setContent {
            MaterialTheme {
                EditorPanel(
                    state = EditorPanelState(
                        originalText = "Hello World",
                        draftText = "Hello World",
                        fontSizePt = 12f,
                        textColor = 0xFF000000.toInt(),
                    ),
                    onTextChange = { latestDraft = it },
                    onFormatChange = {},
                    onApply = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Editing line: Hello World", substring = true)
            .performTextInput(" Beautiful")
        composeRule.runOnIdle {
            assertTrue(latestDraft == "Hello World Beautiful")
        }
    }

    @Test
    fun changing_size_updates_font_size_pt() {
        var latestSize = 0f
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
                    onFormatChange = { state ->
                        latestSize = state.effectiveFontSizePt
                    },
                    onApply = {},
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("A+").performClick()
        composeRule.runOnIdle {
            assertTrue(latestSize == 14f)
        }
    }

    @Test
    fun apply_invokes_on_apply() {
        var applyCalled = false
        composeRule.setContent {
            MaterialTheme {
                EditorPanel(
                    state = EditorPanelState(
                        originalText = "Hello World",
                        draftText = "Hello World",
                        fontSizePt = 12f,
                        textColor = 0xFF000000.toInt(),
                        canApply = true,
                    ),
                    onTextChange = {},
                    onFormatChange = {},
                    onApply = { applyCalled = true },
                    onCancel = {},
                )
            }
        }

        composeRule.onNodeWithText("Apply").performClick()
        composeRule.runOnIdle {
            assertTrue(applyCalled)
        }
    }
}
