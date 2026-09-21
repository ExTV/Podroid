/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Owns the guest-bridge connection for one VM session. Opens a HostTransport
 * (retrying until the guest daemon is up), then loops: read request line ->
 * dispatch -> write response line. On EOF/error it closes and reconnects while
 * the session is active. start()/stop() are driven by VM Running/terminal.
 */
package com.excp.podroid.engine.hostbridge

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one host-bridge generation for the lifetime of [scope]. The synchronous
 * [openTransport] callback may be inside an Android framework/binder connect
 * that cannot be interrupted here; stopping invalidates that generation and
 * closes any transport it eventually returns instead of adding a timeout that
 * could strand a hidden native connect job.
 */
class HostRequestServer(
    private val openTransport: () -> HostTransport?,
    private val dispatcher: HostRequestDispatcher,
    private val scope: CoroutineScope,
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.d(TAG, message) else Log.w(TAG, message, error)
    },
) {
    companion object {
        private const val TAG = "HostRequestServer"
        private const val RETRY_MS = 500L
    }

    private val lock = Any()
    private var job: Job? = null
    private var generation = 0L
    private var active: ActiveTransport? = null

    /** Starts one generation. A cancelled old generation cannot revive itself. */
    fun start() {
        synchronized(lock) {
            if (!scope.isActive || job?.isActive == true) return
            val current = ++generation
            val started = scope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY,
            ) { runLoop(current) }
            job = started
            // Assign before starting so a fast run cannot finish and then be
            // overwritten by this stale Job reference.
            started.start()
            // Parent-scope cancellation does not necessarily interrupt a native
            // socket read. The cancellation watcher closes it in that phase;
            // this completion cleanup is only the post-exit safety net. An old
            // job must never tear down a transport from a restart.
            started.invokeOnCompletion {
                val toClose = synchronized(lock) {
                    if (generation == current) {
                        ++generation
                        job = null
                        active.also { active = null }
                    } else null
                }
                toClose?.close()
            }
        }
    }

    /**
     * Invalidates the generation before closing its transport. Closing first is
     * what unblocks raw socket reads; cancellation alone is not sufficient for
     * native vsock/LocalSocket reads. If [openTransport] is already in a
     * synchronous framework connect, this does not interrupt that native call;
     * a transport returned later is closed as stale.
     */
    fun stop() {
        val toClose = synchronized(lock) {
            ++generation
            job?.cancel()
            job = null
            active.also { active = null }
        }
        toClose?.close()
    }

    private fun isCurrent(current: Long): Boolean = synchronized(lock) {
        generation == current && scope.isActive
    }

    /**
     * Reserves an already-read request against stop(). Once this returns true,
     * stop() cannot cancel the handler itself; the later active check only
     * suppresses its response.
     */
    private fun mayDispatch(current: Long, transport: ActiveTransport): Boolean = synchronized(lock) {
        generation == current && active === transport && scope.isActive
    }

    private suspend fun runLoop(current: Long) = coroutineScope {
        // This child wakes in the cancellation phase, before a blocked read
        // finishes. It belongs to this run only; stopped servers leave no
        // watcher behind in the service scope.
        val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                val toClose = synchronized(lock) {
                    if (generation == current) active.also { active = null } else null
                }
                toClose?.close()
            }
        }
        try {
            while (isCurrent(current)) {
                val opened = openTransport()
                if (opened == null) {
                    if (isCurrent(current)) delay(RETRY_MS)
                    continue
                }
                val transport = ActiveTransport(opened)
                val accepted = synchronized(lock) {
                    if (generation == current && scope.isActive) {
                        active = transport
                        true
                    } else false
                }
                if (!accepted) {
                    // stop() won the registration race. No reconnect from this
                    // stale generation is allowed.
                    transport.close()
                    return@coroutineScope
                }

                log("host bridge connected", null)
                try {
                    while (isCurrent(current)) {
                        val req = transport.value.readRequest() ?: break
                        if (!mayDispatch(current, transport)) break
                        val resp = dispatcher.handle(req)
                        if (!isCurrent(current)) break
                        transport.value.writeResponse(resp)
                    }
                } catch (c: CancellationException) {
                    throw c // don't log a stop/swap's cancellation as a loop error
                } catch (e: HostProtocolViolationException) {
                    // A malformed request line (too long, bad UTF-8): a real bug
                    // from a hostile or buggy guest, not a peer disconnect. Keep
                    // the throwable, unlike the generic IOException case below.
                    log("host bridge loop error: ${e.message}", e)
                } catch (e: IOException) {
                    // The peer went away: expected at every VM stop (also a VM
                    // crash). No throwable/stack trace for the routine case.
                    log("host bridge peer closed: ${e.message}", null)
                } catch (e: Exception) {
                    log("host bridge loop error: ${e.message}", e)
                } finally {
                    synchronized(lock) {
                        if (active === transport) active = null
                    }
                    transport.close()
                }
                if (isCurrent(current)) delay(RETRY_MS) else return@coroutineScope
            }
        } finally {
            cancellationWatcher.cancel()
            synchronized(lock) {
                // A natural exit (for example, parent-scope cancellation) must
                // not leave a completed Job blocking a later start().
                if (generation == current) job = null
            }
        }
    }

    private class ActiveTransport(val value: HostTransport) {
        private val closed = AtomicBoolean(false)

        fun close() {
            if (closed.compareAndSet(false, true)) runCatching { value.close() }
        }
    }
}
