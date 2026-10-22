package com.redraft.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
import com.redraft.ui.MIN_ZOOM
import com.redraft.ui.PdfViewerViewModel
import com.redraft.ui.settings.SettingsViewModel

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

    // "Keep screen on while reading" is a real behaviour, not a stored preference: the window flag
    // is held only while a document is actually open, and released when the viewer leaves.
    val view = LocalView.current
    DisposableEffect(view, settings.keepScreenOn, uiState.pageCount) {
        view.keepScreenOn = settings.keepScreenOn && uiState.pageCount > 0
        onDispose { view.keepScreenOn = false }
    }

    // Tap toggles the chrome. The tap is deferred by the double-tap window inside
    // singlePointerDoubleTap, so a double tap still zooms instead of flashing the bars.
    var barsVisible by remember { mutableStateOf(true) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (barsVisible) {
                TopAppBar(
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
                                contentDescription = "Back",
                            )
                        }
                    },
                    actions = {
                        if (uiState.pageCount > 0) {
                            Text(
                                text = "${uiState.visiblePageIndex + 1} / ${uiState.pageCount}",
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
                                contentDescription = "Fit width",
                            )
                        }
                        IconButton(onClick = onOpenOverview) {
                            Icon(
                                painter = painterResource(R.drawable.ic_grid_view),
                                contentDescription = "Page overview",
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        PdfViewerScreen(
            viewModel = viewModel,
            settingsViewModel = settingsViewModel,
            onSingleTap = { barsVisible = !barsVisible },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
