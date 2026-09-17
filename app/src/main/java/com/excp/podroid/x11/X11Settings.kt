/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * X11 viewer settings model + the pure resolution-selection policy. Pure
 * Kotlin (no Android deps) so the policy unit-tests without a device.
 */
package com.excp.podroid.x11

/** Pure size/rect types so VncClient + ResolutionPolicy unit-test on the JVM. */
data class VncSize(val w: Int, val h: Int)
data class VncRect(val x: Int, val y: Int, val w: Int, val h: Int)

enum class ResolutionMode { MATCH, PRESET, CUSTOM }
enum class ResolutionPreset(val w: Int, val h: Int) {
    R720P(1280, 720), R900P(1600, 900), R1080P(1920, 1080), R1440P(2560, 1440)
}
enum class TouchMode { DIRECT, TRACKPAD }
enum class RotationLock { AUTO, LANDSCAPE, PORTRAIT }

data class X11Settings(
    val resolutionMode: ResolutionMode = ResolutionMode.MATCH,
    val preset: ResolutionPreset = ResolutionPreset.R1080P,
    val customW: Int = 1280,
    val customH: Int = 720,
    val touchMode: TouchMode = TouchMode.DIRECT,
    val trackpadSensitivity: Float = 1.5f,
    val trackpadAccel: Boolean = true,
    val fullscreenDefault: Boolean = false,
    val rotationLock: RotationLock = RotationLock.AUTO,
    val showExtraKeys: Boolean = true,
    val dpi: Int = 96,
    val renderScale: Int = 100,
)

object ResolutionPolicy {
    private const val MIN = 320

    /** Even-rounds a dimension (RandR/virtio prefer even) and clamps to a minimum. */
    private fun norm(v: Int, min: Int) = (v - (v % 2)).coerceAtLeast(min)

    /** Target X-desktop size for the given settings + current viewport (device px). */
    fun target(s: X11Settings, viewportW: Int, viewportH: Int): VncSize = when (s.resolutionMode) {
        ResolutionMode.MATCH -> {
            val scale = s.renderScale / 100f
            VncSize(norm((viewportW * scale).toInt(), MIN), norm((viewportH * scale).toInt(), 240))
        }
        ResolutionMode.CUSTOM -> VncSize(norm(s.customW, MIN), norm(s.customH, 240))
        ResolutionMode.PRESET -> {
            val landscape = viewportW >= viewportH
            val long = maxOf(s.preset.w, s.preset.h)
            val short = minOf(s.preset.w, s.preset.h)
            if (landscape) VncSize(long, short) else VncSize(short, long)
        }
    }
}
