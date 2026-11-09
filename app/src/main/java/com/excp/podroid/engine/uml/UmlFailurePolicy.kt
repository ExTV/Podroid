/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * When a UML run counts as UML failing on this device. A DEMOTE switches the
 * device to the AUTO backend until the next app or system update (or until the
 * user retries); anything else is an ordinary stop or crash the user sees.
 */
package com.excp.podroid.engine.uml

enum class UmlExitClass { NOT_A_FAILURE, DEMOTE }

/**
 * Pure classification of how a UML run ended.
 *
 * NOT_A_FAILURE: any exit while the user is stopping it, no exit code at all
 * (the app process died, or the run never spawned), a clean exit 0, and
 * [UmlEngine.EXIT_ROUTER_DIED] with no fatal signature (the QEMU router
 * failed, not the UML kernel), and a SIGKILL with no fatal signature (Android's phantom-process killer or
 * lmkd: killed by Android is not UML failing).
 * DEMOTE: no `Ready!` before the boot timeout, a fatal signature in the
 * console tail (with any exit code, router-died included: a panicking kernel
 * can take the router down first), or any other nonzero exit.
 */
fun classifyUmlExit(
    reachedRunning: Boolean,
    userStopping: Boolean,
    exitCode: Int?,
    bootTimedOut: Boolean,
    consoleTail: String,
): UmlExitClass = when {
    userStopping -> UmlExitClass.NOT_A_FAILURE
    bootTimedOut && !reachedRunning -> UmlExitClass.DEMOTE
    exitCode == null -> UmlExitClass.NOT_A_FAILURE
    fatalSignature(consoleTail) != null -> UmlExitClass.DEMOTE
    exitCode == UmlEngine.EXIT_ROUTER_DIED -> UmlExitClass.NOT_A_FAILURE
    exitCode == 0 -> UmlExitClass.NOT_A_FAILURE
    exitCode == EXIT_SIGKILL -> UmlExitClass.NOT_A_FAILURE
    else -> UmlExitClass.DEMOTE
}

/** Short, untranslated cause stored with a demotion and shown in Diagnostics. */
fun umlDemotionReason(exitCode: Int?, bootTimedOut: Boolean, consoleTail: String): String =
    when {
        bootTimedOut -> "boot timeout"
        else -> fatalSignature(consoleTail) ?: "exit ${exitCode ?: "unknown"}"
    }

/**
 * A "busy" probe means UML processes already existed, so it never ran: it
 * says nothing about the device and must not be recorded as a failure.
 */
fun shouldPersistProbe(record: UmlProbeRecord): Boolean = record.reason != UmlProbe.REASON_BUSY

/**
 * Whether Settings offers "Try UML again": after a failed probe as well as a
 * demotion, since both persist until an update and the button is the only
 * other way to clear them.
 */
fun umlRetryOffered(status: UmlStatus): Boolean =
    status == UmlStatus.FAILED || status == UmlStatus.DEMOTED

/** The probe/demotion key for this device, app build and bundled kernel. */
fun umlProbeKey(buildFingerprint: String, versionCode: Long, kernelName: String, kernelSize: Long) =
    UmlProbeKey(buildFingerprint, versionCode, "$kernelName:$kernelSize")

/** Shell-style exit status of a process killed by SIGKILL (128 + 9). */
private const val EXIT_SIGKILL = 128 + 9

private fun fatalSignature(text: String): String? =
    UmlProbe.FATAL_SIGNATURES.firstOrNull { it in text }
