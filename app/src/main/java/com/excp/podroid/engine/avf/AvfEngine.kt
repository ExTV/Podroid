/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AvfEngine — VmEngine implementation backed by Android Virtualization Framework.
 *
 * Uses AvfReflect for all android.system.virtualmachine.* calls so the APK
 * compiles against the public SDK. On devices without pKVM the start() call
 * lands in the catch branch and transitions to VmState.Error.
 *
 * Terminal wiring: ConsoleFanout bridges AVF console streams to a filesystem
 * unix socket; libpodroid-bridge connects to that socket and splices PTY ↔
 * socket, same as the QEMU path. The ctrl socket is a dummy path (never bound)
 * — bridge tolerates the connect failure gracefully (no resize on AVF, MVP).
 */
package com.excp.podroid.engine.avf

import android.content.Context
import android.system.Os
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.BootStageDetector
import com.excp.podroid.engine.QmpClient
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.LogProxy
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AvfEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @Suppress("UnusedPrivateMember") private val settingsRepository: SettingsRepository,
) : VmEngine {

    companion object {
        private const val TAG = "AvfEngine"
        // Use "podroid" as the VM name. The smoke-test in AvfDiagnostics used
        // "podroid-avf-smoke" — a different name — so there is no config
        // conflict between the two paths.
        private const val VM_NAME = "podroid"
    }

    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    override val state: StateFlow<VmState> = _state.asStateFlow()

    private val _bootStage = MutableStateFlow("")
    override val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    override val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    override var terminalSession: TerminalSession? = null
        private set

    override val backendId: String = "avf"

    /** AVF has no QMP socket; port forwarding is deferred to a future milestone. */
    override val qmpClient: QmpClient? = null

    override var sessionClientDelegate: TerminalSessionClient? = null

    private val proxySessionClient = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) { sessionClientDelegate?.onTextChanged(s) }
        override fun onTitleChanged(s: TerminalSession) { sessionClientDelegate?.onTitleChanged(s) }
        override fun onSessionFinished(s: TerminalSession) { sessionClientDelegate?.onSessionFinished(s) }
        override fun onCopyTextToClipboard(s: TerminalSession, text: String?) { sessionClientDelegate?.onCopyTextToClipboard(s, text) }
        override fun onPasteTextFromClipboard(s: TerminalSession?) { sessionClientDelegate?.onPasteTextFromClipboard(s) }
        override fun onBell(s: TerminalSession) { sessionClientDelegate?.onBell(s) }
        override fun onColorsChanged(s: TerminalSession) { sessionClientDelegate?.onColorsChanged(s) }
        override fun onTerminalCursorStateChange(state: Boolean) { sessionClientDelegate?.onTerminalCursorStateChange(state) }
        override fun setTerminalShellPid(s: TerminalSession, pid: Int) { sessionClientDelegate?.setTerminalShellPid(s, pid) }
        override fun getTerminalCursorStyle(): Int = sessionClientDelegate?.terminalCursorStyle ?: 0
        override fun getTerminalVersionString(): String? = sessionClientDelegate?.terminalVersionString
        override fun logError(tag: String?, msg: String?) = LogProxy.error(tag, TAG, msg)
        override fun logWarn(tag: String?, msg: String?) = LogProxy.warn(tag, TAG, msg)
        override fun logInfo(tag: String?, msg: String?) = LogProxy.info(tag, TAG, msg)
        override fun logDebug(tag: String?, msg: String?) = LogProxy.debug(tag, TAG, msg)
        override fun logVerbose(tag: String?, msg: String?) = LogProxy.verbose(tag, TAG, msg)
        override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, msg, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vmHandle: Any? = null
    private var consoleStream: java.io.InputStream? = null
    private var consoleStreamInput: java.io.OutputStream? = null
    private var fanout: ConsoleFanout? = null
    /** Initial rules captured from start()'s portForwards arg — replayed when the control channel comes up. */
    private val initialRules = mutableListOf<com.excp.podroid.data.repository.PortForwardRule>()
    @Volatile private var control: VsockControlChannel? = null
    /** vport → forwarder. The diff loop in EngineHolder is the single writer. */
    private val forwarders = java.util.concurrent.ConcurrentHashMap<Int, VsockPortForwarder>()
    @Volatile private var lastSentRows = -1
    @Volatile private var lastSentCols = -1
    private var resizeDebounceJob: kotlinx.coroutines.Job? = null

    val terminalSockPath: String get() = "${context.filesDir.absolutePath}/avf-terminal.sock"
    val ctrlSockPath: String get() = "${context.filesDir.absolutePath}/avf-ctrl.sock"

    private val detector = BootStageDetector(_bootStage, _state) {
        Log.i(TAG, "boot Ready! detected — bridge connects via ConsoleFanout")
        // Wipe the boot log from the emulator so the user sees a clean login
        // prompt. AVF only exposes one captured console stream, so unlike
        // QEMU (separate serial.sock for boot) every kernel byte ends up in
        // the PTY scrollback by the time Open Terminal is tapped. ESC c =
        // RIS (full terminal reset); ESC [2J + ESC [H clears + homes the
        // cursor in case the emulator silently ignores RIS.
        val reset = byteArrayOf(0x1b, 'c'.code.toByte(),
            0x1b, '['.code.toByte(), '2'.code.toByte(), 'J'.code.toByte(),
            0x1b, '['.code.toByte(), 'H'.code.toByte())
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { terminalSession?.emulator?.append(reset, reset.size) }
        }
        // Bring up the vsock control channel and replay initial forwards.
        // EngineHolder's diff loop will keep the set in sync from here on.
        val vm = vmHandle
        if (vm != null) {
            val ctl = VsockControlChannel(vm, scope)
            control = ctl
            ctl.open()
            scope.launch {
                for (rule in initialRules) {
                    runCatching {
                        // Same convention as addPortForward: vport = hostPort.
                        val fw = VsockPortForwarder(
                            hostPort = rule.hostPort,
                            guestVsockPort = rule.hostPort,
                            vm = vm,
                            scope = scope,
                        ).also { it.start() }
                        forwarders[rule.hostPort] = fw
                        ctl.addForward(rule.hostPort, "127.0.0.1", rule.guestPort)
                    }.onFailure { Log.w(TAG, "initial-rule replay failed for $rule", it) }
                }
            }
        }
    }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        if (_state.value is VmState.Running || _state.value is VmState.Starting) return
        initialRules.clear()
        initialRules.addAll(portForwards.filter { it.protocol == "tcp" })
        if (portForwards.any { it.protocol != "tcp" }) {
            Log.w(TAG, "AVF supports TCP only; UDP/non-TCP rules ignored: " +
                "${portForwards.filter { it.protocol != "tcp" }}")
        }
        _state.value = VmState.Starting
        _bootStage.value = "Initializing AVF..."
        // Re-arm the one-shot detector so a Stop → Start cycle's second boot
        // can fire onReady again. Without this the new boot's "Ready!" marker
        // is silently ignored and state stays Starting forever.
        detector.reset()

        try {
            val mgr = AvfReflect.manager(context)
            val vmConfigObj = buildConfig(config)

            // AOSP canonical pattern: get-or-create, then attempt to update the
            // config on the existing VM. If AVF rejects the new config as
            // incompatible (e.g. vmOutputCaptured changed from a stale record),
            // delete the old VM and create a fresh one.
            val vm: Any = run {
                val existing = AvfReflect.getOrCreate(mgr, VM_NAME, vmConfigObj)
                runCatching { AvfReflect.setConfig(existing, vmConfigObj) }.fold(
                    onSuccess = { existing },
                    onFailure = {
                        Log.w(TAG, "stale VM config detected (${it.message}); deleting + recreating")
                        AvfReflect.delete(mgr, VM_NAME)
                        AvfReflect.create(mgr, VM_NAME, vmConfigObj)
                    },
                )
            }
            vmHandle = vm

            val cb = AvfReflect.newVmCallback(
                onError = { code, msg ->
                    Log.e(TAG, "VM onError code=$code msg=$msg")
                    _state.value = VmState.Error("AVF onError($code): ${msg ?: "no message"}")
                },
                onStopped = { reason ->
                    Log.i(TAG, "VM onStopped reason=$reason")
                    if (_state.value is VmState.Running) {
                        _state.value = VmState.Stopped
                    } else if (_state.value is VmState.Starting) {
                        // Stopped during boot — surface as error so the UI shows it.
                        _state.value = VmState.Error("VM exited during boot (reason=$reason)")
                    }
                },
                onDied = { reason ->
                    Log.w(TAG, "VM onDied reason=$reason")
                },
            )
            AvfReflect.setCallback(vm, java.util.concurrent.ForkJoinPool.commonPool(), cb)

            val inStream = AvfReflect.consoleOutput(vm)
            val outStream = AvfReflect.consoleInput(vm)
            consoleStream = inStream
            consoleStreamInput = outStream

            // Fan out: VM ↔ filesystem socket. The bridge subprocess connects to
            // that socket and splices PTY ↔ socket. BootStageDetector tees the
            // VM output to drive boot-stage + state transitions.
            val fo = ConsoleFanout(
                consoleOutput = inStream,
                consoleInput = outStream,
                socketPath = terminalSockPath,
                detector = detector,
                scope = scope,
            )
            fanout = fo
            fo.start()

            AvfReflect.run(vm)
            val status = runCatching { AvfReflect.getStatus(vm) }.getOrDefault(-1)
            Log.i(TAG, "vm.run() returned — VM booting (status=$status)")
            spawnBridge()
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            Log.e(TAG, "AVF start failed", cause)
            _state.value = VmState.Error("AVF rejected: ${cause.javaClass.simpleName}: ${cause.message}")
            cleanup()
        }
    }

    /**
     * Spawn libpodroid-bridge.so unconditionally at VM start, NOT on demand.
     * The fanout's accept() loops until the bridge connects; without an early
     * bridge spawn, the VM's console output is never drained and BootStageDetector
     * never sees "Ready!". This matches QemuEngine's autoStartBridge pattern but
     * with inverse timing (QEMU spawns AFTER ready; AVF spawns BEFORE ready).
     */
    private fun spawnBridge() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (terminalSession != null) return@post
            val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
            if (!bridgeExe.exists()) {
                Log.e(TAG, "bridge missing at ${bridgeExe.absolutePath}")
                return@post
            }
            val sess = com.excp.podroid.engine.ResizeNotifyingSession(
                shellPath = bridgeExe.absolutePath,
                cwd = context.filesDir.absolutePath,
                args = arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
                env = null,
                transcriptRows = 2000,
                client = proxySessionClient,
                onResize = { rows, cols -> sendResizeDebounced(rows, cols) },
            )
            sess.updateSize(80, 24, 0, 0)
            terminalSession = sess
            Log.d(TAG, "AVF bridge auto-spawned (resize-notifying)")
        }
    }

    override fun stop() {
        vmHandle?.let { AvfReflect.stop(it) }
        _state.value = VmState.Stopped
        cleanup()
    }

    override suspend fun addPortForward(rule: com.excp.podroid.data.repository.PortForwardRule) {
        if (_state.value !is VmState.Running) return
        val vm = vmHandle ?: return
        if (rule.protocol != "tcp") {
            Log.w(TAG, "addPortForward($rule) — AVF supports TCP only; rule ignored")
            return
        }
        try {
            // Convention: vsock port == hostPort. The guest agent listens on
            // vsock:hostPort and splices to tcp 127.0.0.1:guestPort. Using
            // hostPort (which is unique per Android-side rule) instead of
            // guestPort avoids vsock-port collisions when two rules target
            // the same service inside the VM (e.g. 8080→80 + 9090→80).
            val fw = VsockPortForwarder(
                hostPort = rule.hostPort,
                guestVsockPort = rule.hostPort,
                vm = vm,
                scope = scope,
            )
            // putIfAbsent races safely with another concurrent addPortForward
            // for the same hostPort — only one forwarder wins; the loser closes
            // immediately so we don't leak a bound ServerSocket.
            val existing = forwarders.putIfAbsent(rule.hostPort, fw)
            if (existing != null) {
                Log.d(TAG, "addPortForward($rule) — forwarder already exists for ${rule.hostPort}")
                return
            }
            // Re-check Running state before .start() so a concurrent cleanup()
            // doesn't orphan us. If state has slipped, drop the freshly-mapped
            // forwarder and don't bind the ServerSocket.
            if (_state.value !is VmState.Running) {
                forwarders.remove(rule.hostPort)
                return
            }
            fw.start()
            control?.addForward(rule.hostPort, "127.0.0.1", rule.guestPort)
            Log.i(TAG, "live forward up: 0.0.0.0:${rule.hostPort} → vsock:${rule.hostPort} → 127.0.0.1:${rule.guestPort}")
        } catch (e: Throwable) {
            // If we got past putIfAbsent before the throw, remove ourselves so
            // cleanup() doesn't try to close a half-built forwarder.
            forwarders.remove(rule.hostPort)
            Log.w(TAG, "addPortForward($rule) failed", e)
        }
    }

    override suspend fun removePortForward(rule: com.excp.podroid.data.repository.PortForwardRule) {
        if (rule.protocol != "tcp") return
        val fw = forwarders.remove(rule.hostPort) ?: return
        runCatching { fw.close() }
        runCatching { control?.removeForward(rule.hostPort) }
        Log.i(TAG, "live forward down: 0.0.0.0:${rule.hostPort}")
    }

    /**
     * Coalesce SIGWINCH bursts (keyboard slide produces ~25 layout events) into
     * one RESIZE message. 80 ms matches the bridge's RESIZE_DEBOUNCE_MS constant
     * so the AVF behavior tracks the QEMU bridge's user-visible cadence.
     */
    private fun sendResizeDebounced(rows: Int, cols: Int) {
        if (rows == lastSentRows && cols == lastSentCols) return
        resizeDebounceJob?.cancel()
        resizeDebounceJob = scope.launch {
            kotlinx.coroutines.delay(80)
            val ctl = control ?: return@launch
            // sendResize queues if the connect retry hasn't completed yet —
            // no need to gate on isOpen.
            ctl.sendResize(rows, cols)
            lastSentRows = rows
            lastSentCols = cols
        }
    }

    override fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client
        terminalSession?.let {
            Log.d(TAG, "Returning auto-spawned AVF terminal session")
            return it
        }
        // start() hasn't reached spawnBridge yet — spawn synchronously as fallback.
        Log.w(TAG, "createTerminalSession called before bridge auto-spawn; spawning now")
        val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }
        val sess = com.excp.podroid.engine.ResizeNotifyingSession(
            shellPath = bridgeExe.absolutePath,
            cwd = context.filesDir.absolutePath,
            args = arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
            env = null,
            transcriptRows = 2000,
            client = proxySessionClient,
            onResize = { rows, cols -> sendResizeDebounced(rows, cols) },
        )
        sess.updateSize(80, 24, 0, 0)
        terminalSession = sess
        return sess
    }

    private fun cleanup() {
        // Tear down forwarders + control before draining the fanout so the
        // VM doesn't see late vsock connect attempts after onStopped.
        forwarders.values.forEach { runCatching { it.close() } }
        forwarders.clear()
        lastSentRows = -1
        lastSentCols = -1
        resizeDebounceJob?.cancel()
        resizeDebounceJob = null
        runCatching { control?.close() }
        control = null
        // Close fanout next — drains its coroutines and closes the streams inside.
        runCatching { fanout?.close() }
        fanout = null
        runCatching { consoleStreamInput?.close() }
        consoleStreamInput = null
        runCatching { consoleStream?.close() }
        consoleStream = null
        // Tear down the old terminal session so the next start()'s spawnBridge
        // doesn't short-circuit on the stale reference and leave the fanout's
        // accept() blocked forever (visible as "stuck at Initializing AVF...").
        runCatching { terminalSession?.finishIfRunning() }
        terminalSession = null
        vmHandle = null
    }

    /**
     * Creates (or resizes) the persistent storage disk. Mirrors QemuEngine's
     * helper exactly so a VM can boot under either backend with the same
     * disk identity. The file is sparse — the actual disk allocation grows
     * as the guest writes blocks. Alpine's init formats it ext4 on first
     * boot via the standard initramfs path.
     */
    private fun ensureStorageImage(storageSizeGb: Int): File {
        val storageFile = File(context.filesDir, "storage.img")
        val desiredBytes = storageSizeGb.toLong() * 1024L * 1024L * 1024L
        if (storageFile.exists() && storageFile.length() == desiredBytes) return storageFile
        if (storageFile.exists()) {
            Log.d(TAG, "storage.img size mismatch — recreating")
            storageFile.delete()
        }
        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${storageSizeGb}GB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create storage.img", e)
        }
        return storageFile
    }

    /**
     * crosvm requires the raw ARM64 Image (magic `ARM\x64` at offset 0x38), not
     * the gzip-compressed `vmlinuz` that QEMU happily auto-decompresses. We
     * gunzip on-demand to a sibling .raw file and feed THAT to AVF.
     *
     * Skips decompression if the .raw file's mtime is newer than the source.
     * Returns the decompressed file (or the source if already raw).
     */
    private fun ensureRawKernel(source: File): File {
        val magic = ByteArray(4)
        source.inputStream().use { it.read(magic) }
        val isGzip = magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()
        if (!isGzip) return source

        val raw = File(source.parentFile, "${source.name}.raw")
        if (raw.exists() && raw.lastModified() >= source.lastModified()) return raw

        Log.d(TAG, "Decompressing ${source.name} → ${raw.name}")
        java.util.zip.GZIPInputStream(source.inputStream().buffered()).use { gz ->
            raw.outputStream().buffered().use { out -> gz.copyTo(out) }
        }
        raw.setLastModified(System.currentTimeMillis())
        return raw
    }

    private fun buildConfig(config: VmConfig): Any {
        val kernelSrc = File(context.filesDir, "vmlinuz-virt").also {
            require(it.exists()) { "kernel missing at ${it.absolutePath}" }
        }
        val kernel = ensureRawKernel(kernelSrc)
        val initrd = File(context.filesDir, "initrd.img").also {
            require(it.exists()) { "initrd missing at ${it.absolutePath}" }
        }
        val storage = ensureStorageImage(config.storageSizeGb)
        val squashfs = File(context.filesDir, "alpine-rootfs.squashfs").also {
            require(it.exists()) { "rootfs missing at ${it.absolutePath}" }
        }

        val cb = AvfReflect.newCustomBuilder()
        AvfReflect.setName(cb, VM_NAME)
        AvfReflect.setKernelPath(cb, kernel.absolutePath)
        AvfReflect.setInitrdPath(cb, initrd.absolutePath)
        // AVF picks the console device via setConsoleInputDevice on the outer
        // builder; the kernel discovers it through DT/ACPI tables crosvm sets up.
        // Don't pass `console=` here — it'll fight AVF's choice.
        // `podroid.tty=ttyS0` tells /usr/local/bin/podroid-getty to spawn
        // the login on PL011 serial (AVF's only console with an input fd).
        // The hvc0 getty becomes a no-op sleeper instead of dual-printing.
        //
        // `podroid.epoch=...` seeds the guest's wall clock from the host —
        // AVF doesn't wire an emulated RTC like QEMU TCG does, so without
        // this the VM boots at 1970-01-01 and TLS fails on every cert.
        val epoch = System.currentTimeMillis() / 1000
        AvfReflect.addParams(cb,
            "root=/dev/ram0 mitigations=off elevator=mq-deadline podroid.tty=ttyS0 podroid.epoch=$epoch ${config.kernelExtraCmdline}".trim()
        )
        AvfReflect.addDisk(cb, storage.absolutePath, writable = true)
        AvfReflect.addDisk(cb, squashfs.absolutePath, writable = false)
        if (config.storageAccessEnabled) {
            val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ).absolutePath
            runCatching {
                // hostUid/hostGid are this app's UID — AVF requires per-user
                // identity even though we share a single uid/gid for app procs.
                // guestUid=1000 (Alpine root, who runs the mount), gid=100
                // (users), mask 0007 = group-readable, world-blocked.
                AvfReflect.addSharedPath(
                    customBuilder = cb,
                    sharedPath = downloads,
                    tag = "downloads",
                    hostUid = android.os.Process.myUid(),
                    hostGid = Os.getgid(),
                    guestUid = 1000,
                    guestGid = 100,
                    mask = 0x0007,
                    socketPath = "${context.filesDir.absolutePath}/avf-virtfs-downloads.sock",
                )
                Log.i(TAG, "downloads share added: $downloads")
            }.onFailure { Log.w(TAG, "addSharedPath failed (continuing without share)", it) }
        }
        AvfReflect.setNetworkSupported(cb, true)
        val customCfg = AvfReflect.build(cb)

        val vb = AvfReflect.newVmConfigBuilder(context)
        AvfReflect.setProtectedVm(vb, false)
        AvfReflect.setMemoryBytes(vb, config.ramMb.toLong() * 1024 * 1024)
        AvfReflect.setNumCpus(vb, config.cpus)
        AvfReflect.setDebugLevel(vb, AvfReflect.DEBUG_LEVEL_FULL)
        AvfReflect.setConsoleInputDevice(vb, "ttyS0")        // AVF expects ttyS0
        AvfReflect.setConnectVmConsole(vb, false)            // false: avoid inheriting Activity FDs (SurfaceFlinger sync fences → SELinux denial)
        AvfReflect.setVmOutputCaptured(vb, true)
        AvfReflect.setVmConsoleInputSupported(vb, true)
        AvfReflect.setCustomImageConfig(vb, customCfg)
        return AvfReflect.build(vb)
    }
}
