/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Minimal RFB 3.8 client. Supports SecurityType None, Raw + CopyRect +
 * ExtendedDesktopSize + ZRLE encodings. Used over a loopback connection on
 * both backends (the VNC port is an implicit loopback forward).
 */
package com.excp.podroid.x11

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

data class VncServerInfo(val width: Int, val height: Int, val name: String)

/**
 * The server sent data this client cannot parse or decode (bad handshake field,
 * unexpected message type, out-of-bounds rect, unsupported encoding, corrupt
 * ZRLE/zlib data). Transport failures (EOF, socket errors, timeouts) are never
 * this type, so a normal disconnect can be told apart from a decode fault.
 */
class RfbProtocolException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/** Pure encoding choice and ZRLE-to-Raw fallback decision for an RFB session. */
object EncodingPolicy {
    fun encodingsFor(wantZrle: Boolean, zrleDisabled: Boolean): IntArray =
        if (wantZrle && !zrleDisabled) VncClient.ZRLE_ENCODINGS else VncClient.DEFAULT_ENCODINGS

    /** True only when the session advertised ZRLE and a protocol error is in [error]'s cause chain. */
    fun shouldFallBack(error: Throwable, sessionUsedZrle: Boolean): Boolean =
        sessionUsedZrle && generateSequence(error) { it.cause }.any { it is RfbProtocolException }
}

object VncClient {
    private const val PROTOCOL_VERSION = "RFB 003.008\n"
    private const val SEC_TYPE_NONE: Byte = 1

    /**
     * Performs the RFB 3.8 handshake. Reads the server greeting from `inp`,
     * writes our responses to `out`, and returns the framebuffer dimensions
     * (the only ServerInit fields we need for v1: pixel format is fixed to
     * 32bpp little-endian, depth 24, R shift 16 / G shift 8 / B shift 0 via
     * SetPixelFormat sent later by the caller).
     *
     * Throws RfbProtocolException on protocol mismatch.
     */
    fun handshake(inp: InputStream, out: OutputStream): VncServerInfo {
        val din = DataInputStream(inp)

        // 1. Read 12-byte version "RFB xxx.yyy\n"
        val serverVersion = ByteArray(12).also { din.readFully(it) }
        if (serverVersion[0] != 'R'.code.toByte()) throw RfbProtocolException("not RFB greeting")

        // 2. Send our version (always 003.008)
        out.write(PROTOCOL_VERSION.toByteArray())
        out.flush()

        // 3. Read security types. 0 => failure (not handled here)
        val numTypes = din.readUnsignedByte()
        if (numTypes <= 0) throw RfbProtocolException("server reported zero security types")
        val types = ByteArray(numTypes).also { din.readFully(it) }
        if (types.none { it == SEC_TYPE_NONE }) throw RfbProtocolException("server has no None auth")

        // 4. Choose None
        out.write(byteArrayOf(SEC_TYPE_NONE))
        out.flush()

        // 5. Read SecurityResult (4 bytes; 0 = OK)
        val secResult = din.readInt()
        if (secResult != 0) throw RfbProtocolException("security result $secResult")

        // 6. Send ClientInit (1 byte: shared = 1)
        out.write(byteArrayOf(1))
        out.flush()

        // 7. Read ServerInit
        val w = din.readUnsignedShort()
        val h = din.readUnsignedShort()
        skipFully(din, 16) // pixel format we'll override
        val nameLen = din.readInt()
        if (nameLen !in 0..(1 shl 20)) throw RfbProtocolException("RFB name length $nameLen")
        val name = ByteArray(nameLen).also { din.readFully(it) }.toString(Charsets.UTF_8)

        return VncServerInfo(w, h, name)
    }

    /**
     * Reads and discards exactly [n] bytes from [din]. DataInputStream.skipBytes
     * may skip fewer than requested over a socket when not all bytes have
     * arrived; a short skip would leave unconsumed bytes and desync RFB framing.
     * No behavior change when bytes are already buffered.
     */
    private fun skipFully(din: DataInputStream, n: Int) {
        var left = n
        val scratch = ByteArray(minOf(n, 4096).coerceAtLeast(1))
        while (left > 0) {
            val r = din.read(scratch, 0, minOf(left, scratch.size))
            if (r < 0) throw java.io.IOException("RFB: EOF skipping $n bytes ($left remaining)")
            left -= r
        }
    }

    /**
     * Validates a server-supplied rect against the framebuffer before any pixel
     * access. Reachable on a resolution-change race where the server sends a
     * rect sized to a different desktop than the client's current buffer.
     * No-op for in-range rects.
     */
    private fun requireInBounds(x: Int, y: Int, w: Int, h: Int, stride: Int, size: Int) {
        if (stride <= 0) throw RfbProtocolException("RFB: zero or negative stride $stride")
        val rows = size / stride
        if (x < 0 || y < 0 || w < 0 || h < 0 || x + w > stride || y + h > rows)
            throw RfbProtocolException("RFB rect out of bounds: x=$x y=$y w=$w h=$h stride=$stride size=$size")
    }

