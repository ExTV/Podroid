/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Engine-agnostic boot-progress detector. Both engines feed it raw guest
 * console bytes; it sets [bootStage] flow markers it observes and flips
 * [state] to Running when "Ready!" appears. Each feed scans the newly-appended
 * region plus a short overlap carried from the previous feed, so a marker that
 * straddles a read boundary — or sits early in a single oversized chunk — is
 * still caught (see the history of detectBootStage in PodroidQemu pre-refactor).
 * One-shot: stops scanning after the first "Ready!" to keep [onReady] idempotent.
 */
class BootStageDetector(
    private val bootStage: MutableStateFlow<String>,
    private val state: MutableStateFlow<VmState>,
    private val onReady: () -> Unit,
) {
    private val buf = StringBuilder()
    private val maxKeep = 4096

    /**
     * Overlap carried across feeds so a marker split between two reads is still
     * matched. (len(longest marker) - 1) chars of the previous feed are
     * re-scanned alongside the new bytes — enough to reconstruct any marker
     * that begins in the prior feed and ends in this one.
     */
    private val overlap = MARKERS.maxOf { it.first.length } - 1

    /** Length of [buf] before the current feed appended — start of "new" text. */
    private var scannedLen = 0
    private var ready = false

    fun feed(bytes: ByteArray, len: Int) {
        if (ready) return
        // Latin-1 decode is byte-safe (1 byte → 1 char) and the ASCII subset
        // matches UTF-8 exactly, so our pure-ASCII markers still match.
        buf.append(String(bytes, 0, len, Charsets.ISO_8859_1))
        if (buf.length > maxKeep) {
            val dropped = buf.length - maxKeep
            buf.delete(0, dropped)
            scannedLen = (scannedLen - dropped).coerceAtLeast(0)
        }
        // Scan the new region plus an overlap into the previously-scanned text,
        // so a marker spanning the boundary is reconstructed. Scanning the
        // whole appended chunk (not a fixed 1024 tail) means a marker buried
        // early in one oversized read is no longer missed.
        val from = (scannedLen - overlap).coerceAtLeast(0)
        val tail = buf.substring(from)
        scannedLen = buf.length
        // First matching marker wins, in MARKERS order (same priority as the
        // old `when` chain). "Ready!" keeps its one-shot + state + onReady
        // side effects; every other marker only updates bootStage.
        val marker = MARKERS.firstOrNull { tail.contains(it.first) } ?: return
        if (marker.first == READY_MARKER) {
            ready = true
            bootStage.value = marker.second
            state.value = VmState.Running
            onReady()
        } else {
            bootStage.value = marker.second
        }
    }

    private companion object {
        private const val READY_MARKER = "Ready!"

        /**
         * The exact substrings [feed] scans for, in priority order (first
         * match wins) and used to size the cross-feed [overlap] (the longest
         * marker drives how much prior text is re-scanned).
         */
        val MARKERS = listOf(
            READY_MARKER to "Ready",
            "Almost ready" to "Almost ready...",
            "Starting SSH" to "Starting SSH...",
            "Configuring containers" to "Configuring containers...",
            "Network found" to "Network found",
            "Loading kernel modules" to "Loading kernel modules...",
            "Mounting storage" to "Mounting storage...",
            "Booting kernel" to "Booting kernel...",
        )
    }
}
