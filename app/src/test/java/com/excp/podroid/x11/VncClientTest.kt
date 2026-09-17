/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class VncClientTest {

    @Test
    fun `handshake replies with RFB 003 008 and selects None auth`() {
        // Server greeting: "RFB 003.008\n" then 1 security type + [None=1]
        val serverBytes = byteArrayOf(
            // 12-byte version greeting
            'R'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), ' '.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '3'.code.toByte(), '.'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '8'.code.toByte(), '\n'.code.toByte(),
            // Security types: count=1, [None=1]
            0x01, 0x01,
            // SecurityResult: OK=0
            0x00, 0x00, 0x00, 0x00,
            // ServerInit: width=1280, height=720, pixel-format (16 bytes), name-len=0
            0x05, 0x00,                                         // width 1280
            0x02, 0xD0.toByte(),                                // height 720
            32, 24, 0, 1,                                       // bpp, depth, big-endian, true-color
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),  // max RGB
            16, 8, 0,                                           // shifts (ARGB)
            0, 0, 0,                                            // padding
            0, 0, 0, 0,                                         // name length 0
        )
        val out = ByteArrayOutputStream()

        val info = VncClient.handshake(ByteArrayInputStream(serverBytes), out)

        assertEquals(1280, info.width)
        assertEquals(720, info.height)
        // Client must have sent: version "RFB 003.008\n", then sec-type 1, then shared=1
        val sent = out.toByteArray()
        // First 12 bytes: client version
        assertArrayEquals("RFB 003.008\n".toByteArray(), sent.copyOfRange(0, 12))
        // Byte 12: chosen security type = 1 (None)
        assertEquals(1, sent[12].toInt())
        // Byte 13: ClientInit shared flag = 1
        assertEquals(1, sent[13].toInt())
    }

    @Test
    fun `Raw rectangle update writes pixels into target ARGB buffer`() {
        // Pre-built FramebufferUpdate message:
        //   msg-type=0, padding, num-rects=1
        //   rect: x=0, y=0, w=2, h=1, encoding=0 (Raw)
        //   pixels: 2 BGRA pixels = red, green
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x02, 0x00, 0x01, // w=2, h=1
            0x00, 0x00, 0x00, 0x00, // encoding = 0 (Raw)
            // Pixel 0: BGRA = (0, 0, 0xFF, 0xFF) -> red
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            // Pixel 1: BGRA = (0, 0xFF, 0, 0xFF) -> green
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
        )
        val target = IntArray(2)

        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = target,
            stride = 2,
            zrle = ZrleDecoder(),
        )

        // ARGB packed: 0xAARRGGBB
        assertEquals(0xFFFF0000.toInt(), target[0])  // red
        assertEquals(0xFF00FF00.toInt(), target[1])  // green
    }

    @Test
    fun `Raw decode matches per-byte reference and ignores the padding byte`() {
        // Multi-row rect (2x2) with a non-zero, varying padding byte on every
        // pixel: the fast IntBuffer decode must produce byte-identical ARGB to
        // an independent per-byte reference implementation, and the padding
        // byte must never leak into the alpha channel (always 0xFF).
        fun refDecode(bgrx: ByteArray): Int {
            val b = bgrx[0].toInt() and 0xFF
            val g = bgrx[1].toInt() and 0xFF
            val r = bgrx[2].toInt() and 0xFF
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val pixel00 = byteArrayOf(0x11, 0x22, 0x33, 0x00)          // padding 0x00
        val pixel01 = byteArrayOf(0x44, 0x55, 0x66, 0x7F)          // padding non-zero
        val pixel10 = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xFF.toByte())
        val pixel11 = byteArrayOf(0x00, 0x00, 0x00, 0x01)          // padding non-zero, black pixel

        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)            // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(2); d.writeShort(2)  // x=0,y=0,w=2,h=2
        d.writeInt(0)                                              // encoding = Raw
        d.write(pixel00); d.write(pixel01)                          // row 0
        d.write(pixel10); d.write(pixel11)                          // row 1

        val target = IntArray(4)
        VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 2, ZrleDecoder())

        assertEquals(refDecode(pixel00), target[0])
        assertEquals(refDecode(pixel01), target[1])
        assertEquals(refDecode(pixel10), target[2])
        assertEquals(refDecode(pixel11), target[3])
        // Explicit alpha check: every pixel's top byte is 0xFF regardless of padding.
        for (px in target) assertEquals(0xFF, (px ushr 24) and 0xFF)
    }

    @Test fun `requestDesktopSize serializes type 251 single-screen layout`() {
        val out = java.io.ByteArrayOutputStream()
        VncClient.requestDesktopSize(out, screenId = 7, width = 1920, height = 1080)
        val b = out.toByteArray()
        assertEquals(24, b.size)
        assertEquals(251, b[0].toInt() and 0xFF)          // msg-type
        // width @ offset 2..3, height @ 4..5 (big-endian)
        assertEquals(1920, ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF))
        assertEquals(1080, ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF))
        assertEquals(1, b[6].toInt() and 0xFF)            // number-of-screens
        // screen id @ 8..11
        assertEquals(7, ((b[8].toInt() and 0xFF) shl 24) or ((b[9].toInt() and 0xFF) shl 16) or ((b[10].toInt() and 0xFF) shl 8) or (b[11].toInt() and 0xFF))
    }

    @Test(expected = java.io.IOException::class)
    fun `Raw rectangle exceeding framebuffer bounds throws IOException`() {
        // FramebufferUpdate with a Raw rect at x=0,y=0,w=4,h=1 into a 2-pixel
        // buffer (stride=2). w*h = 4 pixels would overrun the 2-element array →
        // must raise IOException (bounds guard), not ArrayIndexOutOfBoundsException.
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x04, 0x00, 0x01, // w=4, h=1  (exceeds the 2-pixel buffer)
            0x00, 0x00, 0x00, 0x00, // encoding = 0 (Raw)
            // 4 BGRA pixels of payload so the OOB write is actually reached
            // (without payload, readFully would EOF before any array access).
            0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,  0, 0, 0, 0,
        )
        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = IntArray(2),
            stride = 2,
            zrle = ZrleDecoder(),
        )
    }

    @Test(expected = java.io.IOException::class)
    fun `CopyRect with out-of-bounds source throws IOException`() {
        // CopyRect dst x=0,y=0,w=2,h=1 into a 2-pixel buffer; source srcX=10
        // is out of range. Must raise IOException, not IndexOutOfBoundsException.
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x02, 0x00, 0x01, // w=2, h=1
            0x00, 0x00, 0x00, 0x01, // encoding = 1 (CopyRect)
            0x00, 0x0A, 0x00, 0x00, // srcX=10, srcY=0  (out of bounds)
        )
        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = IntArray(2),
            stride = 2,
            zrle = ZrleDecoder(),
        )
    }

    @Test(expected = Exception::class)
    fun `ServerInit with absurd name length is rejected without OOM`() {
        // Up through ServerInit, then nameLen = 0x7FFFFFFF. Must throw (require/
        // IOException) before allocating ByteArray(nameLen) — no OutOfMemoryError.
        val serverBytes = byteArrayOf(
            'R'.code.toByte(), 'F'.code.toByte(), 'B'.code.toByte(), ' '.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '3'.code.toByte(), '.'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '8'.code.toByte(), '\n'.code.toByte(),
            0x01, 0x01,                                         // sec types: count=1, None
            0x00, 0x00, 0x00, 0x00,                             // SecurityResult OK
            0x05, 0x00, 0x02, 0xD0.toByte(),                    // width 1280, height 720
            32, 24, 0, 1,                                       // pixel format (16 bytes)
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
            16, 8, 0,
            0, 0, 0,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),  // nameLen = 0x7FFFFFFF
        )
        VncClient.handshake(java.io.ByteArrayInputStream(serverBytes), java.io.ByteArrayOutputStream())
    }

    // Fix 1: ExtendedDesktopSize with w=0 must throw IOException, not ArithmeticException.
    @Test(expected = java.io.IOException::class)
    fun `ExtendedDesktopSize with width zero throws IOException`() {
        // FramebufferUpdate: type=0, pad, numRects=1; rect x=0 y=0 w=0 h=600 enc=-308;
        // body: screens=1 + pad[3]; screen descriptor (16 bytes).
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)             // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(0); d.writeShort(600); d.writeInt(-308)  // w=0
        d.writeByte(1); d.writeByte(0); d.writeByte(0); d.writeByte(0)  // screens=1 + pad3
        d.writeInt(1); d.writeShort(0); d.writeShort(0); d.writeShort(0); d.writeShort(600); d.writeInt(0)
        val target = IntArray(1280 * 720)
        VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 1280, ZrleDecoder())
    }

    // Fix 2: An unknown encoding type must throw IOException, not IllegalStateException.
    @Test(expected = java.io.IOException::class)
    fun `unsupported encoding throws IOException`() {
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)          // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(1); d.writeShort(1)  // x=0,y=0,w=1,h=1
        d.writeInt(0x7FFFFFFF)                                    // unknown encoding
        val target = IntArray(1280 * 720)
        VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 1280, ZrleDecoder())
    }

    @Test fun `unsupported encoding is an RfbProtocolException`() {
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)          // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(1); d.writeShort(1)  // x=0,y=0,w=1,h=1
        d.writeInt(0x7FFFFFFF)                                    // unknown encoding
        assertThrows(RfbProtocolException::class.java) {
            VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), IntArray(4), 2, ZrleDecoder())
        }
    }

    @Test fun `ZRLE rect with corrupt zlib data is an RfbProtocolException wrapping DataFormatException`() {
        // Valid zlib header (78 9C), then a deflate block header with BTYPE=11
        // (reserved), which the Inflater rejects with DataFormatException.
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)              // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(2); d.writeShort(2)  // x=0,y=0,w=2,h=2
        d.writeInt(16)                                                // encoding = ZRLE
        d.writeInt(4)
        d.write(byteArrayOf(0x78, 0x9C.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        val ex = assertThrows(RfbProtocolException::class.java) {
            VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), IntArray(4), 2, ZrleDecoder())
        }
        assertTrue("cause was ${ex.cause}", ex.cause is java.util.zip.DataFormatException)
    }

    @Test fun `stream truncated mid-rect is an EOFException, not an RfbProtocolException`() {
        // ZRLE rect announces 100 compressed bytes but the stream ends after 2.
        val zrle = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(zrle).apply {
            writeByte(0); writeByte(0); writeShort(1)
            writeShort(0); writeShort(0); writeShort(2); writeShort(2); writeInt(16)
            writeInt(100); write(byteArrayOf(0x78, 0x9C.toByte()))
        }
        val zrleEx = assertThrows(java.io.IOException::class.java) {
            VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(zrle.toByteArray()), IntArray(4), 2, ZrleDecoder())
        }
        assertTrue("was $zrleEx", zrleEx is java.io.EOFException)
        assertFalse(zrleEx is RfbProtocolException)

        // Raw rect 2x1 with only one of its two pixels on the wire.
        val raw = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(raw).apply {
            writeByte(0); writeByte(0); writeShort(1)
            writeShort(0); writeShort(0); writeShort(2); writeShort(1); writeInt(0)
            write(byteArrayOf(0, 0, 0, 0))
        }
        val rawEx = assertThrows(java.io.IOException::class.java) {
            VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(raw.toByteArray()), IntArray(2), 2, ZrleDecoder())
        }
        assertTrue("was $rawEx", rawEx is java.io.EOFException)
        assertFalse(rawEx is RfbProtocolException)
    }

    @Test fun `EncodingPolicy advertises ZRLE only when wanted and not disabled`() {
        assertArrayEquals(VncClient.ZRLE_ENCODINGS, EncodingPolicy.encodingsFor(wantZrle = true, zrleDisabled = false))
        assertArrayEquals(VncClient.DEFAULT_ENCODINGS, EncodingPolicy.encodingsFor(wantZrle = true, zrleDisabled = true))
        assertArrayEquals(VncClient.DEFAULT_ENCODINGS, EncodingPolicy.encodingsFor(wantZrle = false, zrleDisabled = false))
        assertArrayEquals(VncClient.DEFAULT_ENCODINGS, EncodingPolicy.encodingsFor(wantZrle = false, zrleDisabled = true))
    }

    @Test fun `EncodingPolicy falls back only for a protocol error in a ZRLE session`() {
        val protocol = RfbProtocolException("ZRLE: bad")
        val wrapped = java.io.IOException("outer", RuntimeException("mid", protocol))
        assertTrue(EncodingPolicy.shouldFallBack(protocol, sessionUsedZrle = true))
        assertTrue(EncodingPolicy.shouldFallBack(wrapped, sessionUsedZrle = true))
        assertFalse(EncodingPolicy.shouldFallBack(protocol, sessionUsedZrle = false))
        assertFalse(EncodingPolicy.shouldFallBack(wrapped, sessionUsedZrle = false))
        assertFalse(EncodingPolicy.shouldFallBack(java.io.IOException("reset"), sessionUsedZrle = true))
        assertFalse(EncodingPolicy.shouldFallBack(java.io.EOFException(), sessionUsedZrle = true))
        assertFalse(EncodingPolicy.shouldFallBack(java.net.SocketException("closed"), sessionUsedZrle = true))
        assertFalse(EncodingPolicy.shouldFallBack(java.net.SocketTimeoutException(), sessionUsedZrle = true))
    }

    @Test fun `negotiatePixelFormat with default encodings is byte-identical to the old hardcoded SetEncodings`() {
        val out = java.io.ByteArrayOutputStream()
        VncClient.negotiatePixelFormat(out)
        val sent = out.toByteArray()
        // SetPixelFormat is fixed-size (20 bytes); SetEncodings follows it.
        val setEncodings = sent.copyOfRange(20, sent.size)
        val expected = byteArrayOf(
            0x02, 0x00, 0x00, 0x03,                         // msg=2, pad, count=3
            0x00, 0x00, 0x00, 0x01,                         // CopyRect
            0x00, 0x00, 0x00, 0x00,                         // Raw
            0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xCC.toByte(), // ExtendedDesktopSize (-308)
        )
        assertArrayEquals(expected, setEncodings)
        // Passing DEFAULT_ENCODINGS explicitly must produce the exact same bytes.
        val out2 = java.io.ByteArrayOutputStream()
        VncClient.negotiatePixelFormat(out2, VncClient.DEFAULT_ENCODINGS)
        assertArrayEquals(sent, out2.toByteArray())
    }

    @Test fun `negotiatePixelFormat with ZRLE_ENCODINGS advertises ZRLE first`() {
        val out = java.io.ByteArrayOutputStream()
        VncClient.negotiatePixelFormat(out, VncClient.ZRLE_ENCODINGS)
        val setEncodings = out.toByteArray().copyOfRange(20, out.size())
        val expected = byteArrayOf(
            0x02, 0x00, 0x00, 0x04,                         // msg=2, pad, count=4
            0x00, 0x00, 0x00, 0x10,                         // ZRLE (16)
            0x00, 0x00, 0x00, 0x01,                         // CopyRect
            0x00, 0x00, 0x00, 0x00,                         // Raw
            0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xCC.toByte(), // ExtendedDesktopSize (-308)
        )
        assertArrayEquals(expected, setEncodings)
    }

    @Test fun `onMessageStart fires once when the FramebufferUpdate message-type byte is read`() {
        val msg = byteArrayOf(
            0x00, 0x00,             // msg-type, padding
            0x00, 0x01,             // num rects
            0x00, 0x00, 0x00, 0x00, // x=0, y=0
            0x00, 0x01, 0x00, 0x01, // w=1, h=1
            0x00, 0x00, 0x00, 0x00, // encoding = 0 (Raw)
            0, 0, 0, 0,              // 1 BGRA pixel
        )
        var calls = 0
        VncClient.readFramebufferUpdate(
            inp = java.io.ByteArrayInputStream(msg),
            targetArgb = IntArray(1),
            stride = 1,
            zrle = ZrleDecoder(),
            onMessageStart = { calls++ },
        )
        assertEquals(1, calls)
    }

    @Test fun `ZRLE-encoded rectangle round-trips through readFramebufferUpdate`() {
        // Solid-color 4x4 ZRLE tile (subencoding 1), zlib-compressed per RFB 6.4 -
        // same vector shape as ZrleDecoderTest's solid-tile case, but wrapped in a
        // full FramebufferUpdate message so it exercises the ENC_ZRLE dispatch too.
        fun cpixel(argb: Int) = byteArrayOf(
            (argb and 0xFF).toByte(),
            ((argb shr 8) and 0xFF).toByte(),
            ((argb shr 16) and 0xFF).toByte(),
        )
        val red = 0xFFFF0000.toInt()
        val plain = byteArrayOf(1) + cpixel(red) // subencoding 1 = solid
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, false)
        deflater.setInput(plain); deflater.finish()
        val zbuf = ByteArray(4096)
        val zout = java.io.ByteArrayOutputStream()
        while (!deflater.finished()) { val n = deflater.deflate(zbuf); zout.write(zbuf, 0, n) }
        val compressed = zout.toByteArray()

        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)              // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(4); d.writeShort(4)  // x=0,y=0,w=4,h=4
        d.writeInt(16)                                                // encoding = ZRLE
        d.writeInt(compressed.size)
        d.write(compressed)

        val target = IntArray(4 * 4)
        val r = VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 4, ZrleDecoder())

        assertEquals(red, target[0])
        assertEquals(red, target[15])
        assertEquals(listOf(VncRect(0, 0, 4, 4)), r.damage)
    }

    @Test fun `ExtendedDesktopSize rect reports new size and writes no pixels`() {
        // FramebufferUpdate: type=0, pad, numRects=1; rect x=0 y=0 w=800 h=600 enc=-308;
        // body: screens=1 pad[3]; screen{id=1,x=0,y=0,w=800,h=600,flags=0}
        val bos = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bos)
        d.writeByte(0); d.writeByte(0); d.writeShort(1)             // msg, pad, numRects
        d.writeShort(0); d.writeShort(0); d.writeShort(800); d.writeShort(600); d.writeInt(-308)
        d.writeByte(1); d.writeByte(0); d.writeByte(0); d.writeByte(0)   // screens=1 + pad3
        d.writeInt(1); d.writeShort(0); d.writeShort(0); d.writeShort(800); d.writeShort(600); d.writeInt(0)
        val target = IntArray(800 * 600)
        val r = VncClient.readFramebufferUpdate(java.io.ByteArrayInputStream(bos.toByteArray()), target, 800, ZrleDecoder())
        assertEquals(VncSize(800, 600), r.newSize)
    }
}
