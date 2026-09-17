/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AVF-only live Downloads share. Serves the real Downloads directory to the guest
 * by running an in-process 9p2000.L server (Ninep2000LServer) over the vsock
 * socket the guest mounts via `9p trans=fd`. No subprocess, no native binary.
 * Non-fatal throughout: any failure here leaves the VM running with
 * /mnt/downloads simply absent.
 */
package com.excp.podroid.engine.avf

import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import com.excp.podroid.engine.avf.ninep.Ninep2000LServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AvfDownloadsShare(
    private val vm: Any,
    private val scope: CoroutineScope,
) {
    // job is launched from start() and cancelled from stop(), which may run on
    // different threads (stop() from AvfEngine.cleanup(), the job body on
    // Dispatchers.IO) - mirrors VsockControlChannel.connectJob.
    @Volatile private var job: Job? = null
    // lock guards pfd/pfdOut/stopped together so a stop() that races the
    // coroutine's post-connect field publish can never miss the fds: either the
    // coroutine publishes them before stop() sets stopped (stop closes them), or
    // stop() wins and the coroutine closes its own fds and bails. Mirrors
    // VsockControlChannel's lock discipline.
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null      // guarded by lock
    private var pfdOut: ParcelFileDescriptor? = null   // guarded by lock
    private var stopped = false                        // guarded by lock

    /**
     * Checks the Downloads directory exists, then launches ONE job on [scope]
     * that connects to the guest's 9p rendezvous listener (with retry/backoff)
     * and serves it. Returns immediately - true once the job is launched, false
     * (never throws) only if the Downloads directory is missing. The actual
     * connect outcome ("added"/"unavailable") is decided later inside the job
     * and only logged there, never blocking this call: a racing cleanup() must
     * always find a job (and, once connected, fds) it can tear down.
     */
    fun start(): Boolean {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!root.isDirectory) {
            Log.w(TAG, "Downloads dir missing: ${root.absolutePath}")
            return false
        }
        job = scope.launch(Dispatchers.IO) {
            val p = connectWithRetry(connect = {
                AvfReflect.connectVsock(vm, DOWNLOADS_VSOCK_PORT.toLong())
            })
            if (p == null) {
                Log.w(TAG, "connectVsock($DOWNLOADS_VSOCK_PORT) failed after $DEFAULT_ATTEMPTS attempts")
                return@launch
            }
            // Read side owns p; write side owns a dup, mirroring
            // VsockPortForwarder's fd-ownership discipline (each AutoClose
            // stream closes exactly one descriptor). If dup() fails, close the
            // already-open p ourselves - nothing else owns it yet.
            val pOut = runCatching { p.dup() }.getOrElse {
                Log.w(TAG, "dup() failed after connect", it)
                runCatching { p.close() }
                return@launch
            }
            // Publish the fds under lock unless stop() already ran; if it did,
            // we still own p+pOut and must close them here (stop() saw nulls).
            val proceed = synchronized(lock) {
                if (stopped) {
                    false
                } else {
                    pfd = p
                    pfdOut = pOut
                    true
                }
            }
            if (!proceed) {
                runCatching { p.close() }
                runCatching { pOut.close() }
                return@launch
            }
            Log.i(TAG, "downloads share added: ${root.absolutePath}")
            runCatching {
                ParcelFileDescriptor.AutoCloseInputStream(p).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pOut).use { output ->
                        Ninep2000LServer(root).serve(input, output)
                    }
                }
            }.onFailure { Log.w(TAG, "9p server ended", it) }
        }
        return true
    }

    fun stop() {
        // Mark stopped and take the fds under lock, so a coroutine still mid
        // connect either sees stopped and closes its own fds, or has already
        // published them here for us to close.
        val (closeIn, closeOut) = synchronized(lock) {
            stopped = true
            val a = pfd
            val b = pfdOut
            pfd = null
            pfdOut = null
            a to b
        }
        job?.cancel()
        job = null
        // Force-close BOTH sockets so a serve() blocked in a native read()
        // unblocks; cancelling the coroutine alone cannot interrupt it, and
        // closing only the read-side pfd leaves the write-side dup open, which
        // can keep the vsock connection's refcount above zero.
        runCatching { closeIn?.close() }
        runCatching { closeOut?.close() }
    }

    companion object {
        // Keep == PODROID_DOWNLOADS_VSOCK_PORT in build-rootfs/vsock-agent/podroid-vsock-agent.c
        private const val DOWNLOADS_VSOCK_PORT = 200000
        private const val TAG = "AvfDownloadsShare"
        private const val DEFAULT_ATTEMPTS = 30
        private const val DEFAULT_BACKOFF_MS = 500L

        /**
         * Retries [connect] up to [attempts] times, suspending on [sleep] between
         * attempts (not after the last one). Defaults to a real coroutine delay
         * so the retry loop is cancellable - the caller's job can be cancelled
         * mid-backoff without a Thread.sleep to wait out. Pure and
         * unit-testable: a test passes a no-op suspend sleeper so the retry
         * budget can be exhausted without a real delay. A throwing [connect]
         * counts as a failed attempt, not a propagated exception. Returns null
         * once every attempt is spent.
         */
        suspend fun <T> connectWithRetry(
            connect: () -> T?,
            attempts: Int = DEFAULT_ATTEMPTS,
            sleep: suspend (Long) -> Unit = { delay(it) },
        ): T? {
            repeat(attempts) { attempt ->
                val result = runCatching { connect() }.getOrNull()
                if (result != null) return result
                if (attempt < attempts - 1) sleep(DEFAULT_BACKOFF_MS)
            }
            return null
        }
    }
}
