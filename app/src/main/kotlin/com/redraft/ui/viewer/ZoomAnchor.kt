package com.redraft.ui.viewer

import com.redraft.ui.MAX_ZOOM
import com.redraft.ui.MIN_ZOOM
import kotlin.math.roundToInt

/**
 * The zoom-anchor and pan-clamp math of the pinch gesture.
 *
 * Pure functions only - no Android and no Compose types - so the invariants
 * that keep the content under the fingers (UAT gap G-01-5) can be locked by
 * JVM unit tests.
 */

/** The zoom after applying [factor], clamped to the supported range. */
fun nextZoom(current: Float, factor: Float): Float =
    (current * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)

/**
 * The pending-scroll offset that keeps the pinch focal point fixed.
 *
 * The zoom change multiplies every page height by [k], so the content under
 * the focal point would slide away from the fingers. Re-anchoring the list
 * on the focal PAGE undoes that slide in the same layout pass.
 *
 * [focalY] is the focal point's y in the viewport, [itemOffset] the focal
 * page's top in the viewport, [itemSize] its height, and [k] the zoom
 * factor (new / old).
 *
 * The focal point sits at fraction `f = (focalY - itemOffset) / itemSize`
 * of the page. After the zoom change the page is `k` times taller, and the
 * page's top must land at `focalY - f * itemSize * k` for the focal point
 * to stay put. `scrollToItem` places the page's top that many pixels ABOVE
 * the viewport's top, so the offset is the negated value.
 */
fun anchorScrollOffset(
    focalY: Float,
    itemOffset: Int,
    itemSize: Int,
    k: Float,
): Int {
    if (itemSize <= 0) return 0
    val f = (focalY - itemOffset) / itemSize
    val newSize = itemSize * k
    return (f * newSize - focalY).roundToInt()
}

/**
 * The horizontal pan, clamped to the content bounds.
 *
 * A zoomed page is wider than the viewport, so it can be panned sideways -
 * but not past its own edges. The vertical axis is NOT panned: the list
 * scroll owns it (D-06).
 */
fun clampPanX(panX: Float, viewportWidthPx: Float, zoom: Float): Float =
    panX.coerceIn(viewportWidthPx * (1f - zoom), 0f)
