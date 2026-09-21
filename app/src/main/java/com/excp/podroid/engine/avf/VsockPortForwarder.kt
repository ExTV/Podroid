/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Per-rule TCP listener that bridges Android-side connections to a vsock port
 * on the guest. Listens on 0.0.0.0:hostPort so LAN devices (`ssh root@<phone-IP>
 * -p 9922`, `vncviewer <phone-IP>:5900`) can reach the VM without going through
 * 127.0.0.1.
 *
 * Lifecycle is bounded by the caller's scope: cancelling the scope tears down
 * the accept loop and every per-connection pump. Use [close] for the explicit
 * "remove this rule" path so the inner `accept()` blocking call returns via
 * SocketException instead of hanging until scope cancellation.
 */
package com.excp.podroid.engine.avf

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the resources acquired for one accepted connection until its coroutine
 * completes, including the case where cancellation wins before the body starts.
 */
internal class RelayJobCompletion(
    private val abort: () -> Unit,
    private val remove: () -> Unit,
    private val release: () -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun attach(job: Job) {
        job.invokeOnCompletion { cleanup() }
    }

    fun cleanup() {
        if (!completed.compareAndSet(false, true)) return
        try {
            abort()
        } finally {
            try {
                remove()
            } finally {
                release()
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class VsockPortForwarder(
    private val hostPort: Int,
    private val guestVsockPort: Int,
    private val vm: Any,
    private val scope: CoroutineScope,
    // 127.0.0.1 for the implicit VNC/audio forwards (off the network);
    // 0.0.0.0 for user rules so a PC on the LAN can reach them.
    private val bindAddress: String = "0.0.0.0",
) : Forwarder {
    companion object {
        private const val TAG = "VsockPortForwarder"
        // A runtime-added rule sends the control ADD then immediately starts the
        // listener; the guest agent forks its vsock listener asynchronously, so
        // the very first connection can land in the gap and get ECONNREFUSED.
        // Mirror the control channel's retry, but briefly (per-connection).
        private const val CONNECT_ATTEMPTS = 6
        private const val CONNECT_RETRY_MS = 250L
        // Hard ceiling on simultaneous in-flight proxy connections. A burst
        // against 0.0.0.0:hostPort while the guest agent isn't up would otherwise
        // launch one coroutine per inbound TCP connection, each holding a socket
        // for up to ~1.5s of connect retries — an unbounded fd/coroutine spike.
        private const val MAX_INFLIGHT = 64
    }

    private var server: ServerSocket? = null
    private var acceptCancellationWatcher: Job? = null
    // Parent of every per-connection coroutine so close() cancels them all at
    // once and completed connections don't accumulate Job references for the
    // forwarder's lifetime (the old plain list never removed finished jobs).
    private val connections = SupervisorJob(scope.coroutineContext[Job])
    private var acceptJob: Job? = null
    // Registration happens before the synchronous framework connectVsock call.
    // A close can therefore abort the TCP side immediately and reject a raw
    // vsock endpoint returned after that call races with close().
    private val lifecycleLock = Any()
    private val activeRelays = mutableSetOf<BidirectionalRelay>()
    // Caps concurrent in-flight proxy() coroutines so an accept burst can't
    // exhaust fds/coroutines. tryAcquire (non-suspending) keeps the accept loop
    // hot: when the cap is hit we drop the new connection immediately rather than
    // parking coroutines that would each pin a socket while waiting for a permit.
    private val inflight = Semaphore(MAX_INFLIGHT)
    @Volatile private var closed = false

    override fun start() {
        val s = ServerSocket(hostPort, /* backlog */ 16, InetAddress.getByName(bindAddress))
        synchronized(lifecycleLock) {
            if (closed) {
                runCatching { s.close() }
                return
            }
            // Publish the listener under the same lock close() uses. Otherwise
            // close() can observe null, return, and leave this socket behind.
            server = s
            Log.d(TAG, "listening on $bindAddress:$hostPort → vsock:$guestVsockPort")
            // The accept loop blocks on ServerSocket.accept() for the forwarder's
            // whole lifetime, so it runs on the shared AvfForwarderDispatcher
            // rather than the 64-thread-capped Dispatchers.IO (see its doc).
            val accept = AvfForwarderDispatcher.launch(scope) {
                while (!closed) {
                    currentCoroutineContext().ensureActive()
                    val client = try { s.accept() } catch (_: SocketException) { break }
                    try {
                        currentCoroutineContext().ensureActive()
                    } catch (e: CancellationException) {
                        runCatching { client.close() }
                        throw e
                    }
                    if (!inflight.tryAcquire()) {
                        Log.w(TAG, "inflight cap ($MAX_INFLIGHT) reached on :$hostPort; dropping connection")
                        runCatching { client.close() }
                        continue
                    }

                    val relay = BidirectionalRelay(SocketRelayEndpoint(client))
                    val registered = synchronized(lifecycleLock) {
                        if (closed) {
                            false
                        } else {
                            activeRelays.add(relay)
                            true
                        }
                    }
                    if (!registered) {
                        relay.abort()
                        inflight.release()
                        continue
                    }
                    val completion = RelayJobCompletion(
                        abort = relay::abort,
                        remove = { synchronized(lifecycleLock) { activeRelays.remove(relay) } },
                        release = inflight::release,
                    )
                    val connection = try {
                        // Lazy start closes the registration window: the
                        // completion handler is installed before any body can
                        // run, while still handling a parent already cancelled.
                        scope.launch(Dispatchers.IO + connections, start = CoroutineStart.LAZY) {
                            proxy(relay)
                        }
                    } catch (t: Throwable) {
                        completion.cleanup()
                        throw t
                    }
                    completion.attach(connection)
                    connection.start()
                }
            }
            acceptJob = accept
            // Coroutine cancellation does not interrupt a native accept(). This
            // watcher is scoped to this listener run; it closes the endpoint
            // when the owning scope is cancelled, and is cancelled by close().
            acceptCancellationWatcher = scope.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    accept.join()
                } finally {
                    close()
                }
            }
        }
    }

    private suspend fun proxy(relay: BidirectionalRelay) {
        val pfd = connectVsockWithRetry() ?: return
        val vsock = runCatching { VsockRelayEndpoint.open(pfd) }.getOrNull() ?: return
        if (!relay.attach(vsock)) return
        relay.run()
    }

    private suspend fun connectVsockWithRetry(): ParcelFileDescriptor? {
        var lastCause: Throwable? = null
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (closed) return null
            val pfd = try {
                AvfReflect.connectVsock(vm, guestVsockPort.toLong())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastCause = t.cause ?: t
                null
            }
            if (pfd != null) {
                // Android's synchronous framework connect cannot be made
                // cancellable cheaply. Invalidate the result if cancellation or
                // forwarder close won while it was blocked; relay.attach() also
                // rejects and closes a descriptor returned in that close window.
                try {
                    currentCoroutineContext().ensureActive()
                } catch (e: CancellationException) {
                    runCatching { pfd.close() }
                    throw e
                }
                if (closed) {
                    runCatching { pfd.close() }
                    return null
                }
                return pfd
            }
            if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_MS)
        }
        // Surface the underlying ErrnoException class — e.message alone is null
        // for many ECONNREFUSED/EAFNOSUPPORT paths, so the bare "${e.message}"
        // gave "failed: null" with zero diagnostic value.
        val cause = lastCause
        Log.w(TAG, "connectVsock($guestVsockPort) failed after $CONNECT_ATTEMPTS attempts: " +
            "${cause?.javaClass?.simpleName}: ${cause?.message ?: "(no message)"}")
        return null
    }

    override fun close() {
        val relays: List<BidirectionalRelay>
        val listener: ServerSocket?
        val accept: Job?
        val watcher: Job?
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            // Mark every relay aborted before releasing the lock: a late
            // connect cannot attach a raw endpoint and start pumps after close.
            relays = activeRelays.toList()
            activeRelays.clear()
            relays.forEach { it.abort() }
            listener = server
            server = null
            accept = acceptJob
            acceptJob = null
            watcher = acceptCancellationWatcher
            acceptCancellationWatcher = null
        }
        runCatching { listener?.close() } // unblocks native accept()
        runCatching { accept?.cancel() }
        runCatching { watcher?.cancel() }
        // Abort both TCP and raw-vsock endpoints so native reads wake; cancelling
        // coroutines alone cannot interrupt a blocking read.
        runCatching { connections.cancel() }
    }
}
