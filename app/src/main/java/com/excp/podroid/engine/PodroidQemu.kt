/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * QEMU engine for Podroid. Manages the VM lifecycle and exposes two Unix
 * sockets for the terminal layer:
 *
 *   serial.sock — QEMU serial chardev (ttyAMA0 in the VM). The podroid-bridge
 *                 binary connects here for bidirectional terminal I/O.
 *
 *   ctrl.sock   — QEMU virtio-console chardev (hvc0 in the VM). The bridge
 *                 writes "RESIZE rows cols\n" here on SIGWINCH; the VM daemon
 *                 calls stty on ttyAMA0 which triggers SIGWINCH in the VM.
 *
 * Boot monitoring: PodroidQemu connects to serial.sock and reads until the VM
 * reaches the "Ready" stage (or until releaseSerial() is called). The bridge
 * then takes over the connection for interactive terminal use.
 */
package com.excp.podroid.engine

import android.annotation.SuppressLint
import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import com.excp.podroid.data.repository.PortForwardRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
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

    @Volatile
    var process: Process? = null
        private set

    /** Unix socket paths exposed to TerminalViewModel for the bridge binary. */
    val serialSockPath: String get() = "${context.filesDir.absolutePath}/serial.sock"
    val ctrlSockPath: String get() = "${context.filesDir.absolutePath}/ctrl.sock"

    /** Boot-monitoring socket connection. Closed when the bridge takes over. */
    @Volatile
    private var bootSocket: LocalSocket? = null

    private val qmpSocketPath: String get() = "${context.filesDir.absolutePath}/qmp.sock"

    val qmpClient: QmpClient by lazy { QmpClient(qmpSocketPath) }

    private var ioScope: CoroutineScope? = null

    private val consoleBuilder = StringBuilder()
    private val maxConsoleSize = 64 * 1024

    /**
     * Release the boot-monitoring serial connection so podroid-bridge can connect.
     * Called by TerminalViewModel right before starting the bridge TerminalSession.
     */
    fun releaseSerial() {
        _bootStage.value = "Ready"
        val sock = bootSocket
        bootSocket = null
        if (sock != null) {
            try { sock.shutdownInput() } catch (_: Exception) {}
            try { sock.shutdownOutput() } catch (_: Exception) {}
            try { sock.close() } catch (_: Exception) {}
        }
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

        // Clean up stale sockets from a previous run
        File(serialSockPath).delete()
        File(ctrlSockPath).delete()

        try {
            val cmd = buildCommand(qemuExe, portForwards, ramMb, cpus, sshEnabled, androidIp)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")

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

            // Wait briefly, then confirm QEMU is alive
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

            // Block until QEMU exits (keeps the calling service coroutine alive)
            val exitCode = proc.waitFor()
            Log.d(TAG, "QEMU exited: $exitCode")
            cleanup()
            _state.value = if (exitCode == 0) VmState.Stopped else VmState.Error("Exit code $exitCode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start QEMU", e)
            _state.value = VmState.Error(e.message ?: "Unknown error")
            cleanup()
        }
    }

    /**
     * Connects to serial.sock and reads boot output until:
     *   - The "Ready" stage is detected, or
     *   - releaseSerial() is called (bootSocket closed externally), or
     *   - The coroutine is cancelled.
     *
     * Writes all output to console.log for the test-deploy.sh validator and
     * publishes boot stage updates to the UI.
     */
    private suspend fun monitorBootSerial(proc: Process) {
        val sockFile = File(serialSockPath)

        // Wait up to 8s for QEMU to create the socket
        var waited = 0
        while (waited < 8000 && !sockFile.exists() && proc.isAlive) {
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

        try {
            FileOutputStream(logFile, false).use { logOut ->
                val buf = ByteArray(4096)
                val input = sock.inputStream
                while (true) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        break // socket closed by releaseSerial() or VM exit
                    }
                    if (n < 0) break

                    val chunk = buf.copyOf(n)
                    val text = String(chunk, Charsets.UTF_8)

                    consoleBuilder.append(text)
                    if (consoleBuilder.length > maxConsoleSize) {
                        consoleBuilder.delete(0, consoleBuilder.length - maxConsoleSize)
                    }
                    _consoleText.value = consoleBuilder.toString()

                    logOut.write(chunk, 0, n)
                    logOut.flush()

                    if (_bootStage.value != "Ready") {
                        detectBootStage(text)
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

    private fun detectBootStage(text: String) {
        when {
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
        val proc = process
        if (proc != null) {
            proc.destroy()
            try {
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
        releaseSerial()
        ioScope?.cancel()
        ioScope = null
        process = null
        _bootStage.value = ""
    }

    private fun buildCommand(
        qemuExe: File,
        portForwards: List<PortForwardRule>,
        ramMb: Int,
        cpus: Int,
        sshEnabled: Boolean,
        androidIp: String,
    ): List<String> {
        val args = mutableListOf<String>()

        args += "-M"; args += "virt,gic-version=3"
        args += "-cpu"; args += "max"
        args += "-smp"; args += "$cpus"
        args += "-m";   args += "$ramMb"
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
            args += "-device"; args += "virtio-blk-pci,drive=drive1,num-queues=$cpus"
            args += "-drive";  args += "file=${storagePath.absolutePath},if=none,id=drive1,format=raw,cache=writeback,aio=threads"
        }

        args += "-object"; args += "rng-random,id=rng0,filename=/dev/urandom"
        args += "-device"; args += "virtio-rng-pci,rng=rng0"

        // Downloads folder sharing via virtio-9p
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
            append("user,id=net0,ipv6=off")
            for (rule in portForwards) {
                append(",hostfwd=${rule.protocol}::${rule.hostPort}-:${rule.guestPort}")
            }
        }
        args += "-netdev"; args += netdevArg
        args += "-device"; args += "virtio-net,netdev=net0"

        // ── Serial console (ttyAMA0) → unix socket for podroid-bridge ─────────
        args += "-serial"; args += "unix:$serialSockPath,server,nowait"

        // ── Control channel (hvc0) → unix socket for resize signalling ────────
        // VM daemon reads "RESIZE rows cols\n" from /dev/hvc0 and calls stty
        // on ttyAMA0 — Linux kernel automatically sends SIGWINCH to the foreground
        // process group.
        args += "-chardev"; args += "socket,id=ctrl0,path=$ctrlSockPath,server=on,wait=off"
        args += "-device";  args += "virtio-serial-pci"
        args += "-device";  args += "virtconsole,chardev=ctrl0,name=org.podroid.ctrl"

        args += "-display"; args += "none"
        args += "-qmp";     args += "unix:$qmpSocketPath,server,nowait"
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
            if (storageFile.length() == desiredBytes) return
            Log.d(TAG, "storage.img size mismatch — recreating")
            storageFile.delete()
        }

        try {
            java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
            Log.d(TAG, "Created storage.img (${gb}GB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create storage.img", e)
        }
    }

    private fun qemuExecutable(): File? {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        return if (exe.exists()) exe else null
    }

    companion object {
        private const val TAG = "PodroidQemu"
    }
}
