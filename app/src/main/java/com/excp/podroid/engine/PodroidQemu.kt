/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * QEMU engine for Podroid. Manages the VM lifecycle and exposes three Unix
 * sockets for the terminal layer:
 *
 *   serial.sock   — QEMU -serial (ttyAMA0 in the VM). Boot-log sink only:
 *                   PodroidQemu's monitorBootSerial coroutine connects here
 *                   for the lifetime of the VM, streaming kernel messages
 *                   and init-podroid boot stages into console.log + the
 *                   in-memory ring buffer used by detectBootStage().
 *
 *   terminal.sock — QEMU virtio-console (/dev/hvc0 in the VM). Primary
 *                   terminal I/O. getty runs on hvc0; the podroid-bridge
 *                   binary connects here for bidirectional shell I/O. Fully
 *                   independent of serial.sock, so no socket hand-off.
 *
 *   ctrl.sock     — QEMU virtio-console (/dev/hvc1 in the VM). Resize signal
 *                   channel only. Bridge writes "RESIZE rows cols\n" on
 *                   SIGWINCH (debounced by RESIZE_DEBOUNCE_MS); the resize
 *                   daemon in init-podroid stty's hvc0 to deliver SIGWINCH
 *                   to the foreground TUI inside the VM.
 */
package com.excp.podroid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.util.LogProxy
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("StaticFieldLeak") // ApplicationContext — lives as long as the process, no leak
@Singleton
class PodroidQemu @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> = _state.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _bootStage = MutableStateFlow("")

    private var _terminalSession: TerminalSession? = null

    val terminalSession: TerminalSession? get() = _terminalSession
    val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    @Volatile
    var process: Process? = null
        private set

    /** Unix socket paths exposed to TerminalViewModel for the bridge binary. */
    val serialSockPath: String get() = "${context.filesDir.absolutePath}/serial.sock"
    val terminalSockPath: String get() = "${context.filesDir.absolutePath}/terminal.sock"
    val ctrlSockPath: String get() = "${context.filesDir.absolutePath}/ctrl.sock"

    /** Boot-monitoring socket connection. Closed when the bridge takes over. */
    @Volatile
    private var bootSocket: LocalSocket? = null

    private val qmpSocketPath: String get() = "${context.filesDir.absolutePath}/qmp.sock"

    val qmpClient: QmpClient by lazy { QmpClient(qmpSocketPath) }

    private var ioScope: CoroutineScope? = null

    private val consoleBuilder = StringBuilder()
    private val maxConsoleSize = 64 * 1024

    /** Set once cleanup() has run for the current VM lifetime; reset by start(). */
    private val cleanedUp = AtomicBoolean(true)

    /**
     * Proxy TerminalSessionClient — delegates to whatever real client is set.
     * Lets us create the bridge session at boot-complete time (before the
     * terminal UI exists) and plug in the real ViewModel client later.
     */
    @Volatile
    var sessionClientDelegate: TerminalSessionClient? = null

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

    /**
     * Close the boot-monitoring serial socket. Called from cleanup() when the
     * VM stops; not used as a hand-off trigger any more (bridge has its own
     * socket).
     */
    fun releaseSerial() {
        val sock = bootSocket ?: return
        Log.d(TAG, "Closing boot monitor socket")
        bootSocket = null
        try {
            // Signal EOF to the monitor loop
            sock.shutdownInput()
            sock.shutdownOutput()
            sock.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing boot socket: ${e.message}")
        }
    }

    private fun autoStartBridge() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (_terminalSession != null) return@post
            if (_state.value !is VmState.Running && _state.value !is VmState.Starting) return@post

            // Bridge connects to terminal.sock (virtio-console, separate from serial).
            // No handoff with the boot monitor needed — they use different sockets.
            val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
            if (!bridgeExe.exists()) return@post

            val sess = TerminalSession(
                bridgeExe.absolutePath,
                context.filesDir.absolutePath,
                arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
                null,
                2000,
                proxySessionClient,
            )
            // Cell pixel dims default to 0 — TerminalView.updateSize() pushes real values once measured.
        sess.updateSize(80, 24, 0, 0)
            _terminalSession = sess
            Log.d(TAG, "Bridge auto-started on terminal.sock")
        }
    }

    fun createTerminalSession(client: TerminalSessionClient): TerminalSession {
        sessionClientDelegate = client

        // Session already auto-started during boot — just return it
        if (_terminalSession != null) {
            Log.d(TAG, "Returning pre-started terminal session")
            return _terminalSession!!
        }

        // Fallback: create session now
        val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
        if (!bridgeExe.exists()) {
            throw IllegalStateException("podroid-bridge not found at ${bridgeExe.absolutePath}")
        }

        val sess = TerminalSession(
            bridgeExe.absolutePath,
            context.filesDir.absolutePath,
            arrayOf(bridgeExe.absolutePath, terminalSockPath, ctrlSockPath),
            null,
            2000,
            proxySessionClient,
        )

        // Cell pixel dims default to 0 — TerminalView.updateSize() pushes real values once measured.
        sess.updateSize(80, 24, 0, 0)

        _terminalSession = sess
        Log.d(TAG, "Terminal session created in Qemu singleton")
        return sess
    }

    fun start() = start(emptyList())

    /** All snapshotted settings the engine needs for a single VM launch. */
    data class LaunchConfig(
        val ramMb: Int = 512,
        val cpus: Int = 1,
        val sshEnabled: Boolean = false,
        val androidIp: String = "unknown",
        val storageSizeGb: Int = 2,
        val storageAccessEnabled: Boolean = false,
        val qemuExtraArgs: String = "",
        val kernelExtraCmdline: String = "",
    )

    fun start(
        portForwards: List<PortForwardRule>,
        ramMb: Int = 512,
        cpus: Int = 1,
        sshEnabled: Boolean = false,
        androidIp: String = "unknown",
    ) = start(portForwards, LaunchConfig(ramMb = ramMb, cpus = cpus, sshEnabled = sshEnabled, androidIp = androidIp))

    fun start(portForwards: List<PortForwardRule>, config: LaunchConfig) {
        if (_state.value is VmState.Starting || _state.value is VmState.Running) {
            Log.w(TAG, "start() called while VM is ${_state.value}, ignoring")
            return
        }

        val qemuExe = qemuExecutable() ?: run {
            _state.value = VmState.Error("QEMU binary not found.")
            return
        }

        ensureStorageImage(config.storageSizeGb)

        cleanedUp.set(false)
        _state.value = VmState.Starting
        consoleBuilder.clear()
        _consoleText.value = ""
        _bootStage.value = "Starting QEMU..."

        // Clean up stale sockets from a previous run. qmp.sock must be
        // included — a leftover file from a crashed QEMU prevents the new
        // process from binding its QMP server socket.
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        File(qmpSocketPath).delete()

        try {
            val cmd = buildCommand(qemuExe, portForwards, config)
            Log.d(TAG, "Launching QEMU with ${cmd.size} args: $cmd")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(cmd).directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:${context.filesDir.absolutePath}"

            val proc = pb.start()
            process = proc
            _bootStage.value = "Booting kernel..."

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope

            // Drain QEMU's own stderr (not serial — just QEMU startup messages)
            scope.launch {
                try {
                    val buf = ByteArray(4096)
                    while (isActive) {
                        val n = proc.errorStream.read(buf)
                        if (n < 0) break
                        Log.d("PodroidVM-err", String(buf, 0, n).trimEnd().take(300))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stderr drain ended: ${e.message}")
                }
            }

            // Boot monitor — connects to serial.sock once QEMU creates it
            scope.launch { monitorBootSerial(proc) }

            val startMs = System.currentTimeMillis()
            var socketsReady = false
            while (System.currentTimeMillis() - startMs < SOCKET_READY_TIMEOUT_MS) {
                if (!proc.isAlive) {
                    val exitCode = proc.exitValue()
                    Log.e(TAG, "QEMU died during startup, exit code: $exitCode")
                    _state.value = VmState.Error("QEMU exited with code $exitCode")
                    cleanup()
                    return
                }
                if (File(serialSockPath).exists() && File(qmpSocketPath).exists()) {
                    Log.d(TAG, "QEMU sockets ready after ${System.currentTimeMillis() - startMs}ms")
                    socketsReady = true
                    break
                }
                Thread.sleep(200)
            }
            if (!socketsReady) {
                Log.e(TAG, "Socket timeout — QEMU sockets not ready after ${SOCKET_READY_TIMEOUT_MS}ms")
                proc.destroyForcibly()
                throw RuntimeException("QEMU failed to create sockets within ${SOCKET_READY_TIMEOUT_MS / 1000}s")
            }

            // State stays Starting — boot monitor will set Running when "Ready!" is detected
            scope.launch {
                delay(60_000)
                if (_state.value is VmState.Starting) {
                    Log.w(TAG, "Boot timeout fallback → forcing Running state")
                    _bootStage.value = "Ready"
                    _state.value = VmState.Running
                    autoStartBridge()
                }
            }

            // Block until QEMU exits (keeps the calling service coroutine alive)
            val exitCode = proc.waitFor()
            Log.d(TAG, "QEMU exited: $exitCode")
            cleanup()
            _state.value = if (exitCode == 0) VmState.Stopped
                else VmState.Error(formatExitError(exitCode, config.storageAccessEnabled))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            cleanup()
        }
    }

    /**
     * Connects to serial.sock and streams kernel + init-podroid output for the
     * lifetime of the VM. Writes the bytes to console.log and pushes the
     * latest tail into the consoleText flow used by the diagnostic exporter
     * and the boot-stage detector. Stops when:
     *   - releaseSerial() is called during cleanup() (VM stopping), or
     *   - QEMU itself exits, or
     *   - The coroutine is cancelled.
     *
     * Writes all output to console.log for the test-deploy.sh validator and
     * publishes boot stage updates to the UI.
     */
    private suspend fun monitorBootSerial(proc: Process) {
        val sockFile = File(serialSockPath)

        // Share the same socket-readiness deadline used by start() so a slow
        // QEMU startup doesn't get inconsistent grace periods.
        var waited = 0L
        while (waited < SOCKET_READY_TIMEOUT_MS && !sockFile.exists() && proc.isAlive) {
            delay(100)
            waited += 100
        }
        if (!sockFile.exists()) {
            Log.w(TAG, "serial.sock not found after ${waited}ms — boot detection disabled")
            return
        }

        val sock = LocalSocket()
        try {
            sock.connect(LocalSocketAddress(serialSockPath, LocalSocketAddress.Namespace.FILESYSTEM))
            bootSocket = sock
            Log.d(TAG, "Boot monitor connected to serial.sock")
        } catch (e: Exception) {
            Log.w(TAG, "Boot monitor could not connect to serial.sock: ${e.message}")
            sock.close()
            return
        }

        val logFile = File(context.filesDir, "console.log")
        logFile.delete()

        // Streaming UTF-8 decoder — kept across read() calls so multi-byte
        // sequences split between chunks decode correctly instead of
        // surfacing as U+FFFD replacement chars in the in-memory console
        // and breaking detectBootStage() matches.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val byteBuf = ByteBuffer.allocate(8192)
        val charBuf = CharBuffer.allocate(8192)

        try {
            FileOutputStream(logFile, false).use { logOut ->
                val readBuf = ByteArray(4096)
                val input = sock.inputStream
                while (true) {
                    val n = try {
                        input.read(readBuf)
                    } catch (_: Exception) {
                        break // socket closed by releaseSerial() or VM exit
                    }
                    if (n < 0) break

                    // Raw bytes go straight to disk for the diagnostic log.
                    logOut.write(readBuf, 0, n)
                    logOut.flush()

                    // Feed the decoder; carry leftover undecoded bytes via compact().
                    byteBuf.put(readBuf, 0, n)
                    byteBuf.flip()
                    decoder.decode(byteBuf, charBuf, false)
                    byteBuf.compact()
                    charBuf.flip()
                    if (charBuf.hasRemaining()) {
                        consoleBuilder.append(charBuf)
                        if (consoleBuilder.length > maxConsoleSize) {
                            consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                        }
                        _consoleText.value = consoleBuilder.toString()
                    }
                    charBuf.clear()

                    if (_bootStage.value != "Ready") {
                        detectBootStage()
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Boot monitor ended: ${e.message}")
        } finally {
            try { sock.close() } catch (_: Exception) {}
            bootSocket = null
            Log.d(TAG, "Boot monitor disconnected — bridge can now connect")
        }
    }

    private fun detectBootStage() {
        // Use the existing consoleBuilder as a stateful buffer.
        // We only scan the last 1024 characters for performance, which is
        // plenty for matching our short boot stage strings even if split.
        val bufferLen = consoleBuilder.length
        val scanLen = if (bufferLen > 1024) 1024 else bufferLen
        val text = consoleBuilder.substring(bufferLen - scanLen)

        when {
            text.contains("Ready!") -> {
                _bootStage.value = "Ready"
                _state.value = VmState.Running
                autoStartBridge()
            }
            text.contains("Almost ready") -> _bootStage.value = "Almost ready..."
            text.contains("Starting SSH") -> _bootStage.value = "Starting SSH..."
            text.contains("Configuring containers") -> _bootStage.value = "Configuring containers..."
            text.contains("Network found") -> _bootStage.value = "Network found"
            text.contains("Loading kernel modules") -> _bootStage.value = "Loading kernel modules..."
            text.contains("Mounting storage") -> _bootStage.value = "Mounting storage..."
            text.contains("Booting kernel") -> _bootStage.value = "Booting kernel..."
        }
    }

    /**
     * Signal the VM to stop. Destroys the QEMU process; the start() coroutine's
     * proc.waitFor() will fall through and run cleanup() + set the final state.
     * Avoids the historical race where stop() and the start() exit-path both
     * called cleanup() concurrently and fought over _state.value.
     */
    fun stop() {
        val proc = process ?: return
        proc.destroy()
        try {
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
            proc.destroyForcibly()
        }
    }

    @Synchronized
    private fun cleanup() {
        if (cleanedUp.getAndSet(true)) return
        releaseSerial()
        ioScope?.cancel()
        ioScope = null
        process = null
        _terminalSession?.finishIfRunning()
        _terminalSession = null
        sessionClientDelegate = null
        consoleBuilder.clear()
        _consoleText.value = ""
        File(serialSockPath).delete()
        File(terminalSockPath).delete()
        File(ctrlSockPath).delete()
        _bootStage.value = ""
    }

    private fun buildCommand(
        qemuExe: File,
        portForwards: List<PortForwardRule>,
        config: LaunchConfig,
    ): List<String> {
        val args = mutableListOf<String>()
        val userQemuExtras = config.qemuExtraArgs.trim()
        val userKernelExtras = config.kernelExtraCmdline.trim()

        args += "-M"; args += "virt,gic-version=3"
        args += "-smp"; args += "${config.cpus}"
        args += "-m";   args += "${config.ramMb}"

        val kernelPath = File(context.filesDir, "vmlinuz-virt")
        val initrdPath = File(context.filesDir, "initrd.img")

        if (kernelPath.exists()) {
            args += "-kernel"; args += kernelPath.absolutePath
            val cmdline = buildString {
                append("console=ttyAMA0")
                if (userKernelExtras.isNotEmpty()) append(" ").append(userKernelExtras)
                append(" androidip=").append(config.androidIp)
                if (config.sshEnabled) append(" ssh=1")
            }
            args += "-append"; args += cmdline
        } else {
            Log.w(TAG, "Kernel not found!")
        }

        if (initrdPath.exists()) {
            args += "-initrd"; args += initrdPath.absolutePath
        } else {
            Log.w(TAG, "Initrd not found!")
        }

        val storagePath = File(context.filesDir, "storage.img")
        if (storagePath.exists()) {
            args += "-object"; args += "iothread,id=iothread0"
            args += "-device"; args += "virtio-blk-pci,drive=drive1,num-queues=${config.cpus},iothread=iothread0"
            args += "-drive";  args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw,cache=writeback,aio=threads"
        }

        val rootfsImg = File(context.filesDir, "alpine-rootfs.squashfs")
        if (rootfsImg.exists()) {
            // Dedicated iothread for the read-only squashfs so its decompression
            // reads don't queue behind storage.img writes on iothread0.
            args += "-object"; args += "iothread,id=iothread1"
            args += "-device"; args += "virtio-blk-pci,drive=drive2,num-queues=${config.cpus},iothread=iothread1"
            args += "-drive";  args += "file=${rootfsImg.absolutePath},if=none,id=drive2,format=raw,readonly=on,cache=writeback,aio=threads"
        }

        // Downloads folder sharing via virtio-9p
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            config.storageAccessEnabled &&
            android.os.Environment.isExternalStorageManager() &&
            downloadsDir.exists()) {
            // security_model=mapped-xattr keeps QEMU's 9p worker out of the
            // chmod/chown syscall path that has triggered SIGILL on Tensor /
            // ARMv9.2 PAC devices (Pixel 10) — uid/gid/mode are stored as
            // xattrs on the host file instead of being applied directly.
            // Falls back gracefully on filesystems without xattr support.
            args += "-fsdev"
            args += "local,id=fsdev0,path=${downloadsDir.absolutePath},security_model=none"
            args += "-device"
            args += "virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads"
        }

        val netdevArg = buildString {
            append("user,id=net0,ipv6=off")
            for (rule in portForwards) {
                append(",hostfwd=${rule.protocol}::${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net-pci,netdev=net0,romfile="

        // ── Serial (ttyAMA0) → boot log sink only; kernel msgs + init boot stages ─
        args += "-serial"; args += "unix:$serialSockPath,server,nowait"

        // ── virtio-console bus ────────────────────────────────────────────────
        // hvc0 = primary terminal (getty runs here; bridge connects to terminal.sock)
        // hvc1 = control channel (init daemon reads RESIZE messages from ctrl.sock)
        args += "-device";  args += "virtio-serial-pci"
        args += "-chardev"; args += "socket,id=term0,path=$terminalSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=term0,name=org.podroid.term"
        args += "-chardev"; args += "socket,id=ctrl0,path=$ctrlSockPath,server=on,wait=off"
        args += "-device";  args += "virtconsole,chardev=ctrl0,name=org.podroid.ctrl"

        args += "-display"; args += "none"
        args += "-qmp";     args += "unix:$qmpSocketPath,server,nowait"

        // User extras appended last so later -cpu / -accel overrides earlier ones.
        if (userQemuExtras.isNotEmpty()) {
            args += userQemuExtras.split(Regex("\\s+"))
        }

        // Wrap QEMU in podroid-launcher when available — it sets PR_SET_PDEATHSIG
        // so QEMU dies with the app on uninstall/OOM/force-stop instead of leaking
        // as an orphan under PPID=1. If the launcher is missing (older deploys),
        // fall back to spawning QEMU directly.
        val launcher = File(context.applicationInfo.nativeLibraryDir, "libpodroid-launcher.so")
        return if (launcher.exists()) {
            listOf(launcher.absolutePath, qemuExe.absolutePath) + args
        } else {
            listOf(qemuExe.absolutePath) + args
        }
    }

    private fun ensureStorageImage(storageSizeGb: Int) {
        val storageFile = File(context.filesDir, "storage.img")
        val desiredBytes = storageSizeGb.toLong() * 1024L * 1024L * 1024L

        if (storageFile.exists()) {
            if (storageFile.length() == desiredBytes) return
            Log.d(TAG, "storage.img size mismatch — recreating")
            storageFile.delete()
        }

        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${storageSizeGb}GB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create storage.img", e)
        }
    }

    private fun qemuExecutable(): File? {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        return if (exe.exists()) exe else null
    }

    /**
     * Decode a QEMU process exit code into a user-facing error string. Process
     * exit codes ≥128 are POSIX-encoded signals (128 + signum). On some devices
     * (notably Tensor / ARMv9.2 PAC) virtio-9p crashes the QEMU worker with
     * SIGILL — surface that as a Downloads-sharing hint rather than "Exit 132".
     */
    private fun formatExitError(exitCode: Int, storageSharingEnabled: Boolean): String {
        if (exitCode < 128) return "QEMU exited with code $exitCode"
        val sig = exitCode - 128
        val name = when (sig) {
            4  -> "SIGILL"
            6  -> "SIGABRT"
            7  -> "SIGBUS"
            8  -> "SIGFPE"
            9  -> "SIGKILL"
            11 -> "SIGSEGV"
            13 -> "SIGPIPE"
            15 -> "SIGTERM"
            31 -> "SIGSYS"
            else -> "signal $sig"
        }
        // SIGILL/SIGBUS/SIGSEGV with Downloads sharing on points at virtio-9p
        // on PAC-enforcing kernels — by far the most common crash path here.
        val crashSignals = setOf(4, 7, 11)
        return if (storageSharingEnabled && sig in crashSignals) {
            "QEMU crashed ($name). Downloads sharing is unstable on this device — disable it in Settings and try again."
        } else {
            "QEMU crashed ($name)"
        }
    }

    companion object {
        private const val TAG = "PodroidQemu"

        /** Shared deadline for both start()'s socket-readiness loop and monitorBootSerial's wait. */
        private const val SOCKET_READY_TIMEOUT_MS = 10_000L
    }
}
