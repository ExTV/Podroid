package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchpadScrollDirectionTest {
    @Test fun `default keeps the tested natural direction`() {
        val direction = X11Settings().touchpadScrollDirection
        assertEquals(TouchpadScrollDirection.NATURAL, direction)
        assertFalse(direction.wheelUp(-60f))
        assertTrue(direction.wheelUp(60f))
    }

    @Test fun `traditional reverses both swipe directions`() {
        assertTrue(TouchpadScrollDirection.TRADITIONAL.wheelUp(-60f))
        assertFalse(TouchpadScrollDirection.TRADITIONAL.wheelUp(60f))
    }
}
