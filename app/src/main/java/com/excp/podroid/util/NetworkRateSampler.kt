/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * App UID TrafficStats deltas for Status network charts.
 */
package com.excp.podroid.util

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock

data class NetworkRateSample(
    val rxBytesPerSec: Float,
    val txBytesPerSec: Float,
    val rxTotalBytes: Long,
    val txTotalBytes: Long,
)

class NetworkRateSampler(
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val readBytes: () -> Pair<Long, Long>? = {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) null else rx to tx
    },
) {
    private var lastRx: Long? = null
    private var lastTx: Long? = null
    private var lastTimeMs: Long? = null

    fun reset() {
        lastRx = null
        lastTx = null
        lastTimeMs = null
    }

    fun sample(): NetworkRateSample? {
        val bytes = readBytes() ?: return null
        val (rx, tx) = bytes
        val now = nowMs()
        val prevRx = lastRx
        val prevTx = lastTx
        val prevTime = lastTimeMs
        lastRx = rx
        lastTx = tx
        lastTimeMs = now
        if (prevRx == null || prevTx == null || prevTime == null) return null
        val dt = (now - prevTime) / 1000.0
        if (dt <= 0.0) return null
        return NetworkRateSample(
            rxBytesPerSec = ((rx - prevRx).coerceAtLeast(0) / dt).toFloat(),
            txBytesPerSec = ((tx - prevTx).coerceAtLeast(0) / dt).toFloat(),
            rxTotalBytes = rx,
            txTotalBytes = tx,
        )
    }
}
