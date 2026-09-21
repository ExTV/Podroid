/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Unit tests for ControlProvider's pure logic: caller authorization, method+
 * arg parsing, VmState -> literal mapping, and state reply formatting. Plain
 * JVM, no Robolectric - none of the code under test touches Android classes.
 */
package com.excp.podroid.engine.control

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlProviderTest {

    // ------------------------------------------------------------------
    // isCallerAllowed
    // ------------------------------------------------------------------

    @Test
    fun `shell uid is allowed without DUMP`() {
        assertTrue(isCallerAllowed(uid = 2000, hasDumpPermission = false))
    }

    @Test
    fun `root uid is allowed without DUMP`() {
        assertTrue(isCallerAllowed(uid = 0, hasDumpPermission = false))
    }

    @Test
    fun `ordinary app uid without DUMP is rejected`() {
        assertFalse(isCallerAllowed(uid = 10280, hasDumpPermission = false))
    }

    @Test
    fun `ordinary app uid with DUMP is allowed`() {
        assertTrue(isCallerAllowed(uid = 10280, hasDumpPermission = true))
    }

    // ------------------------------------------------------------------
    // parseCommand - state / backups (no arg)
    // ------------------------------------------------------------------

    @Test
    fun `state parses with no arg`() {
        assertEquals(ControlCommand.State, parseCommand("state", null))
    }

    @Test
    fun `backups parses with no arg`() {
        assertEquals(ControlCommand.Backups, parseCommand("backups", null))
    }

    // ------------------------------------------------------------------
    // parseCommand - set-backend
    // ------------------------------------------------------------------

    @Test
    fun `set-backend accepts lowercase arg`() {
        assertEquals(
            ControlCommand.SetBackend(EngineSelection.QEMU),
            parseCommand("set-backend", "qemu"),
        )
    }

    @Test
    fun `set-backend accepts uppercase and mixed case arg`() {
        assertEquals(
            ControlCommand.SetBackend(EngineSelection.AVF),
            parseCommand("set-backend", "AVF"),
        )
        assertEquals(
            ControlCommand.SetBackend(EngineSelection.AUTO),
            parseCommand("set-backend", "Auto"),
        )
    }

    @Test
    fun `set-backend rejects unknown value`() {
        assertEquals(ControlCommand.BadArg, parseCommand("set-backend", "bogus"))
    }

    @Test
    fun `set-backend rejects missing arg`() {
        assertEquals(ControlCommand.BadArg, parseCommand("set-backend", null))
    }

    // ------------------------------------------------------------------
    // parseCommand - forward-add / forward-remove
    // ------------------------------------------------------------------

    @Test
    fun `forward-add parses a valid serialized rule`() {
        assertEquals(
            ControlCommand.ForwardAdd(PortForwardRule(8080, 80, "tcp")),
            parseCommand("forward-add", "tcp:8080:80"),
        )
    }

    @Test
    fun `forward-add rejects an unparseable rule`() {
        assertEquals(ControlCommand.BadArg, parseCommand("forward-add", "not-a-rule"))
    }

    @Test
    fun `forward-add rejects missing arg`() {
        assertEquals(ControlCommand.BadArg, parseCommand("forward-add", null))
    }

    @Test
    fun `forward-remove parses a valid serialized rule`() {
        assertEquals(
            ControlCommand.ForwardRemove(PortForwardRule(8080, 80, "udp")),
            parseCommand("forward-remove", "udp:8080:80"),
        )
    }

    @Test
    fun `forward-remove rejects an unparseable rule`() {
        assertEquals(ControlCommand.BadArg, parseCommand("forward-remove", "garbage"))
    }

    // ------------------------------------------------------------------
    // parseCommand - navigate / route whitelist
    // ------------------------------------------------------------------

    @Test
    fun `navigate accepts each whitelisted route`() {
        for (route in setOf(Routes.HOME, Routes.TERMINAL, Routes.SETTINGS, Routes.STATUS, Routes.CONTAINER_BACKUP)) {
            assertEquals(ControlCommand.Navigate(route), parseCommand("navigate", route))
        }
    }

    @Test
    fun `navigate rejects setup`() {
        assertEquals(ControlCommand.UnknownRoute, parseCommand("navigate", Routes.SETUP))
    }

    @Test
    fun `navigate rejects terminal x11`() {
        assertEquals(ControlCommand.UnknownRoute, parseCommand("navigate", Routes.TERMINAL_X11))
    }

    @Test
    fun `navigate rejects missing arg`() {
        assertEquals(ControlCommand.BadArg, parseCommand("navigate", null))
    }

    // ------------------------------------------------------------------
    // parseCommand - unknown method
    // ------------------------------------------------------------------

    @Test
    fun `unknown method is reported`() {
        assertEquals(ControlCommand.UnknownMethod, parseCommand("bogus-method", null))
    }

    // ------------------------------------------------------------------
    // vmStateLiteral
    // ------------------------------------------------------------------

    @Test
    fun `vmStateLiteral maps every variant to an explicit literal`() {
        assertEquals("idle", vmStateLiteral(VmState.Idle))
        assertEquals("starting", vmStateLiteral(VmState.Starting))
        assertEquals("running", vmStateLiteral(VmState.Running))
        assertEquals("stopped", vmStateLiteral(VmState.Stopped))
        assertEquals("error", vmStateLiteral(VmState.Error("boom")))
    }

    // ------------------------------------------------------------------
    // formatStateReply
    // ------------------------------------------------------------------

    @Test
    fun `formatStateReply with no forwards reports none`() {
        val reply = formatStateReply(
            vmState = VmState.Running,
            backendId = "qemu",
            selection = EngineSelection.AUTO,
            bootStage = "Ready!",
            forwards = emptyList(),
            versionCode = 42,
        )
        assertEquals(
            "ok vm=running backend=qemu selection=AUTO stage=Ready! forwards=none vc=42",
            reply,
        )
    }

    @Test
    fun `formatStateReply replaces spaces in the boot stage and joins forwards`() {
        val reply = formatStateReply(
            vmState = VmState.Starting,
            backendId = "avf",
            selection = EngineSelection.QEMU,
            bootStage = "Almost ready...",
            forwards = listOf(PortForwardRule(8080, 80, "tcp"), PortForwardRule(9922, 22, "tcp")),
            versionCode = 7,
        )
        assertEquals(
            "ok vm=starting backend=avf selection=QEMU stage=Almost_ready... " +
                "forwards=tcp:8080:80,tcp:9922:22 vc=7",
            reply,
        )
    }
}
