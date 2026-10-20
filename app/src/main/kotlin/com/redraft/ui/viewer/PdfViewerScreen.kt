package com.redraft.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import com.redraft.pdfcore.PdfViewportTransform
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
    //
    // Guarded by the gesture flag: while a pinch is running, the focal
    // anchoring owns the scroll position, and this effect would fight it by
    // scrolling back to the ViewModel's last known position.
    LaunchedEffect(
        uiState.visiblePageIndex,
        uiState.scrollOffsetPx,
        uiState.pageCount,
        uiState.gestureActive,
    ) {
        if (uiState.gestureActive) return@LaunchedEffect
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
                    .zoomInput(viewModel, listState, viewportWidthPx)
                    .singlePointerDoubleTap {
                        val current = viewModel.uiState.value.zoom
                        val target = if (current > 1.2f) 1f else 2f
                        viewModel.onZoomChanged(target)
                        // At fit-width there is no horizontal room: re-clamp the pan
                        // so no offset is left over from the zoomed state.
                        viewModel.onPanChanged(
                            clampPanX(
                                viewModel.uiState.value.panX,
                                viewportWidthPx.toFloat(),
                                target,
                            ),
                        )
                    },
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
                        panX = uiState.panX,
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

private fun Modifier.zoomInput(
    viewModel: PdfViewerViewModel,
    listState: LazyListState,
    viewportWidthPx: Int,
): Modifier = this
    .pointerInput(Unit) {
        // Custom multi-touch handler (gap G-01-5): detectTransformGestures never reached
        // multi-touch mode because the LazyColumn's own scroll detector consumed the
        // second pointer-down first. Here we track the two pressed pointers ourselves
        // and only consume once both are down, leaving single-finger drags to the list.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var previousPair: TouchPair? = null
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        // The gesture owns the scroll position from here on: the
                        // scroll-restore effect must not fight the focal anchoring.
                        viewModel.onGestureActiveChanged(true)
                        val currentPair = TouchPair(
                            pressed[0].position.x,
                            pressed[0].position.y,
                            pressed[1].position.x,
                            pressed[1].position.y,
                        )
                        previousPair?.let { previous ->
                            val delta = gestureDelta(previous, currentPair)
                            val before = viewModel.uiState.value.zoom
                            val after = nextZoom(before, delta.zoomChange)
                            val k = if (before > 0f) after / before else 1f

                            // Re-anchor the scroll on the focal point so the content
                            // under the fingers does not move. requestScrollToItem is
                            // applied in the NEXT layout pass, so it must be requested
                            // in the same frame as the zoom change - otherwise the
                            // content visibly slides for one frame and then snaps back.
                            //
                            // `focalY` is in the viewer's local pixel space and
                            // `item.offset` is the item's top in the viewport, also in
                            // local pixels: the LazyColumn fills the viewport, so the
                            // two spaces coincide.
                            val focalY = delta.focalY
                            val focalItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                                focalY >= item.offset && focalY < item.offset + item.size
                            }
                            if (focalItem != null) {
                                listState.requestScrollToItem(
                                    index = focalItem.index,
                                    scrollOffset = anchorScrollOffset(
                                        focalY = focalY,
                                        itemOffset = focalItem.offset,
                                        itemSize = focalItem.size,
                                        k = k,
                                    ),
                                )
                            }

                            viewModel.onZoomChanged(after)

                            // The horizontal midpoint drift, clamped to the content
                            // bounds so the page cannot walk off-centre. The vertical
                            // drift is dropped: the list scroll owns that axis (D-06).
                            val pan = viewModel.uiState.value.panX + delta.panX
                            viewModel.onPanChanged(
                                clampPanX(pan, viewportWidthPx.toFloat(), after),
                            )
                        }
                        previousPair = currentPair
                        event.changes.forEach { it.consume() }
                    } else {
                        // Single pointer: leave the event unconsumed so the LazyColumn keeps
                        // scrolling (paging) instead of zooming.
                        previousPair = null
                    }
                }
            } finally {
                // ALWAYS clear the flag, including when the gesture is cancelled
                // mid-pinch: a flag stuck at true would disable the scroll restore
                // for the rest of the session and lose the position on rotation.
                viewModel.onGestureActiveChanged(false)
            }
        }
    }

