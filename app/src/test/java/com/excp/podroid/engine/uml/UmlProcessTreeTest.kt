/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.uml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UmlProcessTreeTest {

    private val lib = "/data/app/x/lib/arm64"

    @Test
    fun `uml kernel, stubs and supervisor match by exe or argv0`() {
        assertTrue(UmlProcessTree.isUml("libuml-16k.so", emptyList()))
        assertTrue(UmlProcessTree.isUml("libumlstub-4k.so", emptyList()))
        assertTrue(UmlProcessTree.isUml(null, listOf("$lib/libpodroid-uml.so", "$lib/libqemu-system-aarch64.so")))
        assertTrue(UmlProcessTree.isUml(null, listOf("$lib/libpodroid-launcher.so", "$lib/libpodroid-uml.so")))
    }

    @Test
    fun `the router matches but the QEMU VM does not`() {
        assertTrue(UmlProcessTree.isUml("libqemu-system-aarch64.so", listOf("$lib/libqemu-system-aarch64.so", "-M", "none", "-nodefaults")))
        assertFalse(UmlProcessTree.isUml("libqemu-system-aarch64.so", listOf("$lib/libqemu-system-aarch64.so", "-M", "virt,gic-version=3")))
        assertFalse(UmlProcessTree.isUml(null, listOf("$lib/libpodroid-launcher.so", "$lib/libqemu-system-aarch64.so", "-M", "virt")))
    }

    @Test
    fun `zombies and unrelated processes do not match`() {
        assertFalse(UmlProcessTree.isUml(null, emptyList()))
        assertFalse(UmlProcessTree.isUml("app_process64", listOf("com.excp.podroid")))
    }

    // App pid 100, pgid 100. A running VM: supervisor 200 (workdir files), kernel 201
    // (own group), router 202 and stub 203 in 201's group. A probe: supervisor 300
    // (workdir files/uml-probe), kernel 301 leading group 301, router 302, stub 303.
    private val files = "/data/user/0/p/files"
    private val probeDir = "$files/uml-probe"
    private val vm = listOf(
        UmlProcessTree.Proc(200, 100, 100, listOf("$lib/libpodroid-uml.so", "$lib/libqemu-system-aarch64.so", files, "--")),
        UmlProcessTree.Proc(201, 200, 201, listOf("$lib/libuml-4k.so", "ubd0=$files/storage.img")),
        UmlProcessTree.Proc(202, 200, 201, listOf("$lib/libqemu-system-aarch64.so", "-M", "none", "-qmp", "unix:$files/qmp.sock,server,nowait")),
        UmlProcessTree.Proc(203, 201, 201, emptyList()),
    )
    private val probe = listOf(
        UmlProcessTree.Proc(300, 100, 100, listOf("$lib/libpodroid-uml.so", "$lib/libqemu-system-aarch64.so", probeDir, "--")),
        UmlProcessTree.Proc(301, 300, 301, listOf("$lib/libuml-4k.so")),
        UmlProcessTree.Proc(302, 300, 301, listOf("$lib/libqemu-system-aarch64.so", "-M", "none", "-qmp", "unix:$probeDir/qmp.sock,server,nowait")),
        UmlProcessTree.Proc(303, 301, 301, emptyList()),
    )

    @Test
    fun `probe sweep takes only the probe's tree, never a VM sharing the app group`() {
        assertEquals(listOf(300, 301, 302, 303), UmlProcessTree.ownedBy(vm + probe, probeDir, emptySet(), 100))
    }

    @Test
    fun `orphaned probe stubs are still found through the kernel's group`() {
        val orphans = listOf(UmlProcessTree.Proc(302, 1, 301, probe[2].argv), UmlProcessTree.Proc(303, 1, 301, emptyList()))
        assertEquals(listOf(302, 303), UmlProcessTree.ownedBy(vm + orphans, probeDir, emptySet(), 100))
    }

    @Test
    fun `pre-existing pids are never selected`() {
        assertEquals(listOf(301, 302), UmlProcessTree.ownedBy(probe, probeDir, setOf(300, 303), 100))
    }

    @Test
    fun `nothing is selected when only a VM runs`() {
        assertEquals(emptyList<Int>(), UmlProcessTree.ownedBy(vm, probeDir, emptySet(), 100))
    }
}
