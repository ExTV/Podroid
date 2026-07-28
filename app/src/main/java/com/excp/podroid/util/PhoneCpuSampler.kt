/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Samples phone-wide CPU use from /proc/stat for Status charts.
 */
package com.excp.podroid.util

import android.os.SystemClock
import java.io.File

class PhoneCpuSampler(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val readTotals: () -> Pair<Long, Long>? = { readCpuTotals() },
) {
    private var lastIdle: Long? = null
    private var lastTotal: Long? = null
    private var lastTimeMs: Long? = null

    fun reset() {
        lastIdle = null
        lastTotal = null
        lastTimeMs = null
    }

    /** Phone CPU busy percent 0–100, or null until a second sample exists. */
    fun samplePercent(): Float? {
        val totals = readTotals() ?: return null
        val (idle, total) = totals
        val now = nowMs()
        val prevIdle = lastIdle
        val prevTotal = lastTotal
        lastIdle = idle
        lastTotal = total
        lastTimeMs = now
        if (prevIdle == null || prevTotal == null) return null

        val dIdle = (idle - prevIdle).coerceAtLeast(0)
        val dTotal = (total - prevTotal).coerceAtLeast(0)
        if (dTotal <= 0) return null
        val busy = 1.0 - (dIdle.toDouble() / dTotal.toDouble())
        return (busy * 100.0).toFloat().coerceIn(0f, 100f)
    }

    companion object {
        fun readCpuTotals(): Pair<Long, Long>? {
            return try {
                val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return null
                if (!line.startsWith("cpu ")) return null
                val parts = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (parts.size < 4) return null
                val idle = parts[3] + parts.getOrElse(4) { 0L } // idle + iowait
                val total = parts.sum()
                idle to total
            } catch (_: Exception) {
                null
            }
        }
    }
}
