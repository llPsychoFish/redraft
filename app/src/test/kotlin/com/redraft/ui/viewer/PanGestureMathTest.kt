package com.redraft.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PanGestureMathTest {

    private val TOLERANCE = 1e-4f
    private val SLOP = 24f
    private val VIEWPORT = 1000f
    private val ZOOM = 3f

    @Test
    fun `steady 30 px per frame drag pans 1 to 1`() {
        var state = panSegmentStart(100f, 100f)
        var pan = 0f
        var x = 100f
        repeat(10) {
            x += 30f
            val frame = panFrame(state, pan, x, 100f, SLOP, VIEWPORT, ZOOM)
            state = frame.state
            pan = frame.panX
            assertTrue(frame.consume)
        }
        // 10 frames x 30 px = 300 px of finger travel, and the pan must
        // equal it: no `dx - lastPanDx` cancellation.
        assertEquals(300f, pan, TOLERANCE)
    }

    @Test
    fun `slow 10 px per frame drag engages after cumulative slop`() {
        var state = panSegmentStart(100f, 100f)
        var pan = 0f
        var x = 100f
        val consumed = mutableListOf<Boolean>()
        repeat(3) {
            x += 10f
            val frame = panFrame(state, pan, x, 100f, SLOP, VIEWPORT, ZOOM)
            state = frame.state
            pan = frame.panX
            consumed += frame.consume
        }
        // 10 and 20 px are inside slop; 30 px cumulative engages.
        assertEquals(listOf(false, false, true), consumed)
        assertTrue(state.horizontal)
        assertEquals(10f, pan, TOLERANCE)
    }

    @Test
    fun `a drag at the positive clamp boundary does not consume or over move`() {
        var state = panSegmentStart(0f, 0f).copy(horizontal = true)
        var pan = 990f
        // The frame that reaches the edge applies the clamped pan.
        var frame = panFrame(state, pan, 30f, 0f, SLOP, VIEWPORT, ZOOM)
        assertTrue(frame.consume)
        assertEquals(1000f, frame.panX, TOLERANCE)
        state = frame.state
        pan = frame.panX
        // Pushing further out is released and cannot move past the bound.
        frame = panFrame(state, pan, 60f, 0f, SLOP, VIEWPORT, ZOOM)
        assertFalse(frame.consume)
        assertEquals(1000f, frame.panX, TOLERANCE)
    }

    @Test
    fun `a drag at the negative clamp boundary does not consume or over move`() {
        var state = panSegmentStart(0f, 0f).copy(horizontal = true)
        var pan = -990f
        var frame = panFrame(state, pan, -30f, 0f, SLOP, VIEWPORT, ZOOM)
        assertTrue(frame.consume)
        assertEquals(-1000f, frame.panX, TOLERANCE)
        state = frame.state
        pan = frame.panX
        frame = panFrame(state, pan, -60f, 0f, SLOP, VIEWPORT, ZOOM)
        assertFalse(frame.consume)
        assertEquals(-1000f, frame.panX, TOLERANCE)
    }

    @Test
    fun `reversing at a boundary resumes immediately without a jump`() {
        var state = panSegmentStart(0f, 0f).copy(horizontal = true)
        // At the right edge, pushing out is released.
        var frame = panFrame(state, 1000f, 10f, 0f, SLOP, VIEWPORT, ZOOM)
        assertFalse(frame.consume)
        assertEquals(1000f, frame.panX, TOLERANCE)
        state = frame.state
        // Reversing by 10 px applies exactly that 10 px.
        frame = panFrame(state, 1000f, 0f, 0f, SLOP, VIEWPORT, ZOOM)
        assertTrue(frame.consume)
        assertEquals(990f, frame.panX, TOLERANCE)
    }

    @Test
    fun `a fresh segment after a pinch starts at the remaining finger`() {
        // This locks the segment math only. The Compose handler's pointer
        // selection after a pinch is covered by the device retest; a JVM
        // test cannot observe Compose pointer ids.
        var state = panSegmentStart(500f, 400f)
        var pan = 0f
        // 5 px is inside slop: no pan and no jump from the pre-pinch x.
        var frame = panFrame(state, pan, 505f, 400f, SLOP, VIEWPORT, ZOOM)
        assertFalse(frame.consume)
        assertEquals(0f, frame.panX, TOLERANCE)
        state = frame.state
        // 40 px cumulative engages; only the 35 px increment since the
        // last sample is applied, not the whole distance since down.
        frame = panFrame(state, pan, 540f, 400f, SLOP, VIEWPORT, ZOOM)
        assertTrue(frame.consume)
        assertEquals(35f, frame.panX, TOLERANCE)
    }

    @Test
    fun `a vertical drag never engages the horizontal pan`() {
        val state = panSegmentStart(100f, 100f)
        val frame = panFrame(state, 0f, 130f, 400f, SLOP, VIEWPORT, ZOOM)
        assertFalse(frame.consume)
        assertFalse(frame.state.horizontal)
        assertEquals(0f, frame.panX, TOLERANCE)
    }
}
