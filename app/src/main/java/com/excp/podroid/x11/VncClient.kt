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
}
