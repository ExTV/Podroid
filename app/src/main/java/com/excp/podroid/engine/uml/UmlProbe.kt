/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * On-device UML compatibility probe: boots the UML kernel with the real
 * initrd in probe mode (init-podroid checks SMP, fork/exec, a disk write and
 * DNS through the router, prints PODROID-PROBE: PASS or FAIL <step> and
 * powers off). Fully isolated from a real VM: its own work directory for the
 * supervisor sockets and TMPDIR, a throwaway scratch disk, and no squashfs or
 * storage.img.
 */
package com.excp.podroid.engine.uml

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.BootStageDetector
import com.excp.podroid.engine.QemuBootMonitor
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.HostMetrics
import com.excp.podroid.util.NetworkUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UmlProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val mutex = Mutex()

    /** Runs one probe and returns its verdict; the caller decides whether to persist it. */
    suspend fun run(key: UmlProbeKey): UmlProbeRecord = mutex.withLock {
        val (passed, reason) = withContext(Dispatchers.IO) { runProbe() }
        Log.i(TAG, "UML probe for $key: passed=$passed reason=$reason")
        UmlProbeRecord(key, passed, reason)
    }

    private suspend fun runProbe(): Pair<Boolean, String> {
        // A UML VM (or another probe) is starting or running. Never spawn next
        // to it: the final sweep below must not be able to touch its processes.
        UmlProcessTree.pids().takeIf { it.isNotEmpty() }?.let {
            Log.w(TAG, "UML processes already running $it, not probing")
            return false to REASON_BUSY
        }
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        if (UmlCommandBuilder.kernelFor(pageSize) == null) return false to "unsupported page size $pageSize"
        val lib = File(context.applicationInfo.nativeLibraryDir)
        listOfNotNull(
            UmlCommandBuilder.LAUNCHER, UmlCommandBuilder.SUPERVISOR, UmlCommandBuilder.ROUTER,
            UmlCommandBuilder.kernelFor(pageSize), UmlCommandBuilder.stubFor(pageSize),
        ).firstOrNull { !File(lib, it).exists() }?.let { return false to "missing $it" }
        val filesDir = context.filesDir
        if (!File(filesDir, "initrd.img").exists()) return false to "missing initrd.img"

        val work = UmlCommandBuilder.workDir(filesDir, probe = true)
        if (!work.isDirectory && !work.mkdirs()) return false to "cannot create ${work.absolutePath}"
        SOCKETS.forEach { File(work, it).delete() }
        val image = UmlCommandBuilder.probeImage(filesDir)
        try {
            image.delete()
            java.io.RandomAccessFile(image, "rw").use { it.setLength(UmlCommandBuilder.PROBE_IMAGE_BYTES) }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Could not create the probe scratch disk", e)
            return false to "scratch disk: ${e.message}"
        }

        // Same cmdline inputs as a real boot; RAM and disks are overridden by
        // the builder in probe mode. The guest's DNS check needs one resolver.
        val dns = NetworkUtils.dnsServers(context).ifEmpty { listOf(FALLBACK_DNS) }
        val config = VmConfig(
            cpus = settingsRepository.getVmCpusSnapshot(),
            dnsServers = dns,
            kernelExtraCmdline = settingsRepository.getKernelExtraCmdlineSnapshot(),
            x11Dpi = settingsRepository.getX11DpiSnapshot(),
            bandwidthMbps = settingsRepository.getBandwidthMbpsSnapshot(),
        )
        val cmd = UmlCommandBuilder.build(
            UmlLaunchParams(lib, filesDir, pageSize, config, emptyList(), downloadsPath = null, probe = true),
        )
        Log.d(TAG, "Launching UML probe: ${cmd.argv}")
        val pb = ProcessBuilder(cmd.argv).directory(work)
        pb.environment().putAll(cmd.env)
        pb.redirectOutput(File("/dev/null"))

        // PR_SET_PDEATHSIG is thread-scoped: spawn and reap on one private thread
        // that outlives the process (see QemuEngine.qemuDispatcher).
        val dispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "podroid-uml-probe").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var proc: Process? = null
        var monitor: QemuBootMonitor? = null
        // Anything UML that exists before the spawn is not ours and is never killed.
        val preExisting = UmlProcessTree.pids().toSet()
        val appPgid = HostMetrics.processGroupId(android.os.Process.myPid())
        try {
            val p = withContext(dispatcher) { pb.start() }
            proc = p
            scope.launch {
                try {
                    p.errorStream.bufferedReader().forEachLine { if (isActive) Log.d("PodroidUmlProbe-err", it.take(300)) }
                } catch (e: java.io.IOException) {
                    Log.d(TAG, "Probe stderr drain ended: ${e.message}")
                }
            }
            val console = MutableStateFlow("")
            val m = QemuBootMonitor(
                File(work, "serial.sock").path, File(work, "console.log"),
                BootStageDetector(MutableStateFlow(""), MutableStateFlow<VmState>(VmState.Starting)) {},
                console, SOCKET_READY_TIMEOUT_MS,
            )
            monitor = m
            m.connectAndRun(scope) { p.isAlive }

            val verdict = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                var v: Pair<Boolean, String>? = null
                while (v == null) {
                    v = classifyProbeConsole(console.value)
                    if (v == null && !p.isAlive) {
                        // Let the monitor drain what the guest printed last.
                        delay(EXIT_DRAIN_MS)
                        v = classifyProbeConsole(console.value) ?: (false to "exit ${p.exitValue()}")
                    }
                    if (v == null) delay(POLL_MS)
                }
                v
            } ?: (false to "timeout")

            // The guest powers itself off after a verdict; otherwise ask the
            // supervisor to stop it, then kill it.
            withContext(dispatcher) {
                if (!p.waitFor(SELF_EXIT_S, TimeUnit.SECONDS)) {
                    p.destroy()
                    if (!p.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)) {
                        Log.w(TAG, "Probe supervisor ignored SIGTERM, killing it")
                        p.destroyForcibly()
                        p.waitFor(2, TimeUnit.SECONDS)
                    }
                }
            }
            return verdict
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Could not start the UML probe", e)
            return false to "spawn: ${e.message}"
        } finally {
            withContext(NonCancellable) {
                proc?.takeIf { it.isAlive }?.let {
                    Log.w(TAG, "Probe still running on exit, killing it")
                    it.destroyForcibly()
                }
                monitor?.release()
                scope.cancel()
                dispatcher.close()
                val owned = { UmlProcessTree.ownedBy(UmlProcessTree.procs(), work.path, preExisting, appPgid) }
                if (proc != null && !UmlProcessTree.awaitGone(TREE_SWEEP_MS, select = owned)) {
                    Log.e(TAG, "UML probe processes survived")
                }
                SOCKETS.forEach { File(work, it).delete() }
                if (!image.delete() && image.exists()) Log.w(TAG, "Could not delete ${image.absolutePath}")
            }
        }
    }

    companion object {
        private const val TAG = "UmlProbe"
        private val SOCKETS = listOf("serial.sock", "terminal.sock", "ctrl.sock", "host.sock", "qmp.sock")
        private const val FALLBACK_DNS = "8.8.8.8"
        const val REASON_BUSY = "busy"

        const val PROBE_TIMEOUT_MS = 60_000L
        private const val SOCKET_READY_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 200L
        private const val EXIT_DRAIN_MS = 500L
        private const val SELF_EXIT_S = 5L
        private const val STOP_GRACE_S = 20L
        private const val TREE_SWEEP_MS = 3_000L

        private val FAIL_LINE = Regex("PODROID-PROBE: FAIL (\\S+)")
        private val DETAIL_LINE = Regex("PODROID-PROBE-DETAIL: (.+)")
        private const val PASS_LINE = "PODROID-PROBE: PASS"
        val FATAL_SIGNATURES = listOf("MADV_REMOVE failed", "Kernel panic", "killed by signal")

        /**
         * Verdict from the probe console so far, or null while undecided. The
         * guest's own verdict wins over a fatal signature, so a panic printed
         * while powering off after a PASS does not turn it into a failure.
         */
        fun classifyProbeConsole(text: String): Pair<Boolean, String>? {
            FAIL_LINE.find(text)?.let {
                val detail = DETAIL_LINE.find(text)?.groupValues?.get(1)?.trim()
                return false to "FAIL ${it.groupValues[1]}" + (detail?.let { d -> " ($d)" } ?: "")
            }
            if (PASS_LINE in text) return true to "PASS"
            FATAL_SIGNATURES.firstOrNull { it in text }?.let { return false to it }
            return null
        }
    }
}
