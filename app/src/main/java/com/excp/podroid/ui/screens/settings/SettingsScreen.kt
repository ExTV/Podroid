package com.excp.podroid.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onThemeOrFontChanged: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    // 8 form-style rows in one subscription via combine(...) in the ViewModel —
    // single recomposition wave per emit instead of one per source flow.
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = ui.darkTheme
    val vmRamMb = ui.vmRamMb
    val vmCpus = ui.vmCpus
    val storageSizeGb = ui.storageSizeGb
    val sshEnabled = ui.sshEnabled
    val storageAccessEnabled = ui.storageAccessEnabled
    val qemuExtraArgs = ui.qemuExtraArgs
    val kernelExtraCmdline = ui.kernelExtraCmdline
    // Kept separate — different cadence/consumer than the form rows.
    val portForwardRules by viewModel.portForwardRules.collectAsStateWithLifecycle()
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    var advancedExpanded by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val ramOptions = listOf(512, 1024, 2048, 4096)
    val cpuOptions = listOf(1, 2, 4, 6, 8)
    val vmNotRunning = vmState !is VmState.Running && vmState !is VmState.Starting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
            Spacer(Modifier.height(16.dp))

            // ── VM Resources ─────────────────────────────────────────
            SettingsSection(title = "VM Resources") {
                if (!vmNotRunning) {
                    Text(
                        text = "Stop the VM to change these settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                Text("Memory", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$vmRamMb MB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = ramOptions,
                    selected = vmRamMb,
                    enabled = vmNotRunning,
                    label = { ram -> if (ram >= 1024) "${ram / 1024} GB" else "$ram MB" },
                    onSelect = { viewModel.setVmRamMb(it) },
                )

                Spacer(Modifier.height(16.dp))

                Text("CPU cores", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$vmCpus", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = cpuOptions,
                    selected = vmCpus,
                    enabled = vmNotRunning,
                    label = { cpu -> "$cpu" },
                    onSelect = { viewModel.setVmCpus(it) },
                )
            }

            Spacer(Modifier.height(8.dp))



            Spacer(Modifier.height(4.dp))

            // ── Storage ───────────────────────────────────────────────
            SettingsSection(title = "Storage") {
                Text("Persistent storage", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$storageSizeGb GB", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "Set during initial setup. Reset the VM to change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                DownloadsSharingCard(
                    enabled = storageAccessEnabled,
                    vmNotRunning = vmNotRunning,
                    onToggle = { viewModel.setStorageAccessEnabled(it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Network ───────────────────────────────────────────────
            SettingsSection(title = "Network") {
                val phoneIp = viewModel.phoneIp
                Text(
                    text = "Phone IP: $phoneIp",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Point your devices here to reach the VM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                SettingsSwitchRow(
                    title = "Enable SSH",
                    subtitle = if (sshEnabled) "ssh root@<phone-ip> -p 9922  |  password: podroid" else "Access the VM over your local network via SSH",
                    checked = sshEnabled,
                    onCheckedChange = { viewModel.setSshEnabled(it) },
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Port forwards", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Forward ports from your Android device into the VM. Active immediately when VM is running.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )

                if (portForwardRules.isEmpty()) {
                    Text(
                        text = "No rules configured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    portForwardRules.forEach { rule ->
                        key(rule.hostPort, rule.protocol) {
                            PortForwardRuleRow(
                                rule = rule,
                                onDelete = { viewModel.removePortForward(rule) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add Port Forward")
                }
                if (vmState is VmState.Running) {
                    Text(
                        text = "VM is running — new rules take effect immediately.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Appearance ────────────────────────────────────────────
            SettingsSection(title = "Appearance") {
                SettingsSwitchRow(
                    title = "Dark theme",
                    subtitle = "Use a dark color scheme",
                    checked = darkTheme,
                    onCheckedChange = { viewModel.setDarkTheme(it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── Diagnostics ───────────────────────────────────────────
            SettingsSection(title = "Diagnostics") {
                FilledTonalButton(
                    onClick = { viewModel.exportConsoleLogs() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export Diagnostic Log")
                }
                Text(
                    text = "Shares log.txt with app info, settings, VM state, app logcat, and QEMU console output. Attach this when reporting bugs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(Modifier.height(12.dp))

                FilledTonalButton(
                    onClick = { showResetDialog = true },
                    enabled = vmNotRunning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Full App Reset")
                }
                if (!vmNotRunning) {
                    Text(
                        text = "Stop the VM before resetting.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Advanced QEMU ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Advanced QEMU",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (advancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                )
            }

            if (advancedExpanded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                        )
                        Text(
                            text = "Editing these may prevent the VM from booting. " +
                                "RAM, CPU count, sockets, storage, and networking are " +
                                "managed by the app and not exposed here. Use Reset if " +
                                "the VM stops booting.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                if (!vmNotRunning) {
                    Text(
                        text = "Stop the VM before editing — changes apply on next boot.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                AdvancedTextSetting(
                    label = "Extra QEMU args",
                    helper = "-cpu, -accel, -object, -device, -overcommit, etc. Whitespace-separated.",
                    value = qemuExtraArgs,
                    enabled = vmNotRunning,
                    onValueChange = viewModel::setQemuExtraArgs,
                    onReset = viewModel::resetQemuExtraArgs,
                    minLines = 4,
                )

                Spacer(Modifier.height(12.dp))

                AdvancedTextSetting(
                    label = "Extra kernel cmdline",
                    helper = "Appended after console=ttyAMA0 — controls scheduler, mitigations, log level, etc.",
                    value = kernelExtraCmdline,
                    enabled = vmNotRunning,
                    onValueChange = viewModel::setKernelExtraCmdline,
                    onReset = viewModel::resetKernelExtraCmdline,
                    minLines = 2,
                )

                Spacer(Modifier.height(16.dp))
            }

            // ── About ─────────────────────────────────────────────────
            SettingsSection(title = "About") {
                SettingsInfoRow("Version", BuildConfig.VERSION_NAME)
                SettingsInfoRow("QEMU", BuildConfig.QEMU_VERSION)
                SettingsInfoRow("Architecture", "AArch64 (ARM64)")
                SettingsInfoRow("Linux distro", "Alpine Linux 3.23")
                SettingsInfoRow("Storage", "$storageSizeGb GB persistent overlay")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

    if (showAddDialog) {
        AddPortForwardDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { hostPort, guestPort, protocol ->
                viewModel.addPortForward(hostPort, guestPort, protocol)
                showAddDialog = false
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

@Composable
private fun PortForwardRuleRow(rule: PortForwardRule, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "localhost:${rule.hostPort}  →  VM:${rule.guestPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = rule.protocol.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove rule",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AddPortForwardDialog(
    onDismiss: () -> Unit,
    onAdd: (hostPort: Int, guestPort: Int, protocol: String) -> Unit,
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
                            label = { Text(proto.uppercase(), style = MaterialTheme.typography.labelSmall) },
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
                onAdd(hp, gp, protocol)
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
private fun SettingsSectionHeader(title: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
    }
}

/** Card-grouped section: header above + a surface-tinted card around the content. */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            content()
        }
    }
}

/** Row of FilterChips for picking one value out of a list (RAM, CPUs, etc.). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    enabled: Boolean,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                enabled = enabled,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Mirrors the setup-wizard's storage-access card: toggle, "may crash the VM"
 * warning, and a permission-grant button when MANAGE_EXTERNAL_STORAGE isn't held.
 */
@Composable
private fun DownloadsSharingCard(
    enabled: Boolean,
    vmNotRunning: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val canManageAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val hasStoragePermission = !canManageAllFiles || Environment.isExternalStorageManager()

    fun openAllFilesAccessSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Downloads sharing",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (enabled)
                            "Mounted as /mnt/downloads in the VM via virtio-9p."
                        else
                            "Share the Android Downloads folder with the VM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        onToggle(checked)
                        // Mirror SetupScreen behavior: when the user enables sharing
                        // and we're on R+ without MANAGE_EXTERNAL_STORAGE held, jump
                        // straight to the system grant screen so they can flip it.
                        if (checked && canManageAllFiles && !Environment.isExternalStorageManager()) {
                            openAllFilesAccessSettings()
                        }
                    },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "On some devices this may crash the VM.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
            )

            if (enabled && canManageAllFiles && !hasStoragePermission) {
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(onClick = ::openAllFilesAccessSettings) {
                    Text("Grant storage access")
                }
            } else if (enabled && canManageAllFiles) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (hasStoragePermission) "All files access granted." else "All files access not granted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!vmNotRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Restart the VM for changes to take effect.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
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
