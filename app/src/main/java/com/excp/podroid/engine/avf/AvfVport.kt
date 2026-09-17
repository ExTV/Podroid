/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Maps a port-forward rule to its guest vsock rendezvous port.
 */
package com.excp.podroid.engine.avf

import com.excp.podroid.data.repository.PortForwardRule

internal object AvfVport {
    /**
     * UDP vports are offset so a TCP and a UDP rule on the same host port don't
     * collide on the single vsock port space. TCP stays in [1, 65535]; UDP lands
     * in [65537, 131071]. The guest binds whatever vport it's told, so the offset
     * lives only here.
     */
    private const val UDP_OFFSET = 0x10000  // 65536

    fun forRule(rule: PortForwardRule): Int =
        if (rule.protocol == "udp") rule.hostPort + UDP_OFFSET else rule.hostPort
}
