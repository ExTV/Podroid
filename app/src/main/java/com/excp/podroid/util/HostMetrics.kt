/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Host-side resource snapshots for the Status screen.
 */
package com.excp.podroid.util

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import android.system.Os
import java.io.File
import kotlin.math.roundToInt

data class HostMetricsSnapshot(
    val phoneTotalRamMb: Long,
    val phoneAvailRamMb: Long,
    val phoneCpuCores: Int,
    val loadAvg1: Float?,
    val loadAvg5: Float?,
    val loadAvg15: Float?,
    val phoneStorageTotalGb: Double,
    val phoneStorageAvailGb: Double,
    val vmDiskImageBytes: Long,
    val emulatorRssMb: Long?,
)

object HostMetrics {
    fun snapshot(
        context: Context,
        storageImg: File,
        emulatorRssMb: Long?,
    ): HostMetricsSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)

        val stat = StatFs(context.filesDir.absolutePath)
        val load = readLoadAvg()

        return HostMetricsSnapshot(
            phoneTotalRamMb = mem.totalMem / (1024 * 1024),
            phoneAvailRamMb = mem.availMem / (1024 * 1024),
            phoneCpuCores = DeviceResourcePolicy.deviceCpuCount(),
            loadAvg1 = load?.first,
            loadAvg5 = load?.second,
            loadAvg15 = load?.third,
            phoneStorageTotalGb = stat.totalBytes / (1024.0 * 1024 * 1024),
            phoneStorageAvailGb = stat.availableBytes / (1024.0 * 1024 * 1024),
            vmDiskImageBytes = if (storageImg.isFile) diskFootprintBytes(storageImg) else 0L,
            emulatorRssMb = emulatorRssMb,
        )
    }

    fun readLoadAvg(): Triple<Float, Float, Float>? = try {
        val parts = File("/proc/loadavg").readText().trim().split(Regex("\\s+"))
        if (parts.size < 3) null
        else Triple(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat())
    } catch (_: Exception) {
        null
    }

    /**
     * Real on-disk footprint of a possibly sparse file: st_blocks * 512.
     * storage.img is created sparse via RandomAccessFile.setLength(), so
     * File.length() reports the full apparent capacity (a permanently 100%-full
     * bar); the allocated block count reflects how much the VM disk actually
     * occupies on the phone. Falls back to the apparent length if stat fails.
     */
    fun diskFootprintBytes(file: File): Long = try {
        Os.stat(file.absolutePath).st_blocks * 512L
    } catch (_: Exception) {
        file.length()
    }

    /**
     * PID of a direct child of [parentPid] whose argv[0] file name is one of
     * [executableNames], or null. Android's java.lang.Process has no pid()
     * (a Java 9 API absent from libcore), so the emulator's PID is found by
     * scanning /proc instead. Entries that vanish or are unreadable mid-scan
     * are skipped: processes exit while we walk the directory.
     */
    fun childPidByExecutable(
        parentPid: Int,
        executableNames: Set<String>,
        procRoot: File = File("/proc"),
    ): Int? {
        val entries = procRoot.listFiles() ?: return null
        for (dir in entries) {
            val pid = dir.name.toIntOrNull() ?: continue
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
            // "pid (comm) state ppid ..."; comm may contain spaces or ')'.
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
            if (ppid != parentPid) continue
            val argv0 = runCatching { File(dir, "cmdline").readText() }.getOrNull()
                ?.substringBefore('\u0000') ?: continue
            if (argv0.substringAfterLast('/') in executableNames) return pid
        }
        return null
    }

    /** [root] followed by every descendant, found by walking /proc ppid links. */
    fun descendantPids(root: Int, procRoot: File = File("/proc")): List<Int> {
        val parent = HashMap<Int, Int>()
        procRoot.listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
                ?.let { parent[pid] = it }
        }
        val out = mutableListOf(root)
        var i = 0
        while (i < out.size) {
            val cur = out[i++]
            parent.forEach { (pid, pp) -> if (pp == cur) out += pid }
        }
        return out
    }

    /**
     * PIDs of processes owned by real [uid] for which [match] holds, given the
     * file name of /proc/pid/exe (null when unreadable) and the argv from
     * /proc/pid/cmdline. Zombies have an empty cmdline and no exe, so they
     * only match a predicate that accepts that.
     */
    fun pidsMatching(
        uid: Int,
        procRoot: File = File("/proc"),
        match: (exeName: String?, argv: List<String>) -> Boolean,
    ): List<Int> {
        val entries = procRoot.listFiles() ?: return emptyList()
        val out = mutableListOf<Int>()
        for (dir in entries) {
            val pid = dir.name.toIntOrNull() ?: continue
            val owner = runCatching {
                File(dir, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Uid:") }
                        ?.substringAfter("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
                }
            }.getOrNull() ?: continue
            if (owner != uid) continue
            val exe = runCatching {
                java.nio.file.Files.readSymbolicLink(File(dir, "exe").toPath()).toString().substringAfterLast('/')
            }.getOrNull()
            val argv = runCatching { File(dir, "cmdline").readText() }.getOrNull()
                ?.split('\u0000')?.filter { it.isNotEmpty() } ?: emptyList()
            if (match(exe, argv)) out += pid
        }
        return out
    }

    private fun statFields(pid: Int, procRoot: File): List<String>? =
        runCatching { File(procRoot, "$pid/stat").readText() }.getOrNull()
            ?.substringAfterLast(')')?.trim()?.split(' ')

    /** Parent PID from /proc/[pid]/stat, or null when unreadable. */
    fun parentPid(pid: Int, procRoot: File = File("/proc")): Int? =
        statFields(pid, procRoot)?.getOrNull(1)?.toIntOrNull()

    /** Process group ID from /proc/[pid]/stat, or null when unreadable. */
    fun processGroupId(pid: Int, procRoot: File = File("/proc")): Int? =
        statFields(pid, procRoot)?.getOrNull(2)?.toIntOrNull()

    /** argv from /proc/[pid]/cmdline; empty for zombies and unreadable entries. */
    fun processArgv(pid: Int, procRoot: File = File("/proc")): List<String> =
        runCatching { File(procRoot, "$pid/cmdline").readText() }.getOrNull()
            ?.split('\u0000')?.filter { it.isNotEmpty() } ?: emptyList()

    /** VmRSS from /proc/[pid]/status, in megabytes. */
    fun processVmRssMb(pid: Int): Long? {
        val kb = try {
            File("/proc/$pid/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }
                    ?.substringAfter("VmRSS:")
                    ?.trim()
                    ?.substringBefore(" ")
                    ?.toLongOrNull()
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return (kb / 1024.0).roundToInt().toLong()
    }

    fun formatGb(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return if (gb >= 10) "${gb.roundToInt()} GB" else String.format("%.1f GB", gb)
    }

    fun formatGb(gb: Double): String =
        if (gb >= 10) "${gb.roundToInt()} GB" else String.format("%.1f GB", gb)

    fun formatMb(mb: Long): String =
        if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

    fun percent(used: Long, total: Long): Float =
        if (total <= 0) 0f else (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    fun percent(used: Double, total: Double): Float =
        if (total <= 0.0) 0f else (used / total).toFloat().coerceIn(0f, 1f)
}
