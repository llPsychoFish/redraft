package com.redraft.ui.home

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
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
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
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
        if (uiState.isLoadingRecents) {
            // Blank container — no empty-state flicker while DataStore read is in-flight.
            Box(modifier = Modifier.fillMaxSize().padding(padding))
        } else if (recents.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
            ) {
                EmptyStateIllustration(
                    modifier = Modifier.size(width = 140.dp, height = 160.dp),
                )
                Text(
                    text = "No recent PDFs yet",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Open a PDF to get started. Files you open will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Tip: PDFs you open from other apps appear here too.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(
                    onClick = launchPicker,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
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
                        onOpen = {
                            viewModel.openRecent(
                                file = file,
                                startPage = if (settings?.reopenAtLastPage == true) file.lastPageIndex else 0,
                            )
                        },
                        onRemove = { viewModel.removeRecent(file) },
                        // Staggered fade + placement animation as items enter/leave the list.
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

/**
 * Empty-state illustration: a small stack of page sheets, the front one carrying the
 * brand motif from the app icon (amber selection box with corner handles). Drawn on
 * Canvas so no new image assets or dependencies are needed, and it follows the theme
 * in both light and dark modes.
 */
@Composable
private fun EmptyStateIllustration(modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val amber = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val corner = w * 0.06f

        // Back sheet — tilted, outline only.
        withTransform({
            rotate(degrees = 6f, pivot = Offset(w * 0.55f, h * 0.5f))
        }) {
            drawRoundRect(
                color = outline.copy(alpha = 0.5f),
                topLeft = Offset(w * 0.28f, h * 0.10f),
                size = Size(w * 0.58f, h * 0.74f),
                cornerRadius = CornerRadius(corner),
                style = Stroke(width = 2f),
            )
        }

        // Middle sheet — slight counter-tilt, filled.
        withTransform({
            rotate(degrees = -3.5f, pivot = Offset(w * 0.48f, h * 0.52f))
        }) {
            drawRoundRect(
                color = surfaceVariant,
                topLeft = Offset(w * 0.18f, h * 0.13f),
                size = Size(w * 0.60f, h * 0.74f),
                cornerRadius = CornerRadius(corner),
            )
        }

        // Front sheet — the page being edited.
        val frontLeft = w * 0.14f
        val frontTop = h * 0.16f
        val frontW = w * 0.60f
        val frontH = h * 0.74f
        drawRoundRect(
            color = surface,
            topLeft = Offset(frontLeft, frontTop),
            size = Size(frontW, frontH),
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            color = outline.copy(alpha = 0.6f),
            topLeft = Offset(frontLeft, frontTop),
            size = Size(frontW, frontH),
            cornerRadius = CornerRadius(corner),
            style = Stroke(width = 2f),
        )

        // Muted "text line" bars above and below the selection.
        val lineColor = onSurfaceVariant.copy(alpha = 0.25f)
        val lineHeight = h * 0.035f
        drawRoundRect(
            color = lineColor,
            topLeft = Offset(frontLeft + frontW * 0.12f, frontTop + frontH * 0.12f),
            size = Size(frontW * 0.55f, lineHeight),
            cornerRadius = CornerRadius(lineHeight / 2),
        )
        drawRoundRect(
            color = lineColor,
            topLeft = Offset(frontLeft + frontW * 0.12f, frontTop + frontH * 0.22f),
            size = Size(frontW * 0.72f, lineHeight),
            cornerRadius = CornerRadius(lineHeight / 2),
        )

        // Amber selection box with corner handles — the brand motif.
        val selLeft = frontLeft + frontW * 0.12f
        val selTop = frontTop + frontH * 0.40f
        val selW = frontW * 0.68f
        val selH = frontH * 0.26f
        drawRoundRect(
            color = amber,
            topLeft = Offset(selLeft, selTop),
            size = Size(selW, selH),
            cornerRadius = CornerRadius(corner * 0.5f),
            style = Stroke(width = 3f),
        )
        val handle = h * 0.035f
        listOf(
            Offset(selLeft, selTop),
            Offset(selLeft + selW, selTop),
            Offset(selLeft, selTop + selH),
            Offset(selLeft + selW, selTop + selH),
        ).forEach { center ->
            drawRect(
                color = amber,
                topLeft = Offset(center.x - handle / 2, center.y - handle / 2),
                size = Size(handle, handle),
            )
        }

        drawRoundRect(
            color = lineColor,
            topLeft = Offset(frontLeft + frontW * 0.12f, frontTop + frontH * 0.78f),
            size = Size(frontW * 0.40f, lineHeight),
            cornerRadius = CornerRadius(lineHeight / 2),
        )
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