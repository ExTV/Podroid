package com.excp.podroid.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.BuildConfig
import com.excp.podroid.R
import com.excp.podroid.engine.VmState
import com.excp.podroid.engine.avf.AvfFailureGuidance
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidDestructiveButton
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidStatus
import com.excp.podroid.ui.components.PodroidStatusColors
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.theme.PodroidTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToTerminal: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val bootStage by viewModel.bootStage.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val meta by viewModel.meta.collectAsStateWithLifecycle()
    val uptimeTick by viewModel.uptimeTicker.collectAsStateWithLifecycle()
    val showAvfHint by viewModel.showAvfHint.collectAsStateWithLifecycle()
    val avfBootFailure by viewModel.avfBootFailure.collectAsStateWithLifecycle()
    val avfFailureAdvice by viewModel.avfFailureAdvice.collectAsStateWithLifecycle()
    val stopping by viewModel.stopping.collectAsStateWithLifecycle()

    val isRunning  = vmState is VmState.Running
    val isStarting = vmState is VmState.Starting
    // Stop is asynchronous: the engine stays Running/Starting until teardown
    // finishes, so gate the indicator on the engine's stopping signal while the
    // state is still active. The instant state goes terminal, this falls back to
    // normal Stopped/Error rendering regardless of signal/state flip ordering.
    val isStopping = stopping && (isRunning || isStarting)
    val uptimeLabel = viewModel.uptimeLabel(uptimeTick)
    // Cache the ConnectivityManager binder call so it doesn't re-run on every
    // 1 Hz tick or incidental recomposition, but refresh it on ON_RESUME so a
    // Wi-Fi / hotspot change (the headline SSH use case) shows the new address
    // instead of a stale one for the screen's lifetime.
    var phoneIp by remember { mutableStateOf(viewModel.phoneIp()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) phoneIp = viewModel.phoneIp()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            icon  = { Icon(Icons.Default.SystemUpdate, contentDescription = stringResource(R.string.update_available)) },
            title = { Text(stringResource(R.string.update_available)) },
            text  = { Text(stringResource(R.string.version_available, info.latestVersion, BuildConfig.VERSION_NAME)) },
            confirmButton = {
                TextButton(onClick = {
                    // Guard against ActivityNotFoundException (no browser) or a
                    // blank/malformed releaseUrl from the remote JSON source.
                    // Surface a toast on failure so the tap isn't a silent no-op.
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
                    }.onFailure {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.update_open_failed),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    viewModel.dismissUpdate()
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) { Text(stringResource(R.string.later)) }
            },
        )
    }

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
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
            if (isCompactHeight) {
                // Landscape phone / split-screen: hero on left, action column on right.
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = PodroidTokens.Spacing.XL2, vertical = PodroidTokens.Spacing.LG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = PodroidTokens.Spacing.XL2)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        if (showAvfHint) {
                            AvfHintBanner(onDismiss = { viewModel.dismissAvfHint() })
                        }
                        HomeStatusBlock(
                            isStarting, isRunning, isStopping, vmState, bootStage, meta, uptimeLabel,
                            avfBootFailure = avfBootFailure,
                            avfFailureAdvice = avfFailureAdvice,
                            onUseOneCore = { viewModel.useOneCoreAndRetry() },
                            onSwitchToQemu = { viewModel.switchToQemuAndRetry() },
                            onRetry = { viewModel.restartVm() },
                        )
                        HomeDataSection(isRunning, isStopping, vmState, meta, phoneIp)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                    ) {
                        HomeActionButtons(
                            isRunning = isRunning,
                            isStarting = isStarting,
                            isStopping = isStopping,
                            vmState = vmState,
                            onStart = { viewModel.startPodroid() },
                            onStop = { viewModel.stopVm() },
                            onRestart = { viewModel.restartVm() },
                            onOpenTerminal = onNavigateToTerminal,
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PodroidTokens.Spacing.XL),
                    verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
                ) {
                    Spacer(Modifier.height(PodroidTokens.Spacing.XL))
                    if (showAvfHint) {
                        AvfHintBanner(onDismiss = { viewModel.dismissAvfHint() })
                    }
                    HomeStatusBlock(
                        isStarting = isStarting,
                        isRunning = isRunning,
                        isStopping = isStopping,
                        vmState = vmState,
                        bootStage = bootStage,
                        meta = meta,
                        uptimeLabel = uptimeLabel,
                        avfBootFailure = avfBootFailure,
                        avfFailureAdvice = avfFailureAdvice,
                        onUseOneCore = { viewModel.useOneCoreAndRetry() },
                        onSwitchToQemu = { viewModel.switchToQemuAndRetry() },
                        onRetry = { viewModel.restartVm() },
                    )
                    HomeDataSection(isRunning, isStopping, vmState, meta, phoneIp)
                    Spacer(Modifier.weight(1f))
                    HomeActionButtons(
                        isRunning = isRunning,
                        isStarting = isStarting,
                        isStopping = isStopping,
                        vmState = vmState,
                        onStart = { viewModel.startPodroid() },
                        onStop = { viewModel.stopVm() },
                        onRestart = { viewModel.restartVm() },
                        onOpenTerminal = onNavigateToTerminal,
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.XL))
                }
            }
        }
    }
}

