/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.uml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UmlFailurePolicyTest {

    private fun classify(
        reachedRunning: Boolean = true,
        userStopping: Boolean = false,
        exitCode: Int? = 0,
        bootTimedOut: Boolean = false,
        consoleTail: String = "",
    ) = classifyUmlExit(reachedRunning, userStopping, exitCode, bootTimedOut, consoleTail)

    @Test
    fun `user stop with exit 0 is not a failure`() =
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(userStopping = true, exitCode = 0))

    @Test
    fun `exit 137 while user stopping is not a failure`() =
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(userStopping = true, exitCode = 137))

    @Test
    fun `exit 1 after Running while not stopping demotes`() =
        assertEquals(UmlExitClass.DEMOTE, classify(reachedRunning = true, exitCode = 1))

    @Test
    fun `boot timeout demotes`() =
        assertEquals(UmlExitClass.DEMOTE, classify(reachedRunning = false, exitCode = 0, bootTimedOut = true))

    @Test
    fun `MADV_REMOVE signature with exit 0 demotes`() =
        assertEquals(
            UmlExitClass.DEMOTE,
            classify(exitCode = 0, consoleTail = "physmem: MADV_REMOVE failed, err = -95\n"),
        )

    @Test
    fun `no exit code is not a failure`() =
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(exitCode = null))

    @Test
    fun `router death exit 75 is not a failure`() =
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(exitCode = 75))

    @Test
    fun `router death exit 75 with a kernel panic tail demotes`() {
        val tail = "Kernel panic - not syncing: No working init found.\n"
        assertEquals(UmlExitClass.DEMOTE, classify(reachedRunning = false, exitCode = 75, consoleTail = tail))
        assertEquals("Kernel panic", umlDemotionReason(75, false, tail))
    }

    @Test
    fun `router death exit 75 with a clean tail is not a failure`() =
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(exitCode = 75, consoleTail = "reboot: Power down\n"))

    @Test
    fun `SIGKILL by Android with a quiet tail is not a failure`() {
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(exitCode = 137, consoleTail = "random: crng init done\n"))
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(reachedRunning = false, exitCode = 128 + 9))
    }

    @Test
    fun `SIGKILL with a fatal signature or after a boot timeout still demotes`() {
        assertEquals(UmlExitClass.DEMOTE, classify(exitCode = 137, consoleTail = "physmem: MADV_REMOVE failed, err = -95\n"))
        assertEquals(UmlExitClass.DEMOTE, classify(reachedRunning = false, exitCode = 137, bootTimedOut = true))
    }

    @Test
    fun `missing init panic demotes whatever the exit code`() {
        val tail = "Run /nonexistent as init process\nKernel panic - not syncing: No working init found.\n"
        assertEquals(UmlExitClass.DEMOTE, classify(reachedRunning = false, exitCode = 0, consoleTail = tail))
        assertEquals(UmlExitClass.DEMOTE, classify(reachedRunning = false, exitCode = 1, consoleTail = tail))
    }

    @Test
    fun `clean exit 0 with a quiet tail is not a failure`() =
        assertEquals(UmlExitClass.NOT_A_FAILURE, classify(exitCode = 0, consoleTail = "reboot: Power down\n"))

    @Test
    fun `a busy probe is not persisted, real verdicts are`() {
        val key = UmlProbeKey("fp", 1, "k:1")
        assertFalse(shouldPersistProbe(UmlProbeRecord(key, passed = false, reason = "busy")))
        assertTrue(shouldPersistProbe(UmlProbeRecord(key, passed = false, reason = "FAIL net")))
        assertTrue(shouldPersistProbe(UmlProbeRecord(key, passed = true, reason = "PASS")))
    }

    @Test
    fun `probe key names kernel file and size`() =
        assertEquals(
            UmlProbeKey("google/x/y:17/A/1:user/release-keys", 34, "libuml-kernel.so:123456"),
            umlProbeKey("google/x/y:17/A/1:user/release-keys", 34, "libuml-kernel.so", 123456),
        )

    @Test
    fun `demotion reason prefers the most specific cause`() {
        assertEquals("boot timeout", umlDemotionReason(1, bootTimedOut = true, consoleTail = ""))
        assertEquals("MADV_REMOVE failed", umlDemotionReason(0, false, "x MADV_REMOVE failed y"))
        assertEquals("exit 1", umlDemotionReason(1, false, ""))
    }

    @Test
    fun `try again is offered for a failed probe and for a demotion only`() {
        assertTrue(umlRetryOffered(UmlStatus.FAILED))
        assertTrue(umlRetryOffered(UmlStatus.DEMOTED))
        assertFalse(umlRetryOffered(UmlStatus.PASSED))
        assertFalse(umlRetryOffered(UmlStatus.NOT_PROBED))
    }
}
