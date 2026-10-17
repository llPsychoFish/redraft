package com.redraft.tracer

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redraft.pdfcore.PdfAssetLoader
import com.redraft.pdfcore.PdfDocumentHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface TracerState {
    data object Loading : TracerState
    data class Success(val bitmap: Bitmap) : TracerState
    data class Error(val message: String) : TracerState
}

class PdfTracerViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<TracerState>(TracerState.Loading)
    val state: StateFlow<TracerState> = _state.asStateFlow()

    private var document: PdfDocumentHandle? = null
    private var renderedForWidthPx = 0
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun renderPageOne(widthPx: Int) {
        if (widthPx <= 0 || renderedForWidthPx == widthPx) return
        renderedForWidthPx = widthPx
        viewModelScope.launch {
            _state.value = TracerState.Loading
            try {
                val handle = document ?: openDocument().also { document = it }
                val size = handle.pageSize(0)
                val targetHeight = (widthPx * size.heightPt / size.widthPt)
                    .roundToInt()
                    .coerceAtLeast(1)
                val bitmap = handle.renderPage(0, widthPx, targetHeight)
                _state.value = TracerState.Success(bitmap)
            } catch (t: Throwable) {
                _state.value = TracerState.Error(t.message ?: "Render failed")
            }
        }
    }

    private suspend fun openDocument(): PdfDocumentHandle {
        val context: Context = getApplication()
        return PdfAssetLoader.openAsset(context, SAMPLE_ASSET)
    }

    override fun onCleared() {
        val handle = document ?: return
        cleanupScope.launch(NonCancellable) {
            handle.close()
        }
    }

    private companion object {
        const val SAMPLE_ASSET = "sample.pdf"
    }
}

@Composable
fun TracerRenderScreen(
    viewModel: PdfTracerViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val widthPx = with(density) { maxWidth.toPx() }.roundToInt()

        LaunchedEffect(widthPx) {
            viewModel.renderPageOne(widthPx)
        }

        when (val current = state) {
            is TracerState.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
            is TracerState.Success -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "Page 1 of 1",
                modifier = Modifier.fillMaxSize(),
            )
            is TracerState.Error -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            ) {
                Text(text = current.message)
            }
        }
    }
}

@Composable
private fun TracerLoading(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier = modifier)
}

@Preview(showBackground = true)
@Composable
private fun TracerLoadingPreview() {
    TracerLoading(Modifier)
}
