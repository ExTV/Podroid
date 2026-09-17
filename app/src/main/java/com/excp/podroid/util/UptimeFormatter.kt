/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.util

import android.content.Context
import com.excp.podroid.R

/** Shared "Xh Ym" / "Xm Ys" / "Xs" uptime duration formatting for Home and Status. */
object UptimeFormatter {
    fun format(context: Context, totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> context.getString(R.string.uptime_hours_minutes, hours, minutes)
            minutes > 0 -> context.getString(R.string.uptime_minutes_seconds, minutes, seconds)
            else -> context.getString(R.string.uptime_seconds, seconds)
        }
    }
}
