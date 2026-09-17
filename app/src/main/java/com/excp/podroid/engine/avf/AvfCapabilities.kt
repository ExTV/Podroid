/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure decode of VirtualMachineManager.getCapabilities() bitmask + the
 * protected-VM selection policy. Podroid runs a raw custom kernel/initrd
 * image, which AVF can only boot as a NON-protected VM (protected VMs need a
 * pvmfw-signed payload). So: prefer non-protected; if the device only supports
 * protected VMs we cannot run here and the caller must fall back to QEMU.
 * Pure (no Android deps) so it unit-tests on the JVM.
 */
package com.excp.podroid.engine.avf

object AvfCapabilities {
    private const val CAPABILITY_PROTECTED_VM = 1
    private const val CAPABILITY_NON_PROTECTED_VM = 2

    sealed interface ProtectedVmChoice {
        /** Apply setProtectedVm(false) and proceed - the common path. */
        data object NonProtected : ProtectedVmChoice
        /** getCapabilities() was unavailable; try non-protected and let any throw surface. */
        data object Unknown : ProtectedVmChoice
        /** Device can't run our custom image; caller must fall back to QEMU. */
        data class Unsupported(val reason: String) : ProtectedVmChoice
    }

    fun decode(capabilities: Int): String {
        if (capabilities == 0) return "none/unknown"
        val parts = mutableListOf<String>()
        if (capabilities and CAPABILITY_PROTECTED_VM != 0) parts += "PROTECTED"
        if (capabilities and CAPABILITY_NON_PROTECTED_VM != 0) parts += "NON_PROTECTED"
        return if (parts.isEmpty()) "0x${capabilities.toString(16)}" else parts.joinToString("+")
    }

    fun choose(capabilities: Int): ProtectedVmChoice = when {
        capabilities == 0 -> ProtectedVmChoice.Unknown
        capabilities and CAPABILITY_NON_PROTECTED_VM != 0 -> ProtectedVmChoice.NonProtected
        capabilities and CAPABILITY_PROTECTED_VM != 0 -> ProtectedVmChoice.Unsupported(
            "This device's hypervisor only supports protected VMs; AVF can't run " +
                "Podroid's custom Linux image here. Falling back to QEMU."
        )
        else -> ProtectedVmChoice.Unknown
    }
}
