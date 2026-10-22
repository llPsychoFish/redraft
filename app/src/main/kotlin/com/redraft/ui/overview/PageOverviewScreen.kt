package com.redraft.ui.overview

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.redraft.R
import com.redraft.data.DocumentHandle
import com.redraft.pdfcore.PdfPageSize
import com.redraft.ui.PdfViewerViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val GRID_COLUMNS = 3
private const val GRID_PADDING_DP = 8
private const val GRID_SPACING_DP = 4

/**
 * The page overview: a grid of page thumbnails, tap one to jump to that page.
 *
 * This is a THUMBNAIL grid, not a bookmark table of contents: it works for
 * every PDF, including the ones that have no outline at all. The jump goes
 * through the ViewModel's scroll command (plan 01-11), so it survives a
 * rotation like any other command.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOverviewRoute(
    viewModel: PdfViewerViewModel,
    onBack: () -> Unit,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val handle by viewModel.handle.collectAsStateWithLifecycle()

    // A selection POPS the navigation, so it must fire at most once per entry
    // into the overview: a double tap is two clicks, and the second pop would
    // close the document as well (the viewer would be on top by then).
    var selectionHandled by remember { mutableStateOf(false) }

    // Leaving the overview WITHOUT tapping a page must not emit a scroll
    // command. The viewer's own position is restored by its saveable list
    // state, and a command would additionally reset the reader to the TOP of
    // the page, throwing away the offset they had scrolled to within it.
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Pages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        PageOverviewGrid(
            handle = handle,
            pageCount = uiState.pageCount,
            // Thumbnails are owned by the ViewModel so leaving the grid does not discard them,
            // and a full-resolution page bitmap can never be evicted by a thumbnail (A-1).
            cache = viewModel.thumbnailCache,
            onPageSelected = { pageIndex ->
                if (selectionHandled) return@PageOverviewGrid
                selectionHandled = true
                onPageSelected(pageIndex)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun PageOverviewGrid(
    handle: DocumentHandle?,
    pageCount: Int,
    onPageSelected: (Int) -> Unit,
    cache: ThumbnailCache,
    modifier: Modifier = Modifier,
) {

    // The page sizes are fetched ONCE, off the main thread. They are needed
    // for each page's aspect ratio, and the aspect ratio is what gives a
    // thumbnail its height.
    var pageSizes by remember(handle) { mutableStateOf<List<PdfPageSize?>>(emptyList()) }
    LaunchedEffect(handle) {
        val current = handle ?: return@LaunchedEffect
        val count = current.pageCount
        val sizes = withContext(Dispatchers.IO) {
            (0 until count).map { index -> current.safePageSize(index) }
        }
        pageSizes = sizes
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val gutter = (GRID_PADDING_DP * 2 + GRID_SPACING_DP * (GRID_COLUMNS - 1)).dp
        val cellWidthPx = with(density) {
            ((maxWidth - gutter) / GRID_COLUMNS).toPx()
        }.roundToInt().coerceAtLeast(1)

        // LazyVerticalGrid composes ONLY the visible cells: a 120-page
        // document renders the thumbnails on screen, not 120 of them.
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            contentPadding = PaddingValues(GRID_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(GRID_SPACING_DP.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = pageCount,
                key = { it },
                contentType = { "pdf-thumbnail" },
            ) { pageIndex ->
                ThumbnailCell(
                    pageIndex = pageIndex,
                    handle = handle,
                    pageSize = pageSizes.getOrNull(pageIndex),
                    cellWidthPx = cellWidthPx,
                    cache = cache,
                    onPageSelected = onPageSelected,
                )
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    pageIndex: Int,
    handle: DocumentHandle?,
    pageSize: PdfPageSize?,
    cellWidthPx: Int,
    cache: ThumbnailCache,
    onPageSelected: (Int) -> Unit,
) {
    // The thumbnail's pixel size: the cell's width, and a height that follows
    // the page's aspect ratio (swapped for a quarter-turn page).
    val dimensions: Pair<Int, Int>? = pageSize?.let {
        thumbnailSize(it.widthPt, it.heightPt, it.rotationDegrees, cellWidthPx)
    }
    // A letter-page fallback so the layout never jumps to zero while the
    // page sizes are still loading.
    val fallbackHeightPx = (cellWidthPx * 792f / 612f).roundToInt().coerceAtLeast(1)
    val heightPx = dimensions?.second ?: fallbackHeightPx

    // The cached thumbnail for this page, if it was rendered before.
    var thumbnail by remember(pageIndex) { mutableStateOf(cache.acquire(pageIndex)) }

    // Hold a display claim on the bitmap this cell is drawing. The claim
    // follows the BITMAP, not the cache key, and it is what stops the cache
    // from recycling a bitmap that is on screen (the same pattern as
    // PdfPageItem).
    val drawnBitmap = thumbnail
    DisposableEffect(drawnBitmap) {
        val drawn = drawnBitmap
        if (drawn != null) cache.retainDisplay(drawn)
        onDispose { if (drawn != null) cache.releaseDisplay(drawn) }
    }

    // Render the thumbnail off the main thread (PDFium on the main thread is
    // a StrictMode violation).
    LaunchedEffect(pageIndex, dimensions, handle) {
        if (thumbnail != null) return@LaunchedEffect
        val current = handle ?: return@LaunchedEffect
        val target = dimensions ?: return@LaunchedEffect
        val rendered = withContext(Dispatchers.IO) {
            current.renderPage(pageIndex, target.first, target.second)
        }
        cache.put(pageIndex, rendered)
        thumbnail = rendered
    }

    Column(
        modifier = Modifier
            .clickable { onPageSelected(pageIndex) }
            .semantics { contentDescription = "Page ${pageIndex + 1}" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(
                width = cellWidthPx.dp,
                height = heightPx.dp,
            ),
        ) {
            val current = drawnBitmap
            if (current == null) {
                // The placeholder is a flat surfaceVariant rectangle at the
                // thumbnail's size - never blank.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = "${pageIndex + 1}",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * The page's size, or null when it cannot be read.
 *
 * A per-page failure (the document was closed while the sizes were being
 * fetched) must not take the whole fetch down - the cell falls back to the
 * placeholder. Cancellation, on the other hand, must propagate: swallowing it
 * would keep the effect running after it was cancelled.
 */
private suspend fun DocumentHandle.safePageSize(index: Int): PdfPageSize? =
    try {
        pageSize(index)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        null
    }
