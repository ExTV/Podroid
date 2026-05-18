/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Minimal RFB 3.8 client. Supports SecurityType None, Raw + CopyRect +
 * Cursor pseudo encodings. Designed for loopback (SLIRP) so we don't
 * bother with Tight / ZRLE / TLS.
 */
package com.excp.podroid.x11

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

data class VncServerInfo(val width: Int, val height: Int, val name: String)

object VncClient {
    private const val PROTOCOL_VERSION = "RFB 003.008\n"
    private const val SEC_TYPE_NONE: Byte = 1

    /**
     * Performs the RFB 3.8 handshake. Reads the server greeting from `inp`,
     * writes our responses to `out`, and returns the framebuffer dimensions
     * (the only ServerInit fields we need for v1 — pixel format is fixed
     * 32-bit BGRA via SetPixelFormat sent later by the caller).
     *
     * Throws IOException on protocol mismatch.
     */
    fun handshake(inp: InputStream, out: OutputStream): VncServerInfo {
        val din = DataInputStream(inp)

        // 1. Read 12-byte version "RFB xxx.yyy\n"
        val serverVersion = ByteArray(12).also { din.readFully(it) }
        require(serverVersion[0] == 'R'.code.toByte()) { "not RFB greeting" }

        // 2. Send our version (always 003.008)
        out.write(PROTOCOL_VERSION.toByteArray())
        out.flush()

        // 3. Read security types. 0 => failure (not handled here)
        val numTypes = din.readUnsignedByte()
        require(numTypes > 0) { "server reported zero security types" }
        val types = ByteArray(numTypes).also { din.readFully(it) }
        require(types.any { it == SEC_TYPE_NONE }) { "server has no None auth" }

        // 4. Choose None
        out.write(byteArrayOf(SEC_TYPE_NONE))
        out.flush()

        // 5. Read SecurityResult (4 bytes; 0 = OK)
        val secResult = din.readInt()
        require(secResult == 0) { "security result $secResult" }

        // 6. Send ClientInit (1 byte: shared = 1)
        out.write(byteArrayOf(1))
        out.flush()

        // 7. Read ServerInit
        val w = din.readUnsignedShort()
        val h = din.readUnsignedShort()
        din.skipBytes(16) // pixel format we'll override
        val nameLen = din.readInt()
        val name = ByteArray(nameLen).also { din.readFully(it) }.toString(Charsets.UTF_8)

        return VncServerInfo(w, h, name)
    }

    private const val MSG_FRAMEBUFFER_UPDATE: Int = 0
    private const val ENC_RAW: Int = 0
    private const val ENC_COPY_RECT: Int = 1

    /**
     * Sends SetPixelFormat to lock the server to 32-bit BGRA, then SetEncodings
     * to advertise Raw + CopyRect. Call once after handshake before requesting
     * any framebuffer update.
     */
    fun negotiatePixelFormat(out: OutputStream) {
        // SetPixelFormat (msg=0): pad[3] + 16-byte PixelFormat
        val pf = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,                         // msg + 3 pad
            32, 24, 0, 1,                                   // bpp, depth, big-endian=0, true-color=1
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),  // max RGB
            16, 8, 0,                                       // shifts: R=16, G=8, B=0 (=> ARGB packed)
            0, 0, 0,                                        // padding
        )
        out.write(pf)

        // SetEncodings (msg=2): num-encodings=2, [Raw, CopyRect]
        val se = byteArrayOf(
            0x02, 0x00,                                     // msg + pad
            0x00, 0x02,                                     // count = 2
            0x00, 0x00, 0x00, 0x00,                         // Raw
            0x00, 0x00, 0x00, 0x01,                         // CopyRect
        )
        out.write(se)
        out.flush()
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

    /**
     * Reads a FramebufferUpdate message and copies decoded pixels into `targetArgb`
     * at row stride `stride`. Only Raw and CopyRect encodings are handled.
     */
    fun readFramebufferUpdate(inp: InputStream, targetArgb: IntArray, stride: Int) {
        val din = DataInputStream(inp)
        // RFB servers interleave non-rect messages (Bell, ColourMap, ClipBoard)
        // with FramebufferUpdates. Older versions of this client `require`'d
        // type==0 and bailed on type 3 (ServerCutText, clipboard share) which
        // Xvnc sends opportunistically — that's the "unexpected msg 3" the
        // user saw mid-Firefox-video. Skip the bytes for each non-fatal type
        // and loop until a FramebufferUpdate actually arrives.
        var msgType: Int
        while (true) {
            msgType = din.readUnsignedByte()
            when (msgType) {
                MSG_FRAMEBUFFER_UPDATE -> break  // fall through to rect parsing
                1 -> {  // SetColourMapEntries: pad(1) + first(2) + n(2) + n*6
                    din.skipBytes(1)
                    din.readUnsignedShort()
                    val n = din.readUnsignedShort()
                    din.skipBytes(n * 6)
                }
                2 -> { /* Bell: no body */ }
                3 -> {  // ServerCutText: pad(3) + length(4) + text[length]
                    din.skipBytes(3)
                    val len = din.readInt()
                    if (len in 0..(1 shl 20)) din.skipBytes(len)
                    else throw java.io.IOException("ServerCutText absurd length=$len")
                }
                else -> throw java.io.IOException("unexpected RFB server msg type $msgType")
            }
        }
        din.skipBytes(1)
        val numRects = din.readUnsignedShort()

        // Reused row scratch — only reallocated when a wider rect arrives.
        // Without this, a 1280x720 frame at 60 fps allocates 720 ByteArrays
        // per refresh (~300 KB/s GC pressure).
        var rowBuf: ByteArray? = null

        repeat(numRects) {
            val x = din.readUnsignedShort()
            val y = din.readUnsignedShort()
            val w = din.readUnsignedShort()
            val h = din.readUnsignedShort()
            val enc = din.readInt()

            when (enc) {
                ENC_RAW -> {
                    // 4 bytes BGRA per pixel.
                    val needed = w * 4
                    val existing = rowBuf
                    val rowPixels = if (existing == null || existing.size < needed) {
                        ByteArray(needed).also { rowBuf = it }
                    } else {
                        existing
                    }
                    for (row in 0 until h) {
                        din.readFully(rowPixels, 0, needed)
                        var off = 0
                        val baseIdx = (y + row) * stride + x
                        for (col in 0 until w) {
                            val b = rowPixels[off].toInt() and 0xFF
                            val g = rowPixels[off + 1].toInt() and 0xFF
                            val r = rowPixels[off + 2].toInt() and 0xFF
                            // Alpha byte from server is ignored — RFB true-color
                            // doesn't really have alpha; we force opaque.
                            targetArgb[baseIdx + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                            off += 4
                        }
                    }
                }
                ENC_COPY_RECT -> {
                    val srcX = din.readUnsignedShort()
                    val srcY = din.readUnsignedShort()
                    // Copy with downward/upward order awareness.
                    if (srcY < y) {
                        for (row in h - 1 downTo 0) {
                            System.arraycopy(
                                targetArgb, (srcY + row) * stride + srcX,
                                targetArgb, (y + row) * stride + x,
                                w,
                            )
                        }
                    } else {
                        for (row in 0 until h) {
                            System.arraycopy(
                                targetArgb, (srcY + row) * stride + srcX,
                                targetArgb, (y + row) * stride + x,
                                w,
                            )
                        }
                    }
                }
                else -> error("unsupported encoding $enc")
            }
        }
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
