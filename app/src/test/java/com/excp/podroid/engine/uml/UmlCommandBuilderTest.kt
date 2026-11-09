/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.uml

import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.VmConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UmlCommandBuilderTest {

    private val lib = File("/data/app/x/lib/arm64")
    private val files = File("/data/user/0/com.excp.podroid/files")

    private fun params(
        pageSize: Long = 4096,
        config: VmConfig = VmConfig(ramMb = 2048, cpus = 4, dnsServers = listOf("10.0.0.1")),
        rules: List<PortForwardRule> = emptyList(),
        downloads: String? = null,
        probe: Boolean = false,
    ) = UmlLaunchParams(lib, files, pageSize, config, rules, downloads, probe)

    /** Kernel arguments: everything after the "--" separator and the kernel path. */
    private fun kernelArgs(cmd: UmlCommand): List<String> =
        cmd.argv.drop(cmd.argv.indexOf("--") + 2)

    @Test
    fun `4K page size picks the 4K kernel and stub`() {
        val cmd = UmlCommandBuilder.build(params(pageSize = 4096))
        assertEquals("$lib/libuml-4k.so", cmd.argv[cmd.argv.indexOf("--") + 1])
        assertEquals("$lib/libumlstub-4k.so", cmd.env["PODROID_UML_STUB"])
    }

    @Test
    fun `16K page size picks the 16K kernel and stub`() {
        val cmd = UmlCommandBuilder.build(params(pageSize = 16384))
        assertEquals("$lib/libuml-16k.so", cmd.argv[cmd.argv.indexOf("--") + 1])
        assertEquals("$lib/libumlstub-16k.so", cmd.env["PODROID_UML_STUB"])
    }

    @Test
    fun `unknown page size has no kernel`() {
        assertEquals("libuml-4k.so", UmlCommandBuilder.kernelFor(4096))
        assertEquals("libuml-16k.so", UmlCommandBuilder.kernelFor(16384))
        assertNull(UmlCommandBuilder.kernelFor(65536))
        assertNull(UmlCommandBuilder.kernelFor(0))
    }

    @Test
    fun `supervisor runs under the launcher with the QEMU router and the files dir`() {
        val cmd = UmlCommandBuilder.build(params())
        assertEquals(
            listOf("$lib/libpodroid-launcher.so", "$lib/libpodroid-uml.so", "$lib/libqemu-system-aarch64.so", files.path, "--"),
            cmd.argv.take(5),
        )
        assertEquals("$files/uml-tmp", cmd.env["TMPDIR"])
    }

    @Test
    fun `normal boot args carry the guest contract`() {
        val args = kernelArgs(UmlCommandBuilder.build(params()))
        assertTrue(args.contains("mem=2048M"))
        assertTrue(args.contains("ncpus=4"))
        assertTrue(args.contains("podroid.ncpus=4"))
        assertTrue(args.contains("seccomp=on"))
        assertTrue(args.contains("initrd=$files/initrd.img"))
        assertTrue(args.contains("ubd0=$files/storage.img"))
        assertTrue(args.contains("ubd1r=$files/alpine-rootfs.squashfs"))
        assertTrue(args.contains("podroid.backend=uml"))
        assertTrue(args.contains("podroid.tty=ttyS0"))
        assertTrue(args.contains("podroid.x11.dpi=96"))
        assertTrue(args.contains("podroid.dns=10.0.0.1"))
        assertFalse(args.contains("podroid.probe=1"))
    }

    @Test
    fun `qemuExtraArgs never reaches the command`() {
        val cmd = UmlCommandBuilder.build(params(config = VmConfig(qemuExtraArgs = "-cpu foo")))
        assertFalse(cmd.argv.any { "foo" in it || it == "-cpu" })
        assertFalse(cmd.env.values.any { "foo" in it })
    }

    @Test
    fun `kernel extra cmdline is appended word by word, before the hostfs root`() {
        val args = kernelArgs(UmlCommandBuilder.build(params(config = VmConfig(kernelExtraCmdline = " loglevel=1  quiet "))))
        assertEquals(listOf("loglevel=1", "quiet", "hostfs=$files/uml-hostfs-disabled"), args.takeLast(3))
    }

    @Test
    fun `a user hostfs arg cannot override the confined root`() {
        val args = kernelArgs(UmlCommandBuilder.build(params(config = VmConfig(kernelExtraCmdline = "hostfs=/"))))
        assertEquals("hostfs=$files/uml-hostfs-disabled", args.last())
    }

    @Test
    fun `downloads shared - hostfs root is the downloads dir and the guest gets only an on flag`() {
        val args = kernelArgs(UmlCommandBuilder.build(params(downloads = "/storage/emulated/0/Download")))
        assertTrue(args.contains("hostfs=/storage/emulated/0/Download"))
        assertTrue(args.contains("podroid.downloads=1"))
        assertFalse(args.any { it.startsWith("podroid.downloads=/") })
        assertEquals(1, args.count { it.startsWith("hostfs=") })
    }

    @Test
    fun `downloads not shared - hostfs root is a path that never exists and no downloads flag`() {
        val args = kernelArgs(UmlCommandBuilder.build(params(downloads = null)))
        assertFalse(args.any { it.startsWith("podroid.downloads=") })
        assertEquals(listOf("hostfs=$files/uml-hostfs-disabled"), args.filter { it.startsWith("hostfs=") })
        assertEquals(File(files, "uml-hostfs-disabled"), UmlCommandBuilder.hostfsDisabledRoot(files))
    }

    @Test
    fun `inline rules become hostfwd options for the router`() {
        val rules = listOf(
            PortForwardRule(9922, 22),
            PortForwardRule(5901, 5901, "tcp", loopbackOnly = true),
        )
        val qargs = UmlCommandBuilder.build(params(rules = rules)).env["PODROID_UML_QEMU_ARGS"]
        assertEquals("ipv6=off,hostfwd=tcp::9922-:22,hostfwd=tcp:127.0.0.1:5901-:5901", qargs)
    }

    @Test
    fun `probe uses the scratch disk, no squashfs, no downloads, no forwards, no user cmdline`() {
        val cmd = UmlCommandBuilder.build(
            params(
                rules = listOf(PortForwardRule(9922, 22)),
                config = VmConfig(ramMb = 2048, cpus = 4, dnsServers = listOf("10.0.0.1"), kernelExtraCmdline = "init=/bin/sh quiet"),
                downloads = "/storage/emulated/0/Download",
                probe = true,
            ),
        )
        val args = kernelArgs(cmd)
        assertTrue(args.contains("ubd0=$files/uml-probe/uml-probe.img"))
        assertFalse(args.any { it.startsWith("ubd1") })
        assertFalse(cmd.argv.any { "alpine-rootfs.squashfs" in it })
        assertFalse(cmd.argv.any { "storage.img" in it })
        assertTrue(args.contains("podroid.probe=1"))
        assertTrue(args.contains("mem=256M"))
        assertTrue(args.contains("ncpus=4"))
        assertTrue(args.contains("podroid.ncpus=4"))
        assertTrue(args.contains("podroid.backend=uml"))
        assertTrue(args.contains("podroid.dns=10.0.0.1"))
        assertFalse(args.any { it.startsWith("podroid.downloads=") })
        // Self-contained: no hostfs share and none of the user's extra kernel args.
        assertEquals(listOf("hostfs=$files/uml-hostfs-disabled"), args.filter { it.startsWith("hostfs=") })
        assertFalse(args.contains("init=/bin/sh"))
        assertFalse(args.contains("quiet"))
        assertEquals("ipv6=off", cmd.env["PODROID_UML_QEMU_ARGS"])
        // Probe sockets and TMPDIR live in their own directory.
        assertEquals("$files/uml-probe", cmd.argv[3])
        assertEquals("$files/uml-probe", cmd.env["TMPDIR"])
        assertEquals("$lib/libqemu-system-aarch64.so", cmd.argv[2])
    }
}
