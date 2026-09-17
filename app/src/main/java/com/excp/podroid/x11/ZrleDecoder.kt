/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * ZRLE (RFB encoding 16) decoder.
 *
 * Wire format per rect:
 *   4 bytes big-endian compressed-data length
 *   N bytes zlib-compressed tile stream (standard zlib header, nowrap=false)
 *
 * The zlib stream is continuous across rects for the lifetime of the RFB session,
 * so a single Inflater is held as instance state and must NOT be re-created per call.
 *
 * Tiles are 64x64 max, row-major (left to right, top to bottom) covering the w*h rect.
 * Each tile begins with 1 subencoding byte:
 *   0        raw:          tw*th CPIXELs in raster order
 *   1        solid:        1 CPIXEL fills the whole tile
 *   2..16    packed-palette: N CPIXELs palette, then packed indices (1/2/4 bpp), row-aligned
 *   128      plain RLE:    sequence of (CPIXEL, run-length); run-length = sum-of-bytes+1
 *                          (each 0xFF means +255 and continue; final <0xFF byte ends the run)
 *   130..255 palette RLE:  palette of (subenc-128) CPIXELs; then index bytes:
 *                          bit7=0 -> single pixel palette[index]; bit7=1 -> palette[index&0x7F]
 *                          repeated run-length times (same sum-of-bytes+1 encoding)
 *
 * CPIXEL = 3 bytes in wire order B, G, R (matching the negotiated 32bpp/depth-24/LE/R16-G8-B0
 * pixel format). Decoded to ARGB: 0xFF_000000 | R<<16 | G<<8 | B.
 */
package com.excp.podroid.x11

