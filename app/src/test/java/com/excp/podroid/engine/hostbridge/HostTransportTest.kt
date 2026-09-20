package com.excp.podroid.engine.hostbridge

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTransportTest {
    @Test fun acceptsTheGuestBufferBoundary() {
        val line = "x".repeat(MAX_REQUEST_LINE_BYTES)
        assertEquals(line, HostRequestLineReader.read(ByteArrayInputStream("$line\n".toByteArray())))
    }

    @Test fun rejectsAByteBeyondTheGuestBufferBoundary() {
        val line = "x".repeat(MAX_REQUEST_LINE_BYTES + 1)
        try {
            HostRequestLineReader.read(ByteArrayInputStream("$line\n".toByteArray()))
            throw AssertionError("expected overlong line")
        } catch (_: RequestLineTooLongException) {
            // The transport is closed by the server after this exception.
        }
    }

    @Test fun boundsUtf8ByBytesNotKotlinChars() {
        val line = "x".repeat(MAX_REQUEST_LINE_BYTES - 1) + "é"
        try {
            HostRequestLineReader.read(ByteArrayInputStream("$line\n".toByteArray(Charsets.UTF_8)))
            throw AssertionError("expected overlong UTF-8 line")
        } catch (_: RequestLineTooLongException) {
            // Two UTF-8 bytes do not fit in the final byte slot.
        }
    }

    @Test fun partialEofIsNotARequest() {
        assertNull(HostRequestLineReader.read(ByteArrayInputStream("PING".toByteArray())))
    }

    @Test fun acceptsCrLfAndDoesNotExposeTheCarriageReturn() {
        assertEquals("PING", HostRequestLineReader.read(ByteArrayInputStream("PING\r\n".toByteArray())))
        assertTrue(HostRequestLineReader.read(ByteArrayInputStream("\r\n".toByteArray())).isNullOrEmpty())
    }
}
