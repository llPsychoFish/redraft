package com.redraft.ui.viewer

import kotlin.math.hypot

data class TouchPair(
    val x0: Float, val y0: Float,
    val x1: Float, val y1: Float
)

/**
 * One frame of a two-finger gesture.
 *
 * [focalX] / [focalY] are the midpoint of the CURRENT touch pair, in the
 * viewer's local pixel space. The pinch anchors the scroll on that point so
 * the content under the fingers does not move.
 */
data class GestureDelta(
    val zoomChange: Float,
    val panX: Float,
    val panY: Float,
    val focalX: Float,
    val focalY: Float,
)

fun gestureDelta(previous: TouchPair, current: TouchPair): GestureDelta {
    val prevSpan = hypot(previous.x1 - previous.x0, previous.y1 - previous.y0)
    val currentSpan = hypot(current.x1 - current.x0, current.y1 - current.y0)
    val zoomChange = if (prevSpan > 0f) currentSpan / prevSpan else 1f
    val panX = (current.x0 + current.x1) / 2f - (previous.x0 + previous.x1) / 2f
    val panY = (current.y0 + current.y1) / 2f - (previous.y0 + previous.y1) / 2f
    val focalX = (current.x0 + current.x1) / 2f
    val focalY = (current.y0 + current.y1) / 2f
    return GestureDelta(zoomChange, panX, panY, focalX, focalY)
}
