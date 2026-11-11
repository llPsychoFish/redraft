package com.redraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
        // Title
        Text(
            text = stringResource(R.string.edit_text_title), // "Edit Text"
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

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
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Sentences,
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = state.fontSizePt.sp,
                color = Color(state.textColor),
            ),
            interactionSource = remember { MutableInteractionSource() },
        )

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
                enabled = state.canApply,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}
