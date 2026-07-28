/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Azure-style live metric dashboard for phone + VM resources.
 */
package com.excp.podroid.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.ChartSeries
import com.excp.podroid.ui.components.MetricChartCard
import com.excp.podroid.ui.components.MetricLegend
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.theme.PodroidTokens
import com.excp.podroid.util.HostMetrics

private const val TAB_PHONE = 0
private const val TAB_VM = 1

private val AzureBlue = Color(0xFF0078D4)
private val AzurePink = Color(0xFFE91E8C)
private val AzureTeal = Color(0xFF00BCF2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: StatusViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_VM) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setLiveActive(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setLiveActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            viewModel.setLiveActive(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.status_page_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PodroidTokens.Spacing.XL, vertical = PodroidTokens.Spacing.SM),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusLiveBadge(active = ui.liveTick > 0L)
                }
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == TAB_PHONE,
                        onClick = { selectedTab = TAB_PHONE },
                        text = { Text(stringResource(R.string.status_tab_phone)) },
                    )
                    Tab(
                        selected = selectedTab == TAB_VM,
                        onClick = { selectedTab = TAB_VM },
                        text = { Text(stringResource(R.string.status_tab_vm)) },
                    )
                }
                when (selectedTab) {
                    TAB_PHONE -> PhoneStatusTab(ui = ui)
                    TAB_VM -> VmStatusTab(ui = ui)
                }
            }
        }
    }
}

@Composable
private fun StatusLiveBadge(active: Boolean) {
    if (!active) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = stringResource(R.string.status_live_badge),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.status_live_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhoneStatusTab(ui: StatusUiState) {
    val metrics = ui.metrics
    val charts = ui.charts
    val phoneRamUsedMb = (metrics.phoneTotalRamMb - metrics.phoneAvailRamMb).coerceAtLeast(0)
    val phoneStorageUsedGb = (metrics.phoneStorageTotalGb - metrics.phoneStorageAvailGb).coerceAtLeast(0.0)
    val resource = stringResource(R.string.status_resource_phone)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PodroidTokens.Spacing.LG, vertical = PodroidTokens.Spacing.MD),
        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
    ) {
        MetricChartCard(
            title = stringResource(R.string.status_metric_phone_cpu),
            series = listOf(ChartSeries(charts.phoneCpu, AzureBlue)),
            yMax = 100f,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_cpu_legend),
                    subtitle = resource,
                    value = ui.phoneCpuPercent?.let { String.format("%.1f%%", it) }
                        ?: stringResource(R.string.status_vm_load_collecting),
                    color = AzureBlue,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_phone_ram),
            series = listOf(ChartSeries(charts.phoneRam, AzurePink)),
            yMax = 100f,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.ram_label),
                    subtitle = resource,
                    value = "${HostMetrics.formatMb(phoneRamUsedMb)} / ${HostMetrics.formatMb(metrics.phoneTotalRamMb)}",
                    color = AzurePink,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_phone_storage),
            series = listOf(ChartSeries(charts.phoneStorage, AzureTeal)),
            yMax = 100f,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.storage),
                    subtitle = resource,
                    value = "${HostMetrics.formatGb(phoneStorageUsedGb)} / ${HostMetrics.formatGb(metrics.phoneStorageTotalGb)}",
                    color = AzureTeal,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_network),
            series = listOf(
                ChartSeries(charts.netIn, AzureBlue),
                ChartSeries(charts.netOut, AzurePink),
            ),
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_network_in),
                    subtitle = resource,
                    value = formatRate(ui.netInBps),
                    color = AzureBlue,
                ),
                MetricLegend(
                    label = stringResource(R.string.status_metric_network_out),
                    subtitle = resource,
                    value = formatRate(ui.netOutBps),
                    color = AzurePink,
                ),
            ),
        )

        PodroidSectionLabel(stringResource(R.string.status_phone_details))
        PodroidListRow(label = stringResource(R.string.cpu_cores), value = "${metrics.phoneCpuCores}")
        if (metrics.loadAvg1 != null) {
            PodroidListRow(
                label = stringResource(R.string.status_load_average),
                value = String.format("%.2f · %.2f · %.2f", metrics.loadAvg1, metrics.loadAvg5, metrics.loadAvg15),
                mono = true,
            )
        }
        PodroidListRow(label = stringResource(R.string.phone_ip), value = ui.phoneIp, mono = true, divider = false)
    }
}

