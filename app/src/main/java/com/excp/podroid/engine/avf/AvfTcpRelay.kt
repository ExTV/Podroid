/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Bidirectional stream relay used by the AVF TCP forwarder.  The endpoint
 * contract deliberately keeps half-close separate from abort: EOF on one
 * input shuts down only the other endpoint's output, while failures and
 * cancellation tear down both directions.
 */
package com.excp.podroid.engine.avf

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** The small ownership boundary needed by [BidirectionalRelay]. */
internal interface RelayEndpoint {
    val input: InputStream
    val output: OutputStream

    /** Propagate a clean input EOF without closing the input half. */
    fun shutdownOutput()

    /** Interrupt blocked native I/O and release all endpoint resources. */
    fun abort()

    /** Release resources after both directions have completed cleanly. */
    fun close()
}

/**
 * Relays two connected stream endpoints while preserving TCP-style half-close.
 * Exactly two IO pump coroutines are used for the data paths.  The short-lived
 * cancellation watcher is per relay, so cancellation can wake blocking native
 * reads without registering a permanent watcher on the forwarder's scope.
 */
internal class BidirectionalRelay(
    private val source: RelayEndpoint,
    private val log: (String, Throwable?) -> Unit = { message, error ->
        if (error == null) Log.d(TAG, message) else Log.w(TAG, message, error)
    },
) {
    companion object {
        private const val TAG = "AvfTcpRelay"
    }

    private val lock = Any()
    private var destination: RelayEndpoint? = null
    private var started = false
    private var aborted = false

    /** Attaches the second endpoint, or aborts it if close won the race. */
    fun attach(endpoint: RelayEndpoint): Boolean {
        val accepted = synchronized(lock) {
            if (aborted || started || destination != null) {
                false
            } else {
                destination = endpoint
                true
            }
        }
        if (!accepted) endpoint.abort()
        return accepted
    }

    /** Idempotently interrupts both endpoints, including a late attachment. */
    fun abort() {
        val endpoints = synchronized(lock) {
            if (aborted) return
            aborted = true
            listOfNotNull(source, destination)
        }
        // Best-effort cleanup: one endpoint failing to abort must not prevent
        // teardown of the other endpoint or hide the original relay failure.
        endpoints.forEach { runCatching { it.abort() } }
    }

    suspend fun run() {
        val peer = synchronized(lock) {
            if (aborted) null else destination?.also { started = true }
        }
        if (peer == null) {
            closeEndpoints()
            return
        }

        var completed = false
        try {
            runPumps(peer)
            completed = true
        } finally {
            if (!completed) abort()
            closeEndpoints()
        }
    }

    private suspend fun runPumps(peer: RelayEndpoint) = coroutineScope {
        val completed = AtomicBoolean(false)
        // Coroutine cancellation does not reliably interrupt a blocking read
        // on a native socket. This watcher exists only for this relay run and
        // aborts the endpoints before structured cancellation waits on pumps.
        val cancellationWatcher = launch(
            Dispatchers.IO,
            start = CoroutineStart.UNDISPATCHED,
        ) {
            try {
                awaitCancellation()
            } finally {
                if (!completed.get()) abort()
            }
        }

        try {
            val leftToRight = launch(Dispatchers.IO) { pump(source, peer) }
            val rightToLeft = launch(Dispatchers.IO) { pump(peer, source) }
            joinAll(leftToRight, rightToLeft)
            completed.set(true)
        } finally {
            if (!completed.get()) abort()
            withContext(NonCancellable) {
                cancellationWatcher.cancelAndJoin()
            }
        }
    }

    private fun pump(from: RelayEndpoint, to: RelayEndpoint) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val count = from.input.read(buffer)
                if (count <= 0) {
                    to.shutdownOutput()
                    return
                }
                to.output.write(buffer, 0, count)
                to.output.flush()
            }
        } catch (e: CancellationException) {
            abort()
            throw e
        } catch (e: IOException) {
            log("relay pump ended after I/O failure: ${e.message}", null)
            abort()
        } catch (e: Exception) {
            // Android's Os methods report some native I/O failures as an
            // exception other than IOException. They are still hard relay
            // failures and must not leave the opposite pump blocked.
            log("relay pump failed unexpectedly", e)
            abort()
        }
    }

    private fun closeEndpoints() {
        val endpoints = synchronized(lock) { listOfNotNull(source, destination) }
        // Final close is best-effort cleanup: a close failure must not keep the
        // peer endpoint open or replace the relay's original failure.
        endpoints.forEach { runCatching { it.close() } }
    }
}

/** TCP endpoint whose clean output shutdown preserves its input half. */
internal class SocketRelayEndpoint(
    private val socket: Socket,
) : RelayEndpoint {
    private val lock = Any()
    private var outputShutdown = false
    private var closed = false

    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = socket.getOutputStream()

    override fun shutdownOutput() {
        synchronized(lock) {
            if (closed || outputShutdown) return
            socket.shutdownOutput()
            outputShutdown = true
        }
    }

    override fun abort() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // Best-effort abort cleanup: peer teardown can make any individual
            // operation fail, but the remaining shutdown/close steps must run.
            runCatching { socket.shutdownInput() }
            runCatching { socket.shutdownOutput() }
            runCatching { socket.close() }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { socket.close() }
        }
    }
}

/**
 * Raw AF_VSOCK endpoint. Each AutoClose stream owns a distinct descriptor;
 * shutdown/close are serialized so a reused descriptor cannot be shut down by
 * a late operation from an older connection.
 */
internal class VsockRelayEndpoint private constructor(
    private val inputPfd: ParcelFileDescriptor,
    private val outputPfd: ParcelFileDescriptor,
    override val input: InputStream,
    override val output: OutputStream,
) : RelayEndpoint {
    private val lock = Any()
    private var outputShutdown = false
    private var closed = false

    companion object {
        fun open(pfd: ParcelFileDescriptor): VsockRelayEndpoint {
            val outputPfd = try {
                pfd.dup()
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                throw t
            }
            val input = try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd)
            } catch (t: Throwable) {
                runCatching { outputPfd.close() }
                runCatching { pfd.close() }
                throw t
            }
            val output = try {
                ParcelFileDescriptor.AutoCloseOutputStream(outputPfd)
            } catch (t: Throwable) {
                runCatching { input.close() }
                runCatching { outputPfd.close() }
                throw t
            }
            return VsockRelayEndpoint(pfd, outputPfd, input, output)
        }
    }

    override fun shutdownOutput() {
        synchronized(lock) {
            if (closed || outputShutdown) return
            try {
                Os.shutdown(outputPfd.fileDescriptor, OsConstants.SHUT_WR)
                outputShutdown = true
            } catch (e: Exception) {
                throw IOException("vsock shutdown(SHUT_WR) failed", e)
            }
        }
    }

    override fun abort() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // The native shutdown must happen before either AutoClose stream
            // closes its descriptor. This is also the wakeup for blocked reads.
            // Cleanup is best-effort so one failure cannot skip the remaining
            // descriptor owners.
            runCatching { Os.shutdown(inputPfd.fileDescriptor, OsConstants.SHUT_RDWR) }
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // Best-effort final cleanup: both descriptor owners should get a
            // close attempt even if the first one fails.
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }
}
