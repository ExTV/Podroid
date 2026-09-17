/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderGeometryTest {

    @Test fun `exact fit uses the whole view and buffer equals framebuffer size`() {
        val g = RenderGeometry.compute(viewW = 1280, viewH = 720, fbW = 1280, fbH = 720)

        assertEquals(0, g.dstX); assertEquals(0, g.dstY)
        assertEquals(1280, g.dstW); assertEquals(720, g.dstH)
        assertEquals(1280, g.bufW); assertEquals(720, g.bufH)
        assertEquals(0, g.bufDstX); assertEquals(0, g.bufDstY)
        assertEquals(1280, g.bufDstW); assertEquals(720, g.bufDstH)
    }

    @Test fun `landscape framebuffer in a portrait view is top-pinned and centered`() {
        val g = RenderGeometry.compute(viewW = 1080, viewH = 1920, fbW = 1280, fbH = 720)

        // View-space letterbox: full width used, pinned to the top, extra
        // space (if any) left at the bottom.
        assertEquals(0, g.dstY)
        assertEquals(0, g.dstX)
        assertEquals(1080, g.dstW)
        assertTrue(g.dstH in 1..1920)
        // Buffer-space letterbox mirrors the same top-pinned/centered layout
        // and, unclamped, is exactly the framebuffer's own size.
        assertEquals(0, g.bufDstY)
        assertEquals(1280, g.bufDstW)
        assertEquals(720, g.bufDstH)
        assertEquals(0, g.bufDstX)
    }

    @Test fun `framebuffer larger than the view still fits and centers`() {
        val g = RenderGeometry.compute(viewW = 400, viewH = 800, fbW = 1920, fbH = 1080)

        assertEquals(0, g.dstY)
        assertTrue(g.dstW <= 400)
        assertTrue(g.dstH <= 800)
        assertEquals(1920, g.bufDstW)
        assertEquals(1080, g.bufDstH)
    }

    @Test fun `clamping at maxBuffer keeps the buffer letterbox aspect close to the framebuffer's`() {
        // A huge framebuffer relative to maxBuffer forces the clamp path.
        val g = RenderGeometry.compute(viewW = 1080, viewH = 1920, fbW = 7680, fbH = 4320, maxBuffer = 2048)

        assertTrue(g.bufW <= 2048)
        assertTrue(g.bufH <= 2048)
        // Clamped, so the buffer-space letterbox is smaller than the real
        // framebuffer, but its aspect ratio must still match fbW:fbH.
        assertTrue(g.bufDstW < 7680)
        val fbAspect = 7680.0 / 4320.0
        val bufDstAspect = g.bufDstW.toDouble() / g.bufDstH.toDouble()
        assertTrue(Math.abs(fbAspect - bufDstAspect) < 0.02)
    }

    @Test fun `degenerate zero and negative inputs never crash and stay at least 1x1`() {
        val cases = listOf(
            RenderGeometry.compute(0, 0, 0, 0),
            RenderGeometry.compute(-5, -5, -5, -5),
            RenderGeometry.compute(100, 100, 0, 0),
            RenderGeometry.compute(0, 0, 100, 100),
        )
        for (g in cases) {
            assertTrue(g.dstW >= 1); assertTrue(g.dstH >= 1)
            assertTrue(g.bufW >= 1); assertTrue(g.bufH >= 1)
            assertTrue(g.bufDstW >= 1); assertTrue(g.bufDstH >= 1)
        }
    }

    @Test fun `unclamped buffer letterbox always equals the framebuffer size exactly`() {
        val cases = listOf(
            RenderGeometry.compute(1920, 1080, 1280, 720),
            RenderGeometry.compute(600, 1200, 1920, 1080),
            RenderGeometry.compute(2000, 1000, 640, 480),
        )
        for (g in cases) {
            assertTrue(g.bufW <= RenderGeometry.DEFAULT_MAX_BUFFER)
            assertTrue(g.bufH <= RenderGeometry.DEFAULT_MAX_BUFFER)
        }
        val g0 = cases[0]
        assertEquals(1280, g0.bufDstW); assertEquals(720, g0.bufDstH)
        val g1 = cases[1]
        assertEquals(1920, g1.bufDstW); assertEquals(1080, g1.bufDstH)
        val g2 = cases[2]
        assertEquals(640, g2.bufDstW); assertEquals(480, g2.bufDstH)
    }

    @Test fun `307x313 view with 306x312 framebuffer does not clip the last row`() {
        // Regression case: Float truncation on the fit axis (height) used to
        // land bufH on fbH-1 (311) while bufDstH stayed fbH (312), so the
        // letterbox rect overflowed the buffer by one pixel.
        val g = RenderGeometry.compute(viewW = 307, viewH = 313, fbW = 306, fbH = 312)

        assertEquals(306, g.bufDstW); assertEquals(312, g.bufDstH)
        assertTrue(g.bufW >= g.bufDstW)
        assertTrue(g.bufH >= g.bufDstH)
        assertTrue(g.bufDstX + g.bufDstW <= g.bufW)
        assertTrue(g.bufDstY + g.bufDstH <= g.bufH)
    }

    @Test fun `brute-force sweep never lets the buffer letterbox overflow the buffer`() {
        val fbSizes = listOf(
            1920 to 1080,
            1280 to 720,
            306 to 312,
            Math.round(2400 * 0.75).toInt() to Math.round(1080 * 0.75 / 2).toInt() * 2,
        )
        val maxBuffer = RenderGeometry.DEFAULT_MAX_BUFFER

        for ((fbW, fbH) in fbSizes) {
            var viewW = 200
            while (viewW <= 400) {
                var viewH = 200
                while (viewH <= 400) {
                    val g = RenderGeometry.compute(viewW, viewH, fbW, fbH, maxBuffer)

                    // Fit-inside property: the buffer-space letterbox rect
                    // never overflows the buffer, clamped or not.
                    assertTrue(
                        "overflow x for view=${viewW}x$viewH fb=${fbW}x$fbH: $g",
                        g.bufDstX + g.bufDstW <= g.bufW,
                    )
                    assertTrue(
                        "overflow y for view=${viewW}x$viewH fb=${fbW}x$fbH: $g",
                        g.bufDstY + g.bufDstH <= g.bufH,
                    )
                    assertTrue(g.bufW <= maxBuffer)
                    assertTrue(g.bufH <= maxBuffer)

                    // Mirror compute()'s own pre-clamp math to know whether
                    // this case is expected to hit the maxBuffer clamp.
                    val scale = minOf(viewW.toDouble() / fbW, viewH.toDouble() / fbH)
                    val rawBufW = viewW / scale
                    val rawBufH = viewH / scale
                    val clamped = maxOf(rawBufW, rawBufH) > maxBuffer
                    if (!clamped) {
                        assertEquals(
                            "bufDstW for view=${viewW}x$viewH fb=${fbW}x$fbH: $g",
                            fbW,
                            g.bufDstW,
                        )
                        assertEquals(
                            "bufDstH for view=${viewW}x$viewH fb=${fbW}x$fbH: $g",
                            fbH,
                            g.bufDstH,
                        )
                    }

                    viewH += 7
                }
                viewW += 7
            }
        }
    }

    @Test fun `view rect stays within 1px of the buffer rect mapped back to view space`() {
        val fbSizes = listOf(
            1920 to 1080,
            1080 to 1920,
            1280 to 720,
            2560 to 1440,
            306 to 312,
            1800 to 810,
            810 to 1800,
        )

        for ((fbW, fbH) in fbSizes) {
            var viewW = 100
            while (viewW <= 3200) {
                var viewH = 100
                while (viewH <= 3200) {
                    val g = RenderGeometry.compute(viewW, viewH, fbW, fbH)
                    val label = "view=${viewW}x$viewH fb=${fbW}x$fbH: $g"

                    // Fit-inside in buffer space.
                    assertTrue("buffer overflow x for $label", g.bufDstX + g.bufDstW <= g.bufW)
                    assertTrue("buffer overflow y for $label", g.bufDstY + g.bufDstH <= g.bufH)

                    // View rect stays within the viewport.
                    assertTrue("view dstX for $label", g.dstX >= 0)
                    assertTrue("view dstY for $label", g.dstY >= 0)
                    assertTrue("view overflow x for $label", g.dstX + g.dstW <= viewW)
                    assertTrue("view overflow y for $label", g.dstY + g.dstH <= viewH)

                    // The buffer rect, mapped back to view space, must land
                    // within 1px of the view rect on every edge - this is
                    // the property touch mapping (view rect) relies on to
                    // agree with the drawn edge (buffer rect, scaled up by
                    // the compositor).
                    val mappedLeft = g.bufDstX.toDouble() * viewW / g.bufW
                    val mappedRight = (g.bufDstX + g.bufDstW).toDouble() * viewW / g.bufW
                    val mappedTop = g.bufDstY.toDouble() * viewH / g.bufH
                    val mappedBottom = (g.bufDstY + g.bufDstH).toDouble() * viewH / g.bufH

                    assertTrue("left edge for $label", Math.abs(mappedLeft - g.dstX) <= 1.0)
                    assertTrue(
                        "right edge for $label",
                        Math.abs(mappedRight - (g.dstX + g.dstW)) <= 1.0,
                    )
                    assertTrue("top edge for $label", Math.abs(mappedTop - g.dstY) <= 1.0)
                    assertTrue(
                        "bottom edge for $label",
                        Math.abs(mappedBottom - (g.dstY + g.dstH)) <= 1.0,
                    )

                    viewH += 13
                }
                viewW += 13
            }
        }
    }

    @Test fun `height-constrained cases center horizontally within 1px in both view and buffer space`() {
        // Each case is narrower (fbW / fbH) than the view, so the height
        // axis is the fit constraint and horizontal bars appear on both
        // sides - the scenario the suite previously never exercised (dstX
        // and bufDstX both non-zero).
        data class Case(val viewW: Int, val viewH: Int, val fbW: Int, val fbH: Int)
        val cases = listOf(
            Case(1920, 1080, 810, 1800),
            Case(1921, 1081, 809, 1799),
            Case(2000, 1000, 1900, 1800),
            Case(1600, 900, 1000, 1500),
            Case(3200, 800, 1080, 1920),
        )

        var exercised = 0
        for (c in cases) {
            val g = RenderGeometry.compute(c.viewW, c.viewH, c.fbW, c.fbH)
            val label = "view=${c.viewW}x${c.viewH} fb=${c.fbW}x${c.fbH}: $g"
            assertTrue("expected dstX > 0 for $label", g.dstX > 0)
            assertTrue("expected bufDstX > 0 for $label", g.bufDstX > 0)
            exercised++

            val viewRightBar = c.viewW - g.dstX - g.dstW
            assertTrue(
                "view left/right bars differ for $label",
                Math.abs(g.dstX - viewRightBar) <= 1,
            )
            val bufRightBar = g.bufW - g.bufDstX - g.bufDstW
            assertTrue(
                "buffer left/right bars differ for $label",
                Math.abs(g.bufDstX - bufRightBar) <= 1,
            )
        }
        assertTrue("no case exercised dX > 0 && bufDstX > 0", exercised > 0)
    }
}
