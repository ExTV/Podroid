/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Settings ViewModel for Podroid.
 */
package com.excp.podroid.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val podroidQemu: PodroidQemu,
) : ViewModel() {

    val darkTheme: Flow<Boolean> = settingsRepository.darkTheme

    val vmRamMb: StateFlow<Int> = settingsRepository.vmRamMb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 512)

    val vmCpus: StateFlow<Int> = settingsRepository.vmCpus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1)

    val terminalFontSize: StateFlow<Int> = settingsRepository.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    val terminalColorTheme: StateFlow<String> = settingsRepository.terminalColorTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    val terminalFont: StateFlow<String> = settingsRepository.terminalFont
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    val storageSizeGb: StateFlow<Int> = settingsRepository.storageSizeGb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 2)

    val sshEnabled: Flow<Boolean> = settingsRepository.sshEnabled

    fun setSshEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setSshEnabled(value) }
    }

    val portForwardRules: StateFlow<List<PortForwardRule>> = portForwardRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vmState: StateFlow<VmState> = podroidQemu.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VmState.Idle)

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(value) }
    }

    fun setVmRamMb(value: Int) {
        viewModelScope.launch { settingsRepository.setVmRamMb(value) }
    }

    fun setVmCpus(value: Int) {
        viewModelScope.launch { settingsRepository.setVmCpus(value) }
    }

    fun setTerminalFontSize(value: Int) {
        viewModelScope.launch { settingsRepository.setTerminalFontSize(value) }
    }

    fun setTerminalColorTheme(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalColorTheme(value) }
    }

    fun setTerminalFont(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalFont(value) }
    }

    // "both" expands into separate TCP + UDP rules
    fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp") {
        val protos = if (protocol == "both") listOf("tcp", "udp") else listOf(protocol)
        viewModelScope.launch {
            protos.forEach { proto ->
                val rule = PortForwardRule(hostPort, guestPort, proto)
                portForwardRepository.addRule(rule)
                if (podroidQemu.state.value is VmState.Running) {
                    podroidQemu.qmpClient.addPortForward(hostPort, guestPort, proto)
                }
            }
        }
    }

    /** Current LAN IP of the Android device — shown next to port forward rules. */
    val phoneIp: String
        get() = try {
            java.net.NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "unknown"
        } catch (_: Exception) { "unknown" }

    fun removePortForward(rule: PortForwardRule) {
        viewModelScope.launch {
            portForwardRepository.removeRule(rule)
            if (podroidQemu.state.value is VmState.Running) {
                podroidQemu.qmpClient.removePortForward(rule.hostPort, rule.protocol)
            }
        }
    }

    fun resetVm() {
        val storageFile = File(context.filesDir, "storage.img")
        if (storageFile.exists()) {
            storageFile.delete()
        }
    }

    /**
     * Build a single log.txt containing everything a maintainer would need
     * to triage a user bug report:
     *   - App + device info
     *   - Current settings snapshot
     *   - VM state + boot stage
     *   - Port forward rules
     *   - App logcat (filtered to this process — only Podroid tags)
     *   - QEMU console.log (serial output from the VM, if the VM has run)
     *
     * Written to filesDir/log.txt and shared via the system share sheet.
     */
    fun exportConsoleLogs() {
        viewModelScope.launch {
            val logFile = withContext(Dispatchers.IO) {
                val file = File(context.filesDir, "log.txt")
                file.writeText(buildDiagnosticLog())
                file
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Podroid Diagnostic Log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share diagnostic log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private suspend fun buildDiagnosticLog(): String = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val ram = runCatching { settingsRepository.getVmRamMbSnapshot() }.getOrDefault(-1)
        val cpus = runCatching { settingsRepository.getVmCpusSnapshot() }.getOrDefault(-1)
        val storage = runCatching { settingsRepository.getStorageSizeGbSnapshot() }.getOrDefault(-1)
        val ssh = runCatching { settingsRepository.getSshEnabledSnapshot() }.getOrDefault(false)
        val storageAccess = runCatching { settingsRepository.getStorageAccessEnabledSnapshot() }.getOrDefault(false)
        val theme = runCatching { settingsRepository.getTerminalColorThemeSnapshot() }.getOrDefault("default")
        val font = runCatching { settingsRepository.getTerminalFontSnapshot() }.getOrDefault("default")
        val rules = runCatching { portForwardRepository.getRulesSnapshot() }.getOrDefault(emptyList())

        buildString {
            appendLine("=== Podroid Diagnostic Log ===")
            appendLine("Generated: $timestamp")
            appendLine()

            appendLine("=== App ===")
            appendLine("Version:      ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build type:   ${BuildConfig.BUILD_TYPE}")
            appendLine("App ID:       ${BuildConfig.APPLICATION_ID}")
            appendLine("QEMU version: ${BuildConfig.QEMU_VERSION}")
            appendLine()

            appendLine("=== Device ===")
            appendLine("Manufacturer: ${Build.MANUFACTURER}")
            appendLine("Model:        ${Build.MODEL}")
            appendLine("Device:       ${Build.DEVICE}")
            appendLine("Product:      ${Build.PRODUCT}")
            appendLine("Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs:         ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("Fingerprint:  ${Build.FINGERPRINT}")
            appendLine()

            appendLine("=== Settings ===")
            appendLine("VM RAM:             $ram MB")
            appendLine("VM CPUs:            $cpus")
            appendLine("Storage size:       $storage GB")
            appendLine("SSH enabled:        $ssh")
            appendLine("Downloads sharing:  $storageAccess")
            appendLine("Terminal theme:     $theme")
            appendLine("Terminal font:      $font")
            appendLine()

            appendLine("=== VM State ===")
            appendLine("State:       ${podroidQemu.state.value}")
            appendLine("Boot stage:  ${podroidQemu.bootStage.value.ifEmpty { "(none)" }}")
            val storageFile = File(context.filesDir, "storage.img")
            appendLine(
                "Storage img: " + if (storageFile.exists())
                    "${storageFile.absolutePath} (${storageFile.length() / (1024 * 1024)} MB)"
                else "(not created)"
            )
            appendLine()

            appendLine("=== Port Forward Rules (${rules.size}) ===")
            if (rules.isEmpty()) {
                appendLine("(none)")
            } else {
                rules.forEach { rule ->
                    appendLine("${rule.protocol.uppercase()}  localhost:${rule.hostPort} -> VM:${rule.guestPort}")
                }
            }
            appendLine()

            appendLine("=== App Logcat (this process) ===")
            append(captureAppLogcat())
            appendLine()

            appendLine("=== QEMU Console Log ===")
            val consoleFile = File(context.filesDir, "console.log")
            if (consoleFile.exists() && consoleFile.length() > 0) {
                append(consoleFile.readText())
                if (!consoleFile.readText().endsWith("\n")) appendLine()
            } else {
                appendLine("(no console.log — VM has not been started this session)")
            }
            appendLine()

            appendLine("=== End of Log ===")
        }
    }

    /**
     * Dump the current process's logcat buffer. Apps on Android 4.1+ can only
     * read their own logs, so --pid filtering is redundant for security but
     * keeps the output focused on Podroid tags.
     */
    private fun captureAppLogcat(): String {
        return try {
            val pid = android.os.Process.myPid().toString()
            val proc = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "time", "-t", "2000", "--pid=$pid")
            )
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            if (output.isBlank()) "(logcat returned no lines)\n" else output
        } catch (e: Exception) {
            "(failed to capture logcat: ${e.message})\n"
        }
    }
}
