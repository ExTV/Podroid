/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Reads the guest serial console (serial.sock = ttyAMA0) for one VM run and
 * turns the byte stream into: console.log on disk, the [consoleText] flow, and
 * [BootStageDetector] feeds. One instance per VM run; owns its console buffer.
 *
 * Transport: NO read timeout. read() blocks; the loop ends only on real EOF or
 * when [release] closes the socket (from QemuEngine.cleanup() on stop/exit).
 * Indefinite silence is normal under `quiet` and is harmless. The old soTimeout
 * + `catch (SocketTimeoutException)` was a bug: LocalSocket never throws that
 * class on a read timeout - it surfaces a generic IOException - so a multi-second
 * console-silence gap broke the loop after ~3s and starved boot detection.
 */
class QemuBootMonitor(
    private val serialSockPath: String,
    private val consoleLog: File,
    private val detector: BootStageDetector,
    private val consoleText: MutableStateFlow<String>,
    private val socketReadyTimeoutMs: Long,
) {
    private val maxConsoleSize = 64 * 1024
    private val consoleBuilder = StringBuilder()
    @Volatile private var socket: LocalSocket? = null

    /** Wait for the sock file, connect, run the read loop on [scope]. */
    fun connectAndRun(scope: CoroutineScope, isAlive: () -> Boolean) {
        scope.launch {
            val sockFile = File(serialSockPath)
            var waited = 0L
            while (waited < socketReadyTimeoutMs && !sockFile.exists() && isAlive()) {
                delay(100); waited += 100
            }
            if (!sockFile.exists()) {
                Log.w(TAG, "serial.sock not found after ${waited}ms - boot detection disabled")
                return@launch
            }
            val sock = LocalSocket()
            try {
                sock.connect(LocalSocketAddress(serialSockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = sock
                Log.d(TAG, "Boot monitor connected to serial.sock")
            } catch (e: Exception) {
                Log.w(TAG, "Boot monitor could not connect to serial.sock: ${e.message}")
                runCatching { sock.close() }
                return@launch
            }
            try {
                runLoop(sock.inputStream)
            } finally {
                runCatching { sock.close() }
                socket = null
                Log.d(TAG, "Boot monitor disconnected")
            }
        }
    }

    /**
     * Drive the read loop from any InputStream. Test seam: no LocalSocket, no
     * coroutines, fully synchronous and JVM-unit-testable. Ends on EOF (read
     * returns < 0) or any read exception (socket closed by [release]).
     */
    internal fun runLoop(input: InputStream) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val byteBuf = ByteBuffer.allocate(8192)
        val charBuf = CharBuffer.allocate(8192)
        consoleLog.delete()
        FileOutputStream(consoleLog, false).use { logOut ->
            val readBuf = ByteArray(4096)
            while (true) {
                val n = try { input.read(readBuf) } catch (_: Exception) { break }
                if (n < 0) break

                // Raw bytes to disk for the diagnostic log.
                logOut.write(readBuf, 0, n)
                logOut.flush()

                // Streaming UTF-8 decode; carry leftover undecoded bytes via compact().
                byteBuf.put(readBuf, 0, n)
                byteBuf.flip()
                decoder.decode(byteBuf, charBuf, false)
                byteBuf.compact()
                charBuf.flip()
                if (charBuf.hasRemaining()) {
                    consoleBuilder.append(charBuf)
                    if (consoleBuilder.length > maxConsoleSize) {
                        consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                    }
                    consoleText.value = consoleBuilder.toString()
                }
                charBuf.clear()

                detector.feed(readBuf, n)
            }
        }
    }

    /** Close the socket so a blocked read() returns; idempotent. */
    fun release() {
        val sock = socket ?: return
        socket = null
        runCatching {
            sock.shutdownInput()
            sock.shutdownOutput()
            sock.close()
        }
    }

    private companion object { const val TAG = "QemuBootMonitor" }
}
