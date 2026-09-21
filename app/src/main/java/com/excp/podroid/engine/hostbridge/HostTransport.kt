/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * One open guest<->Android connection carrying the host-bridge line protocol.
 * Two impls: QEMU connects to a filesystem AF_UNIX socket QEMU created
 * (-chardev socket,server=on for /dev/hvc2); AVF dials the guest daemon's
 * AF_VSOCK listener. The server reads requests over a bounded line reader
 * (matching the guest daemon's 8192-byte request buffer) and writes one
 * response per request.
 */
package com.excp.podroid.engine.hostbridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.excp.podroid.engine.avf.AvfReflect
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The guest daemon has `char req[8192]`, so it can carry at most 8191 bytes
 * before its terminating NUL. LF is framing, not part of this limit. A CR
 * immediately before LF is accepted for CRLF-speaking clients and is excluded
 * from the returned request.
 */
internal const val MAX_REQUEST_LINE_BYTES = 8191

/**
 * A malformed request from the guest daemon (too long, or not valid UTF-8):
 * a protocol violation from a hostile or buggy guest, not a peer disconnect.
 * Callers should log this with its stack trace like any other real bug.
 */
internal open class HostProtocolViolationException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class RequestLineTooLongException :
    HostProtocolViolationException("host request line exceeds $MAX_REQUEST_LINE_BYTES bytes")

/** Reads exactly one complete UTF-8 request line, or null for EOF. */
internal object HostRequestLineReader {
    fun read(input: InputStream): String? {
        val bytes = ByteArray(MAX_REQUEST_LINE_BYTES)
        var size = 0
        while (true) {
            val value = input.read()
            if (value < 0) {
                // A partial line is not a request. Returning its prefix would
                // let a reconnect turn a truncated command into a valid one.
                return null
            }
            if (value == '\n'.code) {
                val contentSize = if (size > 0 && bytes[size - 1] == '\r'.code.toByte()) size - 1 else size
                return try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, contentSize))
                        .toString()
                } catch (e: CharacterCodingException) {
                    throw HostProtocolViolationException("invalid UTF-8 request", e)
                }
            }
            if (size == bytes.size) throw RequestLineTooLongException()
            bytes[size++] = value.toByte()
        }
    }
}

interface HostTransport {
    /** Returns one complete bounded LF-terminated request line, or null on EOF/error. */
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
    private val input: BufferedInputStream,
    private val out: OutputStream,
) : HostTransport {
    override fun readRequest(): String? = HostRequestLineReader.read(input)
    override fun writeResponse(line: String) {
        out.write((line + "\n").toByteArray()); out.flush()
    }
    override fun close() {
        // Explicit shutdown is the useful wake-up on Android; close the raw
        // socket before its buffered streams so a blocked native read is not
        // held behind a reader monitor. Both calls are harmless if already shut.
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
        runCatching { input.close() }
        runCatching { out.close() }
    }

    companion object {
        /** Returns null if QEMU has not created the socket yet (caller retries). */
        fun open(socketPath: String): QemuHostTransport? = runCatching {
            val s = LocalSocket()
            try {
                s.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                QemuHostTransport(s, BufferedInputStream(s.inputStream), s.outputStream)
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
    // pfd is owned by AutoCloseInputStream(input); it is retained here only so
    // shutdown can run before that stream releases the descriptor.
    private val pfd: ParcelFileDescriptor,
    private val input: BufferedInputStream,
    private val out: OutputStream,
) : HostTransport {
    override fun readRequest(): String? = HostRequestLineReader.read(input)
    override fun writeResponse(line: String) {
        out.write((line + "\n").toByteArray()); out.flush()
    }
    override fun close() {
        // Coroutine cancellation does not reliably interrupt a native vsock
        // read. Shutdown the socket while both ParcelFileDescriptors are still
        // valid, then let each AutoClose stream release its one fd owner.
        runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { input.close() }
        runCatching { out.close() }
    }

    companion object {
        /** Returns null if the guest daemon is not listening yet (caller retries). */
        fun open(vm: Any): AvfHostTransport? = runCatching {
            val readPfd = AvfReflect.connectVsock(vm, HostTransport.AVF_VSOCK_PORT)
            try {
                val writePfd = readPfd.dup()
                try {
                    val input = BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(readPfd))
                    val out = ParcelFileDescriptor.AutoCloseOutputStream(writePfd)
                    AvfHostTransport(readPfd, input, out)
                } catch (t: Throwable) {
                    // The AutoClose streams may already own either descriptor;
                    // closing both here is safe and covers construction failure.
                    runCatching { writePfd.close() }
                    runCatching { readPfd.close() }
                    throw t
                }
            } catch (t: Throwable) {
                runCatching { readPfd.close() }
                throw t
            }
        }.getOrNull()
    }
}
