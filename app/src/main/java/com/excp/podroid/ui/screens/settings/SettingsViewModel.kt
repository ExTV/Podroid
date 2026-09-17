/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Settings ViewModel for Podroid.
 */
package com.excp.podroid.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.BuildConfig
import com.excp.podroid.R
import com.excp.podroid.data.repository.AddRuleResult
import com.excp.podroid.data.repository.LanguageManager
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.di.ApplicationScope
import com.excp.podroid.engine.EngineHolder
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.DeviceResourcePolicy
import com.excp.podroid.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Snapshot of the form-style settings rows that SettingsScreen reads together.
 * Combined into one StateFlow so an emit on any one source produces a single
 * recomposition instead of one per source. Does *not* include flows with
 * different update cadences or consumers (vmState, portForwardRules, updateInfo,
 * terminal-only theme/font/size) — those stay separate.
 */
data class SettingsUiState(
    val vmRamMb: Int = 512,
    val vmCpus: Int = 1,
    val storageSizeGb: Int = 2,
    val sshEnabled: Boolean = false,
    val storageAccessEnabled: Boolean = false,
    val qemuExtraArgs: String = SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS,
    val kernelExtraCmdline: String = SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE,
    val darkTheme: Boolean = false,
    val dynamicColorEnabled: Boolean = false,
    val engineSelection: EngineSelection = EngineSelection.AUTO,
    val language: String = "auto",
    val systemDefaultLanguage: String = "auto",
    val loadBalanceEnabled: Boolean = false,
    val bandwidthMbps: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val engine: EngineHolder,
    private val languageManager: LanguageManager,
    @ApplicationScope private val externalScope: CoroutineScope,
) : ViewModel() {

    /**
     * Single combined stream of the 8 form-style rows. SettingsScreen can collect
     * this once with collectAsStateWithLifecycle instead of subscribing 8 times.
     */
    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settingsRepository.vmRamMb,
            settingsRepository.vmCpus,
            settingsRepository.storageSizeGb,
            settingsRepository.sshEnabled,
            settingsRepository.loadBalanceEnabled,
        ) { ram, cpus, storage, ssh, loadBal ->
            arrayOf(ram, cpus, storage, ssh, loadBal)
        },
        combine(
            settingsRepository.storageAccessEnabled,
            settingsRepository.qemuExtraArgs,
            settingsRepository.kernelExtraCmdline,
            settingsRepository.darkTheme,
            settingsRepository.dynamicColorEnabled,
        ) { storageAccess, qemu, kernel, dark, dyn ->
            arrayOf(storageAccess, qemu, kernel, dark, dyn)
        },
        combine(
            settingsRepository.bandwidthMbps,
            settingsRepository.engineSelection,
            settingsRepository.language,
            languageManager.language,
        ) { bandwidth, engineSel, lang, sysLang ->
            arrayOf(bandwidth, engineSel, lang, sysLang)
        },
    ) { a, b, c ->
        SettingsUiState(
            vmRamMb = a[0] as Int,
            vmCpus = a[1] as Int,
            storageSizeGb = a[2] as Int,
            sshEnabled = a[3] as Boolean,
            storageAccessEnabled = b[0] as Boolean,
            qemuExtraArgs = b[1] as String,
            kernelExtraCmdline = b[2] as String,
            darkTheme = b[3] as Boolean,
            dynamicColorEnabled = b[4] as Boolean,
            engineSelection = c[1] as EngineSelection,
            language = c[2] as String,
            systemDefaultLanguage = c[3] as String,
            loadBalanceEnabled = a[4] as Boolean,
            bandwidthMbps = c[0] as Int,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    // The Advanced VM-args fields (AdvancedTextSetting) buffer edits locally and
    // commit them on focus loss or composable disposal. Disposal coincides with
    // this ViewModel being cleared on back-navigation, which cancels viewModelScope
    // before a viewModelScope.launch could finish the DataStore write — silently
    // dropping the edit (issue #46). Persist on the application-lifetime scope so
    // the write outlives the screen teardown.
    fun setQemuExtraArgs(value: String) {
        externalScope.launch { settingsRepository.setQemuExtraArgs(value) }
    }

    fun setKernelExtraCmdline(value: String) {
        externalScope.launch { settingsRepository.setKernelExtraCmdline(value) }
    }

    fun resetQemuExtraArgs() {
        externalScope.launch { settingsRepository.setQemuExtraArgs(SettingsRepository.DEFAULT_QEMU_EXTRA_ARGS) }
    }

    fun resetKernelExtraCmdline() {
        externalScope.launch { settingsRepository.setKernelExtraCmdline(SettingsRepository.DEFAULT_KERNEL_EXTRA_CMDLINE) }
    }

    fun setSshEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setSshEnabled(value) }
    }

    fun setStorageAccessEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setStorageAccessEnabled(value) }
    }

    val portForwardRules: StateFlow<List<PortForwardRule>> = portForwardRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Eagerly (like HomeViewModel): WhileSubscribed replays the initial Idle on
    // re-subscription, flashing a "stopped" indicator for a frame when the screen
    // is reopened within the stop-timeout window.
    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(value) }
    }

    fun setDynamicColorEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(value) }
    }

    fun setEngineSelection(value: EngineSelection) {
        viewModelScope.launch { settingsRepository.setEngineSelection(value) }
    }

    val avfVerboseLogging: StateFlow<Boolean> = settingsRepository.avfVerboseLogging
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAvfVerboseLogging(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAvfVerboseLogging(value) }
    }

    val usbPassthroughEnabled: StateFlow<Boolean> = settingsRepository.usbPassthroughEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setUsbPassthroughEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setUsbPassthroughEnabled(value) }
    }

    val autostartOnBoot: StateFlow<Boolean> = settingsRepository.autostartOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setAutostartOnBoot(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutostartOnBoot(value) }
    }

    suspend fun setLanguage(value: String) {
        Log.d(TAG, "setLanguage: $value")
        languageManager.setLanguage(value)
        Log.d(TAG, "setLanguage done")
    }

    fun setVmRamMb(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setVmRamMb(value)
        }
    }

    fun setStorageSizeGb(value: Int) {
        viewModelScope.launch {
            // storage.img is never shrunk; the disabled chips already stop this in
            // the UI, this is defence in depth against a stale/racing value.
            val current = settingsRepository.getStorageSizeGbSnapshot()
            if (value < current) return@launch
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setStorageSizeGb(value)
        }
    }

    fun setVmCpus(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setVmCpus(value)
        }
    }

    fun setBandwidthMbps(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(false)
            settingsRepository.setBandwidthMbps(value)
        }
    }

    fun setLoadBalanceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLoadBalanceEnabled(enabled)
            if (enabled) {
                val profile = DeviceResourcePolicy.balancedProfile(context)
                settingsRepository.setVmRamMb(profile.ramMb)
                settingsRepository.setVmCpus(profile.cpus)
                settingsRepository.setBandwidthMbps(profile.bandwidthMbps)
                // The overlay image is grown to this setting and never shrunk, so a
                // balanced size below the current one would only misreport the real
                // image. Floor the balanced storage at the current size.
                val current = settingsRepository.getStorageSizeGbSnapshot()
                settingsRepository.setStorageSizeGb(maxOf(profile.storageGb, current))
            }
        }
    }

    // "both" expands into separate TCP + UDP rules. Returns true if at least one
    // rule was added; false if every expansion was already present (duplicate).
    // The caller uses the return value to show feedback instead of silently
    // closing the dialog on a duplicate.
    fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp"): Boolean {
        // Backstop the dialog's reserved-port check: these host ports back the
        // implicit loopback-bound X11 forwards and must never be user-bound to
        // 0.0.0.0 (the dialog rejects them with a dedicated message first).
        if (hostPort in PortForwardRepository.RESERVED_HOST_PORTS) return false
        val protos = if (protocol == "both") listOf("tcp", "udp") else listOf(protocol)
        val existing = portForwardRules.value.toSet()
        val toAdd = protos.filter { proto ->
            existing.none { it.hostPort == hostPort && it.protocol == proto }
        }
        if (toAdd.isEmpty()) return false
        viewModelScope.launch {
            // "both" can add TCP then hit the table ceiling on UDP (or vice
            // versa): the dialog already closed on the true return below, so
            // that partial outcome would otherwise vanish silently.
            val notAdded = toAdd.filter { proto ->
                portForwardRepository.addRule(PortForwardRule(hostPort, guestPort, proto)) != AddRuleResult.ADDED
            }
            if (notAdded.isNotEmpty() && notAdded.size < toAdd.size) {
                _portForwardPartialWarning.value = context.getString(
                    R.string.port_forward_partial_add,
                    hostPort,
                    notAdded.joinToString(", ") { it.uppercase() },
                )
            }
        }
        return true
    }

    /** Current LAN IP of the Android device — shown next to port forward rules. Cached for the VM lifetime. */
    val phoneIp: String by lazy { NetworkUtils.localIpv4(context) }

    /** Returns the ID string of the currently active VM backend (e.g. "qemu" or "avf"). */
    fun activeBackendId(): String = engine.backendId

    /**
     * Reactive mirror of [activeBackendId] for the UI: a backend swap (or the
     * async first pick resolving after this screen is already composed) must
     * update the AVF diagnostic dialog and the USB row without the user having
     * to leave and reopen Settings. See #66.
     */
    val activeBackendIdFlow: StateFlow<String> = engine.backendIdFlow

    /** Non-null when the current pick forced AVF but fell back to QEMU; a
     *  short localized reason for the Settings backend caption. See #66. */
    val backendFallback: StateFlow<String?> = engine.backendFallback

    private val _exportError = MutableStateFlow<String?>(null)
    /** One-shot export failure message; clear after showing with [clearExportError]. */
    val exportError: StateFlow<String?> = _exportError.asStateFlow()

    fun clearExportError() { _exportError.value = null }

    private val _portForwardPartialWarning = MutableStateFlow<String?>(null)
    /** One-shot warning when a "both" add only added one protocol (e.g. the
     *  table filled between the TCP and UDP rule); clear after showing with
     *  [clearPortForwardPartialWarning]. */
    val portForwardPartialWarning: StateFlow<String?> = _portForwardPartialWarning.asStateFlow()

    fun clearPortForwardPartialWarning() { _portForwardPartialWarning.value = null }

    fun removePortForward(rule: PortForwardRule) {
        viewModelScope.launch { portForwardRepository.removeRule(rule) }
    }

    /** Removes every persisted user rule. Implicit forwards (SSH/VNC/audio) are in-memory and unaffected. */
    fun clearPortForwards() {
        viewModelScope.launch { portForwardRepository.clearRules() }
    }

    fun resetVm() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                activityManager.clearApplicationUserData()
            }
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
            try {
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
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.export_diagnostic_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_diagnostic_chooser)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                Log.w(TAG, "Export failed", e)
                _exportError.value = context.getString(
                    R.string.export_diagnostic_failed,
                    e.message ?: context.getString(R.string.unknown_error),
                )
            }
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
        val qemuExtras = runCatching { settingsRepository.getQemuExtraArgsSnapshot() }.getOrDefault("")
        val kernelExtras = runCatching { settingsRepository.getKernelExtraCmdlineSnapshot() }.getOrDefault("")
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
            appendLine("QEMU extra args:    $qemuExtras")
            appendLine("Kernel extra cmd:   $kernelExtras")
            appendLine()

            appendLine("=== VM State ===")
            appendLine("State:       ${engine.state.value}")
            appendLine("Boot stage:  ${engine.bootStage.value.ifEmpty { "(none)" }}")
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

            val avf = com.excp.podroid.engine.avf.AvfDiagnostics.probe(context)
                .copy(activeBackend = activeBackendId())
            val cpus = settingsRepository.getVmCpusSnapshot()
            val topology = if (cpus <= 1) "ONE_CPU" else "MATCH_HOST (all host cores)"
            appendLine("== AVF ==")
            appendLine("cpu setting = $cpus -> topology $topology")
            append(avf.pretty())
            appendLine("verboseLogging = ${settingsRepository.getAvfVerboseLoggingSnapshot()}")
            appendLine()

            appendLine("=== Engine Diagnostics ===")
            val engineDiag = runCatching { engine.diagnosticsReport() }.getOrDefault("")
            append(if (engineDiag.isBlank()) "(none)\n" else engineDiag)
            appendLine()

            appendLine("=== App Logcat (this process) ===")
            append(captureAppLogcat())
            appendLine()

            appendLine("=== VM Console Log (backend=${activeBackendId()}) ===")
            val consoleFile = File(context.filesDir, "console.log")
            if (consoleFile.exists() && consoleFile.length() > 0) {
                val text = consoleFile.readText()
                append(text)
                if (!text.endsWith("\n")) appendLine()
            } else {
                appendLine("(no console.log — VM has not been started this session)")
            }
            appendLine()

            appendLine("=== End of Log ===")
        }
    }

    /**
     * Dump this process's own logcat, filtered to Podroid's tags (+ `*:S` to
     * silence everything else). Apps can only read their own logs, but that
     * per-pid buffer is otherwise ~95% framework noise (ImeTracker, Surface,
     * InsetsController, ...) that pushes the engine/vsock/boot lines out of the
     * window before we can capture them; the allowlist keeps the signal.
     */
    private fun captureAppLogcat(): String {
        return try {
            val pid = android.os.Process.myPid().toString()
            val proc = Runtime.getRuntime().exec(
                (listOf("logcat", "-d", "-v", "time", "--pid=$pid") +
                    APP_LOG_TAGS.map { "$it:V" } + "*:S").toTypedArray()
            )
            val output = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val lines = output.trimEnd().lines().filter { it.isNotBlank() }
            when {
                lines.isEmpty() -> "(no Podroid-tagged logcat lines)\n"
                lines.size <= MAX_LOGCAT_LINES -> lines.joinToString("\n") + "\n"
                else -> (listOf("... (${lines.size - MAX_LOGCAT_LINES} earlier lines trimmed) ...") +
                    lines.takeLast(MAX_LOGCAT_LINES)).joinToString("\n") + "\n"
            }
        } catch (e: Exception) {
            "(failed to capture logcat: ${e.message})\n"
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val MAX_LOGCAT_LINES = 1500
        /**
         * Podroid's own logcat tags: the const TAGs plus the two literal-string
         * tags (AvfReflect, PodroidVM-err for QEMU stderr). Keep in sync when a
         * new component starts logging, or its lines won't reach the export.
         */
        private val APP_LOG_TAGS = listOf(
            "AudioStreamer", "AvfDownloadsShare", "AvfEngine", "AvfReflect",
            "ConsoleFanout", "EngineHolder", "HomeViewModel", "HostRequestServer",
            "LanguageManager", "Ninep2000LServer", "PodroidApp", "PodroidService",
            "PodroidVM-err", "QemuBootMonitor", "QemuEngine", "QmpClient",
            "SettingsViewModel", "TerminalVM", "UpdateRepository", "UsbPassthrough",
            "VmControlReceiver", "VsockControlChannel", "VsockPortForwarder",
            "VsockUdpForwarder", "X11Stats", "X11ViewModel",
        )
    }
}
