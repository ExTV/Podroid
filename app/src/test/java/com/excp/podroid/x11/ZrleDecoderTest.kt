/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.zip.Deflater

class ZrleDecoderTest {
    /** Encode 3-byte CPIXEL from an ARGB int: order is B, G, R (little-endian channels). */
    private fun cpixel(argb: Int) = byteArrayOf(
        (argb and 0xFF).toByte(),           // B
        ((argb shr 8) and 0xFF).toByte(),   // G
        ((argb shr 16) and 0xFF).toByte()   // R
    )

    /** Compress plain bytes with zlib (nowrap=false = standard zlib header) and prepend 4-byte big-endian length. */
    private fun zrleRect(plain: ByteArray): ByteArray {
        val def = Deflater(Deflater.DEFAULT_COMPRESSION, /*nowrap=*/false)
        def.setInput(plain); def.finish()
        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!def.finished()) { val n = def.deflate(buf); out.write(buf, 0, n) }
        val z = out.toByteArray()
        val hdr = java.nio.ByteBuffer.allocate(4).putInt(z.size).array()
        return hdr + z
    }

    @Test fun `solid tile fills with its color`() {
        val red = 0xFFFF0000.toInt()
        val plain = byteArrayOf(1) + cpixel(red)                 // subencoding 1 = solid
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain)))
        val target = IntArray(4 * 4)
        ZrleDecoder().decode(din, 0, 0, 4, 4, target, 4)
        assertEquals(red, target[0]); assertEquals(red, target[15])
    }

    @Test fun `raw tile copies pixels`() {
        val a = 0xFF010203.toInt(); val b = 0xFF040506.toInt()
        val plain = byteArrayOf(0) + cpixel(a) + cpixel(b) + cpixel(a) + cpixel(b) // 2x2 raw
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain)))
        val target = IntArray(2 * 2)
        ZrleDecoder().decode(din, 0, 0, 2, 2, target, 2)
        assertEquals(a, target[0]); assertEquals(b, target[1]); assertEquals(a, target[2]); assertEquals(b, target[3])
    }

    /**
     * Marquee fix A: a rect whose compressed zlib block exceeds the 4096-byte
     * inputScratch must decode correctly. High-entropy raw tiles across a wide
     * rect compress to >4 KB, so the old pre-load loop dropped all but the last
     * 4 KB chunk and zlib reported "invalid distance code".
     */
    @Test fun `wide rect with compressed block over 4KB decodes correctly`() {
        // 320x64 rect = 5 raw tiles of 64x64. Pseudo-random colors so zlib can't
        // shrink it below 4096 bytes compressed.
        val w = 320; val h = 64
        val src = IntArray(w * h)
        var seed = 0x12345678
        for (i in src.indices) {
            // xorshift PRNG → high-entropy, deterministic
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            src[i] = (0xFF shl 24) or (seed and 0xFFFFFF)
        }
        // Build the ZRLE tile stream: row-major 64x64 raw tiles.
        val plain = java.io.ByteArrayOutputStream()
        var ty = 0
        while (ty < h) {
            val th = minOf(64, h - ty)
            var tx = 0
            while (tx < w) {
                val tw = minOf(64, w - tx)
                plain.write(0) // subencoding 0 = raw
                for (row in 0 until th) for (col in 0 until tw) {
                    plain.write(cpixel(src[(ty + row) * w + (tx + col)]))
                }
                tx += 64
            }
            ty += 64
        }
        val rect = zrleRect(plain.toByteArray())
        // Sanity: the compressed block must actually exceed inputScratch (4096).
        val compLen = java.nio.ByteBuffer.wrap(rect, 0, 4).int
        org.junit.Assert.assertTrue("compressed block must exceed 4096 (was $compLen)", compLen > 4096)

        val din = DataInputStream(ByteArrayInputStream(rect))
        val target = IntArray(w * h)
        ZrleDecoder().decode(din, 0, 0, w, h, target, w)
        org.junit.Assert.assertArrayEquals(src, target)
    }

    /**
     * Real Xvnc output: every rect is one SYNC_FLUSH segment of a single session-long
     * zlib stream, so each block ends with a few bytes (the empty stored block,
     * 00 00 FF FF) that produce no output. When those bytes are the only thing left
     * for the last 4096-byte input chunk, the tiles finish before that chunk is read,
     * and the unread tail is then parsed as the next rect header.
     */
    @Test fun `rect whose last input chunk is only the sync flush tail leaves stream aligned`() {
        // One SYNC_FLUSH segment of [def]'s stream, with the 4-byte length prefix.
        // The output buffer is large enough for one deflate() call, so each rect gets
        // exactly one flush, as a server does.
        fun syncRect(def: Deflater, plain: ByteArray): ByteArray {
            def.setInput(plain)
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536)
            while (true) {
                val n = def.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
                out.write(buf, 0, n)
                if (n < buf.size) break
            }
            val z = out.toByteArray()
            return java.nio.ByteBuffer.allocate(4).putInt(z.size).array() + z
        }
        // One 64x64 plain-RLE tile: `singles` one-pixel runs of pseudo-random colors,
        // then one run covering the rest. Varying `singles` moves the compressed
        // length a few bytes at a time.
        var seed = 0x2468ACE1
        val colors = IntArray(64 * 64) {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            (0xFF shl 24) or (seed and 0xFFFFFF)
        }
        fun rleTile(singles: Int): ByteArray {
            val plain = java.io.ByteArrayOutputStream()
            plain.write(128) // plain RLE
            for (i in 0 until singles) { plain.write(cpixel(colors[i])); plain.write(0) }
            plain.write(cpixel(colors[singles]))
            var rest = 64 * 64 - singles - 1 // run length minus one
            while (rest >= 255) { plain.write(255); rest -= 255 }
            plain.write(rest)
            return plain.toByteArray()
        }
        // Find a tile whose compressed block is k*4096 + 4 bytes, so the decoder's
        // last input chunk holds only the 4-byte stored-block tail. Probing uses a
        // throwaway Deflater with the same settings (first segment of a stream).
        var singles = -1
        for (n in 1 until 64 * 64) {
            val probe = Deflater(Deflater.DEFAULT_COMPRESSION, /*nowrap=*/false)
            val len = syncRect(probe, rleTile(n)).size - 4
            probe.end()
            if (len > 4096 && len % 4096 == 4) { singles = n; break }
        }
        assertTrue("no tile gives compLen % 4096 == 4", singles > 0)
        val expected1 = IntArray(64 * 64) { colors[minOf(it, singles)] }

        val def = Deflater(Deflater.DEFAULT_COMPRESSION, /*nowrap=*/false)
        val rect1 = syncRect(def, rleTile(singles))
        assertEquals(4, java.nio.ByteBuffer.wrap(rect1, 0, 4).int % 4096)
        val green = 0xFF00FF00.toInt()
        val rect2 = syncRect(def, byteArrayOf(1) + cpixel(green))

        val din = DataInputStream(ByteArrayInputStream(rect1 + rect2))
        val dec = ZrleDecoder()
        val t1 = IntArray(64 * 64)
        dec.decode(din, 0, 0, 64, 64, t1, 64)
        org.junit.Assert.assertArrayEquals(expected1, t1)
        val t2 = IntArray(4)
        dec.decode(din, 0, 0, 2, 2, t2, 2)
        assertEquals(green, t2[0]); assertEquals(green, t2[3])
        assertEquals(0, din.available())
    }

    // Subencoding 2 (1 bit per index) does not occur in the Xvnc captures under
    // resources/x11, so it is covered here. Width 10 leaves 6 padding bits per row.
    @Test fun `two colour packed palette tile decodes with row padding`() {
        val a = 0xFF102030.toInt(); val b = 0xFFA0B0C0.toInt()
        val w = 10; val h = 2
        val rows = arrayOf(
            intArrayOf(0, 1, 1, 0, 0, 0, 0, 0, 1, 1),
            intArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        )
        val plain = java.io.ByteArrayOutputStream()
        plain.write(2) // packed palette, 2 entries
        plain.write(cpixel(a)); plain.write(cpixel(b))
        for (r in rows) {
            var hi = 0; var lo = 0
            for (i in 0 until 8) hi = hi or (r[i] shl (7 - i))
            for (i in 8 until w) lo = lo or (r[i] shl (15 - i))
            plain.write(hi); plain.write(lo)
        }
        val target = IntArray(w * h)
        ZrleDecoder().decode(DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray()))), 0, 0, w, h, target, w)
        val expected = IntArray(w * h) { if (rows[it / w][it % w] == 1) b else a }
        org.junit.Assert.assertArrayEquals(expected, target)
    }

    // Subencoding 128 (plain RLE) does not occur in the Xvnc captures either. Runs
    // cross row boundaries and use the multi-byte (0xFF continuation) run length.
    @Test fun `plain RLE tile with runs crossing rows decodes`() {
        val a = 0xFF0000FF.toInt(); val b = 0xFF00FF00.toInt(); val c = 0xFFFF0000.toInt()
        val w = 64; val h = 64
        val plain = java.io.ByteArrayOutputStream()
        plain.write(128)
        plain.write(cpixel(a)); plain.write(0)                      // run 1
        plain.write(cpixel(b)); plain.write(0xFF); plain.write(44)  // run 255 + 44 + 1 = 300
        plain.write(cpixel(c))                                      // rest: 4096 - 301 = 3795
        var rest = w * h - 301 - 1
        while (rest >= 255) { plain.write(0xFF); rest -= 255 }
        plain.write(rest)
        val target = IntArray(w * h)
        ZrleDecoder().decode(DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray()))), 0, 0, w, h, target, w)
        val expected = IntArray(w * h) { if (it == 0) a else if (it <= 300) b else c }
        org.junit.Assert.assertArrayEquals(expected, target)
    }

    @Test fun `corrupt zlib data is an RfbProtocolException wrapping DataFormatException`() {
        // Valid zlib header, then a deflate block with the reserved BTYPE=11.
        val rect = java.nio.ByteBuffer.allocate(4).putInt(4).array() +
            byteArrayOf(0x78, 0x9C.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val ex = assertThrows(RfbProtocolException::class.java) {
            ZrleDecoder().decode(DataInputStream(ByteArrayInputStream(rect)), 0, 0, 2, 2, IntArray(4), 2)
        }
        assertTrue("cause was ${ex.cause}", ex.cause is java.util.zip.DataFormatException)
    }

    @Test fun `ZRLE content errors are RfbProtocolException`() {
        val plain = java.io.ByteArrayOutputStream()
        plain.write(128) // plain RLE, 2x2 tile, one run of 5 overruns it
        plain.write(cpixel(0xFFAABBCC.toInt()))
        plain.write(0x04)
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        assertThrows(RfbProtocolException::class.java) {
            ZrleDecoder().decode(din, 0, 0, 2, 2, IntArray(4), 2)
        }
        val unsupported = DataInputStream(ByteArrayInputStream(zrleRect(byteArrayOf(17))))
        assertThrows(RfbProtocolException::class.java) {
            ZrleDecoder().decode(unsupported, 0, 0, 1, 1, IntArray(1), 1)
        }
    }

    /** High: a packed-palette tile whose index exceeds the palette size → IOException, not AIOOBE. */
    @Test(expected = java.io.IOException::class)
    fun `packed palette index out of range throws IOException`() {
        // Palette of 2 entries (subenc 2, 1 bit/index). Feed a row byte 0b1000_0000:
        // first index = 1 (valid). Use a 1-entry palette via subenc would not be 2..16;
        // instead force overflow with n=2 but a single-color palette is fine — to
        // overflow we use n that makes idx exceed: build subenc=3 (n=3, 2 bits/idx),
        // so idx can be 0..3 but palette only has 3 entries; idx=3 overflows.
        val n = 3
        val plain = java.io.ByteArrayOutputStream()
        plain.write(n) // subencoding 3 = packed palette, 3 entries
        repeat(n) { plain.write(cpixel(0xFF000000.toInt() or it)) }
        // 1x1 tile: one packed byte. 2 bits/index, top 2 bits = 0b11 = idx 3 (out of range).
        plain.write(0b1100_0000)
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        ZrleDecoder().decode(din, 0, 0, 1, 1, IntArray(1), 1)
    }

    /** High: a plain-RLE run that overruns the tile total → IOException, not AIOOBE. */
    @Test(expected = java.io.IOException::class)
    fun `plain RLE run overrun throws IOException`() {
        // 2x2 tile (total=4). One run of length 5 (> 4) must be rejected.
        val plain = java.io.ByteArrayOutputStream()
        plain.write(128) // plain RLE
        plain.write(cpixel(0xFFAABBCC.toInt()))
        // run-length encoding: sum-of-bytes + 1 == 5 → sum 4 → single byte 0x04
        plain.write(0x04)
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        ZrleDecoder().decode(din, 0, 0, 2, 2, IntArray(4), 2)
    }

    // Fix 3: a run-length whose accumulated sum exceeds the maximum tile size
    // (4096 pixels for a 64x64 tile) must be rejected inside readRunLength itself,
    // by the explicit accumulator cap, BEFORE the sum can be used. Without the cap
    // a sufficiently long 0xFF run overflows Int and wraps the sum negative,
    // defeating the caller's post-hoc (filled + runLen > total) overrun guard.
    //
    // The fixture (17 x 0xFF + 0x00 → run = 4336) is caught on BOTH paths, so the
    // exception type alone proves nothing: pre-fix the caller's overrun guard fires
    // ("plain RLE run overruns tile"), post-fix the cap fires ("exceeds max tile
    // size"). Asserting on the message pins detection to the cap — RED pre-fix
    // (message mismatch), GREEN post-fix.
    @Test
    fun `ZRLE run length exceeding tile size throws from readRunLength cap`() {
        val plain = java.io.ByteArrayOutputStream()
        plain.write(128) // plain RLE
        plain.write(cpixel(0xFFAABBCC.toInt()))
        // 17 x 0xFF: running sum = 17*255 = 4335, exceeds the 4096-pixel cap.
        repeat(17) { plain.write(0xFF) }
        plain.write(0x00)
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        val ex = assertThrows(IOException::class.java) {
            ZrleDecoder().decode(din, 0, 0, 64, 64, IntArray(64 * 64), 64)
        }
        assertTrue(
            "expected the readRunLength cap to fire, was: ${ex.message}",
            ex.message?.contains("exceeds max tile size") == true,
        )
    }

    /** High: a palette-RLE single-pixel index beyond the palette → IOException, not AIOOBE. */
    @Test(expected = java.io.IOException::class)
    fun `palette RLE index out of range throws IOException`() {
        // Palette of 2 entries (subenc 130). A single-pixel index byte 0x05 (bit7=0,
        // index 5) exceeds the 2-entry palette.
        val plain = java.io.ByteArrayOutputStream()
        plain.write(130) // palette RLE, n = 2
        plain.write(cpixel(0xFF111111.toInt())); plain.write(cpixel(0xFF222222.toInt()))
        plain.write(0x05) // single pixel, index 5 → out of range
        val din = DataInputStream(ByteArrayInputStream(zrleRect(plain.toByteArray())))
        ZrleDecoder().decode(din, 0, 0, 2, 2, IntArray(4), 2)
    }
}
