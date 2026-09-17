/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DamageTrackerTest {

    @Test fun `drain on a fresh tracker returns empty`() {
        assertTrue(DamageTracker().drain().isEmpty())
    }

    @Test fun `add then drain returns exactly the added rects`() {
        val t = DamageTracker()
        t.add(listOf(VncRect(0, 0, 10, 10), VncRect(20, 20, 5, 5)))

        val drained = t.drain()

        assertEquals(listOf(VncRect(0, 0, 10, 10), VncRect(20, 20, 5, 5)), drained)
    }

    @Test fun `drain clears pending so a second drain is empty`() {
        val t = DamageTracker()
        t.add(listOf(VncRect(0, 0, 10, 10)))
        t.drain()

        assertTrue(t.drain().isEmpty())
    }

    @Test fun `add with an empty list is a no-op`() {
        val t = DamageTracker()
        t.add(emptyList())

        assertTrue(t.drain().isEmpty())
    }

    @Test fun `rects accumulate across multiple add calls before a drain`() {
        val t = DamageTracker()
        t.add(listOf(VncRect(0, 0, 1, 1)))
        t.add(listOf(VncRect(5, 5, 1, 1)))

        assertEquals(listOf(VncRect(0, 0, 1, 1), VncRect(5, 5, 1, 1)), t.drain())
    }

    @Test fun `exceeding the cap collapses pending rects to one bounding box`() {
        val t = DamageTracker()
        // 33 disjoint 1x1 rects on a diagonal exceeds the 32-entry cap, so they
        // collapse to a single rect spanning the full diagonal.
        repeat(33) { i -> t.add(listOf(VncRect(i, i, 1, 1))) }

        val drained = t.drain()

        assertEquals(1, drained.size)
        assertEquals(VncRect(0, 0, 33, 33), drained[0])
    }

    @Test fun `at exactly the cap rects are not collapsed`() {
        val t = DamageTracker()
        repeat(32) { i -> t.add(listOf(VncRect(i, i, 1, 1))) }

        assertEquals(32, t.drain().size)
    }

    @Test fun `invalidateAll replaces pending rects with one full-frame rect`() {
        val t = DamageTracker()
        t.add(listOf(VncRect(0, 0, 1, 1), VncRect(50, 50, 1, 1)))

        t.invalidateAll(1280, 720)

        assertEquals(listOf(VncRect(0, 0, 1280, 720)), t.drain())
    }

    @Test fun `invalidateAll on an empty tracker still yields a full-frame rect`() {
        val t = DamageTracker()

        t.invalidateAll(640, 480)

        assertEquals(listOf(VncRect(0, 0, 640, 480)), t.drain())
    }

    @Test fun `bounding box collapse covers rects on both axes`() {
        val t = DamageTracker()
        // 34 rects added in one call exceeds the 32-entry cap, forcing an
        // immediate collapse; the box must span both the x-run and the
        // vertically offset outlier.
        val rects = (0 until 33).map { i -> VncRect(i, 0, 1, 1) } + VncRect(0, 50, 1, 1)
        t.add(rects)

        val drained = t.drain()

        assertEquals(1, drained.size)
        val box = drained[0]
        assertEquals(0, box.x)
        assertEquals(0, box.y)
        assertEquals(33, box.x + box.w)
        assertEquals(51, box.y + box.h)
    }
}
