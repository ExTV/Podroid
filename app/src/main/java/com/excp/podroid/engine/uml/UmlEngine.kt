/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * User-Mode Linux engine. The guest kernel runs as ordinary host processes
 * under libpodroid-uml.so (podroid-uml.c), which also runs QEMU as a
 * guest-less SLIRP router and exposes the same Unix sockets QemuEngine's
 * QEMU does, in the same place (filesDir):
 *
 *   serial.sock   con0: kernel + init console, boot-log sink (QemuBootMonitor)
 *   terminal.sock ttyS0: getty, podroid-bridge
 *   ctrl.sock     ttyS1: resize channel
 *   host.sock     ttyS2: host bridge (QemuHostTransport)
 *   qmp.sock      the router's QMP: runtime port forwards
 *
 * So everything Android-side is QemuEngine's code; this class differs only in
 * what it launches and how it maps the supervisor's exit code. Structure and
 * lifecycle deliberately mirror QemuEngine; read that first.
 */
package com.excp.podroid.engine.uml

import android.annotation.SuppressLint
import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.excp.podroid.R
import com.excp.podroid.data.repository.LanguageManager
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.BootStageDetector
import com.excp.podroid.engine.ProxySessionClient
import com.excp.podroid.engine.QemuBootMonitor
import com.excp.podroid.engine.QemuEngine
import com.excp.podroid.engine.QmpClient
import com.excp.podroid.engine.TerminalBridge
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.engine.ensureStorageImage
import com.excp.podroid.engine.hostbridge.HostTransport
import com.excp.podroid.engine.hostbridge.QemuHostTransport
import com.excp.podroid.util.HostMetrics
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("StaticFieldLeak") // ApplicationContext: lives as long as the process, no leak
@Singleton
class UmlEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val probeStore: UmlProbeStore,
) : VmEngine {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    override val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    override val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val _bootStage = MutableStateFlow("")
    override val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    @Volatile private var _terminalSession: TerminalSession? = null
    override val terminalSession: TerminalSession? get() = _terminalSession

    override val backendId: String = "uml"

    @Volatile private var _runningSinceMs: Long? = null
    override val runningSinceMs: Long? get() = _runningSinceMs

    @Volatile
    var process: Process? = null
        private set

    @Volatile private var pidCache: Pair<Process, Int>? = null

    /** The supervisor. libpodroid-launcher execv()s it, so both names are the same PID. */
    override fun emulatorPid(): Int? {
        val proc = process?.takeIf { it.isAlive } ?: return null
        pidCache?.let { (p, pid) -> if (p === proc) return pid }
        val pid = HostMetrics.childPidByExecutable(
            android.os.Process.myPid(),
            setOf(UmlCommandBuilder.LAUNCHER, UmlCommandBuilder.SUPERVISOR),
        )
        if (pid == null) {
            Log.w(TAG, "UML is running but the supervisor PID was not found in /proc")
            return null
        }
        pidCache = proc to pid
        return pid
    }

    /**
     * Summed RSS of the supervisor and everything under it: the router, the
     * kernel and one host process per guest process. Shared pages (the guest
     * memory mapping) count once per process, so this overstates real use.
     */
    override fun emulatorRssMb(): Long? {
        val pid = emulatorPid() ?: return null
        return HostMetrics.descendantPids(pid).sumOf { HostMetrics.processVmRssMb(it) ?: 0L }
    }

    private val filesDir: File get() = context.filesDir
    private val serialSockPath: String get() = "${filesDir.absolutePath}/serial.sock"
    private val terminalSockPath: String get() = "${filesDir.absolutePath}/terminal.sock"
    private val ctrlSockPath: String get() = "${filesDir.absolutePath}/ctrl.sock"
    private val hostSockPath: String get() = "${filesDir.absolutePath}/host.sock"
    private val qmpSocketPath: String get() = "${filesDir.absolutePath}/qmp.sock"

    /** Last supervisor exit code + a bounded tail of its (and the router's) stderr. */
    @Volatile private var lastExitCode: Int? = null

    /** How the last run ended; see [lastOutcome]. */
    data class RunOutcome(
        val reachedRunning: Boolean,
        val userStopping: Boolean,
        val exitCode: Int?,
        val bootTimedOut: Boolean,
        val consoleTail: String,
    ) {
        val exitClass: UmlExitClass
            get() = classifyUmlExit(reachedRunning, userStopping, exitCode, bootTimedOut, consoleTail)
    }

    /**
     * Written before the run's terminal state is published, so a collector
     * that sees that state also sees this. Null while a run is in progress and
     * for runs that never spawned the supervisor.
     */
    @Volatile var lastOutcome: RunOutcome? = null
        private set

    /** No `Ready!` in time: UML is being stopped as a failure, not by the user. */
    @Volatile private var bootTimedOut = false
    /** The timeout was the earlier socket-ready one, for the error message. */
    @Volatile private var socketsTimedOut = false
    private val stderrTail = ArrayDeque<String>()

    override val qmpClient: QmpClient? by lazy { QmpClient(qmpSocketPath) }

    private var ioScope: CoroutineScope? = null

    /**
     * Dedicated thread that both forks the launcher and blocks in waitFor():
     * PR_SET_PDEATHSIG is thread-scoped, so the spawning thread must outlive
     * the supervisor. See QemuEngine.qemuDispatcher.
     */
    private var umlDispatcher: ExecutorCoroutineDispatcher? = null

    private var bootStartTime: Long = 0L
    private var bootMonitor: QemuBootMonitor? = null

    private fun newBootStageDetector() = BootStageDetector(_bootStage, _state) {
        _runningSinceMs = System.currentTimeMillis()
        persistBootDuration()
        autoStartBridge()
    }

    private val cleanedUp = AtomicBoolean(true)
    private val startMutex = Mutex()

    private val proxySessionClient = ProxySessionClient(TAG)

    override var sessionClientDelegate: TerminalSessionClient?
        get() = proxySessionClient.delegate
        set(value) { proxySessionClient.delegate = value }

    /** A string in the app's chosen language (the application context ignores it). */
    private fun str(id: Int, vararg args: Any): String =
        LanguageManager.wrapContextForLocale(context, LanguageManager.getSavedLanguage(context))
            .getString(id, *args)

    private fun persistBootDuration() {
        if (bootStartTime == 0L) return
        val duration = System.currentTimeMillis() - bootStartTime
        bootStartTime = 0L
        ioScope?.launch { settingsRepository.setLastBootDurationMs(duration) }
    }

    private fun autoStartBridge() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (_terminalSession != null) return@post
            if (_state.value !is VmState.Running && _state.value !is VmState.Starting) return@post
            if (cleanedUp.get()) return@post
            if (!TerminalBridge.executable(context).exists()) return@post
            _terminalSession = TerminalBridge.newSession(
                context, terminalSockPath, ctrlSockPath, proxySessionClient, onResize = null,
            )
            Log.d(TAG, "Bridge auto-started on terminal.sock")
        }
    }

    override fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client
        val cached = _terminalSession
        if (cached != null && cached.isRunning) return cached
        if (cached != null) {
            Log.d(TAG, "Cached terminal session is dead, recreating against the running VM")
            _terminalSession = null
        }
        val bridgeExe = TerminalBridge.executable(context)
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }
        val sess = TerminalBridge.newSession(
            context, terminalSockPath, ctrlSockPath, proxySessionClient, onResize = null,
        )
        _terminalSession = sess
        return sess
    }

    private fun failEarlyStart(message: String) {
        cleanedUp.set(true)
        bootStartTime = 0L
        _state.value = VmState.Error(message)
    }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        startMutex.withLock {
            if (_state.value is VmState.Starting || _state.value is VmState.Running) {
                Log.w(TAG, "start() called while VM is ${_state.value}, ignoring")
                return
            }
            cleanedUp.set(false)
            lastOutcome = null
            bootTimedOut = false
            socketsTimedOut = false
            bootStartTime = System.currentTimeMillis()
            _stopping.value = false
            _state.value = VmState.Starting
        }

        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        val kernel = UmlCommandBuilder.kernelFor(pageSize)
        if (kernel == null) {
            Log.e(TAG, "No UML kernel for page size $pageSize")
            failEarlyStart(str(R.string.uml_unsupported_page_size, pageSize.toInt()))
            return
        }
        val missing = requiredBinaries(pageSize).filterNot { it.exists() }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "UML binaries missing: ${missing.map { it.name }}")
            failEarlyStart(str(R.string.uml_binaries_missing))
            return
        }

        try {
            ensureStorageImage(filesDir, config.storageSizeGb, TAG)
        } catch (e: java.io.IOException) {
            failEarlyStart(e.message ?: str(R.string.uml_disk_prepare_failed))
            return
        }

        val tmpDir = UmlCommandBuilder.tmpDir(filesDir, probe = false)
        if (!tmpDir.isDirectory && !tmpDir.mkdirs()) {
            Log.e(TAG, "Could not create ${tmpDir.absolutePath}")
            failEarlyStart(str(R.string.uml_tmpdir_failed))
            return
        }

        _consoleText.value = ""
        _bootStage.value = "Starting UML..."
        val detector = newBootStageDetector()

        // Stale sockets from a crashed run; the supervisor also unlinks before
        // binding, but the readiness loop below keys on these files existing.
        listOf(serialSockPath, terminalSockPath, ctrlSockPath, qmpSocketPath, hostSockPath)
            .forEach { File(it).delete() }

        try {
            val cmd = UmlCommandBuilder.build(
                UmlLaunchParams(
                    nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
                    filesDir = filesDir,
                    pageSize = pageSize,
                    config = config,
                    inlineRules = QemuEngine.inlineLaunchRules(portForwards),
                    downloadsPath = sharedDownloadsPath(config),
                    probe = false,
                ),
            )
            Log.d(TAG, "Launching UML with ${cmd.argv.size} args: ${cmd.argv}")

            val pb = ProcessBuilder(cmd.argv).directory(filesDir)
            pb.environment().putAll(cmd.env)
            // The kernel's own output goes to serial.sock; stdout carries nothing
            // we read, and an unread pipe could fill and stall the supervisor.
            pb.redirectOutput(File("/dev/null"))

            val dispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "podroid-uml").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            umlDispatcher = dispatcher

            val proc = withContext(dispatcher) { pb.start() }
            process = proc
            _bootStage.value = "Booting kernel..."

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope

            // Supervisor + router stderr (exec failures, QEMU netdev errors).
            scope.launch {
                try {
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = proc.errorStream.read(buf)
                        if (n < 0) break
                        val chunk = String(buf, 0, n).trimEnd()
                        Log.d("PodroidUml-err", chunk.take(300))
                        recordStderr(chunk)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stderr drain ended: ${e.message}")
                }
            }

            val monitor = QemuBootMonitor(
                serialSockPath, File(filesDir, "console.log"),
                detector, _consoleText, SOCKET_READY_TIMEOUT_MS,
            )
            bootMonitor = monitor
            monitor.connectAndRun(scope) { proc.isAlive }

            val startMs = System.currentTimeMillis()
            var socketsReady = false
            while (System.currentTimeMillis() - startMs < SOCKET_READY_TIMEOUT_MS) {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    lastExitCode = exitCode
                    Log.e(TAG, "UML died during startup, exit code: $exitCode")
                    lastOutcome = RunOutcome(false, _stopping.value, exitCode, false, outcomeTail())
                    _state.value = VmState.Error(formatExitError(exitCode, outcomeTail()))
                    cleanup()
                    return
                }
                if (File(serialSockPath).exists() && File(qmpSocketPath).exists()) {
                    Log.d(TAG, "UML sockets ready after ${System.currentTimeMillis() - startMs}ms")
                    socketsReady = true
                    break
                }
                delay(200)
            }
            if (!socketsReady) {
                Log.e(TAG, "Socket timeout: UML sockets not ready after ${SOCKET_READY_TIMEOUT_MS}ms")
                // No terminal state here: the waitFor path below records the
                // outcome first and then publishes the timeout error, so the
                // failure policy sees it and demotes.
                socketsTimedOut = true
                bootTimedOut = true
                terminate(proc)
            } else {
                // Unlike QEMU and AVF there is no "promote to Running anyway":
                // a UML boot that never prints Ready! counts as UML failing here.
                scope.launch {
                    delay(BOOT_READY_SAFETY_MS)
                    if (_state.value is VmState.Starting &&
                        process?.isAlive == true && !cleanedUp.get() && !_stopping.value) {
                        Log.w(TAG, "Ready! not detected within ${BOOT_READY_SAFETY_MS / 1000}s, stopping UML")
                        bootTimedOut = true
                        terminate(proc)
                    }
                }
            }

            val exitCode = withContext(dispatcher) { proc.waitFor() }
            lastExitCode = exitCode
            Log.d(TAG, "UML supervisor exited: $exitCode")
            val priorError = _state.value as? VmState.Error
            // A user stop can end nonzero (the supervisor SIGKILLs the group after
            // its grace period, or the router dies on the way down); that is still
            // a stop. Read before cleanup(), which clears the flag.
            val wasStopping = _stopping.value
            val timedOut = bootTimedOut
            lastOutcome = RunOutcome(_runningSinceMs != null, wasStopping, exitCode, timedOut, outcomeTail())
            cleanup()
            _state.value = when {
                priorError != null -> priorError
                wasStopping -> VmState.Stopped
                timedOut && socketsTimedOut ->
                    VmState.Error(str(R.string.uml_sockets_timeout, (SOCKET_READY_TIMEOUT_MS / 1000).toInt()))
                timedOut -> VmState.Error(str(R.string.uml_boot_timeout, (BOOT_READY_SAFETY_MS / 1000).toInt()))
                exitCode == 0 -> VmState.Stopped
                else -> VmState.Error(formatExitError(exitCode, lastOutcome?.consoleTail.orEmpty()))
            }
        } catch (e: CancellationException) {
            // Same as QemuEngine: the service scope is cancelled on every stop,
            // so this is the stop completing, not a failure.
            Log.d(TAG, "start() cancelled, finalizing as Stopped")
            cleanup()
            _state.value = VmState.Stopped
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UML", e)
            _state.value = VmState.Error(e.message ?: str(R.string.uml_unknown_error))
            cleanup()
        }
    }

    /**
     * SIGTERM the supervisor, which runs the graceful path itself (mconsole
     * "cad", up to 60 s for the guest to power off, then SIGKILL of its
     * process group). Escalate only if it is still alive after [STOP_GRACE_S].
     * start()'s waitFor() on the spawn thread drives cleanup() and the final
     * state, as on QemuEngine.
     */
    override fun stop() {
        val proc = process ?: return
        _stopping.value = true
        terminate(proc)
    }

    /** SIGTERM [proc], escalating to SIGKILL plus a tree sweep after [STOP_GRACE_S]. */
    private fun terminate(proc: Process) {
        proc.destroy()
        Thread({
            var forced = false
            try {
                if (!proc.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)) {
                    Log.w(TAG, "UML supervisor still alive ${STOP_GRACE_S}s after SIGTERM, killing it")
                    forced = true
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for UML to stop, killing it", e)
                forced = true
                proc.destroyForcibly()
            }
            // A SIGKILLed supervisor cannot reap its group; the kernel and router
            // die through PDEATHSIG, but guest stubs may linger. Sweep them.
            if (forced && !awaitUmlTreeGone(TREE_SWEEP_MS)) {
                Log.e(TAG, "UML processes survived a forced stop")
            }
        }, "podroid-uml-stop").apply { isDaemon = true }.start()
    }

    /**
     * Blocks until no UML host process (supervisor, kernel, stubs, router) is
     * left in this app's uid, or [timeoutMs] passes; survivors are then
     * SIGKILLed. Another backend must not open storage.img before this
     * returns true. Survivors are never killed while this engine's own VM is
     * still running, since they are then that VM.
     */
    fun awaitUmlTreeGone(timeoutMs: Long): Boolean =
        UmlProcessTree.awaitGone(timeoutMs, killSurvivors = process?.isAlive != true)

    override fun openHostTransport(): HostTransport? = QemuHostTransport.open(hostSockPath)

    override suspend fun addPortForward(rule: PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val qmp = qmpClient ?: return
        qmp.addPortForward(rule.hostPort, rule.guestPort, rule.protocol, rule.loopbackOnly)
            .onFailure { Log.w(TAG, "QMP addPortForward failed for $rule", it) }
            .getOrThrow()
    }

    override suspend fun removePortForward(rule: PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val qmp = qmpClient ?: return
        qmp.removePortForward(rule.hostPort, rule.protocol, rule.loopbackOnly)
            .onFailure { Log.w(TAG, "QMP removePortForward failed for $rule", it) }
            .getOrThrow()
    }

    /** Same as QEMU: only the implicit forwards ride the launch; the rest go over QMP. */
    override fun rulesAppliedAtLaunch(all: Collection<PortForwardRule>): Set<PortForwardRule> =
        QemuEngine.inlineLaunchRules(all).toSet()

    @Synchronized
    private fun cleanup() {
        if (cleanedUp.getAndSet(true)) return
        _stopping.value = false
        bootMonitor?.release()
        bootMonitor = null
        ioScope?.cancel()
        ioScope = null
        umlDispatcher?.close()
        umlDispatcher = null
        process = null
        _terminalSession?.finishIfRunning()
        _terminalSession = null
        sessionClientDelegate = null
        _consoleText.value = ""
        _runningSinceMs = null
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        _bootStage.value = ""
    }

    private fun requiredBinaries(pageSize: Long): List<File> {
        val lib = File(context.applicationInfo.nativeLibraryDir)
        return listOfNotNull(
            UmlCommandBuilder.LAUNCHER, UmlCommandBuilder.SUPERVISOR, UmlCommandBuilder.ROUTER,
            UmlCommandBuilder.kernelFor(pageSize), UmlCommandBuilder.stubFor(pageSize),
        ).map { File(lib, it) }
    }

    /** Downloads path and permission gate identical to QemuEngine's virtio-9p share. */
    private fun sharedDownloadsPath(config: VmConfig): String? {
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val hasStorageAccess = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (config.storageAccessEnabled && hasStorageAccess && downloadsDir.exists()) {
            downloadsDir.absolutePath
        } else {
            null
        }
    }

    /** QemuEngine.formatExitError minus the 9p hint, plus the router-death code. */
    private fun formatExitError(exitCode: Int, consoleTail: String): String = when {
        exitCode == EXIT_ROUTER_DIED && umlDemotionReason(exitCode, false, consoleTail) != "exit $exitCode" ->
            str(R.string.uml_crashed, umlDemotionReason(exitCode, false, consoleTail))
        exitCode == EXIT_ROUTER_DIED -> str(R.string.uml_router_stopped)
        exitCode < 128 -> str(R.string.uml_exited_code, exitCode)
        else -> str(R.string.uml_crashed, signalName(exitCode - 128))
    }

    override fun diagnosticsReport(): String = buildString {
        appendLine("last process exit code: ${lastExitCode?.toString() ?: "(still running / not yet exited)"}")
        val tail = synchronized(stderrTail) { stderrTail.toList() }
        if (tail.isEmpty()) {
            appendLine("uml supervisor stderr: (none captured)")
        } else {
            appendLine("uml supervisor stderr (last ${tail.size} line(s)):")
            tail.forEach { appendLine("  $it") }
        }
        val console = consoleTail(File(filesDir, "console.log"))
        if (console.isEmpty()) {
            appendLine("uml console: (none captured)")
        } else {
            appendLine("uml console (last ${console.size} line(s)):")
            console.forEach { appendLine("  $it") }
        }
        // Called from the diagnostic export on Dispatchers.IO; the read is a
        // single DataStore snapshot, bounded so a stuck store cannot hang it.
        val probe = try {
            runBlocking { withTimeoutOrNull(PROBE_READ_TIMEOUT_MS) { probeStore.probe.first() } }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the UML probe record", e)
            null
        }
        appendLine("uml probe: ${probe?.let { "passed=${it.passed} reason=${it.reason} key=${it.key}" } ?: "(none recorded)"}")
    }

    private fun consoleTail(file: File): List<String> {
        if (!file.isFile) return emptyList()
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - CONSOLE_TAIL_BYTES).coerceAtLeast(0)
                raf.seek(start)
                val bytes = ByteArray((raf.length() - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(CONSOLE_TAIL_LINES)
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not read console.log", e)
            listOf("(unreadable: ${e.message})")
        }
    }

    private fun outcomeTail(): String = _consoleText.value.takeLast(OUTCOME_TAIL_CHARS)

    private fun recordStderr(chunk: String) {
        if (chunk.isBlank()) return
        synchronized(stderrTail) {
            for (line in chunk.lineSequence()) {
                if (line.isBlank()) continue
                stderrTail.addLast(line)
                while (stderrTail.size > STDERR_TAIL_LINES) stderrTail.removeFirst()
            }
        }
    }

    companion object {
        private const val TAG = "UmlEngine"
        private const val STDERR_TAIL_LINES = 40
        private const val CONSOLE_TAIL_LINES = 40
        private const val CONSOLE_TAIL_BYTES = 8 * 1024L
        private const val PROBE_READ_TIMEOUT_MS = 2_000L
        private const val OUTCOME_TAIL_CHARS = 8 * 1024

        /** podroid-uml.c exits EX_TEMPFAIL when the QEMU router died under a running kernel. */
        const val EXIT_ROUTER_DIED = 75

        private const val SOCKET_READY_TIMEOUT_MS = 10_000L
        private const val BOOT_READY_SAFETY_MS = 120_000L

        /** Above the supervisor's own 60 s graceful window, so it normally wins. */
        private const val STOP_GRACE_S = 65L
        private const val TREE_SWEEP_MS = 3_000L

        fun signalName(sig: Int): String = when (sig) {
            4 -> "SIGILL"
            6 -> "SIGABRT"
            7 -> "SIGBUS"
            8 -> "SIGFPE"
            9 -> "SIGKILL"
            11 -> "SIGSEGV"
            13 -> "SIGPIPE"
            15 -> "SIGTERM"
            31 -> "SIGSYS"
            else -> "signal $sig"
        }
    }
}
