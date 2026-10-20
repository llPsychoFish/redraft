package com.redraft.ui.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the reference accounting that keeps a displayed bitmap
 * from being recycled (threat T-03-02).
 *
 * The bug these cover was found on device, not by unit tests: the cache
 * recycled a page bitmap that the viewer was still drawing, which crashed the
 * draw pass with `Canvas: trying to use a recycled bitmap`.
 */
class BitmapRefTrackerTest {

    @Test
    fun `value nobody references is unreferenced`() {
        val tracker = BitmapRefTracker<String>()
        assertTrue(tracker.isUnreferenced("a"))
    }

    @Test
    fun `cached value is still referenced`() {
        val tracker = BitmapRefTracker<String>()
        tracker.retainForCache("a")
        assertFalse(tracker.isUnreferenced("a"))
    }

    @Test
    fun `displayed value is still referenced even when the cache drops it`() {
        val tracker = BitmapRefTracker<String>()
        tracker.retainForCache("a")
        tracker.retainForDisplay("a")
        tracker.releaseFromCache("a")
        assertFalse(tracker.isUnreferenced("a"))
    }

    @Test
    fun `value becomes unreferenced once cache and drawer both let go`() {
        val tracker = BitmapRefTracker<String>()
        tracker.retainForCache("a")
        tracker.retainForDisplay("a")
        tracker.releaseFromCache("a")
        tracker.releaseFromDisplay("a")
        assertTrue(tracker.isUnreferenced("a"))
    }

    @Test
    fun `a bitmap drawn by two drawers is referenced until both release it`() {
        val tracker = BitmapRefTracker<String>()
        tracker.retainForCache("a")
        tracker.retainForDisplay("a")
        tracker.retainForDisplay("a")
        tracker.releaseFromDisplay("a")
        assertFalse(tracker.isUnreferenced("a"))
        // The cache drops it too, but one drawer is still painting it.
        tracker.releaseFromCache("a")
        assertFalse(tracker.isUnreferenced("a"))
        tracker.releaseFromDisplay("a")
        assertTrue(tracker.isUnreferenced("a"))
    }

    @Test
    fun `the same bitmap cached twice needs two cache releases`() {
        val tracker = BitmapRefTracker<String>()
        tracker.retainForCache("a")
        tracker.retainForCache("a")
        tracker.releaseFromCache("a")
        assertFalse(tracker.isUnreferenced("a"))
        tracker.releaseFromCache("a")
        assertTrue(tracker.isUnreferenced("a"))
    }

    @Test
    fun `releasing more than was retained does not go negative`() {
        val tracker = BitmapRefTracker<String>()
        tracker.retainForCache("a")
        tracker.releaseFromCache("a")
        tracker.releaseFromCache("a")
        tracker.releaseFromDisplay("a")
        assertTrue(tracker.isUnreferenced("a"))
    }

    @Test
    fun `forget drops the bookkeeping`() {
        val tracker = BitmapRefTracker<String>()
        tracker.retainForCache("a")
        tracker.forget("a")
        assertTrue(tracker.isUnreferenced("a"))
    }
}
