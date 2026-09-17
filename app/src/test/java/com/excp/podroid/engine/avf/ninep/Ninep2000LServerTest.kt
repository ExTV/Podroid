package com.excp.podroid.engine.avf.ninep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

private const val TEST_S_IFDIR = 0x4000L
private const val TEST_S_IFREG = 0x8000L
private const val TEST_EOPNOTSUPP = 95L

/**
 * Fake StatSource so the server runs on a plain JVM (android.system.Os is unavailable
 * there). Mode is derived from java.io.File; inode numbers are assigned per absolute
 * path and stay stable across calls, mirroring real filesystem inode stability.
 */
private class FakeStatSource : StatSource {
    private val inodes = mutableMapOf<String, Long>()

    override fun lstat(path: String): StatInfo {
        val f = File(path)
        if (!f.exists()) throw FileNotFoundException(path)
        val ino = inodes.getOrPut(path) { }
        val mode = if (f.isDirectory) TEST_S_IFDIR or 0x1FFL else TEST_S_IFREG or 0x1B4L
        return StatInfo(
            ino = ino,
            mode = mode,
            uid = 0,
            gid = 0,
            size = if (f.isFile) f.length() else 0L,
            nlink = 1,
            rdev = 0,
            blksize = 4096,
            blocks = 0,
            atimeSec = 0,
            atimeNsec = 0,
            mtimeSec = 0,
            mtimeNsec = 0,
            ctimeSec = 0,
            ctimeNsec = 0,
        )
    }
}

private fun frame(type: Int, tag: Int, body: ByteArray): ByteArray =
    ByteArrayOutputStream().also { NinepCodec.writeFrame(it, type, tag, body) }.toByteArray()

private fun body(write: (NinepCodec.Writer) -> Unit): ByteArray =
    ByteArrayOutputStream().also { write(NinepCodec.Writer(it)) }.toByteArray()

private fun readFrames(bytes: ByteArray): List<NinepFrame> {
    val input = ByteArrayInputStream(bytes)
    val out = mutableListOf<NinepFrame>()
    while (true) {
        val f = NinepCodec.readFrame(input) ?: break
        out += f
    }
    return out
}

