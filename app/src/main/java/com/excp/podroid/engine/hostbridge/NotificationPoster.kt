/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Seam between the host-bridge dispatcher and Android's NotificationManager,
 * so the dispatcher can be unit-tested with a fake.
 */
package com.excp.podroid.engine.hostbridge

interface NotificationPoster {
    /** True when the app may post notifications (Android 13+ runtime grant). */
    fun notificationsPermitted(): Boolean

    /**
     * Post (or, when [id] is non-null and matches an earlier call, replace) a
     * notification. Returns the notification id actually used.
     * @param priority one of HostProtocol.PRIO_* ; callers pass a validated value.
     */
    fun post(title: String?, body: String, priority: String, id: Int?): Int
}
