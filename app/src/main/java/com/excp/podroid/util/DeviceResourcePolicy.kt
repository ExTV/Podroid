/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Device-aware VM resource recommendations for load-balance mode.
 */
package com.excp.podroid.util

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs

object DeviceResourcePolicy {
    val RAM_OPTIONS_MB = listOf(512, 1024, 2048, 4096, 6144, 8192, 12288, 16384)
    val CPU_OPTIONS = listOf(1, 2, 4, 6, 8)
    /** 0 = unlimited */
    val BANDWIDTH_OPTIONS_MBPS = listOf(0, 10, 50, 100, 500)
    val STORAGE_OPTIONS_GB = listOf(2, 4, 8, 16, 32, 64, 128, 256, 512)

    data class BalancedProfile(
        val ramMb: Int,
        val cpus: Int,
        val storageGb: Int,
        val bandwidthMbps: Int,
    )

    fun deviceTotalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    fun deviceCpuCount(): Int = Runtime.getRuntime().availableProcessors()

    fun deviceAvailableStorageGb(context: Context): Int {
        val stat = StatFs(context.filesDir.absolutePath)
        return (stat.availableBytes / (1024L * 1024 * 1024)).toInt()
    }

    /** RAM options that fit the device, leaving ~3 GB for Android. Always at
     * least the first three options, so small devices still get a usable list. */
    fun ramOptionsFor(totalRamMb: Long): List<Int> {
        val cap = totalRamMb - 3072
        val filtered = RAM_OPTIONS_MB.filter { it <= cap }
        return if (filtered.size >= 3) filtered else RAM_OPTIONS_MB.take(3)
    }

    /** Storage options that fit the device's free app-private space. Always at
     * least the first three options, so small devices still get a usable list. */
    fun storageOptionsFor(availableGb: Int): List<Int> {
        val filtered = STORAGE_OPTIONS_GB.filter { it <= availableGb }
        return if (filtered.size >= 3) filtered else STORAGE_OPTIONS_GB.take(3)
    }

    fun nearestAtMost(options: List<Int>, target: Int): Int =
        options.filter { it <= target }.maxOrNull() ?: options.first()

    fun balancedRamMb(totalRamMb: Long): Int {
        // Leave headroom for Android; give the VM ~35% of physical RAM.
        val target = (totalRamMb * 0.35).toInt()
        return nearestAtMost(RAM_OPTIONS_MB, target)
    }

    fun balancedCpus(cpuCount: Int): Int {
        // Half the host cores, capped at 4. Battery and thermals are the obvious
        // reason; the measured one is that the cap is also simply faster. On an
        // 8-core phone under TCG, 8 vCPUs came out slower than 4 on every metric
        // (boot, sha256, xz, bc, and the node workloads), reproduced across two runs.
        val target = (cpuCount / 2).coerceAtLeast(1).coerceAtMost(4)
        return nearestAtMost(CPU_OPTIONS, target)
    }

    fun balancedStorageGb(availableGb: Int): Int {
        // Use up to ~25% of free app-private space, at least 2 GB.
        val target = (availableGb * 0.25).toInt().coerceAtLeast(2)
        return nearestAtMost(STORAGE_OPTIONS_GB, target)
    }

    fun balancedBandwidthMbps(): Int = 100

    fun balancedProfile(context: Context): BalancedProfile = BalancedProfile(
        ramMb = balancedRamMb(deviceTotalRamMb(context)),
        cpus = balancedCpus(deviceCpuCount()),
        storageGb = balancedStorageGb(deviceAvailableStorageGb(context)),
        bandwidthMbps = balancedBandwidthMbps(),
    )
}
