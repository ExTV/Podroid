/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.uml

import com.excp.podroid.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Identity of the build a probe or demotion applies to; any change invalidates it. */
data class UmlProbeKey(val buildFingerprint: String, val versionCode: Long, val kernelId: String)

data class UmlProbeRecord(val key: UmlProbeKey, val passed: Boolean, val reason: String)

data class UmlDemotion(val key: UmlProbeKey, val reason: String, val atMs: Long)

/** A demotion for the current key wins, then a probe for the current key, else NOT_PROBED. */
fun umlStatus(current: UmlProbeKey, probe: UmlProbeRecord?, demotion: UmlDemotion?): UmlStatus = when {
    demotion != null && demotion.key == current -> UmlStatus.DEMOTED
    probe != null && probe.key == current ->
        if (probe.passed) UmlStatus.PASSED else UmlStatus.FAILED
    else -> UmlStatus.NOT_PROBED
}

/** What the UI and the control interface show about UML: the status and, when there is one, why. */
data class UmlInfo(val status: UmlStatus, val reason: String?)

/** Demotion reason wins (DEMOTED), then the failed probe's reason (FAILED); PASSED/NOT_PROBED carry none. */
fun umlInfo(current: UmlProbeKey, probe: UmlProbeRecord?, demotion: UmlDemotion?): UmlInfo {
    val status = umlStatus(current, probe, demotion)
    val reason = when (status) {
        UmlStatus.DEMOTED -> demotion?.reason
        UmlStatus.FAILED -> probe?.reason
        else -> null
    }
    return UmlInfo(status, reason?.takeIf { it.isNotBlank() })
}

private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

private fun unb64(s: String): String? =
    try {
        String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

// Every free-text field is base64 so a '|' in it cannot shift the field boundaries.
private fun encodeKey(k: UmlProbeKey) = "${b64(k.buildFingerprint)}|${k.versionCode}|${b64(k.kernelId)}"

private fun decodeKey(f: List<String>): UmlProbeKey? {
    val fp = unb64(f[0]) ?: return null
    val vc = f[1].toLongOrNull() ?: return null
    val kid = unb64(f[2]) ?: return null
    return UmlProbeKey(fp, vc, kid)
}

internal fun encodeProbeRecord(r: UmlProbeRecord) = "${encodeKey(r.key)}|${r.passed}|${b64(r.reason)}"

internal fun decodeProbeRecord(s: String): UmlProbeRecord? {
    val f = s.split('|')
    if (f.size != 5) return null
    val key = decodeKey(f) ?: return null
    val passed = when (f[3]) { "true" -> true; "false" -> false; else -> return null }
    val reason = unb64(f[4]) ?: return null
    return UmlProbeRecord(key, passed, reason)
}

internal fun encodeDemotion(d: UmlDemotion) = "${encodeKey(d.key)}|${d.atMs}|${b64(d.reason)}"

internal fun decodeDemotion(s: String): UmlDemotion? {
    val f = s.split('|')
    if (f.size != 5) return null
    val key = decodeKey(f) ?: return null
    val at = f[3].toLongOrNull() ?: return null
    val reason = unb64(f[4]) ?: return null
    return UmlDemotion(key, reason, at)
}

@Singleton
class UmlProbeStore @Inject constructor(private val settings: SettingsRepository) {
    val probe: Flow<UmlProbeRecord?> = settings.umlProbeRecord.map { it?.let(::decodeProbeRecord) }
    val demotion: Flow<UmlDemotion?> = settings.umlDemotion.map { it?.let(::decodeDemotion) }

    suspend fun record(r: UmlProbeRecord) = settings.setUmlProbeRecord(encodeProbeRecord(r))
    suspend fun demote(d: UmlDemotion) = settings.setUmlDemotion(encodeDemotion(d))
    suspend fun clearDemotion() = settings.setUmlDemotion(null)
    suspend fun clearProbe() = settings.setUmlProbeRecord(null)
}
