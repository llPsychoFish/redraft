package com.redraft.ui.viewer

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.abs

data class PageKey(val pageIndex: Int, val scaleBucket: Int)

/**
 * Quantized zoom ladder. Every render is bucketed to one of these zoom levels
 * so small zoom changes reuse cached bitmaps instead of thrashing the cache
 * (decision D-10). Values are the bucket zoom factors; the index is the
 * `scaleBucket` in [PageKey].
 */
val Z_00_SCALE_BUCKETS = listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 5f)

fun scaleBucketForZoom(zoom: Float): Int {
    var bestIndex = 0
    var bestDelta = Float.MAX_VALUE
    Z_00_SCALE_BUCKETS.forEachIndexed { index, bucket ->
        val delta = abs(bucket - zoom)
        if (delta < bestDelta) {
            bestDelta = delta
            bestIndex = index
        }
    }
    return bestIndex
}

/**
 * Byte-sized LRU of rendered page bitmaps (decision D-10, threat T-03-01/T-03-02).
 *
 * Keyed by `(pageIndex, scaleBucket)` and bounded by total bitmap bytes rather
 * than entry count. A bitmap handed out by [acquire] is marked in-use; if it is
 * evicted before [release], recycling is deferred until its use count reaches
 * zero so a bitmap that is currently being drawn is never recycled (T-03-02).
 */
class PageBitmapCache(
    maxBytes: Long = Runtime.getRuntime().maxMemory() / 8,
) {

    private val useCounts = HashMap<PageKey, Int>()
    private val pendingRecycle = HashMap<PageKey, Bitmap>()
    private var recycled = 0

    private val lru = object : LruCache<PageKey, Bitmap>(
        maxBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
    ) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.allocationByteCount

        override fun entryRemoved(
            evicted: Boolean,
            key: PageKey,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            if ((useCounts[key] ?: 0) > 0) {
                pendingRecycle[key] = oldValue
            } else {
                oldValue.recycleIfNeeded()
            }
        }
    }

    val recycledCount: Int
        get() = synchronized(lru) { recycled }

    fun acquire(key: PageKey): Bitmap? {
        synchronized(lru) {
            val bitmap = lru.get(key)
            if (bitmap != null) {
                useCounts[key] = (useCounts[key] ?: 0) + 1
                return bitmap
            }
        }
        return null
    }

    fun put(key: PageKey, bitmap: Bitmap) {
        synchronized(lru) {
            lru.put(key, bitmap)
        }
    }

    fun release(key: PageKey) {
        synchronized(lru) {
            val count = useCounts[key] ?: return@synchronized
            if (count <= 1) {
                useCounts.remove(key)
                pendingRecycle.remove(key)?.recycleIfNeeded()
            } else {
                useCounts[key] = count - 1
            }
        }
    }

    fun recycleAll() {
        synchronized(lru) {
            lru.evictAll()
            pendingRecycle.values.forEach { it.recycleIfNeeded() }
            pendingRecycle.clear()
            useCounts.clear()
        }
    }

    private fun Bitmap.recycleIfNeeded() {
        if (!isRecycled) {
            recycle()
            recycled++
        }
    }
}