@Composable
private fun PdfPageItem(
    pageIndex: Int,
    pageCount: Int,
    zoom: Float,
    panX: Float,
    viewportWidthPx: Int,
    handle: DocumentHandle?,
    cache: PageBitmapCache,
    modifier: Modifier = Modifier,
) {
    // Where the LIVE zoom would render.
    val liveBucket = scaleBucketForZoom(zoom)

    // The page as it is actually rendered. The bucket lags the live zoom during
    // a gesture: a re-render only happens once the pinch settles, so crossing a
    // bucket boundary must NOT drop the bitmap (that blanked the page to the
    // grey placeholder mid-pinch).
    var rendered by remember(pageIndex, handle) {
        val cached = cache.acquire(PageKey(pageIndex, liveBucket))
        mutableStateOf(cached?.let { RenderedPage(it, liveBucket) })
    }
    var pageSize by remember(pageIndex) { mutableStateOf<PdfPageSize?>(null) }
    var renderTick by remember { mutableIntStateOf(0) }

    // The bucket the bitmap on screen was actually rendered at. This is NOT the
    // live bucket: during a gesture the live zoom has moved on while the bitmap
    // has not been re-rendered yet.
    val renderedBucket = rendered?.bucket ?: liveBucket

    // Debounced re-render on zoom settle (D-09, T-03-03): never render per-frame.
    LaunchedEffect(zoom) {
        snapshotFlow { zoom }.debounce(DEBOUNCE_MS).collect { renderTick++ }
    }

    // Hold a display claim on the bitmap this item is drawing.
    // Keyed on the bitmap, not the cache key: a re-render swaps the bitmap under
    // the same key, and the claim must follow the bitmap that was drawn. The
    // claim is what stops the cache from recycling a bitmap that is on screen
    // (threat T-03-02).
    val drawnBitmap = rendered?.bitmap
    DisposableEffect(drawnBitmap) {
        val drawn = drawnBitmap
        if (drawn != null) cache.retainDisplay(drawn)
        onDispose { if (drawn != null) cache.releaseDisplay(drawn) }
    }

    // Load the page's intrinsic size/rotation once.
    LaunchedEffect(handle, pageIndex) {
        val current = handle ?: return@LaunchedEffect
        if (pageSize == null) {
            pageSize = withContext(Dispatchers.IO) {
                current.pageSize(pageIndex)
            }
        }
    }

    val size = pageSize
    val transform = remember(size, viewportWidthPx) {
        size?.let {
            val baseHeightPx = (viewportWidthPx * it.heightPt / it.widthPt)
                .roundToInt()
                .coerceAtLeast(1)
            PdfViewportTransform(
                pageWidthPt = it.widthPt,
                pageHeightPt = it.heightPt,
                rotationDegrees = it.rotationDegrees,
                renderedWidthPx = viewportWidthPx,
                renderedHeightPx = baseHeightPx,
            )
        }
    }

    // The slot follows the LIVE zoom: the drawn page is scaled to exactly fill
    // it, so the slot and the page are always the same size. Sizing the slot
    // from the rendered bucket instead left the page drawn smaller (dark
    // margins) or larger (overlapping the next page) than the slot holding it.
    val display = remember(transform, zoom) {
        transform?.renderedSize(zoom)
    }

    // Re-render only when the pinch has settled (D-09): renderTick increments
    // 250 ms after the zoom stops moving. The bucket is read at that moment, so
    // a mid-gesture bucket crossing never triggers a render.
    //
    // `rendered` is deliberately NOT an effect key: setting it must not
    // re-trigger the effect (the guard below already handles the no-op case).
    LaunchedEffect(handle, pageIndex, renderTick, transform) {
        val current = handle ?: return@LaunchedEffect
        val target = scaleBucketForZoom(zoom)
        if (rendered?.bucket == target) return@LaunchedEffect
        val dimensions = transform?.renderedSize(renderedZoomForBucket(target))
            ?: return@LaunchedEffect
        if (dimensions.widthPx <= 0 || dimensions.heightPx <= 0) return@LaunchedEffect
        val renderedBitmap = withContext(Dispatchers.IO) {
            current.renderPage(pageIndex, dimensions.widthPx, dimensions.heightPx)
        }
        cache.put(PageKey(pageIndex, target), renderedBitmap)
        rendered = RenderedPage(renderedBitmap, target)
    }

    // Fallback (letter) aspect until the page size loads, so layout never jumps to zero.
    val fallbackAspect = 792f / 612f
    val widthDp = with(LocalDensity.current) {
        ((display?.widthPx ?: (viewportWidthPx * zoom).roundToInt())
            .coerceAtLeast(1)).toDp()
    }
    val heightDp = with(LocalDensity.current) {
        ((display?.heightPx ?: (viewportWidthPx * zoom * fallbackAspect).roundToInt())
            .coerceAtLeast(1)).toDp()
    }

    Box(
        // The page is anchored to the TOP of its slot: the scale origin is the
        // top-centre, so a top-aligned page grows downwards into the slot.
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .width(widthDp)
            .height(heightDp),
    ) {
        val current = drawnBitmap
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
                    // Laid out at the BITMAP's own pixel size, so the painter
                    // draws it 1:1 and ContentScale.FillBounds can never stretch
                    // the page. `requiredSize` ignores the slot's constraints:
                    // the bitmap can be larger than the slot while the live
                    // zoom is still below the bucket it was rendered at.
                    .requiredSize(
                        width = with(LocalDensity.current) { current.width.toDp() },
                        height = with(LocalDensity.current) { current.height.toDp() },
                    )
                    .graphicsLayer {
                        // The single uniform zoom factor: the live zoom over the
                        // bucket the bitmap was actually rendered at. One value
                        // for both axes - an unequal pair is an aspect-ratio
                        // stretch.
                        val scale = uniformDisplayScale(zoom, renderedBucket)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        // Two-finger drag pans the zoomed page (D-07: pan lives in
                        // the ViewModel so it survives rotation). The vertical
                        // axis belongs to the list scroll (D-06).
                        translationX = panX
                    }
                    .semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount" },
            )
        }
    }
}
