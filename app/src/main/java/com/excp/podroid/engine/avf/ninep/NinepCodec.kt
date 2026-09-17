package com.excp.podroid.engine.avf.ninep

import androidx.annotation.VisibleForTesting
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 9p2000.L message type numbers, taken from the 9P2000.L spec / Linux
 * include/net/9p/9p.h. Wire is little-endian, framed size[4] type[1] tag[2] body.
 */
object Ninep {
    const val Rlerror = 7
    const val Tlopen = 12
    const val Rlopen = 13
    const val Tlcreate = 14
    const val Rlcreate = 15
    const val Tgetattr = 24
    const val Rgetattr = 25
    const val Tsetattr = 26
    const val Rsetattr = 27
    const val Treaddir = 40
    const val Rreaddir = 41
    const val Tfsync = 50
    const val Rfsync = 51
    const val Tmkdir = 72
    const val Rmkdir = 73
    const val Trenameat = 74
    const val Rrenameat = 75
    const val Tunlinkat = 76
    const val Runlinkat = 77
    const val Tversion = 100
    const val Rversion = 101
    const val Tattach = 104
    const val Rattach = 105
    const val Twalk = 110
    const val Rwalk = 111
    const val Tread = 116
    const val Rread = 117
    const val Twrite = 118
    const val Rwrite = 119
    const val Tclunk = 120
    const val Rclunk = 121
    const val Tremove = 122
    const val Rremove = 123
}

/** A decoded 9p message frame: type[1] tag[2] body (size prefix already consumed). */
data class NinepFrame(val type: Int, val tag: Int, val body: ByteArray)

/**
 * Little-endian 9p2000.L wire primitives, plus whole-message framing.
 * Pure Kotlin/java.io, no Android dependency, so it unit-tests under a plain JVM.
 */
object NinepCodec {

    /** Minimum frame size: size[4] + type[1] + tag[2]. */
    private const val HEADER_SIZE = 7

    /**
     * Hard ceiling for a single frame's declared size, matching the server's msize
     * ceiling. [readFrame] enforces this before allocating the body array, so a
     * corrupt or hostile size prefix can never trigger an oversized allocation.
     */
    const val MAX_FRAME_SIZE = 262144

    /** Reads little-endian primitives out of an in-memory byte array with a cursor. */
    class Reader(private val data: ByteArray, private var pos: Int = 0) {
        @VisibleForTesting
        fun readU8(): Int {
            val v = data[pos].toInt() and 0xFF
            pos += 1
            return v
        }

        fun readU16(): Int {
            val b0 = data[pos].toInt() and 0xFF
            val b1 = data[pos + 1].toInt() and 0xFF
            pos += 2
            return b0 or (b1 shl 8)
        }

        fun readU32(): Long {
            val b0 = data[pos].toLong() and 0xFF
            val b1 = data[pos + 1].toLong() and 0xFF
            val b2 = data[pos + 2].toLong() and 0xFF
            val b3 = data[pos + 3].toLong() and 0xFF
            pos += 4
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        fun readU64(): Long {
            var v = 0L
            for (i in 0 until 8) {
                v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
            }
            pos += 8
            return v
        }

        fun readString(): String {
            val len = readU16()
            val s = String(data, pos, len, StandardCharsets.UTF_8)
            pos += len
            return s
        }

        fun readBytes(len: Int): ByteArray {
            val out = data.copyOfRange(pos, pos + len)
            pos += len
            return out
        }

        fun remaining(): Int = data.size - pos
    }

    /** Writes little-endian primitives to an OutputStream. */
    class Writer(private val out: OutputStream) {
        fun writeU8(v: Int) {
            out.write(v and 0xFF)
        }

        fun writeU16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        fun writeU32(v: Long) {
            out.write((v and 0xFF).toInt())
            out.write(((v ushr 8) and 0xFF).toInt())
            out.write(((v ushr 16) and 0xFF).toInt())
            out.write(((v ushr 24) and 0xFF).toInt())
        }

        fun writeU64(v: Long) {
            for (i in 0 until 8) {
                out.write(((v ushr (8 * i)) and 0xFF).toInt())
            }
        }

        fun writeString(s: String) {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            writeU16(bytes.size)
            out.write(bytes)
        }

        fun writeBytes(b: ByteArray) {
            out.write(b)
        }
    }

    /**
     * Reads one framed message: size[4] type[1] tag[2] body[size-7].
     * Returns null on clean EOF (no bytes read before end of stream).
     *
     * [maxSize] bounds the declared size and is checked before the body array is
     * allocated, so an oversized or overflow-prone size prefix fails fast instead
     * of attempting a huge (or, once truncated to Int, negative) allocation.
     */
    fun readFrame(input: InputStream, maxSize: Int = MAX_FRAME_SIZE): NinepFrame? {
        val sizeBytes = readFully(input, 4) ?: return null
        val size = (sizeBytes[0].toLong() and 0xFF) or
            ((sizeBytes[1].toLong() and 0xFF) shl 8) or
            ((sizeBytes[2].toLong() and 0xFF) shl 16) or
            ((sizeBytes[3].toLong() and 0xFF) shl 24)
        require(size >= HEADER_SIZE) { "9p frame size $size smaller than header $HEADER_SIZE" }
        require(size <= maxSize) { "9p frame size $size exceeds max $maxSize" }
        val rest = readFully(input, (size - 4).toInt())
            ?: throw EOFException("9p stream ended mid-frame")
        val type = rest[0].toInt() and 0xFF
        val tag = (rest[1].toInt() and 0xFF) or ((rest[2].toInt() and 0xFF) shl 8)
        val body = rest.copyOfRange(3, rest.size)
        return NinepFrame(type, tag, body)
    }

    /** Writes one framed message: size[4] type[1] tag[2] body. */
    fun writeFrame(out: OutputStream, type: Int, tag: Int, body: ByteArray) {
        val size = HEADER_SIZE + body.size
        val header = ByteArrayOutputStream(HEADER_SIZE)
        val w = Writer(header)
        w.writeU32(size.toLong())
        w.writeU8(type)
        w.writeU16(tag)
        out.write(header.toByteArray())
        out.write(body)
    }

    /** Reads exactly [len] bytes, or returns null if the stream is at clean EOF before any byte. */
    private fun readFully(input: InputStream, len: Int): ByteArray? {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) {
                return if (off == 0) null else throw EOFException("9p stream ended mid-read")
            }
            off += n
        }
        return buf
    }
}
