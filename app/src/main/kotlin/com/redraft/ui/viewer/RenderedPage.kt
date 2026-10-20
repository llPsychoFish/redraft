package com.redraft.ui.viewer

import android.graphics.Bitmap

/**
 * A page as it is actually rendered: the bitmap on screen and the zoom
 * bucket that bitmap was rendered at.
 *
 * The bucket deliberately lags the live zoom during a gesture. A re-render
 * only happens once the pinch settles (D-09), so this pair is the single
 * source of truth for "what is on screen right now". The live zoom is
 * applied on top as a uniform transform, which is what keeps the page from
 * blanking or being stretched at a bucket boundary.
 */
data class RenderedPage(
    val bitmap: Bitmap,
    val bucket: Int,
)

/**
 * The zoom factor a bitmap of [bucket] was rendered at.
 *
 * Pure function (no Android types) so the display-scale invariant can be
 * unit-tested on the JVM.
 */
fun renderedZoomForBucket(bucket: Int): Float = Z_00_SCALE_BUCKETS[bucket]

/**
 * The uniform scale to apply on top of the rendered page so that it matches
 * [liveZoom].
 *
 * The scale is derived from the RENDERED bucket — the bucket the bitmap on
 * screen was actually rendered at — and not from the live bucket. It is a
 * single value: the same number feeds `scaleX` and `scaleY`, because an
 * unequal pair is an aspect-ratio stretch (gap G-01-5).
 */
fun uniformDisplayScale(liveZoom: Float, renderedBucket: Int): Float =
    liveZoom / renderedZoomForBucket(renderedBucket)