@Composable
private fun AvfHintBanner(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PodroidTokens.Spacing.MD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(PodroidTokens.Spacing.MD),
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
        ) {
            Text(
                stringResource(R.string.avf_available),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.avf_hint_needs_pc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.avf_grant_commands),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}

@Composable
private fun HomeStatusBlock(
    isStarting: Boolean,
    isRunning: Boolean,
    isStopping: Boolean,
    vmState: VmState,
    bootStage: String,
    meta: HomeMeta,
    uptimeLabel: String?,
    avfBootFailure: Boolean = false,
    avfFailureAdvice: AvfFailureGuidance.Advice = AvfFailureGuidance.Advice.SWITCH_TO_QEMU,
    onUseOneCore: () -> Unit = {},
    onSwitchToQemu: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    PodroidSectionLabel(stringResource(R.string.vm_status))
    Text(
        text = when {
            isStopping -> stringResource(R.string.status_stopping)
            isStarting -> stringResource(R.string.status_starting)
            isRunning  -> stringResource(R.string.status_running)
            else       -> stringResource(R.string.status_stopped)
        },
        style = MaterialTheme.typography.displayLarge,
        color = when {
            isStopping -> MaterialTheme.colorScheme.tertiary
            isRunning  -> MaterialTheme.colorScheme.primary
            isStarting -> MaterialTheme.colorScheme.tertiary
            else       -> MaterialTheme.colorScheme.onSurface
        },
    )
    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isStopping) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(Modifier.width(PodroidTokens.Spacing.SM))
                Text(
                    text = stringResource(R.string.stopping_vm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val (dot, label) = when {
                isRunning  -> PodroidStatusColors.Running  to (uptimeLabel ?: stringResource(R.string.up))
                isStarting -> PodroidStatusColors.Starting to bootStage.ifEmpty { stringResource(R.string.status_starting) }
                else       -> PodroidStatusColors.Stopped  to stringResource(R.string.status_idle)
            }
            PodroidStatus(label = label, dotColor = dot)
        }
        Text(
            text = meta.resourcesLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (vmState is VmState.Error) {
        Spacer(Modifier.height(PodroidTokens.Spacing.MD))
        PodroidSectionLabel(stringResource(R.string.error_title))
        Text(
            text = vmState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (avfBootFailure) {
            Spacer(Modifier.height(PodroidTokens.Spacing.MD))
            Text(
                text = stringResource(
                    if (avfFailureAdvice == AvfFailureGuidance.Advice.TRY_ONE_CORE)
                        R.string.avf_boot_failed_try_one_core
                    else
                        R.string.avf_boot_failed_switch_qemu
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            if (avfFailureAdvice == AvfFailureGuidance.Advice.TRY_ONE_CORE) {
                PodroidPrimaryButton(
                    text = stringResource(R.string.avf_action_use_one_core),
                    onClick = onUseOneCore,
                )
                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM)) {
                PodroidGhostButton(
                    text = stringResource(R.string.avf_action_switch_qemu),
                    onClick = onSwitchToQemu,
                    modifier = Modifier.weight(1f),
                )
                PodroidGhostButton(
                    text = stringResource(R.string.avf_action_retry),
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    // Starting state: the meta row already shows the amber dot + boot-stage
    // text, which is the canonical boot indicator. No need for a separate ring.
}

@Composable
private fun HomeDataSection(
    isRunning: Boolean,
    isStopping: Boolean,
    vmState: VmState,
    meta: HomeMeta,
    phoneIp: String,
) {
    val showStarting = vmState is VmState.Starting
    val showError = vmState is VmState.Error
    if (showStarting || showError || isStopping) return
    Spacer(Modifier.height(PodroidTokens.Spacing.MD))
    if (isRunning) {
        PodroidSectionLabel(stringResource(R.string.network))
        PodroidListRow(label = stringResource(R.string.phone_ip), value = phoneIp, mono = true)
        PodroidListRow(
            label = stringResource(R.string.ssh),
            value = if (meta.sshEnabled) ":9922 · podroid" else stringResource(R.string.off),
            mono = meta.sshEnabled,
        )
        PodroidListRow(
            label = stringResource(R.string.port_forwards),
            value = if (meta.portForwardCount == 0) stringResource(R.string.none)
                    else "${meta.portForwardCount} ${stringResource(R.string.active)}",
        )
    } else {
        PodroidSectionLabel(stringResource(R.string.last_session))
        if (meta.lastBootDurationMs > 0L) {
            PodroidListRow(
                label = stringResource(R.string.booted_in),
                value = formatBootDuration(meta.lastBootDurationMs),
            )
        }
        PodroidListRow(
            label = stringResource(R.string.build),
            value = "v${BuildConfig.VERSION_NAME} · QEMU ${BuildConfig.QEMU_VERSION}",
            mono = true,
        )
    }
}

private fun formatBootDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return if (totalSec >= 60) {
        val m = totalSec / 60
        val s = totalSec % 60
        if (s == 0L) "${m}m" else "${m}m ${s}s"
    } else {
        val tenths = (ms / 100) % 10
        if (tenths == 0L) "${totalSec}s" else "${totalSec}.${tenths}s"
    }
}

@Composable
private fun HomeActionButtons(
    isRunning: Boolean,
    isStarting: Boolean,
    isStopping: Boolean,
    vmState: VmState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    if (isStopping) {
        // Teardown in progress: one disabled affordance so the user can't
        // double-stop or start over a stop that's already running. The spinner
        // lives in the status block above.
        PodroidPrimaryButton(
            text = stringResource(R.string.stopping_action),
            onClick = {},
            enabled = false,
        )
    } else if (isRunning) {
        PodroidPrimaryButton(text = stringResource(R.string.open_terminal), onClick = onOpenTerminal)
        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
        Row(horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM)) {
            PodroidGhostButton(text = stringResource(R.string.restart), onClick = onRestart, modifier = Modifier.weight(1f))
            PodroidDestructiveButton(text = stringResource(R.string.stop), onClick = onStop, modifier = Modifier.weight(1f))
        }
    } else if (isStarting) {
        PodroidDestructiveButton(text = stringResource(R.string.stop), onClick = onStop)
    } else if (vmState is VmState.Error) {
        PodroidPrimaryButton(text = stringResource(R.string.try_again), onClick = onStart)
    } else {
        PodroidPrimaryButton(text = stringResource(R.string.start_vm), onClick = onStart)
    }
}
