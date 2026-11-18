/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException

class VncCursorTest {
    private fun rect(w: Int, h: Int, x: Int = 0, y: Int = 0, encoding: Int = -239, payload: ByteArray = byteArrayOf()): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(encoding)
            write(payload)
        }
        return bytes.toByteArray()
    }

    private fun update(vararg rectangles: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeByte(0); writeByte(0); writeShort(rectangles.size)
            rectangles.forEach { write(it) }
        }
        return bytes.toByteArray()
    }

    @Test fun `cursor uses BGRX and padded MSB-first mask without damaging framebuffer`() {
        val payload = ByteArrayOutputStream().apply {
            repeat(18) { write(byteArrayOf(0x11, 0x22, 0x33, 0x7F)) }
            // Width 9 requires two mask bytes per row; low 7 bits of byte 2 are padding.
            write(byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x41, 0))
        }.toByteArray()
        val target = intArrayOf(0x12345678)
        val result = VncClient.readFramebufferUpdate(
            ByteArrayInputStream(update(rect(9, 2, x = 8, y = 1, payload = payload))), target, 1, ZrleDecoder(),
        )
        val cursor = requireNotNull(result.cursor)
        assertEquals(9, cursor.width)
        assertEquals(2, cursor.height)
        assertEquals(8, cursor.hotspotX)
        assertEquals(1, cursor.hotspotY)
        val expected = IntArray(18)
        for (i in listOf(0, 8, 10, 16)) expected[i] = 0xFF332211.toInt()
        assertArrayEquals(expected, cursor.pixels)
        assertArrayEquals(intArrayOf(0x12345678), target)
        assertTrue(result.damage.isEmpty())
        assertNull(result.newSize)
    }

    @Test fun `cursor and raw rectangles preserve framing across consecutive updates`() {
        val cursor = rect(1, 1, payload = byteArrayOf(1, 2, 3, 0, 0x80.toByte()))
        val raw = rect(1, 1, encoding = 0, payload = byteArrayOf(4, 5, 6, 0))
        val input = ByteArrayInputStream(update(cursor, raw) + update(raw))
        val target = IntArray(1)
        val first = VncClient.readFramebufferUpdate(input, target, 1, ZrleDecoder())
        assertEquals(0xFF030201.toInt(), first.cursor!!.pixels.single())
        assertEquals(listOf(VncRect(0, 0, 1, 1)), first.damage)
        assertEquals(0xFF060504.toInt(), target.single())
        assertNull(VncClient.readFramebufferUpdate(input, target, 1, ZrleDecoder()).cursor)
        assertEquals(0, input.available())
    }

    @Test fun `zero-size shape explicitly hides cursor while missing shape leaves it unchanged`() {
        for ((w, h) in listOf(0 to 0, 0 to 2, 2 to 0)) {
            val input = ByteArrayInputStream(update(rect(w, h)) + update())
            val result = VncClient.readFramebufferUpdate(input, IntArray(1), 1, ZrleDecoder())
            assertNotNull(result.cursor)
            assertTrue(result.cursor!!.pixels.isEmpty())
            assertNull(VncClient.readFramebufferUpdate(input, IntArray(1), 1, ZrleDecoder()).cursor)
        }
    }

    @Test fun `fully transparent nonempty cursor stays transparent`() {
        val input = ByteArrayInputStream(update(rect(1, 1, payload = byteArrayOf(1, 2, 3, -1, 0))))
        val result = VncClient.readFramebufferUpdate(input, IntArray(1), 1, ZrleDecoder())
        assertArrayEquals(intArrayOf(0), result.cursor!!.pixels)
    }

    @Test fun `last cursor shape wins within an update including hide requests`() {
        val visible = rect(1, 1, payload = byteArrayOf(1, 2, 3, 0, 0x80.toByte()))
        val input = ByteArrayInputStream(update(visible, rect(0, 0)))
        assertTrue(VncClient.readFramebufferUpdate(input, IntArray(1), 1, ZrleDecoder()).cursor!!.pixels.isEmpty())
    }

    @Test fun `oversized shapes and invalid hotspots fail before reading or allocating payload`() {
        for (invalid in listOf(rect(65535, 65535), rect(1025, 1), rect(1, 1025), rect(1, 1, x = 1), rect(1, 1, y = 1))) {
            assertThrows(RfbProtocolException::class.java) {
                VncClient.readFramebufferUpdate(ByteArrayInputStream(update(invalid)), IntArray(1), 1, ZrleDecoder())
            }
        }
    }

    @Test fun `truncated cursor pixels or mask remain transport EOF errors`() {
        for (payload in listOf(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 0))) {
            assertThrows(EOFException::class.java) {
                VncClient.readFramebufferUpdate(ByteArrayInputStream(update(rect(1, 1, payload = payload))), IntArray(1), 1, ZrleDecoder())
            }
        }
    }
}
