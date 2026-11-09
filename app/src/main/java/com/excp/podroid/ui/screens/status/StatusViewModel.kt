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
import com.excp.podroid.util.MetricHistory
import com.excp.podroid.util.NetworkRateSampler
import com.excp.podroid.util.NetworkUtils
import com.excp.podroid.util.PhoneCpuSampler
import com.excp.podroid.util.UptimeFormatter
import com.excp.podroid.util.VmLoadSampler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class StatusChartHistories(
    val phoneCpu: List<Float> = emptyList(),
    val phoneRam: List<Float> = emptyList(),
    val phoneStorage: List<Float> = emptyList(),
    val netIn: List<Float> = emptyList(),
    val netOut: List<Float> = emptyList(),
    val vmCpu: List<Float> = emptyList(),
    val vmRam: List<Float> = emptyList(),
    val vmDisk: List<Float> = emptyList(),
    val vmDiskActivity: List<Float> = emptyList(),
    val vmAvailability: List<Float> = emptyList(),
)

data class StatusUiState(
    val vmState: VmState = VmState.Idle,
    val backendId: String = "qemu",
    val engineSelection: EngineSelection = EngineSelection.AUTO,
    val bootStage: String = "",
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
    val phoneCpuPercent: Float? = null,
    val netInBps: Float? = null,
    val netOutBps: Float? = null,
    val liveTick: Long = 0L,
    val vmLoadGraphUnavailable: String? = null,
    val charts: StatusChartHistories = StatusChartHistories(),
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
    private val _vmLoadUnavailable = MutableStateFlow<String?>(null)
    private val _chartHistories = MutableStateFlow(StatusChartHistories())
    private val _liveTick = MutableStateFlow(0L)
    private val _phoneIp = MutableStateFlow("—")
    private val _phoneAddresses = MutableStateFlow<List<NetworkUtils.LocalAddress>>(emptyList())
    private val _phoneCpuPercent = MutableStateFlow<Float?>(null)
    private val _netInBps = MutableStateFlow<Float?>(null)
    private val _netOutBps = MutableStateFlow<Float?>(null)
    private val _liveActive = MutableStateFlow(false)
    private var lastDiskBytes = 0L
    // Guards pollOnce(): the perpetual loop below and setLiveActive()'s immediate resume poll
    // both dispatch to Dispatchers.IO and would otherwise race on the samplers' unsynchronized
    // lastX fields and on the read-modify-write of _chartHistories.value.
    private val pollMutex = Mutex()
    private val loadSampler = VmLoadSampler()
    private val phoneCpuSampler = PhoneCpuSampler()
    private val networkSampler = NetworkRateSampler()

    companion object {
        private const val POLL_MS = 500L
    }

    val uiState: StateFlow<StatusUiState> = combine(
        combine(
            engine.state,
            engine.bootStage,
            settingsRepository.vmRamMb,
            settingsRepository.vmCpus,
            settingsRepository.storageSizeGb,
        ) { vmState, bootStage, ram, cpus, storage ->
            arrayOf(vmState, bootStage, ram, cpus, storage)
        },
        combine(
            settingsRepository.bandwidthMbps,
            settingsRepository.loadBalanceEnabled,
            settingsRepository.engineSelection,
            portForwardRepository.rules,
            _metrics,
        ) { bandwidth, loadBal, engineSel, rules, metrics ->
            arrayOf(bandwidth, loadBal, engineSel, rules, metrics)
        },
        combine(
            _uptimeTick,
            _vmLoadPercent,
            _vmLoadUnavailable,
            _chartHistories,
            _phoneCpuPercent,
        ) { tick, pct, unavailable, charts, phoneCpu ->
            arrayOf(tick, pct, unavailable, charts, phoneCpu)
        },
        combine(
            _liveTick,
            _phoneIp,
            _phoneAddresses,
            _netInBps,
            _netOutBps,
        ) { live, ip, addrs, netIn, netOut ->
            arrayOf(live, ip, addrs, netIn, netOut)
        },
        engine.backendIdFlow,
    ) { a, b, c, d, backendId ->
        val vmState = a[0] as VmState
        val tick = c[0] as Long
        val charts = c[3] as StatusChartHistories
        StatusUiState(
            vmState = vmState,
            backendId = backendId,
            bootStage = a[1] as String,
            engineSelection = b[2] as EngineSelection,
            uptimeLabel = uptimeLabel(vmState, tick),
            phoneIp = d[1] as String,
            phoneAddresses = d[2] as List<NetworkUtils.LocalAddress>,
            vmRamMb = a[2] as Int,
            vmCpus = a[3] as Int,
            storageSizeGb = a[4] as Int,
            bandwidthMbps = b[0] as Int,
            loadBalanceEnabled = b[1] as Boolean,
            portForwardCount = (b[3] as List<*>).size,
            vmLoadPercent = c[1] as Float?,
            phoneCpuPercent = c[4] as Float?,
            netInBps = d[3] as Float?,
            netOutBps = d[4] as Float?,
            liveTick = d[0] as Long,
            vmLoadGraphUnavailable = c[2] as String?,
            charts = charts,
            metrics = b[4] as HostMetricsSnapshot,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatusUiState())

    init {
        // Phone address resolution is blocking I/O (NetworkInterface enumeration); seed it once
        // off the main thread so the IP shows immediately instead of waiting for the first resume.
        viewModelScope.launch(Dispatchers.IO) { refreshPhoneAddresses() }
        viewModelScope.launch {
            while (isActive) {
                if (_liveActive.value) {
                    withContext(Dispatchers.IO) { pollMutex.withLock { if (_liveActive.value) pollOnce() } }
                }
                delay(POLL_MS)
            }
        }
    }

    /** Starts/stops live sampling with the screen's lifecycle so it never runs in the background. */
    fun setLiveActive(active: Boolean) {
        _liveActive.value = active
        if (active) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { pollMutex.withLock { if (_liveActive.value) pollOnce() } }
            }
        } else {
            // Same pollMutex as pollOnce(): the pause reset and a resume poll are separate
            // coroutines that acquire this lock in no guaranteed order, so a fast pause-then-resume
            // could otherwise run the stale reset after the fresh poll, or a resume poll could land
            // after the reset. Re-checking _liveActive.value inside the lock makes the latest state
            // win regardless of which coroutine acquired the lock first.
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    pollMutex.withLock {
                        if (!_liveActive.value) {
                            loadSampler.reset()
                            phoneCpuSampler.reset()
                            networkSampler.reset()
                            lastDiskBytes = 0L
                            _chartHistories.value = StatusChartHistories()
                            _vmLoadPercent.value = null
                            _phoneCpuPercent.value = null
                            _netInBps.value = null
                            _netOutBps.value = null
                        }
                    }
                }
            }
        }
    }

    private fun pollOnce() {
        refreshMetrics()
        updatePhoneCharts()
        sampleVmLoad()
        sampleNetwork()
        refreshPhoneAddresses()
        _liveTick.value = System.currentTimeMillis()
        if (engine.state.value is VmState.Running) {
            _uptimeTick.value = System.currentTimeMillis()
        }
    }

    private fun refreshPhoneAddresses() {
        _phoneIp.value = NetworkUtils.localIpv4(context)
        _phoneAddresses.value = NetworkUtils.allLocalIpv4(context)
    }

    private fun updatePhoneCharts() {
        val m = _metrics.value
        val ramUsedPct = if (m.phoneTotalRamMb > 0) {
            ((m.phoneTotalRamMb - m.phoneAvailRamMb).toFloat() / m.phoneTotalRamMb * 100f)
        } else 0f
        val storageUsedPct = if (m.phoneStorageTotalGb > 0) {
            ((m.phoneStorageTotalGb - m.phoneStorageAvailGb) / m.phoneStorageTotalGb * 100.0).toFloat()
        } else 0f
        var h = _chartHistories.value.copy(
            phoneRam = MetricHistory.append(_chartHistories.value.phoneRam, ramUsedPct),
            phoneStorage = MetricHistory.append(_chartHistories.value.phoneStorage, storageUsedPct),
        )
        phoneCpuSampler.samplePercent()?.let { pct ->
            _phoneCpuPercent.value = pct
            h = h.copy(phoneCpu = MetricHistory.append(h.phoneCpu, pct))
        }
        _chartHistories.value = h
    }

    private fun sampleNetwork() {
        val sample = networkSampler.sample() ?: return
        _netInBps.value = sample.rxBytesPerSec
        _netOutBps.value = sample.txBytesPerSec
        val h = _chartHistories.value
        _chartHistories.value = h.copy(
            netIn = MetricHistory.append(h.netIn, sample.rxBytesPerSec),
            netOut = MetricHistory.append(h.netOut, sample.txBytesPerSec),
        )
    }

    fun refreshMetrics() {
        val storageImg = File(context.filesDir, "storage.img")
        val running = engine.state.value is VmState.Running
        val rss = if (running) engine.emulatorRssMb() else null
        _metrics.value = HostMetrics.snapshot(context, storageImg, rss)

        val diskBytes = if (storageImg.isFile) HostMetrics.diskFootprintBytes(storageImg) else 0L
        val diskDeltaKb = if (lastDiskBytes > 0L && diskBytes >= lastDiskBytes) {
            (diskBytes - lastDiskBytes) / 1024f
        } else 0f
        lastDiskBytes = diskBytes

        val h = _chartHistories.value
        _chartHistories.value = h.copy(
            vmDisk = if (running) MetricHistory.append(h.vmDisk, diskBytes.toFloat()) else h.vmDisk,
            vmDiskActivity = if (running) MetricHistory.append(h.vmDiskActivity, diskDeltaKb) else h.vmDiskActivity,
            vmRam = if (rss != null) MetricHistory.append(h.vmRam, rss.toFloat()) else h.vmRam,
            vmAvailability = MetricHistory.append(h.vmAvailability, if (running) 1f else 0f),
        )
    }

    private fun sampleVmLoad() {
        if (engine.state.value !is VmState.Running) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            _vmLoadUnavailable.value = null
            return
        }

        val pid = engine.emulatorPid()
        if (pid == null) {
            loadSampler.reset()
            _vmLoadPercent.value = null
            // "avf" means "no host PID to sample". UML has one, so a missing PID
            // there is a start-up race, not an unsupported backend: keep "collecting".
            _vmLoadUnavailable.value = if (engine.backendId == "uml") null else "avf"
            return
        }

        _vmLoadUnavailable.value = null
        // UML: the supervisor itself is idle; guest CPU time is in its descendants.
        val wholeTree = engine.backendId == "uml"
        val pct = loadSampler.sampleCpuPercent(pid, uiState.value.vmCpus, wholeTree) ?: return
        _vmLoadPercent.value = pct
        val h = _chartHistories.value
        _chartHistories.value = h.copy(vmCpu = MetricHistory.append(h.vmCpu, pct))
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
