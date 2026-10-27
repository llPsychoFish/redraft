package com.redraft.ui.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.redraft.R
import com.redraft.ui.MIN_ZOOM
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.ViewerMessage
import com.redraft.ui.settings.SettingsViewModel
import com.redraft.data.AppSettings

private val TopBarHeight = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
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
    LaunchedEffect(viewModel, noSelectableText, couldNotReadText) {
        viewModel.messages.collect { message ->
            val text = when (message) {
                ViewerMessage.NoSelectableText -> noSelectableText
                ViewerMessage.CouldNotReadText -> couldNotReadText
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
            TopAppBar(
                // TopAppBar already pads itself for the status bar; the old extra manual
                // padding doubled it and made the bar taller than the space reserved for it.
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface),
                title = {
                    Text(
                        text = uiState.documentName.orEmpty(),
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
                    if (uiState.pageCount > 0) {
                        Text(
                            text = stringResource(R.string.viewer_page_counter, uiState.visiblePageIndex + 1, uiState.pageCount),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    // Fit width is the zoom floor, so the control is only meaningful
                    // while the reader is zoomed in.
                    IconButton(
                        onClick = {
                            viewModel.onZoomChanged(MIN_ZOOM)
                            viewModel.onPanChanged(0f)
                        },
                        enabled = uiState.zoom > MIN_ZOOM,
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
                        onClick = { viewModel.toggleEditMode() },
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
                },
            )
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
    }

    // Back handling in Edit mode (D-20): a selected line is cleared first,
    // then Edit mode is left, then the viewer is left.  The handler is
    // enabled ONLY while Edit mode is on.
    BackHandler(enabled = editMode) {
        if (selection != null) viewModel.clearSelection() else viewModel.setEditMode(false)
    }
}