package com.redraft.ui.viewer

import java.util.WeakHashMap

/**
 * Reference counting for cached bitmaps: decides when a bitmap is safe to
 * recycle.
 *
 * A cached bitmap has two independent owners:
 *
 *  - the **cache**, which holds it in an LRU slot, and
 *  - a **drawer**, which is displaying it.
 *
 * It may only be recycled once neither owner holds it any more. Recycling a
 * bitmap that is still on screen is a use-after-free (CWE-416) that surfaces
 * as `Canvas: trying to use a recycled bitmap` (threat T-03-02).
 *
 * Deliberately free of `android.*` imports: the accounting is the part that
 * caused the use-after-free in the field, so it has to be unit-testable on the
 * JVM without an Android runtime. Keys are held weakly, and `Bitmap` uses
 * identity equality, so a dropped bitmap is never kept alive by this tracker.
 */
class BitmapRefTracker<V : Any> {

    private class State {
        var cacheRefs = 0
        var displayRefs = 0
    }

    private val states = WeakHashMap<V, State>()

    /** The cache now holds [value] in one of its slots. */
    @Synchronized
    fun retainForCache(value: V) {
        state(value).cacheRefs++
    }

    /** The cache dropped one of its references to [value]. */
    @Synchronized
    fun releaseFromCache(value: V) {
        val state = states[value] ?: return
        if (state.cacheRefs > 0) state.cacheRefs--
    }

    /** A drawer started displaying [value]. */
    @Synchronized
    fun retainForDisplay(value: V) {
        state(value).displayRefs++
    }

    /** A drawer stopped displaying [value]. */
    @Synchronized
    fun releaseFromDisplay(value: V) {
        val state = states[value] ?: return
        if (state.displayRefs > 0) state.displayRefs--
    }

    /**
     * True when nothing holds [value] any more, i.e. it is safe to recycle.
     * A value that was never referenced is trivially safe.
     */
    @Synchronized
    fun isUnreferenced(value: V): Boolean {
        val state = states[value] ?: return true
        return state.cacheRefs <= 0 && state.displayRefs <= 0
    }

    /** Drops the bookkeeping for [value]; call once it has been recycled. */
    @Synchronized
    fun forget(value: V) {
        states.remove(value)
    }

    private fun state(value: V): State = states.getOrPut(value) { State() }
}