import java.io.DataInputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class ZrleDecoder {

    private val inflater = Inflater(/*nowrap=*/false)

    companion object {
        // Maximum pixels in a single ZRLE tile (64x64). Run-length accumulators
        // are capped at this value to prevent Int overflow from wrapping the sum
        // to a large negative number that defeats the overrun guard.
        private const val MAX_TILE_PIXELS = 64 * 64
    }

    // Scratch buffer for compressed input read from the socket.
    private var inputScratch = ByteArray(4096)
    // Decompressed output buffer; re-used across inflate calls within one decode() call.
    private var outputBuf = ByteArray(4096)

    /**
     * Resets decoder state between RFB sessions.
     *
     * The zlib stream is continuous within a single RFB session, so the [Inflater]
     * must NOT be reset between rects. It MUST be reset on reconnect: a new RFB
     * session starts a fresh zlib stream, and feeding it into a finished or leftover
     * inflater produces corrupt output or a DataFormatException.
     *
     * Call this from the session-level connect path (X11ViewModel.connect()), not
     * from the per-rect decode path.
     */
    fun reset() {
        inflater.reset()
    }

    /**
     * Decodes one ZRLE-encoded rectangle into [target].
     *
     * @param din    source stream positioned at the 4-byte length prefix of the ZRLE data
     * @param x      left edge of the rectangle in the framebuffer
     * @param y      top edge of the rectangle in the framebuffer
     * @param w      rectangle width
     * @param h      rectangle height
     * @param target ARGB framebuffer array
     * @param stride row stride of [target] (pixels per row)
     */
    fun decode(din: DataInputStream, x: Int, y: Int, w: Int, h: Int, target: IntArray, stride: Int) {
        // Read and decompress the rect's zlib block.
        val compLen = din.readInt()
        if (compLen < 0 || compLen > 64 * 1024 * 1024) throw RfbProtocolException("ZRLE: absurd compressed length $compLen")

        // Stream the compressed block on demand. setInput() does not copy/append —
        // it holds inputScratch by reference and is consumed lazily by inflate(),
        // so pre-loading multiple chunks into one buffer would drop all but the
        // last. Instead ZInput.fill() reads the next chunk only once the inflater
        // has consumed the previous one, bounded by its own `remaining` (this
        // rect's compLen) so it never crosses the rect boundary.
        // Wrap the inflater so tile-level code just calls readByte()/readBytes().
        val zi = ZInput(inflater, din, compLen)

        try {
            // Tile loop: 64x64 tiles in row-major order.
            var ty = 0
            while (ty < h) {
                val th = minOf(64, h - ty)
                var tx = 0
                while (tx < w) {
                    val tw = minOf(64, w - tx)
                    decodeTile(zi, x + tx, y + ty, tw, th, target, stride)
                    tx += 64
                }
                ty += 64
            }
            // The tiles can finish before this rect's compressed bytes are all read: a
            // SYNC_FLUSH block ends with bytes that inflate to nothing (00 00 FF FF).
            // Consume them so the socket stays aligned on the next rect header.
            zi.consumeRest()
        } catch (e: DataFormatException) {
            // Corrupt compressed data is a decode fault, not a transport failure.
            throw RfbProtocolException("ZRLE: corrupt zlib data: ${e.message}", e)
        }
    }

    private fun decodeTile(zi: ZInput, tx: Int, ty: Int, tw: Int, th: Int, target: IntArray, stride: Int) {
        val subenc = zi.readByte()
        when {
            subenc == 0 -> {
                // Raw: tw*th CPIXELs.
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    for (col in 0 until tw) {
                        target[base + col] = zi.readCpixel()
                    }
                }
            }
            subenc == 1 -> {
                // Solid: 1 CPIXEL, fill the whole tile.
                val color = zi.readCpixel()
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    for (col in 0 until tw) target[base + col] = color
                }
            }
            subenc in 2..16 -> {
                // Packed palette.
                val n = subenc
                val palette = IntArray(n) { zi.readCpixel() }
                val bitsPerIndex = when {
                    n == 2 -> 1
                    n <= 4 -> 2
                    else -> 4
                }
                for (row in 0 until th) {
                    val base = (ty + row) * stride + tx
                    // Each row is bit-packed, byte-aligned.
                    var col = 0
                    var accumByte = 0
                    var bitsInAccum = 0
                    while (col < tw) {
                        if (bitsInAccum == 0) {
                            accumByte = zi.readByte()
                            bitsInAccum = 8
                        }
                        val idx = (accumByte ushr (8 - bitsPerIndex)) and ((1 shl bitsPerIndex) - 1)
                        accumByte = (accumByte shl bitsPerIndex) and 0xFF
                        bitsInAccum -= bitsPerIndex
                        // bitsPerIndex rounds up, so the index space can exceed n
                        // when n is not a power of two.
                        if (idx >= n) throw RfbProtocolException("ZRLE: packed palette index $idx >= $n")
                        target[base + col] = palette[idx]
                        col++
                    }
                    // Discard any padding bits at end of row (bitsInAccum may be > 0 but
                    // we already read the full byte; nothing extra to consume).
                }
            }
            subenc == 128 -> {
                // Plain RLE: sequence of runs until tile is full.
                val total = tw * th
                var filled = 0
                while (filled < total) {
                    val color = zi.readCpixel()
                    val runLen = zi.readRunLength()
                    if (filled + runLen > total) throw RfbProtocolException("ZRLE: plain RLE run overruns tile ($filled+$runLen > $total)")
                    fillRun(target, stride, tx, ty, tw, filled, runLen, color)
                    filled += runLen
                }
            }
            subenc in 130..255 -> {
                // Palette RLE.
                val n = subenc - 128
                val palette = IntArray(n) { zi.readCpixel() }
                val total = tw * th
                var filled = 0
                while (filled < total) {
                    val indexByte = zi.readByte()
                    if (indexByte and 0x80 == 0) {
                        // Single pixel.
                        if (indexByte >= n) throw RfbProtocolException("ZRLE: palette RLE index $indexByte >= $n")
                        val pos = filled
                        val row = pos / tw; val col = pos % tw
                        target[(ty + row) * stride + (tx + col)] = palette[indexByte]
                        filled++
                    } else {
                        // Run of palette[index & 0x7F].
                        val idx = indexByte and 0x7F
                        if (idx >= n) throw RfbProtocolException("ZRLE: palette RLE index $idx >= $n")
                        val color = palette[idx]
                        val runLen = zi.readRunLength()
                        if (filled + runLen > total) throw RfbProtocolException("ZRLE: palette RLE run overruns tile ($filled+$runLen > $total)")
                        fillRun(target, stride, tx, ty, tw, filled, runLen, color)
                        filled += runLen
                    }
                }
            }
            else -> throw RfbProtocolException("ZRLE: unsupported subencoding $subenc")
        }
    }

    /** Fills [runLen] pixels of [color] into [target] starting at tile-relative offset [filled]. */
    private fun fillRun(target: IntArray, stride: Int, tx: Int, ty: Int, tw: Int, filled: Int, runLen: Int, color: Int) {
        repeat(runLen) {
            val pos = filled + it
            val row = pos / tw
            val col = pos % tw
            target[(ty + row) * stride + (tx + col)] = color
        }
    }

    /**
     * Thin wrapper around [Inflater] that provides byte-level and CPIXEL reads.
     * Compressed input is fed to the inflater on demand via [feedChunk], bounded
     * by this rect's compressed length ([remaining]); this class only drains
     * inflated output.
     */
    private inner class ZInput(private val inf: Inflater, private val din: DataInputStream, private var remaining: Int) {
        private val buf = ByteArray(256)
        private var pos = 0
        private var avail = 0

        private fun fill() {
            while (avail == 0) {
                if (inf.finished()) throw RfbProtocolException("ZRLE: inflater finished early")
                // Feed the next compressed chunk on demand, bounded by this rect's
                // remaining budget. Each chunk is fully consumed before its buffer
                // is reused, and we never read past compLen.
                if (inf.needsInput() && remaining > 0) feedChunk()
                val n = inf.inflate(buf)
                if (n > 0) { pos = 0; avail = n }
                // n == 0 with needsInput means all input was consumed; if finished() is false
                // but no output and needsInput, the caller overfed or the stream is malformed.
                else if (inf.needsInput()) throw RfbProtocolException("ZRLE: inflater needs more input but none queued")
                // n == 0, not finished, not needsInput → needsDictionary or a stuck
                // stream: bail rather than spin forever.
                else throw RfbProtocolException("ZRLE: inflater made no progress")
            }
        }

        private fun feedChunk() {
            val nIn = minOf(remaining, inputScratch.size)
            din.readFully(inputScratch, 0, nIn)
            inf.setInput(inputScratch, 0, nIn)
            remaining -= nIn
        }

        /** Reads and inflates this rect's unread compressed bytes, which must yield no pixels. */
        fun consumeRest() {
            while (remaining > 0) {
                if (inf.needsInput()) feedChunk()
                if (inf.inflate(buf) > 0) throw RfbProtocolException("ZRLE: rect data longer than its tiles")
                if (!inf.needsInput()) throw RfbProtocolException("ZRLE: inflater made no progress")
            }
        }

        fun readByte(): Int {
            fill()
            return buf[pos++].toInt().also { avail-- } and 0xFF
        }

        /** Read 3-byte CPIXEL (B, G, R) and return as ARGB int. */
        fun readCpixel(): Int {
            val b = readByte()
            val g = readByte()
            val r = readByte()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        /**
         * Read a ZRLE run-length (sum-of-bytes + 1).
         * Each byte 0xFF contributes 255 and reading continues;
         * the first byte < 0xFF ends the sequence, contributing its value.
         * The actual run count = sum + 1.
         *
         * The accumulator is capped at MAX_TILE_PIXELS (4096, the pixel count of
         * the largest possible 64x64 tile) to prevent Int overflow: without the cap,
         * enough 0xFF bytes would wrap the sum to a large negative number, defeating
         * the caller's post-hoc (filled + runLen > total) overrun check.
         */
        fun readRunLength(): Int {
            var total = 0
            while (true) {
                val b = readByte()
                total += b
                if (total > MAX_TILE_PIXELS) throw RfbProtocolException("ZRLE: run length $total exceeds max tile size")
                if (b != 0xFF) break
            }
            return total + 1
        }
    }
}
