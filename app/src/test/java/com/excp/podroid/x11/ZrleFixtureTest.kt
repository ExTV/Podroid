/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Decodes real Xvnc ZRLE captures through [VncClient.readFramebufferUpdate] and checks
 * the region against the SHA-256 of the same region decoded from a Raw capture.
 * Fixture format and hash layout are described in resources/x11/manifest.txt.
 */
class ZrleFixtureTest {

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/x11/$name")) { "missing test resource x11/$name" }
            .use { it.readBytes() }

    private fun regionSha256(fb: IntArray, stride: Int, x: Int, y: Int, w: Int, h: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        val px = ByteBuffer.allocate(4)
        for (row in y until y + h) for (col in x until x + w) {
            px.clear(); px.putInt(fb[row * stride + col]); md.update(px.array())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun `every ZRLE fixture decodes to the Raw capture's pixels`() {
        val entries = resource("manifest.txt").toString(Charsets.UTF_8).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue("manifest lists no fixtures", entries.isNotEmpty())
        for (entry in entries) {
            val fields = entry.split(' ')
            val file = fields[0]
            val expected = fields.first { it.startsWith("sha256=") }.removePrefix("sha256=")

            val bytes = resource(file)
            assertEquals("$file magic", "PDRF", String(bytes, 0, 4, Charsets.US_ASCII))
            val hdr = ByteBuffer.wrap(bytes, 4, 12)
            val fbW = hdr.short.toInt() and 0xFFFF; val fbH = hdr.short.toInt() and 0xFFFF
            val rx = hdr.short.toInt() and 0xFFFF; val ry = hdr.short.toInt() and 0xFFFF
            val rw = hdr.short.toInt() and 0xFFFF; val rh = hdr.short.toInt() and 0xFFFF

            val fb = IntArray(fbW * fbH)
            val inp = ByteArrayInputStream(bytes, 16, bytes.size - 16)
            val zrle = ZrleDecoder()
            while (inp.available() > 0) VncClient.readFramebufferUpdate(inp, fb, fbW, zrle)

            assertEquals("$file region hash", expected, regionSha256(fb, fbW, rx, ry, rw, rh))
        }
    }
}
