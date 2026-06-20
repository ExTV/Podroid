/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Persists port forwarding rules in DataStore.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.excp.podroid.x11.X11Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single port forwarding rule.
 * @param hostPort Port on the Android device
 * @param guestPort Port inside the VM
 * @param protocol "tcp" or "udp"
 * @param loopbackOnly Bind the host listener to 127.0.0.1 instead of 0.0.0.0.
 *   Set for the implicit VNC/audio forwards (the in-app viewer dials loopback),
 *   so an unauthenticated X session + raw PCM aren't exposed to the whole LAN.
 *   NOT serialized — implicit rules are never persisted; user-created rules keep
 *   the default (0.0.0.0) so they remain reachable from a PC.
 */
data class PortForwardRule(
    val hostPort: Int,
    val guestPort: Int,
    val protocol: String = "tcp",
    val loopbackOnly: Boolean = false,
) {
    // serialize/deserialize intentionally omit loopbackOnly: persistence format
    // is unchanged (sacred) and only user rules (loopbackOnly=false) persist.
    fun serialize(): String = "$protocol:$hostPort:$guestPort"

    companion object {
        private val VALID_PROTOCOLS = setOf("tcp", "udp")

        fun deserialize(s: String): PortForwardRule? {
            val parts = s.split(":")
            if (parts.size != 3) return null
            val proto = parts[0]
            if (proto !in VALID_PROTOCOLS) return null
            val host = parts[1].toIntOrNull() ?: return null
            val guest = parts[2].toIntOrNull() ?: return null
            if (host !in 1..65535 || guest !in 1..65535) return null
            return PortForwardRule(host, guest, proto)
        }
    }
}

/**
 * Removes any existing entry in [current] that shares the same
 * (hostPort, protocol) key as [newRule], then adds [newRule].
 *
 * The engine and UI treat (hostPort, protocol) as a unique key — two rules
 * with the same host port and protocol would produce duplicate QEMU hostfwd
 * arguments and a duplicate Compose key crash.
 */
internal fun deduplicatePortForwards(
    current: Set<String>,
    newRule: PortForwardRule,
): Set<String> {
    val filtered = current.filterTo(mutableSetOf()) { serialized ->
        val existing = PortForwardRule.deserialize(serialized)
        // Keep entries that differ in either hostPort or protocol.
        existing == null ||
            existing.hostPort != newRule.hostPort ||
            existing.protocol != newRule.protocol
    }
    filtered.add(newRule.serialize())
    return filtered
}

@Singleton
class PortForwardRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private val KEY_PORT_FORWARDS = stringSetPreferencesKey("port_forwards")

        /**
         * Host ports owned by the implicit, loopback-bound X11 display/audio
         * forwards (injected at launch, never persisted). A user rule on one of
         * these would shadow the implicit rule and bind 0.0.0.0, exposing the
         * no-auth X session + raw PCM to the whole LAN. They are rejected on add
         * and filtered from reads here — the single chokepoint feeding the launch
         * snapshot, the EngineHolder live-diff, and the Settings UI — so a stale
         * rule persisted by an older build can never be surfaced or applied.
         * SSH (9922) is intentionally LAN-reachable and is NOT reserved.
         */
        val RESERVED_HOST_PORTS = setOf(X11Constants.VNC_PORT, X11Constants.AUDIO_PORT)
    }

    val rules: Flow<List<PortForwardRule>> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            prefs[KEY_PORT_FORWARDS]
                ?.mapNotNull { PortForwardRule.deserialize(it) }
                ?.filter { it.hostPort !in RESERVED_HOST_PORTS }
                ?.sortedWith(compareBy({ it.hostPort }, { it.protocol }))
                ?: emptyList()
        }
        .distinctUntilChanged()

    suspend fun addRule(rule: PortForwardRule) {
        if (rule.hostPort in RESERVED_HOST_PORTS) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PORT_FORWARDS] ?: emptySet()
            prefs[KEY_PORT_FORWARDS] = deduplicatePortForwards(current, rule)
        }
    }

    suspend fun removeRule(rule: PortForwardRule) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_PORT_FORWARDS]?.toMutableSet() ?: return@edit
            current.remove(rule.serialize())
            prefs[KEY_PORT_FORWARDS] = current
        }
    }

    suspend fun getRulesSnapshot(): List<PortForwardRule> =
        context.dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { prefs ->
                prefs[KEY_PORT_FORWARDS]
                    ?.mapNotNull { PortForwardRule.deserialize(it) }
                    ?.filter { it.hostPort !in RESERVED_HOST_PORTS }
                    ?: emptyList()
            }.first()
}
