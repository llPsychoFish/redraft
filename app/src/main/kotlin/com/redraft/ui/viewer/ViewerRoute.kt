package com.redraft.ui.viewer

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.redraft.R
import com.redraft.ui.MIN_ZOOM
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.settings.SettingsViewModel
import com.redraft.data.AppSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerRoute(
    viewModel: PdfViewerViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenOverview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
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

    // Update system bars visibility when chrome visibility changes
    DisposableEffect(barsVisible) {
        val statusBars = 1 // WindowInsetsControllerCompat.Type.STATUS_BARS
        val navigationBars = 2 // WindowInsetsControllerCompat.Type.NAVIGATION_BARS
        val systemBars = statusBars or navigationBars
        @SuppressLint("WrongConstant")
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

    // Calculate top padding for the content (status bar height) in pixels
    val topPaddingPx = remember(view) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val insets = view.rootWindowInsets
            val statusBarsType = 1 // WindowInsetsCompat.Type.STATUS_BARS
            insets.getInsets(statusBarsType).top.toFloat()
        } else {
            // Fallback for API < 30: use a reasonable default status bar height
            val metrics = view.resources.displayMetrics
            (metrics.heightPixels * 0.05f).toFloat() // ~5% of screen height
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Content - PdfViewerScreen takes full screen
        PdfViewerScreen(
            viewModel = viewModel,
            settingsViewModel = settingsViewModel,
            onSingleTap = { barsVisible = !barsVisible },
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = androidx.compose.ui.platform.LocalDensity.current.run { topPaddingPx.toDp() }),
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
                },
            )
        }
    }
}