package com.excp.podroid.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmState
import com.excp.podroid.engine.avf.AvfDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidDestructiveButton
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidInlineAction
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidChipColors
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.theme.PodroidTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onThemeOrFontChanged: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val portForwardRules by viewModel.portForwardRules.collectAsStateWithLifecycle()
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val exportError by viewModel.exportError.collectAsStateWithLifecycle()

    var advancedExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var avfReportText by remember { mutableStateOf<String?>(null) }
    var avfRunning by remember { mutableStateOf(false) }
    val avfScope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val vmNotRunning = vmState !is VmState.Running && vmState !is VmState.Starting

    // Memoize: both values are constant for the process lifetime / until a backend
    // swap, so there's no point re-running reflection on every recomposition.
    val isDownloadsShareAvailable = remember { viewModel.isDownloadsShareAvailable() }
    val activeBackendId = remember { viewModel.activeBackendId() }

    // Re-sync the persisted storageAccessEnabled flag against the real OS grant on
    // every resume (user may have denied all-files-access on the system screen we
    // sent them to, but the DataStore flag still reads true).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                viewModel.setStorageAccessEnabled(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(exportError) {
        val msg = exportError ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearExportError()
    }

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = "Settings",
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            maxWidth = if (isCompactHeight) 900 else 600,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodroidTokens.Spacing.XL),
            ) {
                // ── APPEARANCE ────────────────────────────────────────
                PodroidSectionLabel("Appearance")
                PodroidListRow(
                    label = "Dark theme",
                    rightSlot = {
                        PodroidSwitch(
                            checked = ui.darkTheme,
                            onCheckedChange = {
                                viewModel.setDarkTheme(it)
                                onThemeOrFontChanged()
                            },
                        )
                    },
                )
                PodroidListRow(
                    label = "Dynamic color (Material You)",
                    rightSlot = {
                        PodroidSwitch(
                            checked = ui.dynamicColorEnabled,
                            onCheckedChange = {
                                viewModel.setDynamicColorEnabled(it)
                                onThemeOrFontChanged()
                            },
                        )
                    },
                )

                // ── VM RESOURCES ──────────────────────────────────────
                PodroidSectionLabel("VM Resources")
                if (!vmNotRunning) {
                    Text(
                        text = "Stop the VM to change these settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = PodroidTokens.Spacing.SM),
                    )
                }
                RamSection(
                    currentMb = ui.vmRamMb,
                    onChange = viewModel::setVmRamMb,
                    enabled = vmNotRunning,
                )
                CpusSection(
                    currentCpus = ui.vmCpus,
                    onChange = viewModel::setVmCpus,
                    enabled = vmNotRunning,
                )
                PodroidListRow(
                    label = "Storage",
                    value = "${ui.storageSizeGb} GB",
                )

                // ── NETWORK ───────────────────────────────────────────
                PodroidSectionLabel("Network")
                PodroidListRow(
                    label = "Phone IP",
                    value = viewModel.phoneIp,
                    mono = true,
                )
                PodroidListRow(
                    label = "SSH (port 9922, password \"podroid\")",
                    rightSlot = {
                        PodroidSwitch(
                            checked = ui.sshEnabled,
                            onCheckedChange = { viewModel.setSshEnabled(it) },
                            enabled = vmNotRunning,
                        )
                    },
                )
                PortForwardSection(
                    rules = portForwardRules,
                    onAdd = { showAddDialog = true },
                    onRemove = { viewModel.removePortForward(it) },
                )

                // ── STORAGE / SHARING ─────────────────────────────────
                PodroidSectionLabel("Storage")
                DownloadsSharingRow(
                    enabled = ui.storageAccessEnabled,
                    vmNotRunning = vmNotRunning,
                    available = isDownloadsShareAvailable,
                    activeBackendId = activeBackendId,
                    onToggle = { viewModel.setStorageAccessEnabled(it) },
                )
                Spacer(Modifier.height(PodroidTokens.Spacing.MD))
                PodroidDestructiveButton(
                    text = "Reset VM (deletes all data)",
                    onClick = { showResetDialog = true },
                )

                // ── ADVANCED ──────────────────────────────────────────
                PodroidSectionLabel("Advanced")
                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                PodroidGhostButton(
                    text = if (advancedExpanded) "Hide advanced" else "Show advanced",
                    onClick = { advancedExpanded = !advancedExpanded },
                )
                if (advancedExpanded) {
                    PodroidSectionLabel("Backend")
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = PodroidTokens.Spacing.MD),
                    ) {
                        EngineSelection.entries.forEach { sel ->
                            FilterChip(
                                selected = ui.engineSelection == sel,
                                onClick = { viewModel.setEngineSelection(sel) },
                                enabled = vmNotRunning,
                                label = {
                                    Text(
                                        when (sel) {
                                            EngineSelection.AUTO -> "Auto"
                                            EngineSelection.AVF  -> "AVF (KVM)"
                                            EngineSelection.QEMU -> "QEMU (TCG)"
                                        },
                                        fontFamily = FontFamily.Monospace,
                                    )
                                },
                                shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                                colors = PodroidChipColors(),
                            )
                        }
                    }
                    Spacer(Modifier.height(PodroidTokens.Spacing.MD))
                    AdvancedFieldsBlock(
                        qemuExtraArgs = ui.qemuExtraArgs,
                        kernelExtraCmdline = ui.kernelExtraCmdline,
                        onQemuChange = viewModel::setQemuExtraArgs,
                        onKernelChange = viewModel::setKernelExtraCmdline,
                        onQemuReset = viewModel::resetQemuExtraArgs,
                        onKernelReset = viewModel::resetKernelExtraCmdline,
                        enabled = vmNotRunning,
                    )
                }

                // ── ABOUT ─────────────────────────────────────────────
                PodroidSectionLabel("About")
                PodroidListRow(label = "Version", value = "v${BuildConfig.VERSION_NAME}", mono = true)
                PodroidListRow(label = "QEMU", value = "v${BuildConfig.QEMU_VERSION}", mono = true)
                PodroidListRow(label = "Architecture", value = "AArch64", mono = true)
                PodroidListRow(label = "Linux distro", value = "Alpine 3.23", mono = true)
                Spacer(Modifier.height(PodroidTokens.Spacing.MD))
                PodroidGhostButton(
                    text = "Export diagnostic log",
                    onClick = { viewModel.exportConsoleLogs() },
                )
                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                // Use lifecycle-aware collection to match the rest of the screen.
                val avfVerbose by viewModel.avfVerboseLogging.collectAsStateWithLifecycle()
                PodroidListRow(
                    label = "Verbose AVF logging",
                    rightSlot = {
                        PodroidSwitch(
                            checked = avfVerbose,
                            onCheckedChange = { viewModel.setAvfVerboseLogging(it) },
                        )
                    },
                )
                PodroidGhostButton(
                    text = if (avfRunning) "Running AVF diagnostic…" else "AVF (pKVM) diagnostic",
                    onClick = {
                        if (avfRunning) return@PodroidGhostButton
                        avfRunning = true
                        avfReportText = "Probing…"
                        avfScope.launch {
                            val probe = AvfDiagnostics.probe(ctx)
                            val smoke = if (probe.featureSupported && probe.managePermissionGranted) {
                                withContext(Dispatchers.IO) { AvfDiagnostics.runSmokeTest(ctx) }
                            } else null
                            avfReportText = probe.copy(
                                smokeTestResult = smoke,
                                activeBackend = activeBackendId,
                            ).pretty()
                            avfRunning = false
                        }
                    },
                )

                Spacer(Modifier.height(PodroidTokens.Spacing.XL2))
            }
        }
    }

    if (showAddDialog) {
        AddPortForwardDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { hostPort, guestPort, protocol ->
                // Only close the dialog when the rule was actually added.
                // addPortForward returns false if the (hostPort, protocol) pair
                // already exists; in that case the dialog stays open with an error.
                val added = viewModel.addPortForward(hostPort, guestPort, protocol)
                if (added) showAddDialog = false
                added
            },
        )
    }

    avfReportText?.let { report ->
        AlertDialog(
            onDismissRequest = { avfReportText = null },
            title = { Text("AVF (pKVM) diagnostic") },
            text = {
                androidx.compose.material3.Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(PodroidTokens.Spacing.SM),
                    ) {
                        Text(
                            text = report,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { avfReportText = null }) { Text("Close") }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Full App Reset?") },
            text = {
                Text(
                    "This will clear ALL application data, including your VM storage, " +
                        "settings, and port rules. The app will close and return to a " +
                        "freshly-installed state."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetVm()
                    showResetDialog = false
                }) {
                    Text("Reset Everything", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RamSection(currentMb: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    Column(modifier = Modifier.padding(bottom = PodroidTokens.Spacing.SM)) {
        Text(
            "RAM  ·  ${if (currentMb >= 1024) "${currentMb / 1024} GB" else "$currentMb MB"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = PodroidTokens.Spacing.MD,
                bottom = PodroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
        ) {
            listOf(512, 1024, 2048, 4096).forEach { mb ->
                FilterChip(
                    selected = mb == currentMb,
                    enabled = enabled,
                    onClick = { onChange(mb) },
                    label = { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") },
                    shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                    colors = PodroidChipColors(),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
            modifier = Modifier.padding(top = PodroidTokens.Spacing.MD),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CpusSection(currentCpus: Int, onChange: (Int) -> Unit, enabled: Boolean) {
    Column(modifier = Modifier.padding(bottom = PodroidTokens.Spacing.SM)) {
        Text(
            "CPU cores  ·  $currentCpus",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(
                top = PodroidTokens.Spacing.MD,
                bottom = PodroidTokens.Spacing.SM,
            ),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
        ) {
            listOf(1, 2, 4, 6, 8).forEach { n ->
                FilterChip(
                    selected = n == currentCpus,
                    enabled = enabled,
                    onClick = { onChange(n) },
                    label = { Text("$n") },
                    shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                    colors = PodroidChipColors(),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
            modifier = Modifier.padding(top = PodroidTokens.Spacing.MD),
        )
    }
}

@Composable
private fun PortForwardSection(
    rules: List<PortForwardRule>,
    onAdd: () -> Unit,
    onRemove: (PortForwardRule) -> Unit,
) {
    PodroidListRow(
        label = "Port forwards (${rules.size})",
        rightSlot = { PodroidInlineAction(label = "+ Add", onClick = onAdd) },
    )
    rules.forEach { rule ->
        key(rule.hostPort, rule.protocol) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PodroidTokens.Spacing.SM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${rule.hostPort} → ${rule.guestPort} (${rule.protocol})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = PodroidTokens.mono(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(rule) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                thickness = 1.dp,
            )
        }
    }
}

/**
 * Mirrors the setup wizard's storage-access toggle: turn it on and, if needed,
 * jump straight to the system MANAGE_EXTERNAL_STORAGE grant screen.
 *
 * Disabled when the active backend can't actually share Downloads — on AVF
 * that's any pKVM device whose framework jar ships only the 9-param
 * SharedPath ctor (no `appDomain` parameter). Google's TerminalApp escapes
 * this because it's installed as a privileged system app under
 * /apex/com.android.virt/priv-app/; third-party APKs can't get the SELinux
 * promotion needed to cross-domain-share external storage.
 */
@Composable
private fun DownloadsSharingRow(
    enabled: Boolean,
    vmNotRunning: Boolean,
    available: Boolean,
    activeBackendId: String,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val canManageAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun openAllFilesAccessSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }

    PodroidListRow(
        label = "Downloads sharing",
        rightSlot = {
            PodroidSwitch(
                checked = enabled && available,
                onCheckedChange = { checked ->
                    onToggle(checked)
                    if (checked && canManageAllFiles && !Environment.isExternalStorageManager()) {
                        openAllFilesAccessSettings()
                    }
                },
                enabled = vmNotRunning && available,
            )
        },
    )
    if (!available) {
        Text(
            text = "Not available on this $activeBackendId build — AVF requires a privileged " +
                "system-app install (Google's Terminal app cheats this way); third-party " +
                "APKs can't cross SELinux domains to read /storage/emulated/Download.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = PodroidTokens.Spacing.MD,
                end = PodroidTokens.Spacing.MD,
                bottom = PodroidTokens.Spacing.SM,
            ),
        )
    }
}

@Composable
private fun AdvancedFieldsBlock(
    qemuExtraArgs: String,
    kernelExtraCmdline: String,
    onQemuChange: (String) -> Unit,
    onKernelChange: (String) -> Unit,
    onQemuReset: () -> Unit,
    onKernelReset: () -> Unit,
    enabled: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
        modifier = Modifier.padding(
            top = PodroidTokens.Spacing.SM,
            bottom = PodroidTokens.Spacing.MD,
        ),
    ) {
        if (!enabled) {
            Text(
                text = "Stop the VM before editing — changes apply on next boot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        AdvancedTextSetting(
            label = "Extra QEMU args",
            helper = "-cpu, -accel, -object, -device, -overcommit, etc. Whitespace-separated.",
            value = qemuExtraArgs,
            enabled = enabled,
            onValueChange = onQemuChange,
            onReset = onQemuReset,
            minLines = 4,
        )
        AdvancedTextSetting(
            label = "Extra kernel cmdline",
            helper = "Appended after console=ttyAMA0 — controls scheduler, mitigations, log level, etc.",
            value = kernelExtraCmdline,
            enabled = enabled,
            onValueChange = onKernelChange,
            onReset = onKernelReset,
            minLines = 2,
        )
    }
}

@Composable
private fun AddPortForwardDialog(
    onDismiss: () -> Unit,
    // Returns true if the rule was added, false if it was a duplicate.
    // The dialog shows an error and stays open on false.
    onAdd: (hostPort: Int, guestPort: Int, protocol: String) -> Boolean,
) {
    var hostPort by remember { mutableStateOf("") }
    var guestPort by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("tcp") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add port forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = hostPort,
                    onValueChange = { hostPort = it; error = null },
                    label = { Text("Android port") },
                    placeholder = { Text("e.g. 8080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = guestPort,
                    onValueChange = { guestPort = it; error = null },
                    label = { Text("VM port") },
                    placeholder = { Text("e.g. 80") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("tcp", "udp", "both").forEach { proto ->
                        FilterChip(
                            selected = protocol == proto,
                            onClick = { protocol = proto },
                            label = {
                                Text(
                                    proto.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                            colors = PodroidChipColors(),
                        )
                    }
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val hp = hostPort.toIntOrNull()
                val gp = guestPort.toIntOrNull()
                if (hp == null || gp == null || hp !in 1..65535 || gp !in 1..65535) {
                    error = "Enter valid port numbers (1–65535)"
                    return@TextButton
                }
                val added = onAdd(hp, gp, protocol)
                if (!added) error = "Port $hp (${protocol.uppercase()}) is already forwarded"
            }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun AdvancedTextSetting(
    label: String,
    helper: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onReset: () -> Unit,
    minLines: Int,
) {
    // Editing is local; we only persist when the field loses focus. Avoids
    // round-tripping every keystroke through DataStore on a multi-line config field.
    // Don't reset local edits on every external emit — only sync when the upstream
    // value actually drifts from what we have buffered (e.g. a Reset tap).
    val localState = remember { mutableStateOf(value) }
    var localValue by localState
    LaunchedEffect(value) {
        if (localValue != value) localValue = value
    }
    var hadFocus by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = localValue,
            onValueChange = { localValue = it },
            label = { Text(label) },
            enabled = enabled,
            singleLine = false,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus && localValue != value) {
                        onValueChange(localValue)
                    }
                },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = helper,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset, enabled = enabled) {
                Text("Reset")
            }
        }
    }
}
