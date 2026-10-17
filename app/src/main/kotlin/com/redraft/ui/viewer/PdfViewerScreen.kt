package com.redraft.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.data.DocumentHandle
import com.redraft.pdfcore.PdfPageSize
import com.redraft.ui.PdfViewerViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

private const val DEBOUNCE_MS = 250L

@Composable
fun PdfViewerScreen(
    viewModel: PdfViewerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val handle by viewModel.handle.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val cache = remember { PageBitmapCache() }

    // Recycle every cached bitmap when the document changes or the viewer leaves composition.
    DisposableEffect(handle) {
        onDispose { cache.recycleAll() }
    }

    // Restore scroll position from the ViewModel (survives rotation, D-07).
    LaunchedEffect(uiState.visiblePageIndex, uiState.scrollOffsetPx, uiState.pageCount) {
        if (uiState.pageCount > 0 && listState.firstVisibleItemIndex != uiState.visiblePageIndex) {
            listState.scrollToItem(uiState.visiblePageIndex, uiState.scrollOffsetPx)
        }
    }

    // Write scroll position back into the ViewModel.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> viewModel.onScroll(index, offset) }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
    ) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }.roundToInt()

        if (uiState.pageCount == 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (uiState.error != null) {
                    Text(
                        text = uiState.error?.message.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomInput(viewModel),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(
                    count = uiState.pageCount,
                    key = { it },
                    contentType = { "pdf-page" },
                ) { pageIndex ->
                    PdfPageItem(
                        pageIndex = pageIndex,
                        pageCount = uiState.pageCount,
                        zoom = uiState.zoom,
                        viewportWidthPx = viewportWidthPx,
                        handle = handle,
                        cache = cache,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

private fun Modifier.zoomInput(viewModel: PdfViewerViewModel): Modifier = this
    .pointerInput(Unit) {
        detectTransformGestures { _, _, zoomChange, _ ->
            viewModel.onZoomChanged(viewModel.uiState.value.zoom * zoomChange)
        }
    }
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = {
            val current = viewModel.uiState.value.zoom
            viewModel.onZoomChanged(if (current > 1.2f) 1f else 2f)
        })
    }

@Composable
private fun PdfPageItem(
    pageIndex: Int,
    pageCount: Int,
    zoom: Float,
    viewportWidthPx: Int,
    handle: DocumentHandle?,
    cache: PageBitmapCache,
    modifier: Modifier = Modifier,
) {
    val bucket = scaleBucketForZoom(zoom)
    val key = PageKey(pageIndex, bucket)
    val bucketZoom = Z_00_SCALE_BUCKETS[bucket]

    var bitmap by remember(pageIndex, bucket) { mutableStateOf(cache.acquire(key)) }
    var pageSize by remember(pageIndex) { mutableStateOf<PdfPageSize?>(null) }
    var renderTick by remember { mutableIntStateOf(0) }

    // Debounced re-render on zoom settle (D-09, T-03-03): never render per-frame.
    LaunchedEffect(zoom) {
        snapshotFlow { zoom }.debounce(DEBOUNCE_MS).collect { renderTick++ }
    }

    DisposableEffect(key) {
        onDispose { cache.release(key) }
    }

    LaunchedEffect(handle, bitmap, key, renderTick, viewportWidthPx) {
        val current = bitmap
        if (current != null || handle == null || viewportWidthPx <= 0) return@LaunchedEffect
        val size = pageSize ?: handle.pageSize(pageIndex).also { pageSize = it }
        val targetWidth = (viewportWidthPx * bucketZoom).roundToInt().coerceAtLeast(1)
        val targetHeight = (targetWidth * size.heightPt / size.widthPt)
            .roundToInt()
            .coerceAtLeast(1)
        val rendered = withContext(Dispatchers.IO) {
            handle.renderPage(pageIndex, targetWidth, targetHeight)
        }
        cache.put(key, rendered)
        bitmap = rendered
    }

    val aspect = pageSize?.let { it.heightPt / it.widthPt } ?: (792f / 612f)
    val widthDp = with(LocalDensity.current) {
        (viewportWidthPx * bucketZoom).roundToInt().toDp()
    }
    val heightDp = with(LocalDensity.current) {
        (viewportWidthPx * bucketZoom * aspect).roundToInt().toDp()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .width(widthDp)
            .height(heightDp),
    ) {
        val current = bitmap
        if (current == null) {
            // Placeholder is a flat surfaceVariant rectangle at final size — never blank (D-09).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1} of $pageCount",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // Live-scale the cached bitmap during a pinch, relative to its bucket.
                        val scale = zoom / bucketZoom
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount" },
            )
        }
    }
}