class Ninep2000LServerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun versionFrame(tag: Int = 0xFFFF): ByteArray =
        frame(Ninep.Tversion, tag, body { it.writeU32(8192); it.writeString("9P2000.L") })

    private fun attachFrame(fid: Long, tag: Int): ByteArray =
        frame(
            Ninep.Tattach,
            tag,
            body { w ->
                w.writeU32(fid)
                w.writeU32(0xFFFFFFFFL) // afid = NOFID, no auth
                w.writeString("user")
                w.writeString("")
                w.writeU32(0)
            },
        )

    private fun walkFrame(fid: Long, newfid: Long, names: List<String>, tag: Int): ByteArray =
        frame(
            Ninep.Twalk,
            tag,
            body { w ->
                w.writeU32(fid)
                w.writeU32(newfid)
                w.writeU16(names.size)
                names.forEach { w.writeString(it) }
            },
        )

    private fun getattrFrame(fid: Long, tag: Int): ByteArray =
        frame(Ninep.Tgetattr, tag, body { w -> w.writeU32(fid); w.writeU64(0x7ffL) })

    private fun clunkFrame(fid: Long, tag: Int): ByteArray =
        frame(Ninep.Tclunk, tag, body { w -> w.writeU32(fid) })

    private fun lopenFrame(fid: Long, flags: Long, tag: Int): ByteArray =
        frame(Ninep.Tlopen, tag, body { w -> w.writeU32(fid); w.writeU32(flags) })

    private fun lcreateFrame(fid: Long, name: String, flags: Long, mode: Long, tag: Int): ByteArray =
        frame(
            Ninep.Tlcreate,
            tag,
            body { w ->
                w.writeU32(fid)
                w.writeString(name)
                w.writeU32(flags)
                w.writeU32(mode)
                w.writeU32(0) // gid
            },
        )

    private fun treadFrame(fid: Long, offset: Long, count: Long, tag: Int): ByteArray =
        frame(Ninep.Tread, tag, body { w -> w.writeU32(fid); w.writeU64(offset); w.writeU32(count) })

    private fun twriteFrame(fid: Long, offset: Long, data: ByteArray, tag: Int): ByteArray =
        frame(
            Ninep.Twrite,
            tag,
            body { w ->
                w.writeU32(fid)
                w.writeU64(offset)
                w.writeU32(data.size.toLong())
                w.writeBytes(data)
            },
        )

    private fun readdirFrame(fid: Long, offset: Long, count: Long, tag: Int): ByteArray =
        frame(Ninep.Treaddir, tag, body { w -> w.writeU32(fid); w.writeU64(offset); w.writeU32(count) })

    private fun mkdirFrame(dfid: Long, name: String, mode: Long, tag: Int): ByteArray =
        frame(
            Ninep.Tmkdir,
            tag,
            body { w -> w.writeU32(dfid); w.writeString(name); w.writeU32(mode); w.writeU32(0) },
        )

    private fun unlinkatFrame(dirfid: Long, name: String, flags: Long, tag: Int): ByteArray =
        frame(
            Ninep.Tunlinkat,
            tag,
            body { w -> w.writeU32(dirfid); w.writeString(name); w.writeU32(flags) },
        )

    private fun removeFrame(fid: Long, tag: Int): ByteArray =
        frame(Ninep.Tremove, tag, body { w -> w.writeU32(fid) })

    private fun renameatFrame(olddirfid: Long, oldname: String, newdirfid: Long, newname: String, tag: Int): ByteArray =
        frame(
            Ninep.Trenameat,
            tag,
            body { w ->
                w.writeU32(olddirfid)
                w.writeString(oldname)
                w.writeU32(newdirfid)
                w.writeString(newname)
            },
        )

    private fun setattrFrame(
        fid: Long,
        valid: Long,
        tag: Int,
        mode: Long = 0,
        size: Long = 0,
        atimeSec: Long = 0,
        atimeNsec: Long = 0,
        mtimeSec: Long = 0,
        mtimeNsec: Long = 0,
    ): ByteArray = frame(
        Ninep.Tsetattr,
        tag,
        body { w ->
            w.writeU32(fid)
            w.writeU32(valid)
            w.writeU32(mode)
            w.writeU32(0) // uid
            w.writeU32(0) // gid
            w.writeU64(size)
            w.writeU64(atimeSec)
            w.writeU64(atimeNsec)
            w.writeU64(mtimeSec)
            w.writeU64(mtimeNsec)
        },
    )

    private fun fsyncFrame(fid: Long, tag: Int): ByteArray =
        frame(Ninep.Tfsync, tag, body { w -> w.writeU32(fid); w.writeU32(0) })

    @Test
    fun version_negotiates_9p2000L() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(versionFrame()), out)

        val resp = readFrames(out.toByteArray()).single()
        assertEquals(Ninep.Rversion, resp.type)
        val r = NinepCodec.Reader(resp.body)
        r.readU32() // msize, not asserted here
        assertEquals("9P2000.L", r.readString())
    }

    @Test
    fun attach_returns_directory_qid() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() + attachFrame(fid = 0, tag = 1)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val rattach = readFrames(out.toByteArray())[1]
        assertEquals(Ninep.Rattach, rattach.type)
        val r = NinepCodec.Reader(rattach.body)
        val qidType = r.readU8()
        assertEquals(0x80, qidType)
    }

    @Test
    fun walk_to_child_returns_its_qid() {
        tmp.newFile("marker.txt")
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() + attachFrame(0, 1) + walkFrame(0, 1, listOf("marker.txt"), 2)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val rwalk = readFrames(out.toByteArray())[2]
        assertEquals(Ninep.Rwalk, rwalk.type)
        val r = NinepCodec.Reader(rwalk.body)
        assertEquals(1, r.readU16())
        assertEquals(0x00, r.readU8()) // regular file qid type
    }

    @Test
    fun walk_does_not_escape_above_root() {
        tmp.newFolder("sub")
        val stat = FakeStatSource()
        val rootIno = stat.lstat(tmp.root.absolutePath).ino
        val server = Ninep2000LServer(tmp.root, stat)
        val req = versionFrame() +
            attachFrame(0, 1) +
            walkFrame(0, 1, listOf("sub"), 2) +
            walkFrame(1, 2, listOf("..", ".."), 3)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val rwalkUp = readFrames(out.toByteArray())[3]
        assertEquals(Ninep.Rwalk, rwalkUp.type)
        val r = NinepCodec.Reader(rwalkUp.body)
        assertEquals(2, r.readU16())
        r.readU8(); r.readU32()
        val firstPath = r.readU64()
        r.readU8(); r.readU32()
        val secondPath = r.readU64()
        // Both ".." hops from an already-root position must resolve back to root,
        // never to the real filesystem's parent of the temp folder.
        assertEquals(rootIno, firstPath)
        assertEquals(rootIno, secondPath)
    }

    @Test
    fun getattr_reports_directory_for_root_and_file_for_child() {
        tmp.newFile("marker.txt")
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() +
            attachFrame(0, 1) +
            getattrFrame(0, 2) +
            walkFrame(0, 1, listOf("marker.txt"), 3) +
            getattrFrame(1, 4)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        val rootAttr = frames[2]
        val fileAttr = frames[4]
        assertEquals(Ninep.Rgetattr, rootAttr.type)
        assertEquals(Ninep.Rgetattr, fileAttr.type)

        val rr = NinepCodec.Reader(rootAttr.body)
        rr.readU64() // valid mask
        rr.readU8(); rr.readU32(); rr.readU64() // qid
        val rootMode = rr.readU32()
        assertEquals(0x4000L, rootMode and 0xF000L)

        val fr = NinepCodec.Reader(fileAttr.body)
        fr.readU64()
        fr.readU8(); fr.readU32(); fr.readU64()
        val fileMode = fr.readU32()
        assertEquals(0x8000L, fileMode and 0xF000L)
    }

    @Test
    fun clunk_drops_fid() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() + attachFrame(0, 1) + clunkFrame(0, 2) + getattrFrame(0, 3)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rclunk, frames[2].type)
        assertEquals(Ninep.Rlerror, frames[3].type) // fid 0 no longer valid
    }

    @Test
    fun unknown_opcode_returns_rlerror_eopnotsupp() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val bogusType = 250 // not a defined 9p2000.L type
        val req = frame(bogusType, 9, ByteArray(0))
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val resp = readFrames(out.toByteArray()).single()
        assertEquals(Ninep.Rlerror, resp.type)
        val r = NinepCodec.Reader(resp.body)
        assertEquals(TEST_EOPNOTSUPP, r.readU32())
    }

    @Test
    fun truncated_request_stream_ends_loop_cleanly() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val full = versionFrame()
        val truncated = full.copyOfRange(0, full.size - 3) // cut off mid-frame
        val out = ByteArrayOutputStream()

        server.serve(ByteArrayInputStream(truncated), out) // must not throw

        assertEquals(0, out.toByteArray().size)
    }

    @Test
    fun corrupt_size_prefix_ends_loop_cleanly() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val bogus = byteArrayOf(1, 0, 0, 0) // size = 1, smaller than the 7-byte header
        val out = ByteArrayOutputStream()

        server.serve(ByteArrayInputStream(bogus), out) // must not throw

        assertTrue(out.toByteArray().isEmpty())
    }

    @Test
    fun oversized_size_prefix_ends_loop_cleanly_without_crashing() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        // Little-endian size = 0x20000000 (~536 MB), a plausible allocation size that
        // would previously have been passed straight to readFully()'s ByteArray(...)
        // before any bound was checked. The oversized-frame guard must reject it
        // before that allocation is attempted, so this must not OOM or throw out.
        val bogus = byteArrayOf(0x00, 0x00, 0x00, 0x20)
        val out = ByteArrayOutputStream()

        server.serve(ByteArrayInputStream(bogus), out) // must not throw

        assertTrue(out.toByteArray().isEmpty())
    }

    @Test
    fun negative_wrap_size_prefix_ends_loop_cleanly() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        // Declared size 0xFFFFFFFF: (size - 4).toInt() would wrap to a negative Int
        // if it were ever computed. The ceiling check must reject this before that
        // arithmetic runs, ending the session cleanly instead of throwing out of serve().
        val bogus = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val out = ByteArrayOutputStream()

        server.serve(ByteArrayInputStream(bogus), out) // must not throw

        assertTrue(out.toByteArray().isEmpty())
    }

    @Test
    fun msize_is_clamped_to_ceiling() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = frame(
            Ninep.Tversion,
            0xFFFF,
            body { it.writeU32(1_000_000); it.writeString("9P2000.L") },
        )
        val out = ByteArrayOutputStream()

        server.serve(ByteArrayInputStream(req), out)

        val resp = readFrames(out.toByteArray()).single()
        assertEquals(Ninep.Rversion, resp.type)
        val r = NinepCodec.Reader(resp.body)
        assertEquals(262144L, r.readU32())
    }

    @Test
    fun lcreate_write_then_walk_lopen_read_returns_same_bytes() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val data = "hello world".toByteArray()
        val req = versionFrame() +
            attachFrame(0, 1) +
            lcreateFrame(0, "hello.txt", flags = 2 /* O_RDWR */, mode = 0x1A4 /* 0644 */, tag = 2) +
            twriteFrame(0, offset = 0, data = data, tag = 3) +
            attachFrame(1, 4) +
            walkFrame(1, 2, listOf("hello.txt"), 5) +
            lopenFrame(2, flags = 0 /* O_RDONLY */, tag = 6) +
            treadFrame(2, offset = 0, count = 64, tag = 7)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rlcreate, frames[2].type)
        assertEquals(Ninep.Rwrite, frames[3].type)
        val rwrite = NinepCodec.Reader(frames[3].body)
        assertEquals(data.size.toLong(), rwrite.readU32())

        val rread = NinepCodec.Reader(frames[7].body)
        val count = rread.readU32()
        assertEquals(data.size.toLong(), count)
        assertTrue(data.contentEquals(rread.readBytes(count.toInt())))
    }

    @Test
    fun write_at_offset_then_read_range_back() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val initial = "0123456789".toByteArray()
        val overwrite = "XYZ".toByteArray()
        val req = versionFrame() +
            attachFrame(0, 1) +
            lcreateFrame(0, "data.bin", flags = 2, mode = 0x1A4, tag = 2) +
            twriteFrame(0, offset = 0, data = initial, tag = 3) +
            twriteFrame(0, offset = 3, data = overwrite, tag = 4) +
            treadFrame(0, offset = 0, count = 64, tag = 5)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        val rread = NinepCodec.Reader(frames[5].body)
        val count = rread.readU32()
        assertEquals("012XYZ6789", String(rread.readBytes(count.toInt())))
    }

    @Test
    fun readdir_lists_dot_dotdot_and_children_with_dt_types_and_splits_across_offsets() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        // "." and ".." are always the first two entries with cookies 1 and 2, so the
        // resume offset for the second page is known ahead of time: no need to run the
        // server twice to discover it.
        val req = versionFrame() +
            attachFrame(0, 1) +
            mkdirFrame(0, "sub", mode = 0x1ED /* 0755 */, tag = 2) +
            walkFrame(0, 1, emptyList(), 3) + // clone root onto fid 1 without disturbing fid 0
            lcreateFrame(1, "a.txt", flags = 2, mode = 0x1A4, tag = 4) +
            readdirFrame(0, offset = 0, count = 4096, tag = 5) +
            readdirFrame(0, offset = 0, count = 52, tag = 6) +
            readdirFrame(0, offset = 2, count = 4096, tag = 7)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        val fullEntries = parseReaddirEntries(frames[5].body)
        assertEquals(listOf(".", "..", "a.txt", "sub"), fullEntries.map { it.name })
        assertEquals(QTDIR_DT, fullEntries[0].type)
        assertEquals(QTDIR_DT, fullEntries[1].type)
        assertEquals(QTREG_DT, fullEntries.first { it.name == "a.txt" }.type)
        assertEquals(QTDIR_DT, fullEntries.first { it.name == "sub" }.type)

        val firstPage = parseReaddirEntries(frames[6].body)
        assertEquals(listOf(".", ".."), firstPage.map { it.name })
        assertEquals(listOf(1L, 2L), firstPage.map { it.offset })

        val secondPage = parseReaddirEntries(frames[7].body)
        assertEquals(listOf("a.txt", "sub"), secondPage.map { it.name })
    }

    @Test
    fun mkdir_then_getattr_shows_directory_and_unlinkat_removes_it() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() +
            attachFrame(0, 1) +
            mkdirFrame(0, "tempdir", mode = 0x1ED, tag = 2) +
            walkFrame(0, 2, listOf("tempdir"), 3) +
            getattrFrame(2, 4) +
            unlinkatFrame(0, "tempdir", flags = 0x200 /* AT_REMOVEDIR */, tag = 5) +
            getattrFrame(2, 6)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rmkdir, frames[2].type)
        val rgetattr = NinepCodec.Reader(frames[4].body)
        rgetattr.readU64()
        rgetattr.readU8(); rgetattr.readU32(); rgetattr.readU64()
        assertEquals(0x4000L, rgetattr.readU32() and 0xF000L)
        assertEquals(Ninep.Runlinkat, frames[5].type)
        assertEquals(Ninep.Rlerror, frames[6].type) // directory is gone
        assertFalse(File(tmp.root, "tempdir").exists())
    }

    @Test
    fun renameat_moves_a_file() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() +
            attachFrame(0, 1) +
            walkFrame(0, 1, emptyList(), 2) +
            lcreateFrame(1, "old.txt", flags = 2, mode = 0x1A4, tag = 3) +
            renameatFrame(0, "old.txt", 0, "new.txt", tag = 4) +
            walkFrame(0, 2, listOf("old.txt"), 5) +
            walkFrame(0, 3, listOf("new.txt"), 6)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rrenameat, frames[4].type)
        assertEquals(Ninep.Rlerror, frames[5].type) // old.txt no longer exists
        assertEquals(Ninep.Rwalk, frames[6].type) // new.txt exists
        assertFalse(File(tmp.root, "old.txt").exists())
        assertTrue(File(tmp.root, "new.txt").exists())
    }

    @Test
    fun setattr_size_truncates() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() +
            attachFrame(0, 1) +
            walkFrame(0, 1, emptyList(), 2) +
            lcreateFrame(1, "trunc.txt", flags = 2, mode = 0x1A4, tag = 3) +
            twriteFrame(1, offset = 0, data = "0123456789".toByteArray(), tag = 4) +
            setattrFrame(1, valid = 0x8L /* SIZE */, tag = 5, size = 4) +
            getattrFrame(1, 6)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rsetattr, frames[5].type)
        val rgetattr = NinepCodec.Reader(frames[6].body)
        rgetattr.readU64()
        rgetattr.readU8(); rgetattr.readU32(); rgetattr.readU64() // qid
        rgetattr.readU32(); rgetattr.readU32(); rgetattr.readU32() // mode, uid, gid
        rgetattr.readU64() // nlink
        rgetattr.readU64() // rdev
        assertEquals(4L, rgetattr.readU64()) // size
        assertEquals(4L, File(tmp.root, "trunc.txt").length())
    }

    @Test
    fun setattr_mtime_sets_mtime() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val mtimeSec = 1_600_000_000L
        val req = versionFrame() +
            attachFrame(0, 1) +
            walkFrame(0, 1, emptyList(), 2) +
            lcreateFrame(1, "mtime.txt", flags = 2, mode = 0x1A4, tag = 3) +
            setattrFrame(
                1,
                valid = 0x20L or 0x100L, // MTIME | MTIME_SET
                tag = 4,
                mtimeSec = mtimeSec,
                mtimeNsec = 0,
            )
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rsetattr, frames[4].type)
        assertEquals(mtimeSec * 1000L, File(tmp.root, "mtime.txt").lastModified())
    }

    @Test
    fun remove_deletes_a_file_and_the_fid_is_gone() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() +
            attachFrame(0, 1) +
            walkFrame(0, 1, emptyList(), 2) +
            lcreateFrame(1, "rm.txt", flags = 2, mode = 0x1A4, tag = 3) +
            removeFrame(1, tag = 4) +
            getattrFrame(1, 5)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rremove, frames[4].type)
        assertEquals(Ninep.Rlerror, frames[5].type) // fid was clunked by Tremove
        assertFalse(File(tmp.root, "rm.txt").exists())
    }

    @Test
    fun fsync_always_replies_success() {
        val server = Ninep2000LServer(tmp.root, FakeStatSource())
        val req = versionFrame() +
            attachFrame(0, 1) +
            walkFrame(0, 1, emptyList(), 2) +
            lcreateFrame(1, "fs.txt", flags = 2, mode = 0x1A4, tag = 3) +
            fsyncFrame(1, tag = 4)
        val out = ByteArrayOutputStream()
        server.serve(ByteArrayInputStream(req), out)

        val frames = readFrames(out.toByteArray())
        assertEquals(Ninep.Rfsync, frames[4].type)
    }

    private data class ReaddirEntry(val name: String, val offset: Long, val type: Int)

    /** Parses a Rreaddir body (count[4] then count bytes of qid[13] offset[8] type[1]
     *  name[s] entries) back into structured entries for assertions. */
    private fun parseReaddirEntries(rreaddirBody: ByteArray): List<ReaddirEntry> {
        val r = NinepCodec.Reader(rreaddirBody)
        val count = r.readU32()
        val dataEnd = (rreaddirBody.size - r.remaining()) + count.toInt()
        val entries = mutableListOf<ReaddirEntry>()
        while (rreaddirBody.size - r.remaining() < dataEnd) {
            r.readU8(); r.readU32(); r.readU64() // qid
            val offset = r.readU64()
            val type = r.readU8()
            val name = r.readString()
            entries += ReaddirEntry(name, offset, type)
        }
        return entries
    }

    companion object {
        private const val QTDIR_DT = 4
        private const val QTREG_DT = 8
    }
}
