package com.redraft.ui.overview

import android.graphics.Bitmap
import com.redraft.ui.viewer.PageBitmapCache
import com.redraft.ui.viewer.PageKey
import kotlin.math.roundToInt

/**
 * The bucket index the overview stores its thumbnails under.
 *
 * [PageBitmapCache] is keyed by (pageIndex, scaleBucket); the zoom ladder
 * only ever uses buckets 0..7, so 8 is free for the thumbnails. The
 * thumbnails are NOT at a zoom level - they are their own thing - which is
 * why they must not share the ladder.
 */
const val THUMBNAIL_BUCKET = 8

/**
 * The overview's thumbnail cache.
 *
 * A separate instance from the viewer's page cache, with its own budget:
 * the thumbnails must never evict the full-resolution page bitmaps, and
 * the full-resolution bitmaps must never be evicted by a grid of
 * thumbnails.
 *
 * The reference-counted recycling is [PageBitmapCache]'s - it is NOT
 * reimplemented here. A drawn bitmap has two owners (the cache slot and the
 * drawer painting it) and is recycled only when both let go; reimplementing
 * that would risk reintroducing the "trying to use a recycled bitmap" crash.
 */
class ThumbnailCache(maxBytes: Long = 12L * 1024 * 1024) {

    private val cache = PageBitmapCache(maxBytes)

    fun acquire(pageIndex: Int): Bitmap? = cache.acquire(PageKey(pageIndex, THUMBNAIL_BUCKET))

    fun put(pageIndex: Int, bitmap: Bitmap) =
        cache.put(PageKey(pageIndex, THUMBNAIL_BUCKET), bitmap)

    fun retainDisplay(bitmap: Bitmap) = cache.retainDisplay(bitmap)

    fun releaseDisplay(bitmap: Bitmap) = cache.releaseDisplay(bitmap)

    fun recycleAll() = cache.recycleAll()
}

/**
 * The pixel size a page thumbnail is rendered at.
 *
 * The width is the grid cell's inner width; the height follows the page's
 * aspect ratio, with the width and height swapped for a quarter-turn
 * (90/270) page. Returns null when the page size is unknown.
 */
fun thumbnailSize(
    pageWidthPt: Float,
    pageHeightPt: Float,
    rotationDegrees: Int,
    targetWidthPx: Int,
): Pair<Int, Int>? {
    if (pageWidthPt <= 0f || pageHeightPt <= 0f || targetWidthPx <= 0) return null
    val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
    val displayWidthPt = if (quarterTurn) pageHeightPt else pageWidthPt
    val displayHeightPt = if (quarterTurn) pageWidthPt else pageHeightPt
    val targetHeightPx = (targetWidthPx * displayHeightPt / displayWidthPt).roundToInt()
        .coerceAtLeast(1)
    return targetWidthPx to targetHeightPx
}
