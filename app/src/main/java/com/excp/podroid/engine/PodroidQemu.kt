/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * QEMU engine for Podroid. Manages the VM lifecycle, serial console I/O,
 * and a persistent terminal emulator that handles terminal queries (DA, DSR,
 * window size) even when the UI is detached.
 */
package com.excp.podroid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("StaticFieldLeak") // ApplicationContext — lives as long as the process, no leak
@Singleton
class PodroidQemu @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: com.excp.podroid.data.repository.SettingsRepository,
) {
    private val _state = MutableStateFlow<VmState>(VmState.Idle)
    val state: StateFlow<VmState> = _state.asStateFlow()

    private val _consoleText = MutableStateFlow("")
    val consoleText: StateFlow<String> = _consoleText.asStateFlow()

    private val _bootStage = MutableStateFlow("")
    val bootStage: StateFlow<String> = _bootStage.asStateFlow()

    /** Persistent terminal emulator that outlives the UI. */
    var terminalEmulator: TerminalEmulator? = null
        private set

    @Volatile
    var process: Process? = null
        private set

    @Volatile
    private var consoleOutputStream: OutputStream? = null

    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    /** Current terminal dimensions for responding to size queries. */
    @Volatile var terminalRows = 24
    @Volatile var terminalCols = 80

    /** Coroutine scope for I/O threads — cancelled on stop(). */
    private var ioScope: CoroutineScope? = null

    /** Console text buffer — capped to prevent unbounded memory growth. */
    private val consoleBuilder = StringBuilder()
    private val maxConsoleSize = 64 * 1024 // Keep last 64KB

    fun writeToConsole(data: ByteArray) {
        writeQueue.offer(data)
    }

    fun updateTerminalSize(cols: Int, rows: Int) {
        terminalCols = cols
        terminalRows = rows
        terminalEmulator?.resize(cols, rows)
    }

    /** Callback for raw console output. Used by TerminalViewModel for UI refresh. */
    @Volatile
    var onConsoleOutput: ((ByteArray, Int) -> Unit)? = null

    val qmpClient: QmpClient by lazy {
        QmpClient("${context.filesDir.absolutePath}/qmp.sock")
    }

    fun start() = start(emptyList())

    fun start(
        portForwards: List<PortForwardRule>,
        ramMb: Int = 512,
        cpus: Int = 1,
        sshEnabled: Boolean = false,
        androidIp: String = "unknown",
    ) {
        if (_state.value is VmState.Starting || _state.value is VmState.Running) {
            Log.w(TAG, "start() called while VM is ${_state.value}, ignoring")
            return
        }

        val qemuExe = qemuExecutable() ?: run {
            _state.value = VmState.Error("QEMU binary not found.")
            return
        }

        ensureStorageImage()

        _state.value = VmState.Starting
        consoleBuilder.clear()
        _consoleText.value = ""
        _bootStage.value = "Starting QEMU..."

        try {
            val cmd = buildCommand(qemuExe, portForwards, ramMb, cpus, sshEnabled, androidIp)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val filesDir = context.filesDir.absolutePath

            val pb = ProcessBuilder(cmd).directory(context.filesDir)
            pb.environment()["LD_LIBRARY_PATH"] = "$nativeDir:$filesDir"

            val proc = pb.start()
            process = proc
            consoleOutputStream = proc.outputStream
            _bootStage.value = "Booting kernel..."

            // Create coroutine scope for I/O
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            ioScope = scope

            // Writer coroutine — drains writeQueue to QEMU stdin
            scope.launch {
                try {
                    while (isActive && process?.isAlive == true) {
                        val data = writeQueue.poll(500, TimeUnit.MILLISECONDS)
                        if (data != null) {
                            consoleOutputStream?.write(data)
                            consoleOutputStream?.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Console writer ended: ${e.message}")
                }
            }

            // Initialize persistent terminal emulator.
            // write() routes emulator responses (DA, DSR, cursor position) back
            // to the VM so TUI apps like nvim receive the answers they need.
            val qemuOutput = object : TerminalOutput() {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    writeToConsole(data.copyOfRange(offset, offset + count))
                }
                override fun titleChanged(oldTitle: String?, newTitle: String?) {}
                override fun onCopyTextToClipboard(text: String?) {}
                override fun onPasteTextFromClipboard() {}
                override fun onBell() {}
                override fun onColorsChanged() {}
            }
            terminalEmulator = TerminalEmulator(
                qemuOutput, 80, 24, 2000, NoOpSessionClient
            )

            // Console reader — reads stdout, feeds emulator, detects boot stages
            val logFile = File(context.filesDir, "console.log")
            logFile.delete()
            scope.launch {
                FileOutputStream(logFile, true).use { logOut ->
                    val buf = ByteArray(4096)
                    val input = proc.inputStream
                    try {
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val chunk = buf.copyOf(n)
                            val text = String(chunk, Charsets.UTF_8)

                            // Cap console buffer to prevent unbounded growth
                            consoleBuilder.append(text)
                            if (consoleBuilder.length > maxConsoleSize) {
                                consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                            }
                            _consoleText.value = consoleBuilder.toString()

                            logOut.write(chunk, 0, n)
                            logOut.flush()

                            // Feed persistent emulator (renders screen buffer)
                            terminalEmulator?.append(chunk, n)

                            // Detect boot stages until "Ready" is reached
                            if (_bootStage.value != "Ready") {
                                detectBootStage(text)
                            }
                            onConsoleOutput?.invoke(chunk, n)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Console reader ended: ${e.message}")
                    }
                }
            }

            // Stderr drain
            scope.launch {
                try {
                    val buf = ByteArray(4096)
                    val err = proc.errorStream
                    while (isActive) {
                        val n = err.read(buf)
                        if (n < 0) break
                        Log.d("PodroidVM-err", String(buf, 0, n).trimEnd().take(300))
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Stderr drain ended: ${e.message}")
                }
            }

            // Wait briefly, then mark as running if alive
            Thread.sleep(2000)
            if (proc.isAlive) {
                Log.d(TAG, "QEMU is running")
                _state.value = VmState.Running
            } else {
                val exitCode = proc.waitFor()
                Log.e(TAG, "QEMU died immediately, exit code: $exitCode")
                _state.value = VmState.Error("QEMU exited with code $exitCode")
                cleanup()
                return
            }

            // Block until QEMU exits (keeps the calling service alive)
            val exitCode = proc.waitFor()
            Log.d(TAG, "QEMU exited with code: $exitCode")
            cleanup()
            _state.value = if (exitCode == 0) VmState.Stopped else VmState.Error("Exit code $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            cleanup()
        }
    }

    private fun detectBootStage(text: String) {
        when {
            // Check terminal conditions first — they may arrive in the same chunk as earlier stages
            "podman run" in text || "Ready!" in text ->
                _bootStage.value = "Ready"
            "internet " in text || "Internet:" in text ->
                _bootStage.value = "Almost ready..."
            "Starting SSH" in text ->
                _bootStage.value = "Starting SSH..."
            "Found" in text && "eth" in text ->
                _bootStage.value = "Network found"
            "Waiting for network" in text ->
                _bootStage.value = "Waiting for network..."
            "Configuring containers" in text ->
                _bootStage.value = "Configuring containers..."
            "Loading kernel modules" in text ->
                _bootStage.value = "Loading kernel modules..."
            "overlay" in text ->
                _bootStage.value = "Setting up overlay..."
            "Mounting persistent" in text || "Formatting" in text ->
                _bootStage.value = "Mounting storage..."
        }
    }

    fun stop() {
        try {
            writeToConsole("sync\n".toByteArray())
        } catch (e: Exception) {
            Log.d(TAG, "Failed to send sync: ${e.message}")
        }

        val proc = process
        if (proc != null) {
            proc.destroy()
            try {
                // Wait up to 3 seconds for graceful exit
                val exited = proc.waitFor(3, TimeUnit.SECONDS)
                if (!exited) {
                    proc.destroyForcibly()
                    proc.waitFor(2, TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
                proc.destroyForcibly()
            }
        }
        cleanup()
        _state.value = VmState.Stopped
    }

    private fun cleanup() {
        ioScope?.cancel()
        ioScope = null
        process = null
        consoleOutputStream = null
        writeQueue.clear()
        terminalEmulator = null
        onConsoleOutput = null
        _bootStage.value = ""
    }

    private fun buildCommand(
        qemuExe: File,
        portForwards: List<PortForwardRule>,
        ramMb: Int,
        cpus: Int,
        sshEnabled: Boolean = false,
        androidIp: String = "unknown",
    ): List<String> {
        val args = mutableListOf<String>()

        // GICv3 reduces interrupt-related vCPU exits vs the default GICv2
        args += "-M"; args += "virt,gic-version=3"
        args += "-cpu"; args += "max"
        args += "-smp"; args += "$cpus"
        args += "-m"; args += "$ramMb"
        // thread=multi (MTTCG): each vCPU runs in its own host thread
        // tb-size=256: cap JIT translation cache at 256MB — default 1024MB thrashes mobile RAM
        args += "-accel"; args += "tcg,thread=multi,tb-size=256"

        val kernelPath = File(context.filesDir, "vmlinuz-virt")
        val initrdPath = File(context.filesDir, "initrd.img")

        if (kernelPath.exists()) {
            args += "-kernel"; args += kernelPath.absolutePath
            val cmdline = buildString {
                append("console=ttyAMA0 loglevel=1 quiet")
                append(" androidip=$androidIp")
                if (sshEnabled) append(" ssh=1")
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
            // num-queues=cpus: with MTTCG each vCPU can drive its own I/O queue in parallel
            args += "-device"; args += "virtio-blk-pci,drive=drive1,num-queues=$cpus"
            // cache=writeback: use host OS page cache instead of direct I/O — major speedup
            // for container workloads (image pulls, package installs, overlay writes)
            // aio=threads: thread-based AIO, safe in Android app sandbox (no io_uring/libaio needed)
            args += "-drive"; args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw,cache=writeback,aio=threads"
        }

        // virtio-rng: feed host entropy to the guest so /dev/random never blocks.
        // Without this, container crypto ops (SSH keygen, TLS, package signing) stall under TCG.
        args += "-object"; args += "rng-random,id=rng0,filename=/dev/urandom"
        args += "-device"; args += "virtio-rng-pci,rng=rng0"

        // Downloads sharing via virtio-9p — tag must match init-podroid mount call
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val storageAccessEnabled = kotlinx.coroutines.runBlocking {
            settingsRepository.getStorageAccessEnabledSnapshot()
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            storageAccessEnabled &&
            android.os.Environment.isExternalStorageManager() &&
            downloadsDir.exists()) {
            args += "-fsdev"
            args += "local,id=fsdev0,path=${downloadsDir.absolutePath},security_model=none"
            args += "-device"
            args += "virtio-9p-pci,fsdev=fsdev0,mount_tag=downloads"
        }

        val netdevArg = buildString {
            // ipv6=off: removes unused IPv6 processing overhead from SLIRP
            append("user,id=net0,ipv6=off")
            for (rule in portForwards) {
                append(",hostfwd=${rule.protocol}::${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net,netdev=net0"

        args += "-serial"; args += "stdio"
        args += "-display"; args += "none"
        args += "-qmp"
        args += "unix:${context.filesDir.absolutePath}/qmp.sock,server,nowait"
        args += "-overcommit"; args += "mem-lock=off"

        return listOf(qemuExe.absolutePath) + args
    }

    private fun ensureStorageImage() {
        val storageFile = File(context.filesDir, "storage.img")
        val gb = kotlinx.coroutines.runBlocking {
            settingsRepository.getStorageSizeGbSnapshot().toLong()
        }
        val desiredBytes = gb * 1024L * 1024L * 1024L

        if (storageFile.exists()) {
            if (storageFile.length() == desiredBytes) {
                Log.d(TAG, "storage.img already correct size (${gb}GB)")
                return
            }
            Log.d(TAG, "storage.img size mismatch (is ${storageFile.length() / (1024*1024*1024)}GB, want ${gb}GB) — recreating")
            storageFile.delete()
        }

        try {
            java.io.RandomAccessFile(storageFile, "rw").use { raf ->
                raf.setLength(desiredBytes)
            }
            Log.d(TAG, "Created storage.img (${gb}GB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create storage.img", e)
        }
    }

    private fun qemuExecutable(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val exe = File(nativeDir, "libqemu-system-aarch64.so")
        return if (exe.exists()) exe else null
    }

    companion object {
        private const val TAG = "PodroidQemu"
    }
}

/** No-op TerminalSessionClient for the persistent emulator (no UI attached). */
private object NoOpSessionClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
