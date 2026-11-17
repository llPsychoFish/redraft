package com.redraft.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
import com.redraft.data.AppSettings
import com.redraft.ui.MIN_ZOOM
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.ViewerMessage
import com.redraft.ui.settings.SettingsViewModel
import kotlinx.coroutines.launch

private val TopBarHeight = 64.dp

@Composable
fun ViewerRoute(
    viewModel: PdfViewerViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenOverview: () -> Unit,
    modifier: Modifier = Modifier,
    initialUri: Uri? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val editMode by viewModel.editMode.collectAsStateWithLifecycle()
    val selection by viewModel.selectionState.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val pastCommands by viewModel.pastCommands.collectAsStateWithLifecycle()
    val futureCommands by viewModel.futureCommands.collectAsStateWithLifecycle()
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current

    // "Keep screen on while reading" is a real behaviour, not a stored preference: the window flag
    // is held only while a document is actually open, and released when the viewer leaves.
    val keepScreenOn = settings?.keepScreenOn ?: false
    DisposableEffect(view, keepScreenOn, uiState.pageCount) {
        view.keepScreenOn = keepScreenOn && uiState.pageCount > 0
        onDispose { view.keepScreenOn = false }
    }

    // System bars controller for immersive mode
    val insetsController = remember(view) {
        val activity = (context as? android.app.Activity)
        val window = activity?.window
            ?: throw IllegalStateException("Unable to get Window for insets controller")
        WindowCompat.getInsetsController(window, view)
    }

    // Tap toggles the chrome. The tap is deferred by the double-tap window inside
    // singlePointerDoubleTap, so a double tap still zooms instead of flashing the bars.
    var barsVisible by remember { mutableStateOf(true) }

    // Edit mode keeps the bars visible: a tap selects text instead of hiding
    // the chrome (D-19).
    LaunchedEffect(editMode) {
        if (editMode) barsVisible = true
    }

    // Snackbar host (D-30, D-31): the messages resolve to string resources
    // here, so the copy stays in strings.xml.
    val snackbarHostState = remember { SnackbarHostState() }
    val noSelectableText = stringResource(R.string.no_selectable_text)
    val couldNotReadText = stringResource(R.string.could_not_read_text)
    val editFailed = stringResource(R.string.edit_failed)
    val nothingSelected = stringResource(R.string.select_line_first)
    val lineNotEditable = stringResource(R.string.line_not_editable)
    val pageTextNotReady = stringResource(R.string.page_text_not_ready)
    val editingUnavailable = stringResource(R.string.editing_unavailable)
    val editTargetAmbiguous = stringResource(R.string.edit_target_ambiguous)
    LaunchedEffect(
        viewModel,
        noSelectableText,
        couldNotReadText,
        editFailed,
        nothingSelected,
        lineNotEditable,
        pageTextNotReady,
        editingUnavailable,
        editTargetAmbiguous,
    ) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                ViewerMessage.NoSelectableText -> noSelectableText
                ViewerMessage.CouldNotReadText -> couldNotReadText
                ViewerMessage.EditFailed -> editFailed
                ViewerMessage.NothingSelected -> nothingSelected
                ViewerMessage.LineNotEditable -> lineNotEditable
                ViewerMessage.PageTextNotReady -> pageTextNotReady
                ViewerMessage.EditingUnavailable -> editingUnavailable
                ViewerMessage.EditTargetAmbiguous -> editTargetAmbiguous
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    // Update system bars visibility when chrome visibility changes
    DisposableEffect(barsVisible) {
        val systemBars = WindowInsetsCompat.Type.systemBars()
        if (barsVisible) {
            // Show system bars
            insetsController.show(systemBars)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Hide system bars
            insetsController.hide(systemBars)
        }
        onDispose {
            // Restore system bars when leaving the viewer
            insetsController.show(systemBars)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // The bar floats over the list, so the list reserves exactly its height: status bar + app bar.
    // Reserved even while the bar is hidden, so toggling the chrome never shifts the page.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarSpace = statusBarTop + TopBarHeight

    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        // Content - PdfViewerScreen takes full screen
        PdfViewerScreen(
            viewModel = viewModel,
            settingsViewModel = settingsViewModel,
            onSingleTap = {
                // In Edit mode a tap selects text, it never toggles the
                // chrome (D-19).
                if (!editMode) barsVisible = !barsVisible
            },
            topContentPadding = topBarSpace,
            modifier = Modifier.fillMaxSize(),
        )

        // Overlay TopAppBar with animation
        AnimatedVisibility(
            visible = barsVisible,
            enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                slideInVertically(initialOffsetY = { -it }, animationSpec = tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(150, easing = FastOutSlowInEasing)) +
                slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(150, easing = FastOutSlowInEasing)),
        ) {
            ViewerTopBar(
                title = uiState.documentName.orEmpty(),
                pageIndex = uiState.visiblePageIndex,
                pageCount = uiState.pageCount,
                zoom = uiState.zoom,
                editMode = editMode,
                hasSelection = selection != null,
                canEditSelection = selection?.editable == true,
                onBack = onBack,
                onFitWidth = {
                    viewModel.onZoomChanged(MIN_ZOOM)
                    viewModel.onPanChanged(0f)
                },
                onOpenOverview = onOpenOverview,
                onToggleEditMode = { viewModel.toggleEditMode() },
                onEditText = { viewModel.onEditText() },
                onNothingSelected = {
                    scope.launch { snackbarHostState.showSnackbar(nothingSelected) }
                },
                canUndo = pastCommands.isNotEmpty(),
                canRedo = futureCommands.isNotEmpty(),
                onUndo = { viewModel.onUndo() },
                onRedo = { viewModel.onRedo() },
                onCannotEdit = {
                    scope.launch { snackbarHostState.showSnackbar(lineNotEditable) }
                },
            )
        }

        // Editor panel (docked above the IME). Drawn AFTER the page and the bar so it is
        // never underneath them. The last non-null state is retained so the exit
        // animation does not read `editorState!!` after it has become null.
        val shownEditor = remember { mutableStateOf<EditorPanelState?>(null) }
        if (editorState != null) shownEditor.value = editorState
        AnimatedVisibility(
            visible = editorState != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(150)) + slideInVertically(initialOffsetY = { it }, animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(100)) + slideOutVertically(targetOffsetY = { it }, animationSpec = tween(100)),
        ) {
            shownEditor.value?.let { state ->
                EditorPanel(
                    state = state,
                    onTextChange = { newText ->
                        // The ViewModel recomputes the warnings and apply eligibility from
                        // the new draft; the UI must not duplicate that logic.
                        viewModel.updateEditorState(state.withDraftText(newText))
                    },
                    onFormatChange = { newState ->
                        viewModel.updateEditorState(newState)
                    },
                    onApply = { viewModel.onApplyEdit(state.draftText) },
                    onCancel = { viewModel.dismissEditor() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding() // respect nav bar
                        .imePadding(), // sits above the keyboard
                )
            }
        }

        // Snackbar host (D-31): overlaid ABOVE the page and the page-indicator
        // pill, positioned above the navigation-bar inset and the pill, so it
        // never shifts the page and never intercepts a tap.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp),
        )

        // Auto-zoom to the selected line when the editor opens (Task 4a).
        // Restore happens in dismissEditor() / endEditSession().
        val density = LocalDensity.current
        val config = LocalConfiguration.current
        val viewportWidthPx = (config.screenWidthDp * density.density).toInt()
        LaunchedEffect(editorState, selection, viewportWidthPx) {
            if (editorState != null) {
                val lineRect = selection?.selectedLine?.rect ?: return@LaunchedEffect
                viewModel.zoomToLine(lineRect, viewportWidthPx)
            }
        }
    }

    // Back handling in Edit mode (D-20, D-301): editor panel is dismissed first,
    // then a selected line is cleared, then Edit mode is left, then the viewer is left.
    // The handler is enabled ONLY while Edit mode is on.
    // Leaving with unsaved edits asks first. The original file is never modified.
    BackHandler(enabled = !editMode && pastCommands.isNotEmpty()) { showUnsavedDialog = true }
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.unsaved_edits_title)) },
            text = { Text(stringResource(R.string.unsaved_edits_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        onBack()
                    },
                ) { Text(stringResource(R.string.leave_without_saving)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) {
                    Text(stringResource(R.string.keep_editing))
                }
            },
        )
    }

    BackHandler(enabled = editMode) {
        val currentSelection = selection
        if (editorState != null) viewModel.dismissEditor()
        else if (currentSelection != null) viewModel.clearSelection()
        else viewModel.setEditMode(false)
    }
}

