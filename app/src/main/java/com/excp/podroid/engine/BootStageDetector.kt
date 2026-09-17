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
        when {
            tail.contains("Ready!")                 -> { ready = true; bootStage.value = "Ready"; state.value = VmState.Running; onReady() }
            tail.contains("Almost ready")           -> bootStage.value = "Almost ready..."
            tail.contains("Starting SSH")           -> bootStage.value = "Starting SSH..."
            tail.contains("Configuring containers") -> bootStage.value = "Configuring containers..."
            tail.contains("Network found")          -> bootStage.value = "Network found"
            tail.contains("Loading kernel modules") -> bootStage.value = "Loading kernel modules..."
            tail.contains("Mounting storage")       -> bootStage.value = "Mounting storage..."
            tail.contains("Booting kernel")         -> bootStage.value = "Booting kernel..."
        }
    }

    private companion object {
        /**
         * The exact substrings the `when` above scans for. Used ONLY to size
         * the cross-feed [overlap]; the `when` remains the authoritative
         * matcher. Keep this list in sync with the `when` search strings —
         * the longest one drives how much prior text is re-scanned.
         */
        private val MARKERS = listOf(
            "Ready!" to "Ready",
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
