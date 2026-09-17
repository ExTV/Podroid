package com.excp.podroid.engine.avf.ninep

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant

private const val TAG = "Ninep2000LServer"

/** Ceiling for the negotiated msize; the client may ask for less. Matches the codec's
 *  per-frame allocation ceiling so a negotiated msize can never exceed what a single
 *  frame is allowed to declare. */
private const val MAX_MSIZE = NinepCodec.MAX_FRAME_SIZE.toLong()
private const val DEFAULT_MSIZE = 8192L

/** qid.type bits, from the 9P2000.L spec. */
private const val QTDIR = 0x80
private const val QTSYMLINK = 0x02
private const val QTFILE = 0x00

/** st_mode format bits (POSIX, same values as android.system.OsConstants). */
private const val S_IFMT = 0xF000L
private const val S_IFDIR = 0x4000L
private const val S_IFLNK = 0xA000L

/** Linux errno values used on the wire for Rlerror (see linux/errno.h). */
private const val ENOENT = 2
private const val EIO = 5
private const val EEXIST = 17
private const val ENOTEMPTY = 39
private const val EOPNOTSUPP = 95

/** P9_GETATTR_BASIC: the field set filled in by Rgetattr below. */
private const val P9_GETATTR_BASIC = 0x000007ffL

/** Linux open(2) flag bits used to decode Tlopen/Tlcreate's flags[4]. */
private const val O_ACCMODE = 0x3L
private const val O_WRONLY = 1L
private const val O_RDWR = 2L
private const val O_CREAT = 0x40L
private const val O_TRUNC = 0x200L
private const val O_APPEND = 0x400L

/** P9_SETATTR valid-mask bits, from the 9P2000.L spec. */
private const val SETATTR_MODE = 0x1L
private const val SETATTR_SIZE = 0x8L
private const val SETATTR_ATIME = 0x10L
private const val SETATTR_MTIME = 0x20L
private const val SETATTR_ATIME_SET = 0x80L
private const val SETATTR_MTIME_SET = 0x100L

/** dirent d_type values used by Treaddir/Rreaddir, from linux/fs.h (DT_*). */
private const val DT_REG = 8
private const val DT_DIR = 4
private const val DT_LNK = 10

/**
 * Plain-data stat result, decoupled from android.system.Os so the server is
 * unit-testable on a plain JVM (Os/StructStat are Android framework classes).
 */
data class StatInfo(
    val ino: Long,
    val mode: Long,
    val uid: Long,
    val gid: Long,
    val size: Long,
    val nlink: Long,
    val rdev: Long,
    val blksize: Long,
    val blocks: Long,
    val atimeSec: Long,
    val atimeNsec: Long,
    val mtimeSec: Long,
    val mtimeNsec: Long,
    val ctimeSec: Long,
    val ctimeNsec: Long,
)

/** Seam over android.system.Os.lstat; production uses [OsStatSource], tests inject a fake. */
interface StatSource {
    fun lstat(path: String): StatInfo
}

/** Production [StatSource] backed by android.system.Os.lstat. */
object OsStatSource : StatSource {
    override fun lstat(path: String): StatInfo {
        val st = android.system.Os.lstat(path)
        return StatInfo(
            ino = st.st_ino,
            mode = st.st_mode.toLong(),
            uid = st.st_uid.toLong(),
            gid = st.st_gid.toLong(),
            size = st.st_size,
            nlink = st.st_nlink,
            rdev = st.st_rdev,
            blksize = st.st_blksize,
            blocks = st.st_blocks,
            atimeSec = st.st_atim.tv_sec,
            atimeNsec = st.st_atim.tv_nsec,
            mtimeSec = st.st_mtim.tv_sec,
            mtimeNsec = st.st_mtim.tv_nsec,
            ctimeSec = st.st_ctim.tv_sec,
            ctimeNsec = st.st_ctim.tv_nsec,
        )
    }
}

/** A 9p2000.L qid: type[1] version[4] path[8]. */
data class NinepQid(val type: Int, val version: Long, val path: Long)

private fun NinepCodec.Writer.writeQid(qid: NinepQid) {
    writeU8(qid.type)
    writeU32(qid.version)
    writeU64(qid.path)
}

