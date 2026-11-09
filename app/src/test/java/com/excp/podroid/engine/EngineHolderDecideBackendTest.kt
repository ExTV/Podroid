/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import com.excp.podroid.R
import com.excp.podroid.engine.EngineHolder.Companion.FallbackReason
import com.excp.podroid.engine.EngineHolder.Companion.decideBackend
import com.excp.podroid.engine.EngineHolder.Companion.fallbackReasonResId
import com.excp.podroid.engine.EngineHolder.Companion.mustAwaitUmlTree
import com.excp.podroid.engine.EngineHolder.Companion.shouldProbeNetworkFeature
import com.excp.podroid.engine.EngineHolder.Companion.umlRetryAllowed
import com.excp.podroid.engine.avf.AvfReport
import com.excp.podroid.engine.uml.UmlStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure backend-selection decision extracted from EngineHolder.pick()
 * (#66). The decision itself must never change here - only its exposure as a
 * testable function - so these cases mirror pick()'s old inline `when`
 * branch-for-branch.
 */
class EngineHolderDecideBackendTest {

    @Test
    fun `auto selection with usable AVF picks avf and no fallback`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AUTO,
            avfUsable = true,
            protectedOnly = false,
            networkAvailable = true,
        )
        assertEquals("avf", backendId)
        assertNull(reason)
    }

    @Test
    fun `auto selection with unusable AVF picks qemu and no fallback`() {
        // AUTO never surfaces a fallback reason: falling through to QEMU is the
        // normal AUTO behavior, not a forced selection that failed.
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AUTO,
            avfUsable = false,
            protectedOnly = false,
            networkAvailable = true,
        )
        assertEquals("qemu", backendId)
        assertNull(reason)
    }

    @Test
    fun `forced avf with unusable AVF falls back to qemu with a reason`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AVF,
            avfUsable = false,
            protectedOnly = false,
            networkAvailable = true,
        )
        assertEquals("qemu", backendId)
        assertEquals(FallbackReason.UNAVAILABLE, reason)
    }

    @Test
    fun `forced avf with usable AVF picks avf and no fallback`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AVF,
            avfUsable = true,
            protectedOnly = false,
            networkAvailable = true,
        )
        assertEquals("avf", backendId)
        assertNull(reason)
    }

    @Test
    fun `forced qemu always picks qemu regardless of AVF usability`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.QEMU,
            avfUsable = true,
            protectedOnly = true,
            networkAvailable = true,
        )
        assertEquals("qemu", backendId)
        assertNull(reason)
    }

    @Test
    fun `forced avf on a protected-only device gets the protected-only reason`() {
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AVF,
            avfUsable = false,
            protectedOnly = true,
            networkAvailable = true,
        )
        assertEquals("qemu", backendId)
        assertEquals(FallbackReason.PROTECTED_ONLY, reason)
    }

    @Test
    fun `auto selection with network compiled out falls back to qemu with the network reason`() {
        // #43: OEM build has isFeatureEnabled(com.android.kvm.NETWORK) == false.
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AUTO,
            avfUsable = true,
            protectedOnly = false,
            networkAvailable = false,
        )
        assertEquals("qemu", backendId)
        assertEquals(FallbackReason.NETWORK_DISABLED, reason)
    }

    @Test
    fun `auto selection with unknown network support picks avf`() {
        // Missing method or a throw resolves to networkAvailable = true before
        // reaching decideBackend, so an "unknown" device behaves like before #43.
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AUTO,
            avfUsable = true,
            protectedOnly = false,
            networkAvailable = true,
        )
        assertEquals("avf", backendId)
        assertNull(reason)
    }

    @Test
    fun `forced avf with network compiled out stays avf`() {
        // The user explicitly asked for AVF; #43's check only steers AUTO.
        val (backendId, reason) = decideBackend(
            selection = EngineSelection.AVF,
            avfUsable = true,
            protectedOnly = false,
            networkAvailable = false,
        )
        assertEquals("avf", backendId)
        assertNull(reason)
    }

    @Test
    fun `network-disabled fallback maps to its dedicated resource`() {
        assertEquals(
            R.string.backend_fallback_reason_network,
            fallbackReasonResId(
                FallbackReason.NETWORK_DISABLED,
                report(),
            ),
        )
    }

    @Test
    fun `protected-only fallback maps to its dedicated resource`() {
        assertEquals(
            R.string.backend_fallback_reason_protected_only,
            fallbackReasonResId(
                FallbackReason.PROTECTED_ONLY,
                report(featureSupported = false),
            ),
        )
    }

    @Test
    fun `unavailable fallback reports the missing feature first`() {
        assertEquals(
            R.string.backend_fallback_reason_feature,
            fallbackReasonResId(
                FallbackReason.UNAVAILABLE,
                report(featureSupported = false),
            ),
        )
    }

    @Test
    fun `unavailable fallback reports missing permissions before service`() {
        assertEquals(
            R.string.backend_fallback_reason_permissions,
            fallbackReasonResId(
                FallbackReason.UNAVAILABLE,
                report(managePermissionGranted = false, serviceReachable = false),
            ),
        )
    }

    @Test
    fun `unavailable fallback reports service when prerequisites are present`() {
        assertEquals(
            R.string.backend_fallback_reason_service,
            fallbackReasonResId(
                FallbackReason.UNAVAILABLE,
                report(serviceReachable = false),
            ),
        )
    }

    @Test
    fun `network feature probe runs only for auto with avf otherwise usable`() {
        // #43 follow-up (T5b): the probe is a binder IPC, so it must be
        // skipped whenever it cannot change decideBackend's outcome.
        assertTrue(shouldProbeNetworkFeature(EngineSelection.AUTO, avfUsable = true))
    }

    @Test
    fun `network feature probe skipped for auto when avf is not usable anyway`() {
        assertFalse(shouldProbeNetworkFeature(EngineSelection.AUTO, avfUsable = false))
    }

    @Test
    fun `network feature probe skipped for forced avf`() {
        assertFalse(shouldProbeNetworkFeature(EngineSelection.AVF, avfUsable = true))
    }

    @Test
    fun `network feature probe runs for uml so its fallback matches auto`() {
        assertTrue(shouldProbeNetworkFeature(EngineSelection.UML, avfUsable = true))
        assertFalse(shouldProbeNetworkFeature(EngineSelection.UML, avfUsable = false))
    }

    @Test
    fun `uml tree is awaited for a uml selection, leftover uml processes, or a uml run this process`() {
        assertTrue(mustAwaitUmlTree(EngineSelection.UML, umlPidsPresent = false, umlStartedThisProcess = false))
        assertTrue(mustAwaitUmlTree(EngineSelection.QEMU, umlPidsPresent = true, umlStartedThisProcess = false))
        assertTrue(mustAwaitUmlTree(EngineSelection.AUTO, umlPidsPresent = false, umlStartedThisProcess = true))
        assertTrue(mustAwaitUmlTree(null, umlPidsPresent = true, umlStartedThisProcess = false))
    }

    @Test
    fun `uml tree is not awaited on a plain qemu or avf start`() {
        assertFalse(mustAwaitUmlTree(EngineSelection.QEMU, umlPidsPresent = false, umlStartedThisProcess = false))
        assertFalse(mustAwaitUmlTree(EngineSelection.AVF, umlPidsPresent = false, umlStartedThisProcess = false))
        assertFalse(mustAwaitUmlTree(EngineSelection.AUTO, umlPidsPresent = false, umlStartedThisProcess = false))
        assertFalse(mustAwaitUmlTree(null, umlPidsPresent = false, umlStartedThisProcess = false))
    }

    @Test
    fun `network feature probe skipped for forced qemu`() {
        assertFalse(shouldProbeNetworkFeature(EngineSelection.QEMU, avfUsable = true))
    }

    private fun report(
        featureSupported: Boolean = true,
        managePermissionGranted: Boolean = true,
        customPermissionGranted: Boolean = true,
        serviceReachable: Boolean = true,
    ) = AvfReport(
        featureSupported = featureSupported,
        managePermissionGranted = managePermissionGranted,
        customPermissionGranted = customPermissionGranted,
        virtApexPresent = true,
        managerClassPresent = true,
        serviceReachable = serviceReachable,
        customVmConfigSupported = true,
        smokeTestResult = null,
    )

    @Test fun umlSelectedAndPassed_picksUml() =
        assertEquals("uml" to null, decideBackend(EngineSelection.UML, avfUsable = true, protectedOnly = false, networkAvailable = true, umlStatus = UmlStatus.PASSED))

    @Test fun umlSelectedProbeFailed_fallsBackToAvfWhenUsable() =
        assertEquals("avf" to FallbackReason.UML_PROBE_FAILED, decideBackend(EngineSelection.UML, true, false, true, UmlStatus.FAILED))

    @Test fun umlSelectedDemoted_fallsBackToQemuWithoutAvf() =
        assertEquals("qemu" to FallbackReason.UML_DEMOTED, decideBackend(EngineSelection.UML, false, false, true, UmlStatus.DEMOTED))

    @Test fun umlSelectedNotProbed_doesNotPickUml() =
        assertEquals("qemu" to FallbackReason.UML_PROBE_FAILED, decideBackend(EngineSelection.UML, false, false, true, UmlStatus.NOT_PROBED))

    @Test fun autoIgnoresUmlInRelease1() =
        assertEquals("qemu" to null, decideBackend(EngineSelection.AUTO, false, false, true, UmlStatus.PASSED))

    @Test fun umlFallbackHonorsAvfNetworkDisabled() =
        assertEquals("qemu" to FallbackReason.UML_PROBE_FAILED, decideBackend(EngineSelection.UML, true, false, false, UmlStatus.FAILED))

    @Test
    fun `a uml start awaits leftover uml processes like any other start`() {
        // runEngine calls this for the UML engine too (before it spawns), so an
        // orphan tree from a dead app process is awaited and, if it survives,
        // the UML start is refused.
        assertTrue(mustAwaitUmlTree(EngineSelection.UML, umlPidsPresent = true, umlStartedThisProcess = false))
        assertTrue(mustAwaitUmlTree(EngineSelection.UML, umlPidsPresent = false, umlStartedThisProcess = true))
    }

    @Test
    fun `uml retry is refused while a vm is starting or running`() {
        assertFalse(umlRetryAllowed(VmState.Starting))
        assertFalse(umlRetryAllowed(VmState.Running))
        assertTrue(umlRetryAllowed(VmState.Idle))
        assertTrue(umlRetryAllowed(VmState.Stopped))
        assertTrue(umlRetryAllowed(VmState.Error("x")))
    }
}