@Composable
private fun VmStatusTab(ui: StatusUiState) {
    val metrics = ui.metrics
    val charts = ui.charts
    val resource = stringResource(R.string.status_resource_vm)
    val vmCpuUnavailable = when {
        ui.vmState !is VmState.Running -> stringResource(R.string.status_vm_load_stopped)
        ui.vmLoadGraphUnavailable == "avf" -> stringResource(R.string.status_vm_load_avf_only)
        else -> null
    }
    val stopped = if (ui.vmState !is VmState.Running) stringResource(R.string.status_vm_load_stopped) else null
    val diskUsedPct = if (ui.storageSizeGb > 0) {
        metrics.vmDiskImageBytes.toDouble() / (ui.storageSizeGb.toLong() * 1024 * 1024 * 1024) * 100.0
    } else 0.0
    val lastDiskAct = charts.vmDiskActivity.lastOrNull() ?: 0f
    val availValue = if (ui.vmState is VmState.Running) "1" else "0"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PodroidTokens.Spacing.LG, vertical = PodroidTokens.Spacing.MD),
        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
    ) {
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_cpu),
            series = listOf(ChartSeries(charts.vmCpu, AzureBlue)),
            yMax = 100f,
            unavailable = vmCpuUnavailable,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_cpu_legend),
                    subtitle = resource,
                    value = ui.vmLoadPercent?.let { String.format("%.2f%%", it) }
                        ?: stringResource(R.string.status_vm_load_collecting),
                    color = AzureBlue,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_memory),
            series = listOf(ChartSeries(charts.vmRam, AzurePink)),
            unavailable = stopped,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_memory_legend),
                    subtitle = resource,
                    value = metrics.emulatorRssMb?.let { HostMetrics.formatMb(it) }
                        ?: stringResource(R.string.status_unavailable),
                    color = AzurePink,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_disk),
            series = listOf(ChartSeries(charts.vmDisk, AzureBlue)),
            unavailable = stopped,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_vm_disk),
                    subtitle = resource,
                    value = "${HostMetrics.formatGb(metrics.vmDiskImageBytes)} (${String.format("%.1f%%", diskUsedPct)})",
                    color = AzureBlue,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_disk_activity),
            series = listOf(ChartSeries(charts.vmDiskActivity, AzurePink)),
            unavailable = stopped,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_disk_write_legend),
                    subtitle = resource,
                    value = String.format("%.1f KB/sample", lastDiskAct),
                    color = AzurePink,
                ),
            ),
        )
        MetricChartCard(
            title = stringResource(R.string.status_metric_vm_availability),
            series = listOf(ChartSeries(charts.vmAvailability, AzureBlue)),
            yMax = 1f,
            timeHint = stringResource(R.string.status_chart_window),
            legends = listOf(
                MetricLegend(
                    label = stringResource(R.string.status_metric_availability_legend),
                    subtitle = resource,
                    value = availValue,
                    color = AzureBlue,
                ),
            ),
        )

        PodroidSectionLabel(stringResource(R.string.status_vm_details))
        PodroidListRow(
            label = stringResource(R.string.vm_status),
            value = vmStatusLabel(ui.vmState, ui.uptimeLabel, ui.bootStage),
        )
        PodroidListRow(
            label = stringResource(R.string.backend),
            value = backendLabel(ui.engineSelection, ui.backendId),
            mono = true,
        )
        PodroidListRow(label = stringResource(R.string.cpu_cores), value = "${ui.vmCpus}")
        PodroidListRow(
            label = stringResource(R.string.status_vm_ram_allocated),
            value = HostMetrics.formatMb(ui.vmRamMb.toLong()),
        )
        PodroidListRow(
            label = stringResource(R.string.bandwidth_limit),
            value = if (ui.bandwidthMbps <= 0) {
                stringResource(R.string.bandwidth_unlimited)
            } else {
                "${ui.bandwidthMbps} Mbps"
            },
        )
        PodroidListRow(
            label = stringResource(R.string.port_forwards),
            value = "${ui.portForwardCount}",
            divider = false,
        )
    }
}

@Composable
private fun formatRate(bytesPerSec: Float?): String {
    if (bytesPerSec == null) return stringResource(R.string.status_vm_load_collecting)
    return when {
        bytesPerSec >= 1024f * 1024f -> String.format("%.2f MiB/s", bytesPerSec / (1024f * 1024f))
        bytesPerSec >= 1024f -> String.format("%.1f KiB/s", bytesPerSec / 1024f)
        else -> String.format("%.0f B/s", bytesPerSec)
    }
}

@Composable
private fun vmStatusLabel(vmState: VmState, uptime: String?, bootStage: String): String = when (vmState) {
    is VmState.Running -> uptime?.let { "${stringResource(R.string.status_running)} · $it" }
        ?: stringResource(R.string.status_running)
    is VmState.Starting -> bootStage.ifEmpty { stringResource(R.string.status_starting) }
    is VmState.Error -> stringResource(R.string.status_error)
    else -> stringResource(R.string.status_stopped)
}

@Composable
private fun backendLabel(selection: EngineSelection, activeId: String): String = when (selection) {
    EngineSelection.AUTO -> "${stringResource(R.string.auto)} ($activeId)"
    EngineSelection.AVF -> stringResource(R.string.avf_kvm)
    EngineSelection.QEMU -> stringResource(R.string.qemu_tcg)
}
