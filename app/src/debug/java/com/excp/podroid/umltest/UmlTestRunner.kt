/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Debug-only UML feasibility harness. Spawns the User-Mode Linux kernel the
 * way a real backend would: from nativeLibraryDir, as a child of the app
 * process, in the untrusted_app SELinux domain. Nothing here ships in release.
 */
package com.excp.podroid.umltest

import android.content.Context
import android.util.Log
import com.excp.podroid.util.HostMetrics
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object UmlTestRunner {
    private const val TAG = "PodroidUmlTest"

    private val executables = setOf("libqnet.so", "libuml.so")

    private var process: Process? = null
    private var stdin: OutputStream? = null
    private var startedAt = 0L
    private var lastExit: Int? = null

    fun workDir(ctx: Context): File = File(ctx.filesDir, "uml").apply { mkdirs() }

    fun consoleLog(ctx: Context): File = File(workDir(ctx), "console.log")

    /**
     * [kernelArgs] are passed to the UML kernel after stub_exe=. The kernel
     * always runs under libqnet.so (signal reset, extra channels); with [net]
     * qnet also starts QEMU as a guest-less SLIRP router and appends
     * vec0:transport=fd,... itself. [env] is added to qnet's environment.
     */
    @Synchronized
    fun start(ctx: Context, kernelArgs: List<String>, net: Boolean, env: Map<String, String>): String {
        if (process?.isAlive == true) return "error already-running"
        val lib = ctx.applicationInfo.nativeLibraryDir
        val dir = workDir(ctx)
        val cmd = mutableListOf<String>()
        cmd += "$lib/libqnet.so"
        cmd += if (net) "$lib/libqemu-system-aarch64.so" else "-"
        cmd += File(dir, "qnet-qemu.log").absolutePath
        cmd += "--"
        cmd += "$lib/libuml.so"
        cmd += "stub_exe=$lib/libstub.so"
        cmd += kernelArgs

        val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
        pb.environment().apply {
            put("TMPDIR", dir.absolutePath)
            put("HOME", dir.absolutePath)
            put("LD_LIBRARY_PATH", lib)
            putAll(env)
        }
        val log = consoleLog(ctx)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            Log.e(TAG, "spawn failed", e)
            return "error spawn ${e.message}"
        }
        process = p
        stdin = p.outputStream
        startedAt = System.currentTimeMillis()
        lastExit = null
        Log.i(TAG, "started: ${cmd.joinToString(" ")}")

        Thread({
            FileOutputStream(log, false).use { out ->
                val buf = ByteArray(8192)
                val input = p.inputStream
                while (true) {
                    val n = try { input.read(buf) } catch (e: Exception) {
                        Log.w(TAG, "console read ended", e); -1
                    }
                    if (n < 0) break
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
            val code = p.waitFor()
            synchronized(this) { lastExit = code }
            Log.i(TAG, "exited code=$code after ${(System.currentTimeMillis() - startedAt) / 1000}s")
        }, "uml-console").start()
        return "ok started ${cmd.size} args"
    }

    @Synchronized
    fun send(text: String): String {
        val out = stdin ?: return "error not-running"
        if (process?.isAlive != true) return "error not-running"
        return try {
            out.write(text.toByteArray())
            out.flush()
            "ok sent ${text.length}"
        } catch (e: Exception) {
            Log.w(TAG, "send failed", e)
            "error send ${e.message}"
        }
    }

    /** Copies a file from the work dir into the guest console. */
    fun sendFile(ctx: Context, name: String): String {
        val f = File(workDir(ctx), name)
        if (!f.isFile || f.canonicalPath.startsWith(workDir(ctx).canonicalPath).not()) {
            return "error no-such-file"
        }
        return send(f.readText())
    }

    /**
     * Runs one mconsole command through libummc.so. The client must be a
     * child of the app: SELinux denies other domains (run-as, shell) sending
     * to the kernel's untrusted_app datagram socket.
     */
    fun mconsole(ctx: Context, command: String): String {
        val sock = Regex("mconsole \\(version 2\\) initialized on (\\S+)")
            .findAll(consoleLog(ctx).readText()).lastOrNull()?.groupValues?.get(1)
            ?: return "error no-mconsole-socket"
        val lib = ctx.applicationInfo.nativeLibraryDir
        val p = ProcessBuilder(listOf("$lib/libummc.so", sock) + command.trim().split(Regex("\\s+")))
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return "ok rc=${p.waitFor()} ${out.replace('\n', '|')}"
    }

    @Synchronized
    fun signal(sig: Int): String {
        val pid = mainPid() ?: return "error not-running"
        android.os.Process.sendSignal(pid, sig)
        return "ok signalled $pid $sig"
    }

    /** SIGKILL the supervisor and every descendant, deepest first. */
    @Synchronized
    fun killTree(): String {
        val pid = mainPid() ?: return "error not-running"
        val tree = descendants(pid)
        tree.reversed().forEach { android.os.Process.sendSignal(it, 9) }
        return "ok killed ${tree.size}"
    }

    @Synchronized
    fun destroy(): String {
        val p = process ?: return "error not-running"
        p.destroy()
        return "ok destroyed"
    }

    @Synchronized
    fun status(ctx: Context): String {
        val alive = process?.isAlive == true
        val pid = mainPid()
        val tree = if (pid != null) descendants(pid) else emptyList()
        val rssMb = tree.sumOf { HostMetrics.processVmRssMb(it) ?: 0L }
        val up = if (alive) (System.currentTimeMillis() - startedAt) / 1000 else 0
        return "ok alive=$alive pid=${pid ?: "-"} exit=${lastExit ?: "-"} up=${up}s " +
            "hostprocs=${tree.size} rssMb=$rssMb log=${consoleLog(ctx).length()}"
    }

    private fun mainPid(): Int? =
        HostMetrics.childPidByExecutable(android.os.Process.myPid(), executables)

    /** pid and every descendant, found by walking /proc ppid links. */
    private fun descendants(root: Int): List<Int> {
        val parent = HashMap<Int, Int>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()
                ?.let { parent[pid] = it }
        }
        val out = mutableListOf(root)
        var i = 0
        while (i < out.size) {
            val cur = out[i++]
            parent.forEach { (pid, pp) -> if (pp == cur) out += pid }
        }
        return out
    }
}
