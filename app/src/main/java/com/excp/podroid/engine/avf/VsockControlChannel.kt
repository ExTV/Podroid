/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Line-oriented control channel from the Android side to podroid-vsock-agent.
 * Single connection — the guest serializes commands so we never need request
 * IDs or response correlation. All sends are best-effort: failure logs and
 * drops the bytes; the caller (engine) decides whether to surface that in UI.
 *
 * Wire format (LF-terminated ASCII):
 *   RESIZE <rows> <cols>
 *   ADD    <vport> <tcp|udp> <host> <gport>
 *   REMOVE <vport>
 *   PING                      → agent replies "PONG\n" (we ignore the reply)
 *   SYNC                      → agent runs sync(2), replies "SYNCED\n"
 */
package com.excp.podroid.engine.avf

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.PrintWriter

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class VsockControlChannel(
    private val vm: Any,
    private val scope: CoroutineScope,
) {
    companion object {
        const val CTL_PORT: Long = 9100L
        private const val TAG = "VsockControlChannel"
        private const val MAX_ATTEMPTS = 30
        // Cap the pre-connect queue. Without a cap, every RESIZE (a keyboard
        // slide fires ~25) appended forever once the connect gave up — an
        // unbounded leak that also replayed stale resizes if a connect
        // eventually succeeded. RESIZE is coalesced (only the latest matters),
        // so the cap mainly bounds ADD/REMOVE churn before first connect.
        private const val MAX_PENDING = 64
    }

    private var pfd: ParcelFileDescriptor? = null
    private var writer: PrintWriter? = null
    // @Volatile: assigned in open()/reconnect() off one thread, cancelled in the
    // @Synchronized close() on another — without it a stale null could skip the cancel.
    @Volatile private var connectJob: Job? = null
    @Volatile private var closed = false
    /** Set once the retry loop exhausts; further sends are dropped, not queued. */
    private var gaveUp = false
    private var warnedUnavailable = false

    /**
     * Commands written before the agent connection is established. Drained
     * in-order once open() succeeds. Without this, the first ADDs fired from
     * AvfEngine.detector.onReady silently drop because open()'s retry
     * coroutine hasn't run yet (same-tick scheduling race).
     */
    private val pending = mutableListOf<String>()

    /**
     * Open the connection. The guest agent may not be ready immediately after
     * VM Running fires (OpenRC sequencing), so we retry on a short backoff.
     * Commands enqueued via sendResize/addForward/removeForward before connect
     * succeeds are buffered and flushed in order on first success.
     */
    fun open() {
        connectJob = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (!closed && attempt < MAX_ATTEMPTS) {
                val ok = runCatching {
                    val p = AvfReflect.connectVsock(vm, CTL_PORT)
                    val w = PrintWriter(ParcelFileDescriptor.AutoCloseOutputStream(p), /* autoFlush */ true)
                    synchronized(this@VsockControlChannel) {
                        if (closed) { runCatching { p.close() }; return@launch }
                        pfd = p
                        writer = w
                        // Drain anything queued before we got connected. writeLine
                        // checks checkError() after each println; a mid-drain dead
                        // connection tears down + re-arms (and stops the drain) so we
                        // don't spin writing into a broken pipe. Snapshot first so the
                        // re-arm path can't mutate `pending` while we iterate it.
                        val toDrain = pending.toList()
                        pending.clear()
                        for (line in toDrain) {
                            if (!writeLine(w, line)) break
                        }
                    }
                    Log.d(TAG, "control channel connected after ${attempt + 1} attempts")
                    true
                }.getOrElse { false }
                if (ok) return@launch
                attempt += 1
                kotlinx.coroutines.delay(500)
            }
            // Connect never succeeded: stop queueing so later sends don't grow
            // `pending` without bound. Drop the buffer; it can never be drained.
            synchronized(this@VsockControlChannel) {
                gaveUp = true
                pending.clear()
            }
            Log.w(TAG, "control channel: gave up after $attempt attempts")
        }
    }

    /**
     * Write one line and check for a silently-swallowed I/O error. PrintWriter
     * never throws on a dead socket — it only flips an internal flag exposed via
     * checkError(). Without this poll a dropped guest agent meant every
     * RESIZE/ADD/REMOVE was lost forever with no signal and no reconnect. On
     * error we tear down the writer/pfd and re-arm the connect loop so a later
     * command reconnects. Returns true if the write looks healthy.
     *
     * Caller must hold the instance monitor (both call sites are @Synchronized
     * or run inside `synchronized(this)`).
     */
    private fun writeLine(w: PrintWriter, line: String): Boolean {
        w.println(line)
        if (!w.checkError()) return true
        Log.w(TAG, "control channel write error on '$line'; tearing down + reconnecting")
        reconnect()
        return false
    }

    /**
     * Tear down a dead connection and re-arm the retry loop so the next command
     * reconnects. Idempotent against close(): a closed channel never reconnects.
     * The in-flight line that triggered this is dropped (RESIZE self-heals on the
     * next geometry change; ADD/REMOVE are re-dispatched by the engine layer).
     * Caller must hold the instance monitor.
     */
    private fun reconnect() {
        if (closed) return
        runCatching { writer?.close() }
        runCatching { pfd?.close() }
        writer = null
        pfd = null
        // Clear gaveUp so the fresh open() actually retries instead of immediately
        // dropping; cancel any prior connect coroutine before launching a new one.
        gaveUp = false
        warnedUnavailable = false
        runCatching { connectJob?.cancel() }
        open()
    }

    @Synchronized private fun sendOrQueue(line: String) {
        val w = writer
        when {
            w != null -> writeLine(w, line)
            closed || gaveUp -> {
                // Channel will never connect (gave up) or is shutting down:
                // drop with a single warning instead of growing `pending`.
                if (gaveUp && !warnedUnavailable) {
                    warnedUnavailable = true
                    Log.w(TAG, "control channel unavailable; dropping commands (first: $line)")
                }
            }
            // Coalesce RESIZE: only the most recent geometry matters, so replace
            // any queued RESIZE rather than appending a new one per slide event.
            line.startsWith("RESIZE ") -> {
                pending.removeAll { it.startsWith("RESIZE ") }
                pending.add(line)
            }
            pending.size >= MAX_PENDING -> Log.w(TAG, "pending queue full ($MAX_PENDING); dropping: $line")
            else -> pending.add(line)
        }
    }

    fun sendResize(rows: Int, cols: Int)             = sendOrQueue("RESIZE $rows $cols")
    fun addForward(vport: Int, proto: String, host: String, gport: Int) = sendOrQueue("ADD $vport $proto $host $gport")
    fun removeForward(vport: Int)                    = sendOrQueue("REMOVE $vport")

    /**
     * Deterministic guest flush before stop: tells the agent to sync(2) and waits
     * up to timeoutMs for the "SYNCED" ack. Best-effort - returns true only on a
     * SYNCED ack within the timeout; false if the channel is down, the write
     * fails, or no ack arrives in time. The caller then just proceeds to stop.
     */
    suspend fun syncAndWait(timeoutMs: Long): Boolean {
        val pfdLocal: ParcelFileDescriptor
        synchronized(this) {
            val w = writer
            val p = pfd
            if (closed || w == null || p == null) return false
            if (!writeLine(w, "SYNC")) return false
            pfdLocal = p
        }
        // Read the ack OUTSIDE the monitor so a slow guest can't hold the lock.
        return withContext(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(
                    java.io.FileInputStream(pfdLocal.fileDescriptor)))
                // Do NOT close `reader`: it wraps the same fd the writer/pfd own;
                // close() owns the pfd. A read still blocked at timeout unblocks
                // when close()/cleanup shuts the fd moments later, which is fine on
                // the stop path.
                var line = reader.readLine()
                while (line != null && line != "SYNCED") line = reader.readLine()
                line == "SYNCED"
            } ?: false
        }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        runCatching { connectJob?.cancel() }
        runCatching { writer?.close() }
        runCatching { pfd?.close() }
        writer = null
        pfd = null
    }
}
