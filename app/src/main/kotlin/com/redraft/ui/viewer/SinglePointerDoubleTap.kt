package com.redraft.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput

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
 * of the first tap's release and within the touch-slop distance of it.
 */
internal fun Modifier.singlePointerDoubleTap(onDoubleTap: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        var lastTapUpTime = 0L
        var lastTapPosition = Offset.Zero
        awaitEachGesture {
            // Wait for a first pointer, then for it to go up, with no second
            // pointer allowed in between: a two-finger gesture is never a tap.
            val down = awaitFirstDown(requireUnconsumed = false)
            var sawSecondPointer = false
            var maxMovement = 0f
            var upTime = 0L
            var upPosition = down.position
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) sawSecondPointer = true
                event.changes.forEach { change ->
                    if (change.pressed) {
                        maxMovement = maxOf(
                            maxMovement,
                            (change.position - down.position).getDistance(),
                        )
                    }
                }
                if (event.changes.none { it.pressed }) {
                    upTime = event.changes.maxOf { it.uptimeMillis }
                    upPosition = event.changes.first().position
                    break
                }
            }

            val tapDuration = upTime - down.uptimeMillis
            val isTap = tapDuration >= viewConfiguration.doubleTapMinTimeMillis
            if (sawSecondPointer || !isTap || maxMovement > viewConfiguration.touchSlop) {
                // Not a tap: drop the remembered first tap so a gesture in
                // between cannot complete a double tap.
                lastTapUpTime = 0L
                return@awaitEachGesture
            }

            val sinceFirstTap = down.uptimeMillis - lastTapUpTime
            val isDoubleTap = lastTapUpTime != 0L &&
                sinceFirstTap <= viewConfiguration.doubleTapTimeoutMillis &&
                (down.position - lastTapPosition).getDistance() <= viewConfiguration.touchSlop
            if (isDoubleTap) {
                lastTapUpTime = 0L
                onDoubleTap()
            } else {
                lastTapUpTime = upTime
                lastTapPosition = upPosition
            }
        }
    }
