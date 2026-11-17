package com.redraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redraft.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorPanel(
    state: EditorPanelState,
    onTextChange: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    // Request focus (and the keyboard) ONCE when the panel is first shown. Run after
    // the first layout pass: a FocusRequester that is not attached yet throws.
    LaunchedEffect(Unit) {
        delay(1)
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Title, with the document's real text colour as a small swatch so the
        // preview can use the theme colour without hiding that information.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.edit_text_title), // "Edit Text"
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            val colorDesc = stringResource(R.string.cd_document_text_color)
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(state.textColor))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        shape = CircleShape,
                    )
                    .semantics { contentDescription = colorDesc },
            )
        }

        // Inline notices (amber = primary)
        if (state.fontChangeNotice != null) {
            Text(
                text = stringResource(R.string.font_will_change, state.fontChangeNotice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        if (state.lengthWarning) {
            Text(
                text = stringResource(R.string.text_longer_than_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
        }
        if (state.showUnsupportedChar != null) {
            Text(
                text = stringResource(R.string.unsupported_char_notice, state.showUnsupportedChar.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        // Single-line TextField with proper IME options
        val editingDesc = stringResource(R.string.cd_editing_line, state.originalText.take(30))
        TextField(
            value = state.draftText,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = editingDesc }
                .focusRequester(focusRequester),
            singleLine = true,
            enabled = !state.applyInProgress,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Sentences,
            ),
            // The preview uses the theme's readable colour, never the document's:
            // painting black PDF text on this dark panel made the draft invisible.
            // The size is clamped so a large heading still fits the field.
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = state.fontSizePt.coerceIn(12f, 20f).sp,
            ),
            interactionSource = remember { MutableInteractionSource() },
        )

        // Every failure is reported here, inside the panel, not in a snackbar
        // that can sit behind the keyboard.
        state.applyError?.let { error ->
            Text(
                text = error.message(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        state.applyErrorDetail?.let { detail ->
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }

        // Apply / Cancel buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onApply,
                enabled = state.canApply && !state.applyInProgress,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (state.applyInProgress) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

@Composable
private fun EditorEditFailure.message(): String = stringResource(
    when (this) {
        EditorEditFailure.NO_ENGINE -> R.string.edit_error_no_engine
        EditorEditFailure.NO_SELECTION -> R.string.edit_error_no_selection
        EditorEditFailure.TARGET_NOT_FOUND -> R.string.edit_error_target_not_found
        EditorEditFailure.PIECES_DO_NOT_ADD_UP -> R.string.edit_error_pieces
        EditorEditFailure.FONT_FAILED -> R.string.edit_error_font
        EditorEditFailure.SAVE_FAILED -> R.string.edit_error_save
        EditorEditFailure.UNSUPPORTED_CHARACTER -> R.string.edit_error_unsupported
        EditorEditFailure.GENERIC -> R.string.edit_error_generic
    },
)
