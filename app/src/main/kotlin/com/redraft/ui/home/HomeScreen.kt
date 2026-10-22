package com.redraft.ui.home

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
import com.redraft.data.DocumentError
import com.redraft.data.RecentFile
import com.redraft.data.SafDocumentOpener
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.settings.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: PdfViewerViewModel,
    settingsViewModel: SettingsViewModel,
    onDocumentOpened: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            SafDocumentOpener.persistPermission(context, it)
            viewModel.open(it.toString())
        }
    }
    val launchPicker = { openLauncher.launch(SafDocumentOpener.openPdfMimeTypes) }

    LaunchedEffect(Unit) {
        viewModel.documentOpened.collect { onDocumentOpened() }
    }

    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(error.userMessage())
        viewModel.clearError()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Redraft") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "Settings",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            // The empty state carries the only "Open PDF" action while there is nothing to
            // show, so the FAB would be a second button doing the same thing.
            if (recents.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = launchPicker,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_pdf),
                            contentDescription = null,
                        )
                    },
                    text = { Text("Open PDF") },
                )
            }
        },
    ) { padding ->
        if (recents.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_pdf),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = "No recent PDFs yet",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Open a PDF to get started. Files you open will appear here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = launchPicker) {
                    Text("Open PDF")
                }
            }
        } else {
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(recents, key = { it.uri }) { file ->
                    RecentItem(
                        file = file,
                        onOpen = { viewModel.openRecent(file) },
                        onRemove = { viewModel.removeRecent(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentItem(
    file: RecentFile,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        leadingContent = {
            Icon(
                painter = painterResource(R.drawable.ic_pdf),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // Last-opened date is the metadata the UI contract asks for (file size or date).
            Text(
                text = DateUtils.getRelativeTimeSpanString(file.lastOpenedEpochMs).toString(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = "More options",
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Remove from recents") },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onRemove()
                    },
                )
            }
        },
    )
}

private fun DocumentError.userMessage(): String = when (this) {
    is DocumentError.PasswordProtected -> "This PDF is password-protected"
    is DocumentError.Unreadable -> "Couldn't open this file"
    is DocumentError.AccessRevoked -> "Access to this file was revoked"
}