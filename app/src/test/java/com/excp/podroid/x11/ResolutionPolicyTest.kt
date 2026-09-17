package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolutionPolicyTest {
    @Test fun `match returns viewport rounded to even`() {
        assertEquals(VncSize(1080, 2336), ResolutionPolicy.target(
            X11Settings(resolutionMode = ResolutionMode.MATCH), viewportW = 1080, viewportH = 2337))
    }
    @Test fun `preset returns chosen size in landscape`() {
        val s = X11Settings(resolutionMode = ResolutionMode.PRESET, preset = ResolutionPreset.R1440P)
        assertEquals(VncSize(2560, 1440), ResolutionPolicy.target(s, viewportW = 2337, viewportH = 1080))
    }
    @Test fun `preset orients to match portrait viewport`() {
        val s = X11Settings(resolutionMode = ResolutionMode.PRESET, preset = ResolutionPreset.R1080P)
        assertEquals(VncSize(1080, 1920), ResolutionPolicy.target(s, viewportW = 1080, viewportH = 2337))
    }
    @Test fun `custom returns custom dims`() {
        val s = X11Settings(resolutionMode = ResolutionMode.CUSTOM, customW = 1600, customH = 900)
        assertEquals(VncSize(1600, 900), ResolutionPolicy.target(s, viewportW = 1080, viewportH = 2337))
    }
    @Test fun `match clamps to sane minimum`() {
        assertEquals(VncSize(320, 240), ResolutionPolicy.target(
            X11Settings(resolutionMode = ResolutionMode.MATCH), viewportW = 10, viewportH = 8))
    }
    @Test fun `match at 75 percent scales viewport before rounding`() {
        assertEquals(VncSize(810, 1752), ResolutionPolicy.target(
            X11Settings(resolutionMode = ResolutionMode.MATCH, renderScale = 75), viewportW = 1080, viewportH = 2337))
    }
    @Test fun `match at 50 percent scales viewport before rounding`() {
        assertEquals(VncSize(540, 1168), ResolutionPolicy.target(
            X11Settings(resolutionMode = ResolutionMode.MATCH, renderScale = 50), viewportW = 1080, viewportH = 2337))
    }
    @Test fun `match at 50 percent still clamps to sane minimum`() {
        assertEquals(VncSize(320, 240), ResolutionPolicy.target(
            X11Settings(resolutionMode = ResolutionMode.MATCH, renderScale = 50), viewportW = 100, viewportH = 80))
    }
    @Test fun `preset ignores render scale`() {
        val s = X11Settings(resolutionMode = ResolutionMode.PRESET, preset = ResolutionPreset.R1440P, renderScale = 50)
        assertEquals(VncSize(2560, 1440), ResolutionPolicy.target(s, viewportW = 2337, viewportH = 1080))
    }
    @Test fun `custom ignores render scale`() {
        val s = X11Settings(resolutionMode = ResolutionMode.CUSTOM, customW = 1600, customH = 900, renderScale = 50)
        assertEquals(VncSize(1600, 900), ResolutionPolicy.target(s, viewportW = 1080, viewportH = 2337))
    }
}