/**
 * The viewer's top bar, as a plain composable with no ViewModel dependency.
 *
 * Every action sits directly in `actions` — no `fillMaxSize` wrapper — so the bar
 * never steals width from the title or overlaps the back arrow. The Edit-text
 * action is a separate icon from the Edit-mode pencil and is deliberately kept
 * enabled when it cannot act, so a tap can explain why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerTopBar(
    title: String,
    pageIndex: Int,
    pageCount: Int,
    zoom: Float,
    editMode: Boolean,
    hasSelection: Boolean,
    canEditSelection: Boolean,
    onBack: () -> Unit,
    onFitWidth: () -> Unit,
    onOpenOverview: () -> Unit,
    onToggleEditMode: () -> Unit,
    onEditText: () -> Unit,
    onNothingSelected: () -> Unit,
    onCannotEdit: () -> Unit,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        // TopAppBar already pads itself for the status bar; the old extra manual
        // padding doubled it and made the bar taller than the space reserved for it.
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                )
            }
        },
        actions = {
            if (pageCount > 0) {
                Text(
                    text = stringResource(R.string.viewer_page_counter, pageIndex + 1, pageCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            // Fit width is the zoom floor, so the control is only meaningful
            // while the reader is zoomed in.
            IconButton(
                onClick = onFitWidth,
                enabled = zoom > MIN_ZOOM,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_fit_width),
                    contentDescription = stringResource(R.string.cd_fit_width),
                )
            }
            IconButton(onClick = onOpenOverview) {
                Icon(
                    painter = painterResource(R.drawable.ic_grid_view),
                    contentDescription = stringResource(R.string.cd_page_overview),
                )
            }
            // Edit mode (D-18, D-22): a pencil action with a
            // string-resource content description and a selected state
            // for TalkBack.
            IconButton(
                onClick = onToggleEditMode,
                modifier = Modifier.semantics { selected = editMode },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit_mode),
                    tint = if (editMode) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            // "Edit text" — a DIFFERENT glyph from the Edit-mode pencil, only in
            // Edit mode, and always tappable so an unavailable action can say why.
            if (editMode) {
                IconButton(onClick = onUndo, enabled = canUndo) {
                    Icon(
                        painter = painterResource(R.drawable.ic_undo),
                        contentDescription = stringResource(R.string.cd_undo),
                    )
                }
                IconButton(onClick = onRedo, enabled = canRedo) {
                    Icon(
                        painter = painterResource(R.drawable.ic_redo),
                        contentDescription = stringResource(R.string.cd_redo),
                    )
                }
                val editDesc = if (canEditSelection) {
                    stringResource(R.string.edit_selected_text)
                } else {
                    stringResource(R.string.cannot_edit_text)
                }
                IconButton(
                    onClick = {
                        when {
                            !hasSelection -> onNothingSelected()
                            !canEditSelection -> onCannotEdit()
                            else -> onEditText()
                        }
                    },
                    modifier = Modifier.semantics { contentDescription = editDesc },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit_text),
                        contentDescription = editDesc,
                        tint = if (canEditSelection) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        },
    )
}
