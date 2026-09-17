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

    private fun readLoadAvg(): Triple<Float, Float, Float>? = try {
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
    private fun diskFootprintBytes(file: File): Long = try {
        Os.stat(file.absolutePath).st_blocks * 512L
    } catch (_: Exception) {
        file.length()
    }

    fun processPid(process: Process): Int? = try {
        val m = Process::class.java.getMethod("pid")
        (m.invoke(process) as Int).takeIf { it > 0 }
    } catch (_: Exception) {
        null
    }

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