/**
 * Per-fid server state: the path the fid currently refers to, plus an open
 * [FileChannel] once Tlopen/Tlcreate have opened it (null for a fid that has never
 * been opened, or that refers to a directory - directories are read via [File.listFiles]
 * rather than a held handle).
 */
private class FidState(var file: File, var channel: FileChannel? = null)

/**
 * In-process 9p2000.L server. Serves [root] (and everything under it) to a client
 * driving [serve] over a pair of streams. Backed by [android.system.Os] in production;
 * tests inject a fake [StatSource] so the session/fid/walk/attr logic runs on a plain JVM.
 *
 * This class implements Tversion, Tattach, Twalk, Tgetattr, Tclunk, plus the file and
 * directory operations Tlopen, Tlcreate, Tread, Twrite, Treaddir, Tsetattr, Tmkdir,
 * Tunlinkat, Tremove, Trenameat and Tfsync. File content and directory mutation ride
 * java.nio/java.io (FileChannel positional read/write, Files.createFile/createDirectory/
 * delete/move) so
 * they are unit-testable on a plain JVM against a real temp directory; only attribute
 * lookups (Tgetattr, qids) stay on the [StatSource] seam. Operations with no production
 * use yet (Tsymlink, Treadlink, Tlink, Txattrwalk, Txattrcreate, Tstatfs, ...) still
 * reply Rlerror(EOPNOTSUPP).
 */
