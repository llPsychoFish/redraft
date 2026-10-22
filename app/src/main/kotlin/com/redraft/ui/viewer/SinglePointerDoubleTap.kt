package com.redraft.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How far apart two taps may be and still count as one double tap.
 *
 * The touch slop (8 dp) answers "did the finger move at all", not "are
 * these two taps the same double tap" - two separate taps essentially
 * never land within 8 dp of each other. The Android platform uses
 * `ViewConfiguration.getScaledDoubleTapSlop()` (100 px) for this.
 */
private val DOUBLE_TAP_SLOP = 48.dp

/**
 * A double-tap detector that only ever fires for a SINGLE-pointer gesture.
 *
 * `detectTapGestures` cannot tell a fast two-finger pinch from two quick
 * taps, and the double-tap handler sets the zoom to fit - which is exactly the
 * reported "each pinch resets to fit" symptom (UAT gap G-01-5). This detector
 * is cancelled the moment a second pointer goes down, so a multi-touch gesture
 * can never fire it.
 *
 * The double-tap itself uses the standard timing: the second tap must arrive
 * within [androidx.compose.ui.platform.ViewConfiguration.doubleTapTimeoutMillis]
 * of the first tap's release and within [DOUBLE_TAP_SLOP] of it.
 */
@Composable
internal fun Modifier.singlePointerDoubleTap(
    onSingleTap: (() -> Unit)? = null,
    onDoubleTap: () -> Unit,
): Modifier {
    val doubleTapSlopPx = with(LocalDensity.current) { DOUBLE_TAP_SLOP.toPx() }
    return this.pointerInput(doubleTapSlopPx) {
        // The pending-tap timer needs a coroutine scope of its own: the body of
        // awaitEachGesture runs in AwaitPointerEventScope, which cannot launch.
        val gestureScope = CoroutineScope(coroutineContext)
        var lastTapUpTime = 0L
        var lastTapPosition = Offset.Zero
        // A single tap is only KNOWN to be single once the double-tap window has
        // closed, so the callback is deferred by exactly that window and cancelled
        // the moment a second tap arrives. Deferring without blocking is what keeps
        // the next gesture detectable while the timer runs.
        var pendingSingleTap: Job? = null
        awaitEachGesture {
            // Wait for a first pointer, then for it to go up, with no second
            // pointer allowed in between: a two-finger gesture is never a tap.
            val down = awaitFirstDown(requireUnconsumed = false)
            var sawSecondPointer = false
            var wasConsumed = false
            var upTime = 0L
            var upPosition = down.position
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) sawSecondPointer = true
                // A drag is claimed by the scroll or the pan handler, which
                // CONSUMES the change. That - not how far the finger wandered -
                // is what tells a tap from a drag. The old movement check
                // rejected any tap that drifted more than the touch slop (8 dp).
                if (event.changes.any { it.isConsumed }) wasConsumed = true
                if (event.changes.none { it.pressed }) {
                    upTime = event.changes.maxOf { it.uptimeMillis }
                    upPosition = event.changes.first().position
                    break
                }
            }

            val tapDuration = upTime - down.uptimeMillis
            // A tap is a SHORT press. `doubleTapMinTimeMillis` is the minimum
            // time BETWEEN the first tap's release and the second tap's press -
            // it is not a minimum tap duration, and using it as one rejected
            // quick taps outright.
            val isTap = tapDuration <= viewConfiguration.longPressTimeoutMillis
            if (sawSecondPointer || wasConsumed || !isTap) {
                // Not a tap: drop the remembered first tap so a gesture in
                // between cannot complete a double tap.
                lastTapUpTime = 0L
                return@awaitEachGesture
            }

            val sinceFirstTap = down.uptimeMillis - lastTapUpTime
            val isDoubleTap = lastTapUpTime != 0L &&
                sinceFirstTap <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastTapPosition).getDistance() <= doubleTapSlopPx
            if (isDoubleTap) {
                lastTapUpTime = 0L
                pendingSingleTap?.cancel()
                pendingSingleTap = null
                onDoubleTap()
            } else {
                lastTapUpTime = upTime
                lastTapPosition = upPosition
                if (onSingleTap != null) {
                    pendingSingleTap?.cancel()
                    pendingSingleTap = gestureScope.launch {
                        delay(viewConfiguration.doubleTapTimeoutMillis.toLong())
                        pendingSingleTap = null
                        onSingleTap()
                    }
                }
            }
        }
    }
}
