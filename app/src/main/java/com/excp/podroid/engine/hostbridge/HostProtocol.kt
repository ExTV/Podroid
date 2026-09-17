/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Shared wire helpers for the guest -> Android host bridge. Free-text fields
 * (notification title/body, the forward listing, error messages) are standard
 * base64 so UTF-8, spaces, and newlines survive the line-oriented protocol.
 * java.util.Base64 (not android.util.Base64) keeps the dispatcher unit-testable.
 */
package com.excp.podroid.engine.hostbridge

import java.util.Base64

object HostProtocol {
    const val PRIO_LOW = "low"
    private const val PRIO_NORMAL = "normal"
    const val PRIO_HIGH = "high"
    val VALID_PRIORITIES = setOf(PRIO_LOW, PRIO_NORMAL, PRIO_HIGH)

    fun enc(s: String): String =
        Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    /** Decodes base64; returns null on malformed input rather than throwing. */
    fun dec(s: String): String? = try {
        String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun ok(payload: String? = null): String = if (payload == null) "OK" else "OK $payload"
    fun err(message: String): String = "ERR ${enc(message)}"
}
