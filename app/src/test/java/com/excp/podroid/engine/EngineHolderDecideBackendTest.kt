/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import com.excp.podroid.R
import com.excp.podroid.engine.EngineHolder.Companion.FallbackReason
import com.excp.podroid.engine.EngineHolder.Companion.decideBackend
import com.excp.podroid.engine.EngineHolder.Companion.fallbackReasonResId
import com.excp.podroid.engine.avf.AvfReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        )
        assertEquals("qemu", backendId)
        assertEquals(FallbackReason.PROTECTED_ONLY, reason)
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
}
