package com.redraft.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The zoom-anchor and pan-clamp math of the pinch gesture.
 *
 * These are the invariants that keep the content under the fingers and keep
 * the page from walking off-centre (UAT gap G-01-5). They are pure functions
 * so they can be locked on the JVM.
 */
class ZoomAnchorTest {

    private val TOLERANCE = 1e-4f

    // nextZoom ----------------------------------------------------------------

    @Test
    fun `next zoom multiplies the current zoom`() {
        assertEquals(2f, nextZoom(1f, 2f), TOLERANCE)
        assertEquals(1.5f, nextZoom(3f, 0.5f), TOLERANCE)
    }

    @Test
    fun `next zoom clamps at the upper bound`() {
        assertEquals(5f, nextZoom(4f, 2f), TOLERANCE)
        assertEquals(5f, nextZoom(5f, 3f), TOLERANCE)
    }

    @Test
    fun `next zoom clamps at the lower bound`() {
        assertEquals(1f, nextZoom(2f, 0.1f), TOLERANCE)
        assertEquals(1f, nextZoom(1f, 0.5f), TOLERANCE)
    }

    // anchorScrollOffset ------------------------------------------------------

    @Test
    fun `anchor with no zoom change leaves the page where it is`() {
        // The item's top is 250 px above the viewport's top, the focal point is
        // 300 px down the viewport and 1000 px tall.
        val offset = anchorScrollOffset(
            focalY = 300f,
            itemOffset = 250,
            itemSize = 1000,
            k = 1f,
        )
        // The item's start is placed that many pixels above the viewport's top,
        // so -250 puts it back exactly where it was.
        assertEquals(-250, offset)
    }

    @Test
    fun `anchor with no zoom change and a focal at the item top is a no-op`() {
        val offset = anchorScrollOffset(
            focalY = 0f,
            itemOffset = 0,
            itemSize = 1000,
            k = 1f,
        )
        assertEquals(0, offset)
    }

    @Test
    fun `anchor keeps a focal at the top of the page under the fingers`() {
        // The page's top is 100 px down the viewport and the focal point is
        // exactly there: the fraction is 0, so the page's start must land on
        // the focal point.
        val offset = anchorScrollOffset(
            focalY = 100f,
            itemOffset = 100,
            itemSize = 1000,
            k = 2f,
        )
        assertEquals(-100, offset)
    }

    @Test
    fun `anchor keeps a focal in the middle of the page under the fingers`() {
        // The page's top is 100 px down the viewport, the page is 1000 px tall
        // and the focal point is at 600 px -> fraction 0.5. After the zoom the
        // page is 2000 px tall, so its start must be at 600 - 0.5 * 2000 = -400.
        val offset = anchorScrollOffset(
            focalY = 600f,
            itemOffset = 100,
            itemSize = 1000,
            k = 2f,
        )
        assertEquals(400, offset)
    }

    @Test
    fun `anchor works when the page starts above the viewport`() {
        // The page's top is 200 px ABOVE the viewport's top (negative offset)
        // and the focal point is 100 px down the viewport -> fraction 0.3.
        val offset = anchorScrollOffset(
            focalY = 100f,
            itemOffset = -200,
            itemSize = 1000,
            k = 2f,
        )
        assertEquals(500, offset)
    }

    @Test
    fun `anchor of a zero sized page is a no-op`() {
        val offset = anchorScrollOffset(
            focalY = 100f,
            itemOffset = 0,
            itemSize = 0,
            k = 2f,
        )
        assertEquals(0, offset)
    }

    // clampPanX ---------------------------------------------------------------

    @Test
    fun `pan is zero at zoom one`() {
        assertEquals(0f, clampPanX(120f, 1000f, 1f), TOLERANCE)
        assertEquals(0f, clampPanX(-120f, 1000f, 1f), TOLERANCE)
    }

    @Test
    fun `pan inside the bounds is unchanged`() {
        assertEquals(-500f, clampPanX(-500f, 1000f, 3f), TOLERANCE)
        assertEquals(500f, clampPanX(500f, 1000f, 3f), TOLERANCE)
    }

    @Test
    fun `pan beyond the bounds is clamped`() {
        // At zoom 3 the page overflows the viewport by 1000 px on EACH side,
        // so the pan can reach -1000 and +1000 but no further.
        assertEquals(-1000f, clampPanX(-3000f, 1000f, 3f), TOLERANCE)
        assertEquals(1000f, clampPanX(3000f, 1000f, 3f), TOLERANCE)
    }

    // panBounds ----------------------------------------------------------------
    //
    // The bounds the one-finger horizontal pan depends on (plan 01-08): the
    // page is drawn CENTRED in the viewport and is wider than the viewport as
    // soon as zoom > 1, so it overflows the viewport by
    // (viewportWidth * zoom - viewportWidth) / 2 on EACH side. The pan has to
    // reach both ends - and no further, or a gap opens on the opposite side.

    @Test
    fun `pan bounds are empty at zoom one`() {
        val bounds = panBounds(viewportWidthPx = 1000f, zoom = 1f)
        assertEquals(0f, bounds.start, TOLERANCE)
        assertEquals(0f, bounds.endInclusive, TOLERANCE)
        // The page fits the screen: there is nothing to reveal, so the pan
        // stays at 0 whatever the drag asks for.
        assertEquals(0f, clampPanX(120f, 1000f, 1f), TOLERANCE)
        assertEquals(0f, clampPanX(-120f, 1000f, 1f), TOLERANCE)
    }

    @Test
    fun `pan bounds at zoom three reach half an overflow on each side`() {
        // The page is 3000 px wide in a 1000 px viewport, so it overflows the
        // viewport by 1000 px on the left AND on the right.
        val bounds = panBounds(viewportWidthPx = 1000f, zoom = 3f)
        assertEquals(-1000f, bounds.start, TOLERANCE)
        assertEquals(1000f, bounds.endInclusive, TOLERANCE)
        // The range is symmetric about zero: the page can be dragged equally
        // far in both directions.
        assertEquals(-bounds.endInclusive, bounds.start, TOLERANCE)
    }

    @Test
    fun `a pan below the lower bound is clamped to the lower bound`() {
        assertEquals(-1000f, clampPanX(-2500f, 1000f, 3f), TOLERANCE)
        assertEquals(-1000f, clampPanX(-99999f, 1000f, 3f), TOLERANCE)
    }

    @Test
    fun `a pan above the upper bound is clamped to the upper bound`() {
        // The page's right end is reachable: the page can be dragged to the
        // right until its right edge meets the viewport's right edge.
        assertEquals(1000f, clampPanX(1500f, 1000f, 3f), TOLERANCE)
        assertEquals(1000f, clampPanX(99999f, 1000f, 3f), TOLERANCE)
    }

    @Test
    fun `a positive pan at zoom one is clamped to zero`() {
        assertEquals(0f, clampPanX(500f, 1000f, 1f), TOLERANCE)
        assertEquals(0f, clampPanX(99999f, 1000f, 1f), TOLERANCE)
    }
}
