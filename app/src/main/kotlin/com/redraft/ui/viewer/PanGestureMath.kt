package com.redraft.ui.viewer

import kotlin.math.abs

/**
 * State of one single-pointer pan segment.
 *
 * [originX]/[originY] are where this segment started: the initial
 * pointer-down, or the remaining pointer's position when a pinch ends.
 * [lastX] is the previous sampled x, so the applied delta is the
 * per-frame increment. [horizontal] is the mode decided once per
 * segment and never flips back within the segment.
 */
data class PanState(
    val originX: Float,
    val originY: Float,
    val lastX: Float,
    val horizontal: Boolean = false,
)

/** One sampled pointer frame: the next state, the pan to apply, and whether to consume. */
data class PanFrame(
    val state: PanState,
    val panX: Float,
    val consume: Boolean,
)

/** Start a new single-pointer segment at [x], [y]. */
fun panSegmentStart(x: Float, y: Float): PanState =
    PanState(originX = x, originY = y, lastX = x)

/**
 * Sample one pointer frame.
 *
 * Entry is gated on CUMULATIVE displacement from the segment origin for
 * both axes, so a slow drag that never exceeds touch slop in a single
 * frame still engages. Once horizontal, the per-frame [x] increment is
 * applied directly (`panX + dx`), which is the 1:1 tracking the old
 * `dx - lastPanDx` cancelled.
 *
 * The pan is clamped with the existing [clampPanX]. The gesture is
 * consumed while the pan can move, INCLUDING the frame that reaches the
 * exact edge; it is released only when the page is already at the edge
 * and the finger pushes further out. This reaches the full extent and
 * preserves the edge release.
 */
fun panFrame(
    state: PanState,
    panX: Float,
    x: Float,
    y: Float,
    touchSlop: Float,
    viewportWidthPx: Float,
    zoom: Float,
): PanFrame {
    val dx = x - state.lastX
    val cumulativeDx = x - state.originX
    val cumulativeDy = y - state.originY

    if (!state.horizontal) {
        val engages = abs(cumulativeDx) > touchSlop &&
            abs(cumulativeDx) > abs(cumulativeDy)
        val nextState = state.copy(lastX = x, horizontal = engages)
        val nextPan = if (engages) {
            clampPanX(panX + dx, viewportWidthPx, zoom)
        } else {
            panX
        }
        return PanFrame(nextState, nextPan, consume = engages)
    }

    val requested = panX + dx
    val clamped = clampPanX(requested, viewportWidthPx, zoom)
    val atEdgePushingOut = clamped == panX && requested != panX
    return PanFrame(
        state = state.copy(lastX = x),
        panX = clamped,
        consume = !atEdgePushingOut,
    )
}
