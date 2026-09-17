package com.excp.podroid.ui.screens.status

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.EngineHolder
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.HostMetrics
import com.excp.podroid.util.HostMetricsSnapshot
import com.excp.podroid.util.NetworkUtils
import com.excp.podroid.util.UptimeFormatter
import com.excp.podroid.util.VmLoadSampler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class StatusUiState(
    val vmState: VmState = VmState.Idle,
    val backendId: String = "qemu",
    val engineSelection: EngineSelection = EngineSelection.AUTO,
    val uptimeLabel: String? = null,
    val phoneIp: String = "—",
    /** Every reachable IPv4 address, so a peer on a tether or second network is not left guessing. */
    val phoneAddresses: List<NetworkUtils.LocalAddress> = emptyList(),
    val vmRamMb: Int = 512,
    val vmCpus: Int = 2,
    val storageSizeGb: Int = 8,
    val bandwidthMbps: Int = 0,
    val loadBalanceEnabled: Boolean = false,
    val portForwardCount: Int = 0,
    val vmLoadPercent: Float? = null,
    val vmLoadHistory: List<Float> = emptyList(),
    val vmLoadGraphUnavailable: String? = null,
    val metrics: HostMetricsSnapshot = HostMetricsSnapshot(
        phoneTotalRamMb = 0,
        phoneAvailRamMb = 0,
        phoneCpuCores = 1,
        loadAvg1 = null,
        loadAvg5 = null,
        loadAvg15 = null,
        phoneStorageTotalGb = 0.0,
        phoneStorageAvailGb = 0.0,
        vmDiskImageBytes = 0L,
        emulatorRssMb = null,
    ),
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineHolder,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
) : ViewModel() {

    private val _metrics = MutableStateFlow(defaultMetrics())
    private val _uptimeTick = MutableStateFlow(0L)
    private val _vmLoadPercent = MutableStateFlow<Float?>(null)
    private val _vmLoadHistory = MutableStateFlow<List<Float>>(emptyList())
    private val _vmLoadUnavailable = MutableStateFlow<String?>(null)
    private val loadSampler = VmLoadSampler()

    val uiState: StateFlow<StatusUiState> = combine(
        combine(
            engine.state,
            settingsRepository.vmRamMb,
            settingsRepository.vmCpus,
            settingsRepository.storageSizeGb,
            settingsRepository.bandwidthMbps,
        ) { vmState, ram, cpus, storage, bandwidth ->
            arrayOf(vmState, ram, cpus, storage, bandwidth)
        },
        combine(
            settingsRepository.loadBalanceEnabled,
            settingsRepository.engineSelection,
            portForwardRepository.rules,
            _metrics,
            _uptimeTick,
        ) { loadBal, engineSel, rules, metrics, tick ->
            arrayOf(loadBal, engineSel, rules, metrics, tick)
        },
        combine(
            _vmLoadPercent,
            _vmLoadHistory,
            _vmLoadUnavailable,
        ) { pct, history, unavailable ->
            arrayOf(pct, history, unavailable)
        },
        engine.backendIdFlow,
    ) { a, b, c, backendId ->
        val vmState = a[0] as VmState
        val tick = b[4] as Long
        StatusUiState(
            vmState = vmState,
            backendId = backendId,
            engineSelection = b[1] as EngineSelection,
            uptimeLabel = uptimeLabel(vmState, tick),
            phoneIp = NetworkUtils.localIpv4(context),
            phoneAddresses = NetworkUtils.allLocalIpv4(context),
            vmRamMb = a[1] as Int,
            vmCpus = a[2] as Int,
            storageSizeGb = a[3] as Int,
            bandwidthMbps = a[4] as Int,
            loadBalanceEnabled = b[0] as Boolean,
            portForwardCount = (b[2] as List<*>).size,
            vmLoadPercent = c[0] as Float?,
            vmLoadHistory = c[1] as List<Float>,
            vmLoadGraphUnavailable = c[2] as String?,
            metrics = b[3] as HostMetricsSnapshot,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatusUiState())

    init {
        refreshMetrics()
        viewModelScope.launch {
            while (isActive) {
                delay(2_000)
                refreshMetrics()
                sampleVmLoad()
                if (engine.state.value is VmState.Running) {
                    _uptimeTick.value = System.currentTimeMillis()
                }
            }
        }
    }

    fun refreshMetrics() {
        val storageImg = File(context.filesDir, "storage.img")
        val rss = if (engine.state.value is VmState.Running) engine.emulatorRssMb() else null
        _metrics.value = HostMetrics.snapshot(context, storageImg, rss)
    }

    private fun sampleVmLoad() {
        if (engine.state.value !is VmState.Running) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            _vmLoadHistory.value = emptyList()
            _vmLoadUnavailable.value = null
            return
        }

        val pid = engine.emulatorPid()
        if (pid == null) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            _vmLoadHistory.value = emptyList()
            _vmLoadUnavailable.value = "avf"
            return
        }

        _vmLoadUnavailable.value = null
        val pct = loadSampler.sampleCpuPercent(pid, uiState.value.vmCpus) ?: return
        _vmLoadPercent.value = pct
        _vmLoadHistory.value = (_vmLoadHistory.value + pct).takeLast(VmLoadSampler.MAX_SAMPLES)
    }

    private fun defaultMetrics(): HostMetricsSnapshot {
        val storageImg = File(context.filesDir, "storage.img")
        return HostMetrics.snapshot(context, storageImg, null)
    }

    private fun uptimeLabel(vmState: VmState, tick: Long): String? {
        if (vmState !is VmState.Running) return null
        val since = engine.runningSinceMs ?: return null
        val secs = ((tick.takeIf { it > 0 } ?: System.currentTimeMillis()) - since) / 1000
        if (secs < 0) return null
        return UptimeFormatter.format(context, secs)
    }
}