    private const val MSG_FRAMEBUFFER_UPDATE: Int = 0
    private const val ENC_RAW: Int = 0
    private const val ENC_COPY_RECT: Int = 1
    private const val ENC_ZRLE = 16
    private const val ENC_EXTENDED_DESKTOP_SIZE = -308

    // Default SetEncodings list: CopyRect, Raw, ExtendedDesktopSize(-308). ZRLE
    // is opt-in only, via the debug-only x11-debug.conf switch (read only when
    // BuildConfig.DEBUG); a ZRLE session that hits a protocol error falls back
    // to this Raw list for the rest of the ViewModel's lifetime (see
    // EncodingPolicy). The release default stays Raw.
    val DEFAULT_ENCODINGS: IntArray = intArrayOf(ENC_COPY_RECT, ENC_RAW, ENC_EXTENDED_DESKTOP_SIZE)
    val ZRLE_ENCODINGS: IntArray = intArrayOf(ENC_ZRLE, ENC_COPY_RECT, ENC_RAW, ENC_EXTENDED_DESKTOP_SIZE)

    /**
     * Sends SetPixelFormat to lock the server to 32bpp little-endian, depth 24,
     * R shift 16 / G shift 8 / B shift 0, then SetEncodings to advertise
     * [encodings] (default: Raw + CopyRect + ExtendedDesktopSize, byte-identical
     * to the previously hardcoded list). Call once after handshake before
     * requesting any framebuffer update.
     */
    fun negotiatePixelFormat(out: OutputStream, encodings: IntArray = DEFAULT_ENCODINGS) {
        // SetPixelFormat (msg=0): pad[3] + 16-byte PixelFormat
        val pf = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,                         // msg + 3 pad
            32, 24, 0, 1,                                   // bpp, depth, big-endian=0, true-color=1
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),  // max RGB
            16, 8, 0,                                       // shifts: R=16, G=8, B=0 (=> ARGB packed)
            0, 0, 0,                                        // padding
        )
        out.write(pf)

        // SetEncodings (msg=2): header + one int32 per encoding, in order.
        val se = java.nio.ByteBuffer.allocate(4 + encodings.size * 4)
        se.put(2.toByte()); se.put(0.toByte()); se.putShort(encodings.size.toShort())
        for (enc in encodings) se.putInt(enc)
        out.write(se.array()); out.flush()
    }

    /**
     * Send a FramebufferUpdateRequest. `incremental=false` forces the server
     * to send a full refresh (use after first connect or on reconnect).
     */
    fun requestFramebufferUpdate(
        out: OutputStream,
        x: Int = 0, y: Int = 0,
        w: Int = X11Constants.FB_WIDTH,
        h: Int = X11Constants.FB_HEIGHT,
        incremental: Boolean = true,
    ) {
        val buf = java.nio.ByteBuffer.allocate(10)
        buf.put(3.toByte())                                 // msg-type
        buf.put(if (incremental) 1.toByte() else 0)
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        buf.putShort(w.toShort())
        buf.putShort(h.toShort())
        out.write(buf.array())
        out.flush()
    }

    data class RfbUpdate(val newSize: VncSize?, val damage: List<VncRect>)

    /**
     * [onMessageStart], when non-null, is invoked exactly once, right when the
     * message-type byte for the FramebufferUpdate itself is read (i.e. once
     * its first byte is available on the wire), not for any Bell/
     * SetColourMapEntries/ServerCutText messages skipped beforehand. Callers
     * use this to time transfer + decode of one update without VncClient
     * depending on any timing/stats type itself.
     */
    fun readFramebufferUpdate(
        inp: InputStream,
        targetArgb: IntArray,
        stride: Int,
        zrle: ZrleDecoder,
        onMessageStart: (() -> Unit)? = null,
    ): RfbUpdate {
        val din = DataInputStream(inp)
        var msgType: Int
        while (true) {
            msgType = din.readUnsignedByte()
            when (msgType) {
                MSG_FRAMEBUFFER_UPDATE -> { onMessageStart?.invoke(); break }
                1 -> { skipFully(din, 1); din.readUnsignedShort(); val n = din.readUnsignedShort(); skipFully(din, n * 6) }
                2 -> { }
                3 -> { skipFully(din, 3); val len = din.readInt(); if (len in 0..(1 shl 20)) skipFully(din, len) else throw RfbProtocolException("ServerCutText absurd length=$len") }
                else -> throw RfbProtocolException("unexpected RFB server msg type $msgType")
            }
        }
        skipFully(din, 1)
        val numRects = din.readUnsignedShort()
        var rowBuf: ByteArray? = null
        var newSize: VncSize? = null
        val damage = ArrayList<VncRect>(numRects)

        repeat(numRects) {
            val x = din.readUnsignedShort(); val y = din.readUnsignedShort()
            val w = din.readUnsignedShort(); val h = din.readUnsignedShort()
            val enc = din.readInt()
            when (enc) {
                ENC_EXTENDED_DESKTOP_SIZE -> {       // -308: w/h are the new fb dims
                    val screens = din.readUnsignedByte(); skipFully(din, 3)
                    skipFully(din, screens * 16)     // we use a single-screen model; dims come from w/h
                    if (w <= 0 || h <= 0) throw RfbProtocolException("RFB ExtendedDesktopSize: degenerate geometry w=$w h=$h")
                    newSize = VncSize(w, h)
                }
                ENC_RAW -> {
                    requireInBounds(x, y, w, h, stride, targetArgb.size)
                    val needed = w * 4
                    val rowPixels = rowBuf?.takeIf { it.size >= needed } ?: ByteArray(needed).also { rowBuf = it }
                    for (row in 0 until h) {
                        din.readFully(rowPixels, 0, needed)
                        val base = (y + row) * stride + x
                        // Wire pixel is BGRX little-endian, so reading it as one
                        // little-endian int32 gives 0xXXRRGGBB directly (R shift
                        // 16, G shift 8, B shift 0); no per-byte masking needed.
                        // OR-ing 0xFF000000 forces alpha to 0xFF regardless of the
                        // padding byte X, matching the old per-byte loop exactly.
                        java.nio.ByteBuffer.wrap(rowPixels, 0, needed)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .asIntBuffer()
                            .get(targetArgb, base, w)
                        for (col in base until base + w) targetArgb[col] = targetArgb[col] or (0xFF shl 24)
                    }
                    damage.add(VncRect(x, y, w, h))
                }
                ENC_COPY_RECT -> {
                    val srcX = din.readUnsignedShort(); val srcY = din.readUnsignedShort()
                    requireInBounds(x, y, w, h, stride, targetArgb.size)
                    requireInBounds(srcX, srcY, w, h, stride, targetArgb.size)
                    if (srcY < y) for (row in h - 1 downTo 0) System.arraycopy(targetArgb, (srcY + row) * stride + srcX, targetArgb, (y + row) * stride + x, w)
                    else for (row in 0 until h) System.arraycopy(targetArgb, (srcY + row) * stride + srcX, targetArgb, (y + row) * stride + x, w)
                    damage.add(VncRect(x, y, w, h))
                }
                ENC_ZRLE -> {
                    requireInBounds(x, y, w, h, stride, targetArgb.size)
                    zrle.decode(din, x, y, w, h, targetArgb, stride)
                    damage.add(VncRect(x, y, w, h))
                }
                else -> throw RfbProtocolException("unsupported encoding $enc")
            }
        }
        return RfbUpdate(newSize, damage)
    }

    const val BTN_LEFT = 1; const val BTN_MIDDLE = 2; const val BTN_RIGHT = 4
    const val BTN_WHEEL_UP = 8; const val BTN_WHEEL_DOWN = 16

    /** SetDesktopSize (msg 251): request a single-screen desktop of width x height. */
    fun requestDesktopSize(out: OutputStream, screenId: Int, width: Int, height: Int) {
        val buf = java.nio.ByteBuffer.allocate(24)   // 8 header + 16 screen
        buf.put(251.toByte()); buf.put(0.toByte())
        buf.putShort(width.toShort()); buf.putShort(height.toShort())
        buf.put(1.toByte()); buf.put(0.toByte())     // number-of-screens=1, pad
        buf.putInt(screenId)                         // id
        buf.putShort(0); buf.putShort(0)             // x, y
        buf.putShort(width.toShort()); buf.putShort(height.toShort())
        buf.putInt(0)                                // flags
        out.write(buf.array()); out.flush()
    }

    private const val MSG_KEY_EVENT: Byte = 4
    private const val MSG_POINTER_EVENT: Byte = 5

    /**
     * Sends a PointerEvent. `buttonMask` bit i = button (i+1) pressed.
     * Bit 0 = left, 1 = middle, 2 = right, 3 = scroll-up, 4 = scroll-down.
     */
    fun sendPointer(out: OutputStream, x: Int, y: Int, buttonMask: Int) {
        val buf = java.nio.ByteBuffer.allocate(6)
        buf.put(MSG_POINTER_EVENT)
        buf.put(buttonMask.toByte())
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        out.write(buf.array())
        out.flush()
    }

    /**
     * Sends a KeyEvent. `keysym` is an X11 keysym (e.g. 0x61 for 'a').
     */
    fun sendKey(out: OutputStream, keysym: Int, down: Boolean) {
        val buf = java.nio.ByteBuffer.allocate(8)
        buf.put(MSG_KEY_EVENT)
        buf.put(if (down) 1.toByte() else 0)
        buf.putShort(0)
        buf.putInt(keysym)
        out.write(buf.array())
        out.flush()
    }
}
