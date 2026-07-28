/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.util

object MetricHistory {
    const val MAX_SAMPLES = 120 // ~1 min at 500ms

    fun append(history: List<Float>, value: Float, max: Int = MAX_SAMPLES): List<Float> =
        (history + value).takeLast(max)
}
