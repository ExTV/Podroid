/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.uml

import com.excp.podroid.engine.uml.UmlProbe.Companion.classifyProbeConsole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UmlProbeClassifyTest {

    @Test
    fun `undecided until a verdict or fatal line appears`() {
        assertNull(classifyProbeConsole("[podroid-init] mounted /proc\n"))
    }

    @Test
    fun `pass line passes`() {
        assertEquals(true to "PASS", classifyProbeConsole("x\nPODROID-PROBE: PASS\nreboot: Power down\n"))
    }

    @Test
    fun `fail line names the step`() {
        assertEquals(false to "FAIL net", classifyProbeConsole("PODROID-PROBE: FAIL net\n"))
    }

    @Test
    fun `fatal signatures fail`() {
        assertEquals(false to "MADV_REMOVE failed", classifyProbeConsole("physmem: MADV_REMOVE failed\n"))
        assertEquals(false to "Kernel panic", classifyProbeConsole("Kernel panic - not syncing: x\n"))
    }

    @Test
    fun `a pass is not undone by a panic while powering off`() {
        val v = classifyProbeConsole("PODROID-PROBE: PASS\nKernel panic - not syncing: Attempted to kill init!\n")
        assertTrue(v!!.first)
    }

    @Test
    fun `a fail wins over a later fatal signature`() {
        val v = classifyProbeConsole("PODROID-PROBE: FAIL smp\nKernel panic\n")
        assertFalse(v!!.first)
        assertEquals("FAIL smp", v.second)
    }
}
