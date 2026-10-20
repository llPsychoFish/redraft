package com.redraft.ui.viewer

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Choreographer
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
 * Byte-sized LRU of rendered page bitmaps (decision D-10, threats T-03-01/T-03-02).
 *
 * Keyed by `(pageIndex, scaleBucket)` and bounded by total bitmap bytes rather
 * than entry count.
 *
 * A bitmap has two independent owners, and it is recycled only once *both* have
 * let go:
 *
 *  - the **cache** — one claim per LRU slot the bitmap sits in;
 *  - the **drawer** — one claim per viewer item currently painting it.
 *
 * Recycling a bitmap that is still on screen is a use-after-free (CWE-416)
 * that crashes the draw pass with `Canvas: trying to use a recycled bitmap`
 * (T-03-02). It is easy to hit here: a single high-zoom page bitmap can be
 * larger than the whole byte budget, so the LRU evicts it the instant it is
 * inserted — while it is the bitmap on screen.
 *
 * The two claims are counted separately so a re-render that swaps the bitmap
 * under a key cannot release the claim held by the drawer that is still
 * painting the previous bitmap.
 */
class PageBitmapCache(
    maxBytes: Long = Runtime.getRuntime().maxMemory() / 8,
) {

    private val refs = BitmapRefTracker<Bitmap>()

    /** Bitmaps nothing references any more, waiting to be freed safely. */
    private val pendingRecycle = LinkedHashSet<Bitmap>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recycled = 0
    private var recycleScheduled = false
    private var framesToWait = 0

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
            // oldValue left the LRU. When the very same instance is re-inserted
            // as newValue its cache claim carries over instead of being dropped
            // and re-taken.
            if (oldValue !== newValue) {
                refs.releaseFromCache(oldValue)
                recycleIfUnreferenced(oldValue)
            }
        }
    }

    val recycledCount: Int
        get() = synchronized(lru) { recycled }

    /**
     * Returns the cached bitmap for [key], or null when it has not been
     * rendered yet.
     *
     * No claim is taken here: the drawer takes its display claim in
     * [retainDisplay] for the bitmap it actually paints, which is the only
     * bitmap that must be kept alive.
     */
    fun acquire(key: PageKey): Bitmap? {
        synchronized(lru) {
            return lru.get(key)
        }
    }

    /**
     * Stores [bitmap] as the rendering for [key].
     *
     * The cache claim is taken before the bitmap enters the LRU, so a
     * high-zoom bitmap that exceeds the whole byte budget and is evicted
     * immediately still has a claim that the drawer can add to.
     */
    fun put(key: PageKey, bitmap: Bitmap) {
        synchronized(lru) {
            val existing = lru.get(key)
            // Re-storing the very same instance keeps the slot's claim; taking
            // another one would leak it.
            if (existing !== bitmap) {
                refs.retainForCache(bitmap)
            }
            lru.put(key, bitmap)
        }
    }

    /**
     * Takes a display claim: a drawer is painting [bitmap] right now.
     *
     * The claim is released by [releaseDisplay] when that drawer stops
     * painting it. The bitmap may be evicted from the LRU in the meantime; it
     * is recycled only once this claim is gone as well.
     */
    fun retainDisplay(bitmap: Bitmap) {
        synchronized(lru) {
            refs.retainForDisplay(bitmap)
        }
    }

    /** Releases a display claim taken by [retainDisplay]. */
    fun releaseDisplay(bitmap: Bitmap) {
        synchronized(lru) {
            refs.releaseFromDisplay(bitmap)
            recycleIfUnreferenced(bitmap)
        }
    }

    /** Drops every cached bitmap; used when the document is closed. */
    fun recycleAll() {
        synchronized(lru) {
            lru.evictAll()
        }
    }

    /**
     * A bitmap nothing references any more is queued for recycling.
     *
     * The free is deferred by a couple of frames: the frame that last drew the
     * bitmap may still be in flight on the render thread, and freeing the pixel
     * data underneath it is a use-after-free even when the app itself no longer
     * references the bitmap.
     */
    private fun recycleIfUnreferenced(bitmap: Bitmap) {
        if (!refs.isUnreferenced(bitmap)) return
        pendingRecycle.add(bitmap)
        if (recycleScheduled) return
        recycleScheduled = true
        framesToWait = RECYCLE_DELAY_FRAMES
        scheduleRecycleFlush()
    }

    private fun scheduleRecycleFlush() {
        mainHandler.post {
            Choreographer.getInstance().postFrameCallback {
                if (framesToWait > 0) {
                    framesToWait--
                    scheduleRecycleFlush()
                    return@postFrameCallback
                }
                recycleScheduled = false
                flushPendingRecycle()
            }
        }
    }

    private fun flushPendingRecycle() {
        synchronized(lru) {
            val iterator = pendingRecycle.iterator()
            while (iterator.hasNext()) {
                val bitmap = iterator.next()
                // Re-check: the bitmap may have been re-referenced in between.
                // Drop it from the queue either way — if it is referenced again
                // it gets queued anew when that claim is released.
                if (!refs.isUnreferenced(bitmap)) {
                    iterator.remove()
                    continue
                }
                iterator.remove()
                refs.forget(bitmap)
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    recycled++
                }
            }
        }
    }

    private companion object {
        /**
         * Frames to wait before freeing a bitmap. The frame that last drew it
         * may still be queued on the render thread.
         */
        const val RECYCLE_DELAY_FRAMES = 2
    }
}
