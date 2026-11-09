/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.uml

import android.util.Log
import com.excp.podroid.util.HostMetrics

/**
 * Finds and reaps the host processes a UML run leaves behind: the supervisor,
 * the kernel and its helper clones, one stub process per guest process, and
 * the QEMU SLIRP router. Another backend must not open storage.img until
 * none of them is left.
 */
internal object UmlProcessTree {
    private const val TAG = "UmlProcessTree"
    private const val POLL_MS = 100L
    private const val AFTER_KILL_MS = 1_000L

    private val UML_NAMES = setOf(
        "libuml-4k.so", "libuml-16k.so",
        "libumlstub-4k.so", "libumlstub-16k.so",
        UmlCommandBuilder.SUPERVISOR,
    )

    /**
     * True for a UML process. The router shares its binary with QemuEngine's
     * VM, so it is told apart by the "-M none" the supervisor always passes;
     * QemuEngine's own QEMU is "-M virt,...". The launcher counts only in the
     * instant before it execs the supervisor.
     */
    fun isUml(exeName: String?, argv: List<String>): Boolean {
        val name0 = argv.firstOrNull()?.substringAfterLast('/')
        if (exeName != null && exeName in UML_NAMES || name0 != null && name0 in UML_NAMES) return true
        if (name0 == UmlCommandBuilder.LAUNCHER &&
            argv.getOrNull(1)?.substringAfterLast('/') == UmlCommandBuilder.SUPERVISOR) return true
        val isQemu = exeName == UmlCommandBuilder.ROUTER || name0 == UmlCommandBuilder.ROUTER
        return isQemu && argv.zipWithNext().any { it == ("-M" to "none") }
    }

    fun pids(): List<Int> = HostMetrics.pidsMatching(android.os.Process.myUid(), match = ::isUml)

    /** A UML host process as seen in /proc, for [ownedBy]. */
    data class Proc(val pid: Int, val ppid: Int?, val pgid: Int?, val argv: List<String>)

    fun procs(): List<Proc> = pids().map {
        Proc(it, HostMetrics.parentPid(it), HostMetrics.processGroupId(it), HostMetrics.processArgv(it))
    }

    /**
     * Of [procs] (UML processes), the ones belonging to the run whose
     * supervisor work directory is [workDir], never one of [preExisting].
     * A process is marked when its argv names [workDir] (supervisor argv[3],
     * the router's qmp path, the probe kernel's ubd0); the run's process group
     * is the group of a marked process, or one led by a child of a marked
     * process (the kernel leads it; stubs and the router join it). [appPgid],
     * which the supervisor inherits from the app and so shares with any other
     * run's supervisor, is never treated as the run's group.
     */
    fun ownedBy(procs: List<Proc>, workDir: String, preExisting: Set<Int>, appPgid: Int?): List<Int> {
        val marked = procs.filter { p -> p.argv.any { it == workDir || it.contains("$workDir/") } }
            .map { it.pid }.toSet()
        val groups = procs.mapNotNull { p ->
            when {
                p.pid in marked -> p.pgid
                p.ppid in marked && p.pgid == p.pid -> p.pid
                else -> null
            }
        }.filter { it != appPgid }.toSet()
        return procs.filter { (it.pid in marked || it.pgid in groups) && it.pid !in preExisting }.map { it.pid }
    }

    /**
     * Polls until [select] finds no process or [timeoutMs] passes; survivors
     * are then SIGKILLed by pid and given [AFTER_KILL_MS] to go. Unless
     * [killSurvivors] is false, which callers use when a live run may own them.
     * [select] defaults to every UML process. Blocking.
     */
    fun awaitGone(
        timeoutMs: Long,
        killSurvivors: Boolean = true,
        select: () -> List<Int> = ::pids,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (select().isEmpty()) return true
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(POLL_MS)
        }
        val survivors = select()
        if (survivors.isEmpty()) return true
        if (!killSurvivors) {
            Log.w(TAG, "UML processes still running after ${timeoutMs}ms: $survivors")
            return false
        }
        Log.w(TAG, "UML processes survived ${timeoutMs}ms, sending SIGKILL: $survivors")
        survivors.forEach { android.os.Process.sendSignal(it, android.os.Process.SIGNAL_KILL) }
        val killDeadline = System.currentTimeMillis() + AFTER_KILL_MS
        while (System.currentTimeMillis() < killDeadline) {
            if (select().isEmpty()) return true
            Thread.sleep(POLL_MS)
        }
        val left = select()
        if (left.isEmpty()) return true
        Log.e(TAG, "UML processes survived SIGKILL: $left")
        return false
    }
}
