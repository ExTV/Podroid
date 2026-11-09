/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class HostMetricsTreeTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun proc(pid: Int, ppid: Int, uid: Int = 10123, exe: String? = null, argv: List<String> = emptyList()) {
        val dir = File(tmp.root, pid.toString()).apply { mkdirs() }
        File(dir, "stat").writeText("$pid (x) S $ppid $pid $pid 0 -1 0\n")
        File(dir, "status").writeText("Name:\tx\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        File(dir, "cmdline").writeText(argv.joinToString("") { "$it\u0000" })
        if (exe != null) Files.createSymbolicLink(File(dir, "exe").toPath(), File("/data/app/lib/$exe").toPath())
    }

    @Test
    fun `descendants include grandchildren and reparented children`() {
        proc(100, 1)
        proc(200, 100)
        proc(300, 200)
        proc(301, 200)
        proc(400, 1)
        assertEquals(setOf(100, 200, 300, 301), HostMetrics.descendantPids(100, tmp.root).toSet())
    }

    @Test
    fun `pidsMatching filters by uid and sees exe and argv`() {
        proc(10, 1, exe = "libuml-4k.so", argv = listOf("/data/app/lib/libuml-4k.so", "mem=512M"))
        proc(11, 1, uid = 0, exe = "libuml-4k.so")
        proc(12, 1, argv = listOf("/data/app/lib/libqemu-system-aarch64.so", "-M", "none"))
        proc(13, 1, argv = listOf("/data/app/lib/libqemu-system-aarch64.so", "-M", "virt"))
        val found = HostMetrics.pidsMatching(10123, tmp.root) { exe, argv ->
            exe == "libuml-4k.so" || (argv.firstOrNull()?.endsWith("aarch64.so") == true && argv.getOrNull(2) == "none")
        }
        assertEquals(setOf(10, 12), found.toSet())
    }

    private fun cpuProc(pid: Int, ppid: Int, utime: Long, stime: Long, cutime: Long = 0, cstime: Long = 0) {
        val dir = File(tmp.root, pid.toString()).apply { mkdirs() }
        // Fields after "(comm) ": state ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime stime cutime cstime ...
        File(dir, "stat").writeText("$pid (a b) S $ppid $pid $pid 0 -1 0 0 0 0 0 $utime $stime $cutime $cstime 20 0 1 0\n")
    }

    @Test
    fun `tree cpu ticks sum the root and every descendant, reaped children included`() {
        cpuProc(100, 1, utime = 10, stime = 5, cutime = 3, cstime = 2)
        cpuProc(200, 100, utime = 100, stime = 50)
        cpuProc(300, 200, utime = 7, stime = 1, cutime = 4, cstime = 0)
        cpuProc(400, 1, utime = 999, stime = 999)
        assertEquals(10L + 5 + 3 + 2 + 100 + 50 + 7 + 1 + 4, VmLoadSampler.readProcessTreeCpuTicks(100, tmp.root))
    }

    @Test
    fun `tree cpu ticks are null when the root is gone`() {
        cpuProc(200, 100, utime = 1, stime = 1)
        assertEquals(null, VmLoadSampler.readProcessTreeCpuTicks(100, tmp.root))
    }
}
