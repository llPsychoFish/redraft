package com.redraft.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The displayed zoom is a UNIFORM transform of the bitmap that is actually on
 * screen (gap G-01-5).
 *
 * A page bitmap is rendered at a quantized bucket, while the live zoom moves
 * continuously. The scale that bridges the two must be a single number applied
 * to both axes: an unequal pair is exactly the aspect-ratio stretch that the
 * gap reported ("the page stretches instead of zooming").
 *
 * The scale is derived from the RENDERED bucket — the bucket the bitmap on
 * screen was actually rendered at — never from the live bucket.
 */
class RenderedPageScaleTest {

    @Test
    fun `scale is one when the live zoom matches the rendered bucket`() {
        val bucket = scaleBucketForZoom(1f)
        assertEquals(1f, uniformDisplayScale(1f, bucket), 0.0001f)
    }

    @Test
    fun `live zoom above the rendered bucket scales the page up`() {
        val bucket = scaleBucketForZoom(1f)
        assertEquals(2f, uniformDisplayScale(2f, bucket), 0.0001f)
    }

    @Test
    fun `live zoom below the rendered bucket scales the page down`() {
        val bucket = scaleBucketForZoom(2f)
        assertEquals(0.5f, uniformDisplayScale(1f, bucket), 0.0001f)
    }

    @Test
    fun `the scale comes from the rendered bucket, not the live one`() {
        val renderedBucket = scaleBucketForZoom(1f)
        val liveBucket = scaleBucketForZoom(2f)
        // Same live zoom, different rendered bucket -> different scale.
        assertEquals(2f, uniformDisplayScale(2f, renderedBucket), 0.0001f)
        assertEquals(1f, uniformDisplayScale(2f, liveBucket), 0.0001f)
    }

    @Test
    fun `rendered zoom is the bucket's own zoom factor`() {
        assertEquals(Z_00_SCALE_BUCKETS[2], renderedZoomForBucket(2), 0.0001f)
        assertEquals(Z_00_SCALE_BUCKETS[4], renderedZoomForBucket(4), 0.0001f)
    }

    @Test
    fun `the scale is the same number on both axes`() {
        // One value feeds scaleX and scaleY, so the page can never be
        // stretched into a different aspect ratio than the bitmap has.
        val scale = uniformDisplayScale(1.75f, scaleBucketForZoom(1f))
        assertEquals(scale, uniformDisplayScale(1.75f, scaleBucketForZoom(1f)), 0.0001f)
        assertEquals(1.75f, scale, 0.0001f)
    }
}
