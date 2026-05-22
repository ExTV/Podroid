/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** Returns one chunk per read() call so we can force read-boundary splits. */
private class ChunkedInputStream(chunks: List<ByteArray>) : InputStream() {
    private val q = ArrayDeque(chunks)
    override fun read(): Int = throw UnsupportedOperationException()
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (q.isEmpty()) return -1
        val c = q.removeFirst()
        val n = minOf(len, c.size)
        System.arraycopy(c, 0, b, off, n)
        return n
    }
}

class QemuBootMonitorTest {
    private fun newMonitor(
        consoleText: MutableStateFlow<String>,
    ): Triple<QemuBootMonitor, MutableStateFlow<VmState>, MutableStateFlow<String>> {
        val state = MutableStateFlow<VmState>(VmState.Starting)
        val stage = MutableStateFlow("")
        val detector = BootStageDetector(stage, state) { /* onReady no-op */ }
        val log = Files.createTempFile("console", ".log").toFile().also { it.deleteOnExit() }
        val monitor = QemuBootMonitor("/unused/serial.sock", log, detector, consoleText, 1000L)
        return Triple(monitor, state, stage)
    }

    @Test fun readyMarkerInSingleChunkFlipsRunning() {
        val ct = MutableStateFlow("")
        val (m, state, stage) = newMonitor(ct)
        m.runLoop(ByteArrayInputStream("Booting...\nReady!\n".toByteArray()))
        assertEquals(VmState.Running, state.value)
        assertEquals("Ready", stage.value)
    }

    @Test fun readyMarkerSplitAcrossReadsIsDetected() {
        val ct = MutableStateFlow("")
        val (m, state, _) = newMonitor(ct)
        m.runLoop(ChunkedInputStream(listOf("noise Rea".toByteArray(), "dy!\n".toByteArray())))
        assertEquals(VmState.Running, state.value)
    }

    @Test fun multipleChunksWithoutMarkerKeepLooping() {
        // The old bug broke the loop between reads; here the loop must consume
        // all chunks and only flip Running when the marker finally arrives.
        val ct = MutableStateFlow("")
        val (m, state, _) = newMonitor(ct)
        m.runLoop(ChunkedInputStream(listOf(
            "Booting kernel...\n".toByteArray(),
            "Mounting storage...\n".toByteArray(),
            "Starting SSH...\n".toByteArray(),
            "Ready!\n".toByteArray(),
        )))
        assertEquals(VmState.Running, state.value)
    }

    @Test fun eofWithoutMarkerEndsCleanlyStillStarting() {
        val ct = MutableStateFlow("")
        val (m, state, _) = newMonitor(ct)
        m.runLoop(ByteArrayInputStream("just kernel noise, no marker\n".toByteArray()))
        assertEquals(VmState.Starting, state.value)
    }

    @Test fun throwingStreamEndsLoopWithoutPropagating() {
        val ct = MutableStateFlow("")
        val (m, _, _) = newMonitor(ct)
        val s = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("closed")
        }
        m.runLoop(s) // must not throw
    }

    @Test fun consoleTextAndLogArePopulated() {
        val ct = MutableStateFlow("")
        val (m, _, _) = newMonitor(ct)
        m.runLoop(ByteArrayInputStream("hello console\n".toByteArray()))
        assertTrue(ct.value.contains("hello console"))
    }
}
