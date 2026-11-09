/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure construction of the User-Mode Linux launch: launcher -> supervisor
 * (podroid-uml.c) -> QEMU SLIRP router + UML kernel. No I/O, so the whole
 * guest contract is unit-testable.
 */
package com.excp.podroid.engine.uml

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.VmConfig
import java.io.File

/**
 * [inlineRules] must already be narrowed to QemuEngine.inlineLaunchRules: the
 * router is QEMU's SLIRP, so a hostfwd that cannot bind kills the launch here
 * exactly as it does on QEMU. [downloadsPath] is the host directory to expose
 * over hostfs (the guest can mount nothing else), or null when sharing is off
 * or not permitted.
 */
data class UmlLaunchParams(
    val nativeLibDir: File,
    val filesDir: File,
    val pageSize: Long,
    val config: VmConfig,
    val inlineRules: List<PortForwardRule>,
    val downloadsPath: String?,
    val probe: Boolean,
)

data class UmlCommand(val argv: List<String>, val env: Map<String, String>)

object UmlCommandBuilder {
    const val SUPERVISOR = "libpodroid-uml.so"
    const val LAUNCHER = "libpodroid-launcher.so"
    const val ROUTER = "libqemu-system-aarch64.so"

    /** The probe's scratch disk size: the guest writes 8 MiB to it. */
    const val PROBE_IMAGE_BYTES = 16L * 1024 * 1024
    private const val PROBE_RAM_MB = 256

    /** UML kernel for the host page size, or null when no build matches it. */
    fun kernelFor(pageSize: Long): String? = when (pageSize) {
        4096L -> "libuml-4k.so"
        16384L -> "libuml-16k.so"
        else -> null
    }

    fun stubFor(pageSize: Long): String? = when (pageSize) {
        4096L -> "libumlstub-4k.so"
        16384L -> "libumlstub-16k.so"
        else -> null
    }

    /** Supervisor WORKDIR: where serial/terminal/ctrl/host/qmp sockets are created. */
    fun workDir(filesDir: File, probe: Boolean): File =
        if (probe) File(filesDir, "uml-probe") else filesDir

    /** TMPDIR (and HOME, which holds the umid directory) for the kernel. */
    fun tmpDir(filesDir: File, probe: Boolean): File =
        if (probe) workDir(filesDir, true) else File(filesDir, "uml-tmp")

    fun probeImage(filesDir: File): File = File(workDir(filesDir, true), "uml-probe.img")

    /**
     * The hostfs root when Downloads is not shared: a path under the app files
     * dir that nothing ever creates, so every guest hostfs mount fails.
     */
    fun hostfsDisabledRoot(filesDir: File): File = File(filesDir, "uml-hostfs-disabled")

    /** Throws IllegalArgumentException for a page size [kernelFor] rejects. */
    fun build(p: UmlLaunchParams): UmlCommand {
        val kernel = requireNotNull(kernelFor(p.pageSize)) { "No UML kernel for page size ${p.pageSize}" }
        val stub = requireNotNull(stubFor(p.pageSize))
        val lib = p.nativeLibDir
        val files = p.filesDir
        val c = p.config

        val args = mutableListOf<String>()
        args += "mem=${if (p.probe) PROBE_RAM_MB else c.ramMb}M"
        args += "ncpus=${c.cpus}"
        args += "seccomp=on"
        args += "initrd=${File(files, "initrd.img").path}"
        if (p.probe) {
            // Only the scratch disk: init-podroid refuses to run the probe at all
            // when a second ubd device exists, so no persistent disk is attached.
            args += "ubd0=${probeImage(files).path}"
        } else {
            args += "ubd0=${File(files, "storage.img").path}"
            args += "ubd1r=${File(files, "alpine-rootfs.squashfs").path}"
        }
        args += "podroid.backend=uml"
        args += "podroid.tty=ttyS0"
        // ncpus= is consumed by the UML kernel and never reaches /proc/cmdline.
        args += "podroid.ncpus=${c.cpus}"
        args += "podroid.x11.dpi=${c.x11Dpi}"
        if (c.bandwidthMbps > 0) args += "podroid.bandwidth=${c.bandwidthMbps}"
        if (c.dnsServers.isNotEmpty()) args += "podroid.dns=${c.dnsServers.joinToString(",")}"
        if (p.probe) {
            args += "podroid.probe=1"
        } else if (p.downloadsPath != null) {
            // Only an on/off marker: the host path is the hostfs root below.
            args += "podroid.downloads=1"
        }
        // The probe stays self-contained: the user's extra args cannot fail it.
        val extra = if (p.probe) "" else c.kernelExtraCmdline.trim()
        if (extra.isNotEmpty()) args += extra.split(Regex("\\s+"))
        // Every hostfs mount is confined to this root (hostfs-confine.patch), so
        // a guest can reach Downloads when shared and nothing otherwise. Last,
        // so a hostfs= in the user's extra args cannot replace it (the kernel
        // keeps the last one).
        val hostfsRoot = if (p.probe) null else p.downloadsPath
        args += "hostfs=${hostfsRoot ?: hostfsDisabledRoot(files).path}"
        // c.qemuExtraArgs is deliberately unused: QEMU here is only a SLIRP
        // router with no guest, so user -cpu/-accel flags have nothing to apply to.

        val netdev = buildString {
            append("ipv6=off")
            if (!p.probe) {
                // Same hostfwd formatting as QemuEngine.buildCommand: empty host
                // address = 0.0.0.0, 127.0.0.1 for loopback-only rules.
                for (rule in p.inlineRules) {
                    val hostAddr = if (rule.loopbackOnly) "127.0.0.1" else ""
                    append(",hostfwd=${rule.protocol}:$hostAddr:${rule.hostPort}-:${rule.guestPort}")
                }
            }
        }

        val argv = listOf(
            File(lib, LAUNCHER).path,
            File(lib, SUPERVISOR).path,
            File(lib, ROUTER).path,
            workDir(files, p.probe).path,
            "--",
            File(lib, kernel).path,
        ) + args

        val env = mapOf(
            // libslirp lives next to QEMU; same search path QemuEngine sets.
            "LD_LIBRARY_PATH" to "${lib.path}:${files.path}",
            "TMPDIR" to tmpDir(files, p.probe).path,
            "PODROID_UML_STUB" to File(lib, stub).path,
            "PODROID_UML_QEMU_ARGS" to netdev,
        )
        return UmlCommand(argv, env)
    }
}
