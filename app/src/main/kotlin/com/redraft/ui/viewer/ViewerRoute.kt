package com.redraft.ui.viewer

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
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
                    IconButton(onClick = onOpenOverview) {
                        Icon(
                            painter = painterResource(R.drawable.ic_grid_view),
                            contentDescription = "Page overview",
                        )
                    }
                },
            )
        },
    ) { padding ->
        PdfViewerScreen(
            viewModel = viewModel,
            settingsViewModel = settingsViewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}