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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class HostRequestServer(
    private val openTransport: () -> HostTransport?,
    private val dispatcher: HostRequestDispatcher,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HostRequestServer"
        private const val RETRY_MS = 500L
    }

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            val transport = openTransport()
            if (transport == null) {
                delay(RETRY_MS)
                continue
            }
            Log.d(TAG, "host bridge connected")
            try {
                while (scope.isActive) {
                    val req = transport.readRequest() ?: break
                    val resp = dispatcher.handle(req)
                    transport.writeResponse(resp)
                }
            } catch (c: CancellationException) {
                throw c // don't log a stop/swap's cancellation as a loop error
            } catch (e: Exception) {
                Log.w(TAG, "host bridge loop error: ${e.message}")
            } finally {
                transport.close()
            }
            delay(RETRY_MS) // brief pause before reconnecting
        }
    }
}
