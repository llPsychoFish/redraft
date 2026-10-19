package com.redraft.ui.viewer

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert
import org.junit.Test

class ZoomGestureMathTest {

    private val TOLERANCE = 1e-4f

    // Test 1: spread — previous (0,0)-(100,0), current (0,0)-(200,0)
    // prevSpan = 100, currentSpan = 200 -> zoomChange = 2f
    @Test
    fun `spread zoom in two fingers`() {
        val previous = TouchPair(0f, 0f, 100f, 0f)
        val current = TouchPair(0f, 0f, 200f, 0f)
        val delta = gestureDelta(previous, current)
        Assert.assertEquals(2f, delta.zoomChange, TOLERANCE)
    }

    // Test 2: pinch — previous (0,0)-(200,0), current (0,0)-(100,0)
    // prevSpan = 200, currentSpan = 100 -> zoomChange = 0.5f
    @Test
    fun `pinch zoom out two fingers`() {
        val previous = TouchPair(0f, 0f, 200f, 0f)
        val current = TouchPair(0f, 0f, 100f, 0f)
        val delta = gestureDelta(previous, current)
        Assert.assertEquals(0.5f, delta.zoomChange, TOLERANCE)
    }

    // Test 3: no distance change — previous (0,0)-(100,0), current (50,50)-(150,50)
    // prevSpan = 100, currentSpan = 100 -> zoomChange = 1f
    @Test
    fun `translate without zoom`() {
        val previous = TouchPair(0f, 0f, 100f, 0f)
        val current = TouchPair(50f, 50f, 150f, 50f)
        val delta = gestureDelta(previous, current)
        Assert.assertEquals(1f, delta.zoomChange, TOLERANCE)
    }

    // Test 4: degenerate zero span — previous (10,10)-(10,10), current (0,0)-(100,0)
    // prevSpan = 0, currentSpan = 100 -> zoomChange = 1f (guard)
    @Test
    fun `zero previous span guard`() {
        val previous = TouchPair(10f, 10f, 10f, 10f)
        val current = TouchPair(0f, 0f, 100f, 0f)
        val delta = gestureDelta(previous, current)
        Assert.assertEquals(1f, delta.zoomChange, TOLERANCE)
    }

    // Test 5: diagonal — previous (0,0)-(30,40) span 50, current (0,0)-(60,80) span 100
    // prevSpan = hypot(30, 40) = 50, currentSpan = hypot(60, 80) = 100 -> zoomChange = 2f
    @Test
    fun `diagonal spread`() {
        val previous = TouchPair(0f, 0f, 30f, 40f)
        val current = TouchPair(0f, 0f, 60f, 80f)
        val delta = gestureDelta(previous, current)
        Assert.assertEquals(2f, delta.zoomChange, TOLERANCE)
    }
}