package com.redraft.ui.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun EditorPanel(
    state: EditorPanelState,
    onTextChange: (String) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    // Docked above IME, no dim — uses imePadding()
    AnimatedVisibility(
        visible = true, // Controlled by parent visibility
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(150)),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(100)),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp)
                .imePadding()  // Critical: sits above keyboard
                .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Title
            Text(
                text = stringResource(com.redraft.R.string.edit_text_title), // "Edit Text"
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )

            // Inline notices (amber = primary)
            if (state.fontChangeNotice != null) {
                Text(
                    text = stringResource(com.redraft.R.string.font_will_change, state.fontChangeNotice),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            if (state.lengthWarning) {
                Text(
                    text = stringResource(com.redraft.R.string.text_longer_than_line),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            if (state.showUnsupportedChar != null) {
                Text(
                    text = stringResource(com.redraft.R.string.unsupported_char_notice, state.showUnsupportedChar.toString()),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }

            // Single-line TextField
            val editingDesc = stringResource(com.redraft.R.string.cd_editing_line, state.originalText.take(30))
            TextField(
                value = state.draftText,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = editingDesc },
                singleLine = true,
                // keyboardOptions and keyboardActions use defaults
                textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                    fontSize = state.fontSizePt.sp,
                    color = Color(state.textColor),
                ),
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            )

            // Apply / Cancel buttons
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(com.redraft.R.string.cancel))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onApply,
                    enabled = state.canApply,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(stringResource(com.redraft.R.string.apply))
                }
            }
        }
    }
}