class Ninep2000LServer(
    private val root: File,
    private val stat: StatSource = OsStatSource,
) {
    private val fids = mutableMapOf<Long, FidState>()
    private var msize: Long = DEFAULT_MSIZE
    private var negotiated = false

    /**
     * Reads and replies to frames from [input] until the client disconnects. Never
     * throws: a clean EOF ends the loop normally, a truncated frame or a corrupt
     * (or oversized) size prefix logs a warning and ends the loop, and per-request
     * failures are reported to the client as Rlerror instead of tearing down the
     * session. Both catch blocks guard against Throwable, not just Exception, as
     * defense in depth: a bug that manages to throw an Error (e.g. OutOfMemoryError)
     * around the read/dispatch boundary must still end the session cleanly rather
     * than crash the caller.
     */
    fun serve(input: InputStream, output: OutputStream) {
        try {
            while (true) {
                // Before Tversion, bound reads by the ceiling; afterwards, by the msize
                // actually negotiated with this client so no frame can exceed it.
                val maxSize = if (negotiated) msize.toInt() else NinepCodec.MAX_FRAME_SIZE
                val frame = try {
                    NinepCodec.readFrame(input, maxSize) ?: break
                } catch (e: EOFException) {
                    break
                } catch (e: Throwable) {
                    // Covers IllegalArgumentException (bad or oversized size prefix),
                    // NegativeArraySizeException (size overflow) and, as defense in
                    // depth, any Error a corrupt frame could otherwise trigger. One
                    // corrupt frame ends the session; it must never crash the caller.
                    logWarn("corrupt 9p frame, ending session", e)
                    break
                }

                try {
                    dispatch(frame, output)
                } catch (e: IOException) {
                    // The reply could not be written; the peer is gone. Stop serving.
                    logWarn("failed to write 9p reply, ending session", e)
                    break
                } catch (e: Throwable) {
                    // dispatch() already reports ordinary per-request failures as
                    // Rlerror, so only an unexpected Error reaches here. It must not
                    // crash the caller either.
                    logWarn("unexpected failure handling 9p request, ending session", e)
                    break
                }
            }
        } finally {
            // However the loop ended, no fid's open handle may outlive the session.
            closeAllFids()
        }
    }

    private fun dispatch(frame: NinepFrame, output: OutputStream) {
        try {
            when (frame.type) {
                Ninep.Tversion -> handleVersion(frame, output)
                Ninep.Tattach -> handleAttach(frame, output)
                Ninep.Twalk -> handleWalk(frame, output)
                Ninep.Tgetattr -> handleGetattr(frame, output)
                Ninep.Tsetattr -> handleSetattr(frame, output)
                Ninep.Tlopen -> handleLopen(frame, output)
                Ninep.Tlcreate -> handleLcreate(frame, output)
                Ninep.Tread -> handleRead(frame, output)
                Ninep.Twrite -> handleWrite(frame, output)
                Ninep.Treaddir -> handleReaddir(frame, output)
                Ninep.Tmkdir -> handleMkdir(frame, output)
                Ninep.Tunlinkat -> handleUnlinkat(frame, output)
                Ninep.Tremove -> handleRemove(frame, output)
                Ninep.Trenameat -> handleRenameat(frame, output)
                Ninep.Tfsync -> handleFsync(frame, output)
                Ninep.Tclunk -> handleClunk(frame, output)
                else -> replyError(frame.tag, output, EOPNOTSUPP)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // A malformed request body (short read past the array bounds, etc.) fails
            // this one request, not the whole session.
            replyError(frame.tag, output, EIO)
        }
    }

    private fun handleVersion(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val clientMsize = r.readU32()
        val clientVersion = r.readString()
        val negotiatedVersion = if (clientVersion == "9P2000.L") "9P2000.L" else "unknown"
        msize = minOf(clientMsize, MAX_MSIZE)
        negotiated = true
        writeReply(output, Ninep.Rversion, frame.tag) { w ->
            w.writeU32(msize)
            w.writeString(negotiatedVersion)
        }
    }

    private fun handleAttach(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        r.readU32() // afid, ignored: no auth support
        r.readString() // uname
        r.readString() // aname
        r.readU32() // n_uname

        val st = statOrNull(root.absolutePath)
        if (st == null) {
            replyError(frame.tag, output, ENOENT)
            return
        }
        closeAndReplaceFid(fid, FidState(root))
        writeReply(output, Ninep.Rattach, frame.tag) { w -> w.writeQid(qidFromStat(st)) }
    }

    private fun handleWalk(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        val newfid = r.readU32()
        val nwname = r.readU16()
        val names = (0 until nwname).map { r.readString() }

        val start = fids[fid]
        if (start == null) {
            replyError(frame.tag, output, EIO)
            return
        }

        if (names.isEmpty()) {
            // A zero-component walk just clones the fid onto newfid; give the clone
            // its own FidState so opening one later never aliases the other's handle.
            if (newfid != fid) {
                closeAndReplaceFid(newfid, FidState(start.file))
            }
            writeReply(output, Ninep.Rwalk, frame.tag) { w -> w.writeU16(0) }
            return
        }

        val qids = mutableListOf<NinepQid>()
        var current: File = start.file
        for (name in names) {
            val candidate = when (name) {
                "." -> current
                ".." -> parentWithinRoot(current)
                else -> File(current, name)
            }
            if (!isWithinRoot(candidate)) break
            val st = statOrNull(candidate.absolutePath) ?: break
            qids += qidFromStat(st)
            current = candidate
        }

        if (qids.isEmpty()) {
            replyError(frame.tag, output, ENOENT)
            return
        }
        if (qids.size == names.size) {
            closeAndReplaceFid(newfid, FidState(current))
        }
        writeReply(output, Ninep.Rwalk, frame.tag) { w ->
            w.writeU16(qids.size)
            qids.forEach { w.writeQid(it) }
        }
    }

    private fun handleGetattr(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        r.readU64() // request_mask, ignored: we always report the basic field set

        val file = fids[fid]?.file
        if (file == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val st = statOrNull(file.absolutePath)
        if (st == null) {
            replyError(frame.tag, output, ENOENT)
            return
        }
        writeReply(output, Ninep.Rgetattr, frame.tag) { w ->
            w.writeU64(P9_GETATTR_BASIC)
            w.writeQid(qidFromStat(st))
            w.writeU32(st.mode)
            w.writeU32(st.uid)
            w.writeU32(st.gid)
            w.writeU64(st.nlink)
            w.writeU64(st.rdev)
            w.writeU64(st.size)
            w.writeU64(st.blksize)
            w.writeU64(st.blocks)
            w.writeU64(st.atimeSec)
            w.writeU64(st.atimeNsec)
            w.writeU64(st.mtimeSec)
            w.writeU64(st.mtimeNsec)
            w.writeU64(st.ctimeSec)
            w.writeU64(st.ctimeNsec)
            w.writeU64(0) // btime_sec: not tracked
            w.writeU64(0) // btime_nsec
            w.writeU64(0) // gen
            w.writeU64(0) // data_version
        }
    }

    private fun handleClunk(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        fids.remove(fid)?.channel?.closeQuietly()
        writeReply(output, Ninep.Rclunk, frame.tag) { }
    }

    private fun handleLopen(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        val flags = r.readU32()

        val state = fids[fid]
        if (state == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val st = statOrNull(state.file.absolutePath)
        if (st == null) {
            replyError(frame.tag, output, ENOENT)
            return
        }

        // Directories are served via File.listFiles() in Treaddir, not a held handle,
        // but Tlopen on a directory fid must still succeed (the client opens it before
        // Treaddir).
        val isDir = (st.mode and S_IFMT) == S_IFDIR
        if (!isDir) {
            val channel = try {
                openChannel(state.file, flags)
            } catch (e: Exception) {
                replyError(frame.tag, output, EIO)
                return
            }
            state.channel?.closeQuietly()
            state.channel = channel
        }
        writeReply(output, Ninep.Rlopen, frame.tag) { w ->
            w.writeQid(qidFromStat(st))
            w.writeU32(0) // iounit: 0, client falls back to msize
        }
    }

    private fun handleLcreate(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        val name = r.readString()
        val flags = r.readU32()
        val mode = r.readU32()
        r.readU32() // gid, ignored: no multi-user support

        val state = fids[fid]
        if (state == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val target = File(state.file, name)
        if (!isWithinRoot(target)) {
            replyError(frame.tag, output, EIO)
            return
        }

        val channel = try {
            Files.createFile(target.toPath())
            applyPosixMode(target, mode)
            openChannel(target, flags)
        } catch (e: FileAlreadyExistsException) {
            replyError(frame.tag, output, EEXIST)
            return
        } catch (e: NoSuchFileException) {
            replyError(frame.tag, output, ENOENT)
            return
        } catch (e: Exception) {
            replyError(frame.tag, output, EIO)
            return
        }
        // fid now refers to the newly created (and opened) file, per the 9p2000.L spec.
        state.channel?.closeQuietly()
        state.file = target
        state.channel = channel

        val st = statOrNull(target.absolutePath)
        if (st == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        writeReply(output, Ninep.Rlcreate, frame.tag) { w ->
            w.writeQid(qidFromStat(st))
            w.writeU32(0) // iounit: 0, client falls back to msize
        }
    }

    private fun handleRead(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        val offset = r.readU64()
        val count = r.readU32()

        val channel = fids[fid]?.channel
        if (channel == null) {
            replyError(frame.tag, output, EIO)
            return
        }

        // Rread's own header (size+type+tag+count) costs 11 bytes of the negotiated
        // msize, so the payload can never exceed msize - 11.
        val cap = maxOf(0L, minOf(count, msize - 11)).toInt()
        val buf = ByteBuffer.allocate(cap)
        val n = try {
            channel.read(buf, offset)
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }
        val length = if (n < 0) 0 else n
        writeReply(output, Ninep.Rread, frame.tag) { w ->
            w.writeU32(length.toLong())
            w.writeBytes(buf.array().copyOfRange(0, length))
        }
    }

    private fun handleWrite(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        val offset = r.readU64()
        val count = r.readU32()
        val data = r.readBytes(count.toInt())

        val channel = fids[fid]?.channel
        if (channel == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val n = try {
            channel.write(ByteBuffer.wrap(data), offset)
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }
        writeReply(output, Ninep.Rwrite, frame.tag) { w -> w.writeU32(n.toLong()) }
    }

    private fun handleReaddir(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        val offset = r.readU64()
        val count = r.readU32()

        val dir = fids[fid]?.file
        if (dir == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val entries = try {
            buildDirEntries(dir)
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }

        // The cookie for the entry at index i is i + 1 ("resume after this entry"), so
        // resuming from a client-supplied offset means starting at that same index.
        val startIndex = offset.coerceIn(0L, entries.size.toLong()).toInt()
        val maxBytes = maxOf(0L, minOf(count, msize - 11))
        val data = ByteArrayOutputStream()
        for (i in startIndex until entries.size) {
            val entry = entries[i]
            val entryBytes = ByteArrayOutputStream().also { eout ->
                val w = NinepCodec.Writer(eout)
                w.writeQid(entry.qid)
                w.writeU64((i + 1).toLong())
                w.writeU8(entry.type)
                w.writeString(entry.name)
            }.toByteArray()
            if (data.size() + entryBytes.size > maxBytes) break
            data.write(entryBytes)
        }

        writeReply(output, Ninep.Rreaddir, frame.tag) { w ->
            w.writeU32(data.size().toLong())
            w.writeBytes(data.toByteArray())
        }
    }

    private fun handleSetattr(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        val valid = r.readU32()
        val mode = r.readU32()
        r.readU32() // uid, ignored: no multi-user support
        r.readU32() // gid, ignored: no multi-user support
        val size = r.readU64()
        val atimeSec = r.readU64()
        val atimeNsec = r.readU64()
        val mtimeSec = r.readU64()
        val mtimeNsec = r.readU64()

        val state = fids[fid]
        if (state == null) {
            replyError(frame.tag, output, EIO)
            return
        }

        try {
            if (valid and SETATTR_MODE != 0L) {
                applyPosixMode(state.file, mode)
            }
            if (valid and SETATTR_SIZE != 0L) {
                val existing = state.channel
                if (existing != null) {
                    existing.truncate(size)
                } else {
                    FileChannel.open(state.file.toPath(), StandardOpenOption.WRITE).use { it.truncate(size) }
                }
            }
            val atimeRequested = valid and SETATTR_ATIME != 0L
            val mtimeRequested = valid and SETATTR_MTIME != 0L
            if (atimeRequested || mtimeRequested) {
                val view = Files.getFileAttributeView(state.file.toPath(), BasicFileAttributeView::class.java)
                val newMtime = if (mtimeRequested) {
                    if (valid and SETATTR_MTIME_SET != 0L) {
                        FileTime.from(Instant.ofEpochSecond(mtimeSec, mtimeNsec))
                    } else {
                        FileTime.from(Instant.now())
                    }
                } else {
                    null
                }
                val newAtime = if (atimeRequested) {
                    if (valid and SETATTR_ATIME_SET != 0L) {
                        FileTime.from(Instant.ofEpochSecond(atimeSec, atimeNsec))
                    } else {
                        FileTime.from(Instant.now())
                    }
                } else {
                    null
                }
                view.setTimes(newMtime, newAtime, null)
            }
            // uid/gid changes are silently ignored (unsupported for an unprivileged app)
            // rather than erroring, so callers like "cp -p" still succeed overall.
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }
        writeReply(output, Ninep.Rsetattr, frame.tag) { }
    }

    private fun handleMkdir(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val dfid = r.readU32()
        val name = r.readString()
        val mode = r.readU32()
        r.readU32() // gid, ignored: no multi-user support

        val state = fids[dfid]
        if (state == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val target = File(state.file, name)
        if (!isWithinRoot(target)) {
            replyError(frame.tag, output, EIO)
            return
        }

        try {
            Files.createDirectory(target.toPath())
            applyPosixMode(target, mode)
        } catch (e: FileAlreadyExistsException) {
            replyError(frame.tag, output, EEXIST)
            return
        } catch (e: NoSuchFileException) {
            replyError(frame.tag, output, ENOENT)
            return
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }

        val st = statOrNull(target.absolutePath)
        if (st == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        writeReply(output, Ninep.Rmkdir, frame.tag) { w -> w.writeQid(qidFromStat(st)) }
    }

    private fun handleUnlinkat(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val dirfid = r.readU32()
        val name = r.readString()
        // flags (AT_REMOVEDIR = 0x200) is not needed: Files.delete already removes an
        // empty directory or a file as appropriate for what "name" actually is.
        r.readU32()

        val state = fids[dirfid]
        if (state == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val target = File(state.file, name)
        if (!isWithinRoot(target)) {
            replyError(frame.tag, output, EIO)
            return
        }

        try {
            if (!Files.deleteIfExists(target.toPath())) {
                replyError(frame.tag, output, ENOENT)
                return
            }
        } catch (e: DirectoryNotEmptyException) {
            replyError(frame.tag, output, ENOTEMPTY)
            return
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }
        writeReply(output, Ninep.Runlinkat, frame.tag) { }
    }

    private fun handleRemove(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()

        // Tremove removes the fid's file and clunks the fid, even if the removal fails.
        val state = fids.remove(fid)
        state?.channel?.closeQuietly()
        if (state == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        try {
            Files.deleteIfExists(state.file.toPath())
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }
        writeReply(output, Ninep.Rremove, frame.tag) { }
    }

    private fun handleRenameat(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val olddirfid = r.readU32()
        val oldname = r.readString()
        val newdirfid = r.readU32()
        val newname = r.readString()

        val oldDirState = fids[olddirfid]
        val newDirState = fids[newdirfid]
        if (oldDirState == null || newDirState == null) {
            replyError(frame.tag, output, EIO)
            return
        }
        val source = File(oldDirState.file, oldname)
        val target = File(newDirState.file, newname)
        if (!isWithinRoot(source) || !isWithinRoot(target)) {
            replyError(frame.tag, output, EIO)
            return
        }

        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: NoSuchFileException) {
            replyError(frame.tag, output, ENOENT)
            return
        } catch (e: DirectoryNotEmptyException) {
            replyError(frame.tag, output, ENOTEMPTY)
            return
        } catch (e: IOException) {
            replyError(frame.tag, output, EIO)
            return
        }
        writeReply(output, Ninep.Rrenameat, frame.tag) { }
    }

    private fun handleFsync(frame: NinepFrame, output: OutputStream) {
        val r = NinepCodec.Reader(frame.body)
        val fid = r.readU32()
        r.readU32() // datasync, ignored: force() always does a full sync

        try {
            fids[fid]?.channel?.force(true)
        } catch (e: IOException) {
            // An fsync failure must never surface as an error to the client: 9p clients
            // treat a failed flush as a write failure, and the write itself succeeded.
        }
        writeReply(output, Ninep.Rfsync, frame.tag) { }
    }

    /** Replaces the fid table entry for [fid], closing any handle it previously held. */
    private fun closeAndReplaceFid(fid: Long, state: FidState) {
        fids[fid]?.channel?.closeQuietly()
        fids[fid] = state
    }

    private fun closeAllFids() {
        fids.values.forEach { it.channel?.closeQuietly() }
        fids.clear()
    }

    private fun FileChannel.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            // ignored: best-effort cleanup, the fid is going away regardless
        }
    }

    /** Decodes Linux open(2) flags (Tlopen/Tlcreate's flags[4]) into NIO open options. */
    private fun openChannel(file: File, flags: Long): FileChannel {
        val append = flags and O_APPEND != 0L
        val creat = flags and O_CREAT != 0L
        val trunc = flags and O_TRUNC != 0L

        val options = mutableSetOf<StandardOpenOption>()
        if (append) {
            // APPEND cannot be combined with READ in java.nio; O_APPEND is only ever
            // meaningful for a writable open, so this matches practice.
            options += StandardOpenOption.WRITE
            options += StandardOpenOption.APPEND
        } else {
            when (flags and O_ACCMODE) {
                O_WRONLY -> options += StandardOpenOption.WRITE
                O_RDWR -> {
                    options += StandardOpenOption.READ
                    options += StandardOpenOption.WRITE
                }
                else -> options += StandardOpenOption.READ
            }
        }
        if (creat) options += StandardOpenOption.CREATE
        if (trunc && !append) options += StandardOpenOption.TRUNCATE_EXISTING
        return FileChannel.open(file.toPath(), options)
    }

    /** Best-effort chmod: some filesystems (or, in tests, the JVM's view of them) may
     *  not support POSIX permissions, in which case the mode request is dropped rather
     *  than failing the whole request. */
    private fun applyPosixMode(file: File, mode: Long) {
        try {
            Files.setPosixFilePermissions(file.toPath(), modeToPosixPermissions(mode))
        } catch (e: Exception) {
            // ignored: see comment above
        }
    }

    private fun modeToPosixPermissions(mode: Long): Set<PosixFilePermission> {
        val perms = mutableSetOf<PosixFilePermission>()
        if (mode and 0x100L != 0L) perms += PosixFilePermission.OWNER_READ
        if (mode and 0x080L != 0L) perms += PosixFilePermission.OWNER_WRITE
        if (mode and 0x040L != 0L) perms += PosixFilePermission.OWNER_EXECUTE
        if (mode and 0x020L != 0L) perms += PosixFilePermission.GROUP_READ
        if (mode and 0x010L != 0L) perms += PosixFilePermission.GROUP_WRITE
        if (mode and 0x008L != 0L) perms += PosixFilePermission.GROUP_EXECUTE
        if (mode and 0x004L != 0L) perms += PosixFilePermission.OTHERS_READ
        if (mode and 0x002L != 0L) perms += PosixFilePermission.OTHERS_WRITE
        if (mode and 0x001L != 0L) perms += PosixFilePermission.OTHERS_EXECUTE
        return perms
    }

    private data class DirEntry(val name: String, val qid: NinepQid, val type: Int)

    /** "." and ".." (bounded at [root], like Twalk's "..") followed by the directory's
     *  children sorted by name, so the ordering (and therefore the offset cookies) is
     *  stable across the multiple Treaddir calls a client may issue. */
    private fun buildDirEntries(dir: File): List<DirEntry> {
        val selfStat = statOrNull(dir.absolutePath) ?: throw IOException("cannot stat $dir")
        val parent = parentWithinRoot(dir)
        val parentStat = statOrNull(parent.absolutePath) ?: selfStat

        val entries = mutableListOf<DirEntry>()
        entries += DirEntry(".", qidFromStat(selfStat), direntType(selfStat))
        entries += DirEntry("..", qidFromStat(parentStat), direntType(parentStat))
        val children = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        for (child in children) {
            val st = statOrNull(child.absolutePath) ?: continue
            entries += DirEntry(child.name, qidFromStat(st), direntType(st))
        }
        return entries
    }

    private fun direntType(st: StatInfo): Int = when (st.mode and S_IFMT) {
        S_IFDIR -> DT_DIR
        S_IFLNK -> DT_LNK
        else -> DT_REG
    }

    /** Walks one ".." step without ever leaving [root]. */
    private fun parentWithinRoot(current: File): File = try {
        if (current.canonicalFile == root.canonicalFile) {
            current
        } else {
            current.parentFile ?: current
        }
    } catch (e: IOException) {
        current
    }

    /** True if [file] canonicalizes to [root] or somewhere under it. */
    private fun isWithinRoot(file: File): Boolean = try {
        val rootPath = root.canonicalFile.path
        val filePath = file.canonicalFile.path
        filePath == rootPath || filePath.startsWith(rootPath + File.separator)
    } catch (e: IOException) {
        false
    }

    private fun statOrNull(path: String): StatInfo? = try {
        stat.lstat(path)
    } catch (e: Exception) {
        null
    }

    private fun qidFromStat(st: StatInfo): NinepQid {
        val type = when (st.mode and S_IFMT) {
            S_IFDIR -> QTDIR
            S_IFLNK -> QTSYMLINK
            else -> QTFILE
        }
        return NinepQid(type = type, version = 0L, path = st.ino)
    }

    private fun replyError(tag: Int, output: OutputStream, errno: Int) {
        writeReply(output, Ninep.Rlerror, tag) { w -> w.writeU32(errno.toLong()) }
    }

    private fun writeReply(output: OutputStream, type: Int, tag: Int, write: (NinepCodec.Writer) -> Unit) {
        val body = ByteArrayOutputStream()
        write(NinepCodec.Writer(body))
        NinepCodec.writeFrame(output, type, tag, body.toByteArray())
    }

    private fun logWarn(message: String, cause: Throwable) {
        // android.util.Log is a framework stub under plain JVM unit tests and throws
        // there; a logging failure must never take down the serve loop, so guard it.
        try {
            Log.w(TAG, message, cause)
        } catch (_: Throwable) {
            // ignored: see comment above
        }
    }
}
