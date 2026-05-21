/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Tiny shared helper for resolving the device's primary IPv4 address.
 * Used by both PodroidService (when launching QEMU) and the Settings UI
 * (to display "Phone IP: …" next to port-forward rules).
 */
package com.excp.podroid.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object NetworkUtils {
    /**
     * The address users would `ssh root@<this> -p 9922` to, from another
     * device on the same LAN.
     *
     * Picks by transport preference rather than by address-pattern matching:
     * WiFi first (LAN), then Ethernet (USB-C dongles), then Cellular (hotspot
     * or LTE), skipping VPN tunnels. No address-range literals — selection is
     * a policy on transports, so it stays correct whatever network the user
     * is on.
     */
    fun localIpv4(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.let(::firstIpv4ByTransportPreference) ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private val TRANSPORT_PREFERENCE = intArrayOf(
        NetworkCapabilities.TRANSPORT_WIFI,
        NetworkCapabilities.TRANSPORT_ETHERNET,
        NetworkCapabilities.TRANSPORT_CELLULAR,
    )

    private fun firstIpv4ByTransportPreference(cm: ConnectivityManager): String? {
        // Prefer the active (default-route) network first — it's the address
        // that traffic actually leaves through, not just any connected interface.
        cm.activeNetwork?.let { active ->
            val caps = cm.getNetworkCapabilities(active)
            if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                val link = cm.getLinkProperties(active)
                if (link != null) {
                    for (la in link.linkAddresses) {
                        val addr = la.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        }
        // Fall back to transport-preference scan for edge cases (e.g. active network
        // has only IPv6, but a secondary WiFi interface has an IPv4 address).
        for (preferred in TRANSPORT_PREFERENCE) {
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasTransport(preferred)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = cm.getLinkProperties(net) ?: continue
                for (la in link.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        }
        return null
    }
}
