/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * One open guest<->Android connection carrying the host-bridge line protocol.
 * Two impls: QEMU connects to a filesystem AF_UNIX socket QEMU created
 * (-chardev socket,server=on for /dev/hvc2); AVF dials the guest daemon's
 * AF_VSOCK listener. The server reads requests (guest-originated) and writes
 * one response per request.
 */
package com.excp.podroid.engine.hostbridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.excp.podroid.engine.avf.AvfReflect
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStream

interface HostTransport {
    /** Blocks until one LF-terminated request line arrives; null on EOF/error. */
    fun readRequest(): String?
    /** Writes one response line (a trailing LF is appended). */
    fun writeResponse(line: String)
    fun close()

    companion object {
        const val AVF_VSOCK_PORT: Long = 9101L
    }
}

/** QEMU: client connection to the host.sock that backs guest /dev/hvc2. */
class QemuHostTransport private constructor(
    private val socket: LocalSocket,
    private val reader: BufferedReader,
    private val out: OutputStream,
) : HostTransport {
    override fun readRequest(): String? = reader.readLine()
    override fun writeResponse(line: String) {
        out.write((line + "\n").toByteArray()); out.flush()
    }
    override fun close() { runCatching { socket.close() } }

    companion object {
        /** Returns null if QEMU has not created the socket yet (caller retries). */
        fun open(socketPath: String): QemuHostTransport? = runCatching {
            val s = LocalSocket()
            try {
                s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                QemuHostTransport(s, BufferedReader(InputStreamReader(s.inputStream)), s.outputStream)
            } catch (t: Throwable) {
                // connect()/stream access failed after the socket was created —
                // close it so a retry loop doesn't leak one fd per attempt.
                runCatching { s.close() }
                throw t
            }
        }.getOrNull()
    }
}

/** AVF: vsock connection dialed into the guest daemon's :9101 listener. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AvfHostTransport private constructor(
    private val pfd: ParcelFileDescriptor,
    private val pfdOut: ParcelFileDescriptor,
    private val reader: BufferedReader,
    private val out: OutputStream,
) : HostTransport {
    override fun readRequest(): String? = reader.readLine()
    override fun writeResponse(line: String) {
        out.write((line + "\n").toByteArray()); out.flush()
    }
    override fun close() {
        runCatching { reader.close() }
        runCatching { out.close() }
        runCatching { pfd.close() }
        runCatching { pfdOut.close() }
    }

    companion object {
        /** Returns null if the guest daemon is not listening yet (caller retries). */
        fun open(vm: Any): AvfHostTransport? = runCatching {
            // Read side owns p; write side owns a dup (each AutoClose closes one fd).
            var p: ParcelFileDescriptor? = null
            var pOut: ParcelFileDescriptor? = null
            try {
                p = AvfReflect.connectVsock(vm, HostTransport.AVF_VSOCK_PORT)
                pOut = p.dup()
                val r = BufferedReader(InputStreamReader(FileInputStream(p.fileDescriptor)))
                val o = ParcelFileDescriptor.AutoCloseOutputStream(pOut)
                AvfHostTransport(p, pOut, r, o)
            } catch (t: Throwable) {
                // dup()/stream construction failed after connectVsock — close the
                // fds already opened so a retry loop doesn't leak them.
                runCatching { pOut?.close() }
                runCatching { p?.close() }
                throw t
            }
        }.getOrNull()
    }
}
