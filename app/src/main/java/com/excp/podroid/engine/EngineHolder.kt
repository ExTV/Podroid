/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * EngineHolder — the single @Singleton VmEngine Hilt hands out. Internally
 * swaps between QemuEngine and AvfEngine when the user changes Settings →
 * Backend; the swap only takes effect once the current VM is Stopped/Idle/Error
 * so a running VM is never killed mid-flight.
 *
 * Also owns the cross-cutting rule-diff loop: it watches
 * PortForwardRepository.rules and dispatches add/remove to whichever engine
 * is current. This removes the special-case calls SettingsViewModel used to
 * make directly into QemuEngine.qmpClient.
 */
package com.excp.podroid.engine

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.annotation.StringRes
import com.excp.podroid.BuildConfig
import com.excp.podroid.R
import com.excp.podroid.data.repository.LanguageManager
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.avf.AvfCapabilities
import com.excp.podroid.engine.avf.AvfDiagnostics
import com.excp.podroid.engine.avf.AvfEngine
import com.excp.podroid.engine.avf.AvfReflect
import com.excp.podroid.engine.avf.AvfReport
import com.excp.podroid.engine.uml.UmlCommandBuilder
import com.excp.podroid.engine.uml.UmlDemotion
import com.excp.podroid.engine.uml.UmlEngine
import com.excp.podroid.engine.uml.UmlExitClass
import com.excp.podroid.engine.uml.UmlProbe
import com.excp.podroid.engine.uml.UmlProbeKey
import com.excp.podroid.engine.uml.UmlProbeStore
import com.excp.podroid.engine.uml.UmlProcessTree
import com.excp.podroid.engine.uml.UmlInfo
import com.excp.podroid.engine.uml.UmlStatus
import com.excp.podroid.engine.uml.shouldPersistProbe
import com.excp.podroid.engine.uml.umlDemotionReason
import com.excp.podroid.engine.uml.umlProbeKey
import com.excp.podroid.engine.uml.umlInfo
import com.excp.podroid.engine.uml.umlStatus
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class EngineHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val portForwards: PortForwardRepository,
    private val qemuProvider: Provider<QemuEngine>,
    private val avfProvider: Provider<AvfEngine>,
    private val umlProvider: Provider<UmlEngine>,
    private val umlProbe: Provider<UmlProbe>,
    private val umlProbeStore: UmlProbeStore,
) : VmEngine {

    // Single-threaded dispatcher: confines every appliedRules read-modify-write
    // (swap-reset + diff loop) to one thread so the @Volatile field can't be
    // torn by a swap landing between the diff loop's read and write.
    private val holderDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + holderDispatcher)

    // First pick is resolved OFF the main thread: getEngineSelectionSnapshot()
    // is a DataStore disk read and pick() does a binder-IPC AVF probe — running
    // either in the field initializer (on whatever thread first injects this
    // @Singleton, i.e. the main thread) is an ANR risk. We seed _currentFlow
    // with a side-effect-free QEMU singleton (its ctor starts no VM) and replace
    // it with the real pick once firstPick resolves. start() awaits firstPick
    // before delegating, so the first Start can NEVER run the seed by mistake.
    private val firstPick: Deferred<PickResult> =
        scope.async { pick(settings.getEngineSelectionSnapshot()) }

    // start() runs on the Service's IO thread, not the holder scope, so the
    // first-pick publish can be raced by the init coroutine. CAS makes it land
    // exactly once; both callers pass the same resolved firstPick value anyway.
    private val firstPickPublished = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _currentFlow: MutableStateFlow<VmEngine> =
        MutableStateFlow(qemuProvider.get())
    val currentFlow: StateFlow<VmEngine> = _currentFlow.asStateFlow()
    private val current: VmEngine get() = _currentFlow.value

    /** Cosmetic mirror of [currentFlow]'s backendId, for UI surfaces that only
     *  need the id (not the whole engine) and want it as a StateFlow they can
     *  collect reactively instead of reading [VmEngine.backendId] once. */
    val backendIdFlow: StateFlow<String> = _currentFlow
        .map { it.backendId }
        .stateIn(scope, SharingStarted.Eagerly, _currentFlow.value.backendId)

    // Non-null while the current pick's backend differs from what the raw
    // selection would naively suggest: either a forced-AVF selection fell
    // back to QEMU, or an AUTO selection avoided AVF because the OEM build
    // has networking compiled out (FallbackReason.NETWORK_DISABLED, #43).
    // The @StringRes ID of the reason (see FallbackReason / pick()). Keep the
    // ID unresolved until the UI renders it so a locale change cannot leave
    // this in-memory state with text from the old locale. Updated at the same
    // two points _currentFlow is (publishFirstPick, trySwap) so it always
    // describes the pick that produced the active engine, even on a no-op
    // swap where the engine instance doesn't change but the selection (and
    // therefore the reason) does.
    private val _backendFallback = MutableStateFlow<Int?>(null)
    val backendFallback: StateFlow<Int?> = _backendFallback.asStateFlow()

    // True when AVF can actually start on this device, as of the last pick.
    // Settings uses it to tell a UML user that AVF would be faster.
    private val _avfUsable = MutableStateFlow(false)
    val avfUsable: StateFlow<Boolean> = _avfUsable.asStateFlow()

    /** Last rule set we pushed into the engine, used to compute add/remove diffs. */
    @Volatile private var appliedRules: Set<PortForwardRule> = emptySet()

    // Rules handed to the current engine's start(): these are baked into QEMU's
    // launch cmdline at cold start, so they are already live the instant the VM
    // reaches Running. The diff loop seeds appliedRules from this set on the
    // →Running edge so an unchanged boot is a no-op (no re-add noise) and a rule
    // removed during the boot window is still torn down.
    @Volatile private var launchRules: Set<PortForwardRule> = emptySet()

    // Always-on forwards that PodroidService injects into the launch set but
    // never persists to the DataStore (SSH 9922→22 when enabled, plus the X11
    // viewer's VNC 5900 and audio 4713). They live in launchRules but not in the
    // DataStore `rules`, so a diff of `desired = rules` computes them as removed
    // on the first →Running edge and tears them down — racing the engine's own
    // initial setup, which surfaced as intermittent SSH/VNC/audio dropout.
    // Captured once on the →Running edge (launchRules - rules) and folded into
    // `desired` so they are never removed; explicit user rules still diff live.
    @Volatile private var implicitRules: Set<PortForwardRule> = emptySet()

    // The engine instance started during the current selection cycle. @Singleton
    // engines retain their last terminal state (e.g. Stopped) across a swap, so a
    // freshly (re)selected engine that hasn't been started this cycle must surface
    // Idle instead of that stale terminal value (else PodroidService treats the
    // republished Stopped as actionable and tears down). Cleared on every fresh
    // publish; set when start() runs.
    @Volatile private var startedEngine: VmEngine? = null

    // Service-side preflight can fail before a concrete backend is started.
    // Keep that failure at the holder layer so it survives the asynchronous
    // first backend pick and is visible through the same state flow the UI uses.
    private val _startFailure = MutableStateFlow<VmState.Error?>(null)

    // ── UML (selection == UML only; every field below stays idle otherwise) ──
    // Holder-level state shown instead of the engine's while the holder itself
    // is busy: Starting during the probe and a fallback (so PodroidService does
    // not tear down between two engines), Stopped when a stop lands there.
    private val _overlay = MutableStateFlow<VmState?>(null)
    private val _stageOverlay = MutableStateFlow<String?>(null)

    // One lock for the UML probe and every VM start: a probe never runs while
    // a start is in flight, and no start begins while a probe runs.
    private val vmMutex = Mutex()
    private val startsInFlight = AtomicInteger(0)
    @Volatile private var stopRequested = false
    @Volatile private var umlStartedThisProcess = false

    // The selection of the most recent pick(), so start() can tell a UML
    // selection without another DataStore read on the QEMU/AVF path.
    @Volatile private var lastPickedSelection: EngineSelection? = null

    private val _umlFallbackNotices = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Backend id ("qemu"/"avf") each time a failing UML VM was restarted on it. */
    val umlFallbackNotices: SharedFlow<String> = _umlFallbackNotices.asSharedFlow()

    init {
        // 0. Publish the real first pick as soon as it resolves off-main, so the
        //    delegate flows (state/bootStage/consoleText via flatMapLatest) and
        //    the cosmetic backendId reads converge onto the correct engine on
        //    cold start without anyone blocking the main thread. start() also
        //    awaits firstPick directly, so an early Start beats no race here.
        // runCatching: start() consumes firstPick's result, so an await() throw here
        // would otherwise be an uncaught exception on the scope thread.
        scope.launch {
            runCatching { publishFirstPick(firstPick.await()) }
                .onFailure { android.util.Log.w(TAG, "init first-pick failed; start() will retry", it) }
        }

        // 1. Backend swap observer — drops the first emit so we don't re-pick
        //    on cold start. Waits for Stopped/Idle/Error so we never kill a
        //    running VM (the Settings UI also disables the chips, but defend
        //    in depth).
        scope.launch {
            settings.engineSelection
                .drop(1)
                .distinctUntilChanged()
                .collect { newSel -> trySwap(newSel) }
        }

        // 2. Live rule-diff observer. Re-subscribed across engine swaps via
        //    flatMapLatest. Combined with state so we only push diffs when
        //    the VM is Running (initial rules go via start()).
        scope.launch {
            // Tracks the previous emission's Running-ness so we can seed
            // appliedRules from launchRules on the non-Running → Running edge
            // exactly once per boot, not on every Running emission.
            var wasRunning = false
            currentFlow.flatMapLatest { eng ->
                portForwards.rules.combine(eng.state) { rules, state ->
                    Triple(eng, rules.toSet(), state)
                }
            }.collect { (eng, rules, state) ->
                if (state !is VmState.Running) {
                    appliedRules = emptySet()
                    implicitRules = emptySet()
                    wasRunning = false
                    return@collect
                }
                if (!wasRunning) {
                    // Entering Running: seed from what the engine actually applied
                    // while launching, which is NOT always the whole launch set.
                    // QEMU keeps user rules off its cmdline (one hostfwd it cannot
                    // bind kills the VM), so those arrive here as `added` and go in
                    // over QMP. Seeding from launchRules instead would mark them
                    // applied and they would silently never exist.
                    appliedRules = eng.rulesAppliedAtLaunch(launchRules)
                    // The implicit always-on forwards (in launchRules but never
                    // persisted to the DataStore) must never be torn down by the
                    // diff. Capture them once here so they can be folded into
                    // `desired` below — without this they'd be computed as
                    // removed and race the engine's initial setup.
                    implicitRules = launchRules - rules
                    wasRunning = true
                }
                // Persisted user rules plus the implicit always-on forwards, so
                // SSH/VNC/audio are never in `removed`. User rules still add and
                // remove live as the DataStore changes.
                val desired = rules + implicitRules
                val (added, removed) = computeRuleDiff(applied = appliedRules, desired = desired)
                // Track what is actually live so a transient add/remove failure
                // doesn't permanently desync appliedRules from the engine: a
                // failed add isn't recorded as applied (retried next diff), a
                // failed remove stays recorded (retried next diff). Removes go
                // first so a same-port churn frees the host port before re-add.
                val live = appliedRules.toMutableSet()
                for (r in removed) {
                    try {
                        eng.removePortForward(r)
                        live.remove(r)
                    } catch (c: CancellationException) {
                        throw c // a swap cancelled the inner collector; abort, don't persist a stale set
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "removePortForward failed for $r", e)
                    }
                }
                for (r in added) {
                    try {
                        eng.addPortForward(r)
                        live.add(r)
                    } catch (c: CancellationException) {
                        throw c
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "addPortForward failed for $r", e)
                    }
                }
                appliedRules = live
            }
        }
    }

    /** Result of a pick: the chosen engine plus, when a forced-AVF selection
     *  fell back to QEMU, the @StringRes ID of its reason for [backendFallback]. */
    private data class PickResult(
        val engine: VmEngine,
        @StringRes val fallbackReasonResId: Int?,
    )

    private suspend fun pick(sel: EngineSelection): PickResult {
        lastPickedSelection = sel
        val probe = AvfDiagnostics.probe(context)
        val capsChoice = AvfCapabilities.choose(probe.capabilitiesRaw)
        // avfUsable = AVF can actually start here. serviceReachable is the new
        // gate: capabilitiesRaw is 0 (→ Unknown, NOT Unsupported) whenever the
        // system service is unreachable, so without this an unreachable AVF
        // passed the "!is Unsupported" check and AvfEngine.start() errored out
        // instead of falling back to QEMU. On a working AVF device all four
        // conjuncts are true (feature+perms+reachable, caps=NonProtected), so
        // this is unchanged for the happy path.
        val avfUsable = probe.featureSupported &&
            probe.managePermissionGranted &&
            probe.customPermissionGranted &&
            probe.serviceReachable &&
            probe.customVmConfigSupported &&
            capsChoice !is AvfCapabilities.ProtectedVmChoice.Unsupported
        val protectedOnly = capsChoice is AvfCapabilities.ProtectedVmChoice.Unsupported
        _avfUsable.value = avfUsable
        // Only probed when it can actually change the outcome: AUTO (or UML,
        // whose fallback is the AUTO pick) with AVF otherwise usable
        // (decideBackend only reads this flag on the AUTO+avfUsable branch).
        // A forced QEMU/AVF selection, or an AUTO selection
        // that would pick QEMU anyway, skips the extra binder call. Missing
        // method/throw already resolves to "available" inside
        // isNetworkFeatureEnabled.
        val networkAvailable = if (shouldProbeNetworkFeature(sel, avfUsable)) {
            runCatching { AvfReflect.isNetworkFeatureEnabled(AvfReflect.manager(context)) }
                .getOrDefault(true)
        } else true
        val umlStatus = if (sel == EngineSelection.UML) currentUmlStatus() else UmlStatus.NOT_PROBED
        val (backendId, decidedReason) = decideBackend(sel, avfUsable, protectedOnly, networkAvailable, umlStatus)
        // Not probed yet is not a failure: start() probes before the first UML boot.
        val fallbackReason =
            if (sel == EngineSelection.UML && umlStatus == UmlStatus.NOT_PROBED) null else decidedReason
        // Logging unchanged from the old inline branch: protected-only keeps its
        // dedicated message, everything else logs the full prerequisite dump.
        when (fallbackReason) {
            FallbackReason.PROTECTED_ONLY -> android.util.Log.w(
                TAG,
                "AVF forced but device is protected-only; falling back to QEMU. " +
                    "caps=${probe.capabilitiesRaw}(${probe.capabilitiesDecoded})"
            )
            FallbackReason.UNAVAILABLE -> android.util.Log.w(
                TAG,
                "AVF forced but unavailable; falling back to QEMU. " +
                    "feature=${probe.featureSupported} " +
                    "perms=${probe.managePermissionGranted}/${probe.customPermissionGranted} " +
                    "reachable=${probe.serviceReachable} " +
                    "customVm=${probe.customVmConfigSupported} " +
                    "caps=${probe.capabilitiesRaw}(${probe.capabilitiesDecoded})"
            )
            FallbackReason.NETWORK_DISABLED -> android.util.Log.w(
                TAG,
                "AVF networking compiled out by this device's OEM build " +
                    "(isFeatureEnabled(com.android.kvm.NETWORK)=false); AUTO falling back to QEMU."
            )
            FallbackReason.UML_PROBE_FAILED, FallbackReason.UML_DEMOTED -> android.util.Log.w(
                TAG,
                "UML selected but not usable ($fallbackReason); falling back to $backendId."
            )
            null -> {}
        }
        val engine = when (backendId) {
            "avf" -> avfProvider.get()
            "uml" -> umlProvider.get()
            else -> qemuProvider.get()
        }
        android.util.Log.i(
            TAG,
            "pick: selection=$sel feature=${probe.featureSupported} " +
                "perms=${probe.managePermissionGranted}/${probe.customPermissionGranted} " +
                "customVm=${probe.customVmConfigSupported} " +
                "caps=${probe.capabilitiesRaw}(${probe.capabilitiesDecoded}) " +
                (if (sel == EngineSelection.UML) "uml=$umlStatus " else "") + "→ ${engine.backendId}"
        )
        val fallbackReasonResId = fallbackReason?.let { fallbackReasonResId(it, probe) }
        return PickResult(engine, fallbackReasonResId)
    }

    /**
     * Publish the first picked engine into _currentFlow exactly once. Idempotent
     * and safe to call from both the init coroutine and the first start(): the
     * loser is a no-op. We only replace the seed (never an engine a swap already
     * installed) by gating on firstPickPublished and keeping the swap observer
     * the sole writer thereafter — both run on the single-thread scope, so the
     * flag check + write don't interleave.
     */
    private fun publishFirstPick(result: PickResult) {
        if (!firstPickPublished.compareAndSet(false, true)) return
        val first = result.engine
        if (first !== _currentFlow.value) {
            android.util.Log.i(TAG, "first pick: ${_currentFlow.value.backendId} → ${first.backendId}")
            // Fresh selection: this engine has not been started this cycle, so its
            // surfaced state is normalized to Idle until start() runs.
            startedEngine = null
            _currentFlow.value = first
        }
        _backendFallback.value = result.fallbackReasonResId
    }

    private suspend fun trySwap(newSel: EngineSelection) {
        // The swap observer drops emit #0, so it never fires before the first
        // pick is published; firstPick is already resolved by the time a user
        // changes the backend chip. Defensive: also wait for a swappable state
        // even though Settings UI gates chips.
        currentFlow.value.state.first {
            it is VmState.Stopped || it is VmState.Idle || it is VmState.Error
        }
        // A swap is the authoritative selection from here on. Mark first-pick
        // published so a late init/start publish (firstPick that hadn't resolved
        // when this swap fired — only possible if the user changed the backend
        // within ~ms of cold launch) can never clobber the swapped engine.
        firstPickPublished.set(true)
        // Serialized with the UML probe and VM starts, so a selection change
        // cannot swap the engine underneath a probe that is about to start one.
        vmMutex.withLock { install(pick(newSel)) }
    }

    /** Make [result] the current pick. Runs on the holder thread (appliedRules). */
    private suspend fun install(result: PickResult) = withContext(holderDispatcher) {
        // Update the surfaced reason even on a no-op engine swap: forcing AVF
        // on an already-QEMU device changes the selection (and therefore the
        // reason) without changing the engine instance.
        _backendFallback.value = result.fallbackReasonResId
        val next = result.engine
        if (next === currentFlow.value) return@withContext
        // NOTE: AvfEngine.stop() flips state to Stopped before cleanup() finishes
        // (socket delete + coroutine cancel), so publishing `next` here can
        // briefly overlap the old engine's teardown. This is the lower-risk
        // choice: the @Singleton engines mean `next` is never a fresh instance,
        // the UI gates the chips while Running/Starting, and no two VMs run at
        // once (state is already terminal). Forcing stop()+cleanup ordering would
        // require touching the engine classes (out of scope) and risks the
        // happy-path swap. The residual window is teardown-only, not dual-run.
        android.util.Log.i(TAG, "swap: ${currentFlow.value.backendId} → ${next.backendId}")
        appliedRules = emptySet()
        // Fresh selection: the swapped-in @Singleton engine may retain a stale
        // terminal state from a prior cycle; clear the marker so the state flow
        // surfaces Idle until this engine is started again.
        startedEngine = null
        _currentFlow.value = next
    }

    override val runningSinceMs: Long? get() = current.runningSinceMs
    override fun emulatorRssMb(): Long? = current.emulatorRssMb()
    override fun emulatorPid(): Int? = current.emulatorPid()

    // ── VmEngine: flows that follow the currently-selected engine ──────────
    // A freshly (re)selected engine that hasn't been started this cycle has its
    // retained terminal state (Stopped/Error) normalized to Idle, so a swap-back
    // to a previously-Stopped @Singleton engine doesn't republish an actionable
    // Stopped that PodroidService would treat as a teardown signal. Once start()
    // marks the engine started, its real state (including a later Stopped) passes
    // through unchanged.
    override val state: StateFlow<VmState> = combine(
        currentFlow.flatMapLatest { eng ->
            eng.state.map { st -> surfaceState(eng, st) }
        },
        _startFailure,
        _overlay,
    ) { backendState, failure, overlay ->
        failure ?: overlay ?: backendState
    }.stateIn(scope, SharingStarted.Eagerly, VmState.Idle)

    override val bootStage: StateFlow<String> = combine(
        currentFlow.flatMapLatest { it.bootStage },
        _stageOverlay,
    ) { stage, overlay -> overlay ?: stage }
        .stateIn(scope, SharingStarted.Eagerly, "")

    override val consoleText: StateFlow<String> = currentFlow
        .flatMapLatest { it.consoleText }
        .stateIn(scope, SharingStarted.Eagerly, "")

    override val stopping: StateFlow<Boolean> = currentFlow
        .flatMapLatest { it.stopping }
        .stateIn(scope, SharingStarted.Eagerly, false)

    // ── VmEngine: imperative members — pass through to current engine ──────
    override val terminalSession: TerminalSession? get() = current.terminalSession
    override val backendId: String get() = current.backendId
    override val qmpClient: QmpClient? get() = current.qmpClient
    override var sessionClientDelegate: TerminalSessionClient?
        get() = current.sessionClientDelegate
        set(v) { current.sessionClientDelegate = v }

    override fun reportStartFailure(message: String) {
        _startFailure.value = VmState.Error(message)
    }

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
        _startFailure.value = null
        _overlay.value = null
        stopRequested = false
        // Guarantee the first Start runs on the correctly-picked engine even on a
        // fast cold launch where Start beats the init publish coroutine. start()
        // is always called off-main (PodroidService.launchPodroid → withContext
        // (Dispatchers.IO)), so awaiting the off-main first pick here is safe and
        // closes the seed→AVF race: the seed (QEMU) can never run by mistake.
        //
        // Recoverable first pick: firstPick is a one-shot Deferred. If its body
        // threw (e.g. a non-IOException DataStore read), awaiting it again would
        // rethrow forever and wedge every later Start. On failure, recompute the
        // pick here; if that also fails, fall back to the QEMU seed already in
        // _currentFlow so the VM stays startable rather than permanently broken.
        val picked = try {
            firstPick.await()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            android.util.Log.w(TAG, "first pick failed; recovering for start()", e)
            runCatching { pick(settings.getEngineSelectionSnapshot()) }
                .getOrElse { PickResult(_currentFlow.value, null) }
        }
        publishFirstPick(picked)
        // Capture the launch set for the diff loop's →Running seeding, and mark
        // this engine started so its state passes through un-normalized. Read
        // `current` once: a concurrent swap between the two reads would mark
        // one engine started while starting the other.
        launchRules = portForwards.toSet()
        val eng = vmMutex.withLock {
            if (lastPickedSelection == EngineSelection.UML && !prepareUml()) return
            startsInFlight.incrementAndGet()
            current
        }
        // Held across a UML fallback too (runEngine -> fallBackFromUml ->
        // runEngine), so anyVmActive() stays true through demote, await and restart.
        try {
            runEngine(eng, portForwards, config)
        } finally {
            startsInFlight.decrementAndGet()
        }
    }

    /**
     * Start [eng] and, when it is UML and the run ended as a UML failure, demote
     * UML and restart on the AUTO pick. [startsInFlight] is held by start()
     * for the whole call, including any fallback.
     */
    private suspend fun runEngine(eng: VmEngine, portForwards: List<PortForwardRule>, config: VmConfig) {
        // Only set on the UML paths (probe, fallback): a stop that landed while
        // the holder was busy has no engine process to stop, so honor it here.
        val holderBusy = _overlay.value != null
        var watcher: kotlinx.coroutines.Job? = null
        try {
            if (holderBusy && stopRequested) {
                _overlay.value = VmState.Stopped
                return
            }
            // No VM, UML included, may open storage.img while a UML host process
            // (this process's earlier run, or an orphan from a dead app process)
            // may hold it. Awaited before eng.start(), so a UML engine's own new
            // tree does not exist yet; the wait is bounded by its timeout.
            if (mustAwaitUmlTree(lastPickedSelection, UmlProcessTree.pids().isNotEmpty(), umlStartedThisProcess) &&
                !awaitUmlGone()
            ) {
                _overlay.value = null
                _startFailure.value = VmState.Error(str(R.string.uml_tree_survived))
                return
            }
            // Mark started in the same order as before: a concurrent swap between
            // two reads of `current` would mark one engine and start another.
            startedEngine = eng
            if (eng is UmlEngine) umlStartedThisProcess = true
            // Keep the overlay until the engine itself says Starting, so its
            // stale terminal state from an earlier cycle never surfaces.
            if (holderBusy) {
                watcher = scope.launch {
                    eng.state.first { it is VmState.Starting || it is VmState.Running }
                    _overlay.compareAndSet(VmState.Starting, null)
                }
            }
            eng.start(portForwards, config)
        } finally {
            watcher?.cancel()
            if (holderBusy) _overlay.compareAndSet(VmState.Starting, null)
        }
        if (eng is UmlEngine) {
            val outcome = eng.lastOutcome
            if (outcome != null && outcome.exitClass == UmlExitClass.DEMOTE) {
                fallBackFromUml(outcome, portForwards, config)
            }
        }
    }

    /**
     * Probe UML before its first boot for this key, record the verdict, then
     * re-pick. Runs under [vmMutex], only when the selection is UML. Returns
     * false, with the failure surfaced, when nothing should be started.
     */
    private suspend fun prepareUml(): Boolean = try {
        prepareUmlOrThrow()
        true
    } catch (c: CancellationException) {
        _overlay.value = VmState.Stopped
        throw c
    } catch (e: Exception) {
        failUmlHandling("UML preparation failed", e)
        false
    }

    /**
     * A DataStore write, a probe throw or a pick failure on a UML path: drop
     * the holder overlay and surface an Error so the service tears down and
     * the next start() (which clears both) begins clean.
     */
    private fun failUmlHandling(what: String, e: Exception) {
        android.util.Log.e(TAG, what, e)
        _stageOverlay.value = null
        _overlay.value = null
        _startFailure.value = VmState.Error(str(R.string.uml_backend_failed))
    }

    private suspend fun prepareUmlOrThrow() {
        // lastPickedSelection can be stale (a pick that predates a selection
        // change still in flight to the swap observer): never probe or boot
        // UML for a user who has since chosen something else.
        val selection = settings.getEngineSelectionSnapshot()
        if (selection != EngineSelection.UML) {
            install(pick(selection))
            return
        }
        val key = currentUmlKey()
        val status = umlStatus(key, umlProbeStore.probe.first(), umlProbeStore.demotion.first())
        if (status == UmlStatus.NOT_PROBED) {
            if (anyVmActive()) {
                android.util.Log.w(TAG, "UML not probed but a VM is active; falling back for this boot")
            } else {
                _overlay.value = VmState.Starting
                _stageOverlay.value = str(R.string.uml_probe_stage)
                try {
                    val record = umlProbe.get().run(key)
                    if (shouldPersistProbe(record)) {
                        umlProbeStore.record(record)
                    } else {
                        android.util.Log.w(TAG, "UML probe could not run (${record.reason}); falling back for this boot")
                    }
                } finally {
                    _stageOverlay.value = null
                }
            }
        }
        install(pick(EngineSelection.UML))
    }

    private fun anyVmActive(): Boolean =
        startsInFlight.get() > 0 || listOfNotNull(current, startedEngine).any {
            val st = it.state.value
            st is VmState.Starting || st is VmState.Running
        }

    /**
     * The UML run failed: record the demotion, wait until no UML process can
     * still hold storage.img, then restart on what AUTO would pick, with the
     * same launch rules and config. Data safety wins over availability: if
     * UML processes survive, nothing else is started.
     */
    private suspend fun fallBackFromUml(
        outcome: UmlEngine.RunOutcome,
        portForwards: List<PortForwardRule>,
        config: VmConfig,
    ) {
        _overlay.value = VmState.Starting
        val next = try {
            val reason = umlDemotionReason(outcome.exitCode, outcome.bootTimedOut, outcome.consoleTail)
            android.util.Log.w(TAG, "UML failed on this device ($reason); demoting and falling back")
            umlProbeStore.demote(UmlDemotion(currentUmlKey(), reason, System.currentTimeMillis()))
            if (!awaitUmlGone()) {
                _overlay.value = null
                _startFailure.value = VmState.Error(str(R.string.uml_tree_survived))
                return
            }
            vmMutex.withLock {
                val result = pick(EngineSelection.UML)
                check(result.engine !is UmlEngine) { "UML still picked after its demotion" }
                install(result)
                result.engine
            }
        } catch (c: CancellationException) {
            _overlay.value = VmState.Stopped
            throw c
        } catch (e: Exception) {
            failUmlHandling("UML fallback failed before restarting", e)
            return
        }
        if (!stopRequested) _umlFallbackNotices.tryEmit(next.backendId)
        try {
            runEngine(next, portForwards, config)
        } catch (c: CancellationException) {
            _overlay.value = VmState.Stopped
            throw c
        }
    }

    private suspend fun awaitUmlGone(): Boolean = withContext(Dispatchers.IO) {
        umlProvider.get().awaitUmlTreeGone(UML_TREE_GONE_TIMEOUT_MS).also {
            if (!it) android.util.Log.e(TAG, "UML processes survived; refusing to start another backend")
        }
    }

    /**
     * Settings "Try UML again": forget the demotion and the probe verdict, so
     * the next start probes again, and re-pick. Returns false, doing nothing,
     * while a VM is starting or running.
     */
    fun retryUml(): Boolean {
        if (!umlRetryAllowed(state.value)) return false
        scope.launch {
            // Re-checked: a start may have begun since the call.
            if (!umlRetryAllowed(state.value)) return@launch
            try {
                umlProbeStore.clearDemotion()
                umlProbeStore.clearProbe()
                trySwap(settings.getEngineSelectionSnapshot())
            } catch (e: java.io.IOException) {
                android.util.Log.e(TAG, "retryUml: could not reset the UML records", e)
            }
        }
        return true
    }

    /** Live UML status and reason for the current device/app/kernel key (Settings, control interface). */
    val umlInfo: Flow<UmlInfo> = combine(umlProbeStore.probe, umlProbeStore.demotion) { probe, demotion ->
        umlInfo(currentUmlKey(), probe, demotion)
    }.flowOn(Dispatchers.IO)

    private suspend fun currentUmlStatus(): UmlStatus =
        umlStatus(currentUmlKey(), umlProbeStore.probe.first(), umlProbeStore.demotion.first())

    /** Probe/demotion key: device build, app build, and the bundled kernel for this page size. */
    private fun currentUmlKey(): UmlProbeKey {
        val pageSize = Os.sysconf(OsConstants._SC_PAGESIZE)
        val kernel = UmlCommandBuilder.kernelFor(pageSize) ?: "none-$pageSize"
        val size = File(context.applicationInfo.nativeLibraryDir, kernel).length()
        return umlProbeKey(Build.FINGERPRINT, BuildConfig.VERSION_CODE.toLong(), kernel, size)
    }

    /** A string in the app's chosen language (the application context ignores it). */
    private fun str(id: Int): String =
        LanguageManager.wrapContextForLocale(context, LanguageManager.getSavedLanguage(context)).getString(id)

    override fun stop() {
        stopRequested = true
        current.stop()
    }
    override fun createTerminalSession(client: TerminalSessionClient) =
        current.createTerminalSession(client)
    override suspend fun addPortForward(rule: PortForwardRule) = current.addPortForward(rule)
    override suspend fun removePortForward(rule: PortForwardRule) = current.removePortForward(rule)
    override fun openHostTransport(): com.excp.podroid.engine.hostbridge.HostTransport? =
        current.openHostTransport()
    override fun diagnosticsReport(): String {
        val base = current.diagnosticsReport()
        if (lastPickedSelection != EngineSelection.UML) return base
        // Called from the diagnostic export on Dispatchers.IO; bounded reads.
        val (probe, demotion) = try {
            runBlocking {
                withTimeoutOrNull(UML_RECORD_READ_TIMEOUT_MS) {
                    umlProbeStore.probe.first() to umlProbeStore.demotion.first()
                } ?: (null to null)
            }
        } catch (e: java.io.IOException) {
            android.util.Log.w(TAG, "Could not read the UML records", e)
            null to null
        }
        return buildString {
            append(base)
            appendLine("uml selected; key: ${currentUmlKey()}")
            appendLine("uml probe: ${probe?.let { "passed=${it.passed} reason=${it.reason} key=${it.key}" } ?: "(none recorded)"}")
            appendLine("uml demotion: ${demotion?.let { "reason=${it.reason} atMs=${it.atMs} key=${it.key}" } ?: "(none)"}")
        }
    }

    /**
     * Normalize a per-engine state for the holder's surfaced [state] flow. A
     * freshly (re)selected @Singleton engine that has not been started this
     * cycle reports its retained terminal state (Stopped/Error); surface Idle
     * for those until start() marks it [startedEngine]. Any non-terminal state,
     * and every state of the started engine, passes through unchanged.
     */
    private fun surfaceState(eng: VmEngine, st: VmState): VmState {
        val normalized = normalizeCycleState(eng, st)
        // A UML run that ended as a UML failure is about to fall back. Its
        // outcome is written before its terminal state, so hide that state
        // here, synchronously: by the time the holder's own code runs, the
        // service would already have torn down on it.
        val failingUml = eng is UmlEngine && eng === startedEngine &&
            (normalized is VmState.Stopped || normalized is VmState.Error) &&
            eng.lastOutcome?.exitClass == UmlExitClass.DEMOTE
        return if (failingUml) VmState.Starting else normalized
    }

    private fun normalizeCycleState(eng: VmEngine, st: VmState): VmState =
        if (eng !== startedEngine && (st is VmState.Stopped || st is VmState.Error)) {
            VmState.Idle
        } else {
            st
        }

    companion object {
        private const val TAG = "EngineHolder"
        private const val UML_TREE_GONE_TIMEOUT_MS = 5_000L
        private const val UML_RECORD_READ_TIMEOUT_MS = 2_000L

        /** Coarse reason a forced-AVF selection fell back to QEMU, or (for
         *  NETWORK_DISABLED) an AUTO selection avoided AVF. Protected-only
         *  devices get their own value because pick() already logs them on a
         *  dedicated branch (a signed pvmfw kernel can never appear later). */
        enum class FallbackReason { UNAVAILABLE, PROTECTED_ONLY, NETWORK_DISABLED, UML_PROBE_FAILED, UML_DEMOTED }

        /**
         * Select the localized reason resource for a backend fallback. The
         * first failed prerequisite is reported, except protected-only and
         * network-disabled devices, which have their dedicated explanations.
         */
        @StringRes
        internal fun fallbackReasonResId(reason: FallbackReason, probe: AvfReport): Int = when {
            reason == FallbackReason.PROTECTED_ONLY -> R.string.backend_fallback_reason_protected_only
            reason == FallbackReason.NETWORK_DISABLED -> R.string.backend_fallback_reason_network
            reason == FallbackReason.UML_PROBE_FAILED -> R.string.backend_fallback_reason_uml_probe
            reason == FallbackReason.UML_DEMOTED -> R.string.backend_fallback_reason_uml_demoted
            !probe.featureSupported -> R.string.backend_fallback_reason_feature
            !probe.managePermissionGranted || !probe.customPermissionGranted ->
                R.string.backend_fallback_reason_permissions
            else -> R.string.backend_fallback_reason_service
        }

        /**
         * Whether a start (UML or not) must first wait for every UML host
         * process to be gone, so two VMs never hold storage.img at once: with
         * UML selected (a UML start or its fallback), when UML processes exist
         * (an orphan from an earlier app process), or when a UML VM ran in
         * this process.
         */
        fun mustAwaitUmlTree(
            selection: EngineSelection?,
            umlPidsPresent: Boolean,
            umlStartedThisProcess: Boolean,
        ): Boolean = selection == EngineSelection.UML || umlPidsPresent || umlStartedThisProcess

        /** "Try UML again" resets nothing while a VM is starting or running. */
        fun umlRetryAllowed(state: VmState): Boolean =
            state !is VmState.Starting && state !is VmState.Running

        /**
         * Pure gate for pick()'s reflective AvfReflect.isNetworkFeatureEnabled
         * probe (#43 follow-up, T5b): the probe is a binder IPC, so it's only
         * worth making when it can change decideBackend's outcome - AUTO
         * selection with AVF otherwise usable (avfUsable), or UML, whose
         * fallback must equal the AUTO pick. A forced QEMU/AVF selection
         * never consults networkAvailable, and AUTO already falls to QEMU
         * without it when AVF isn't usable, so skip the call there too.
         */
        fun shouldProbeNetworkFeature(selection: EngineSelection, avfUsable: Boolean): Boolean =
            (selection == EngineSelection.AUTO || selection == EngineSelection.UML) && avfUsable

        /**
         * Pure backend-selection decision, extracted from pick() so it's unit
         * testable without a Context or a binder-IPC AVF probe. Mirrors pick()'s
         * old inline `when` exactly - no change to which backend gets chosen,
         * only which id/reason pair describes the choice. The fine-grained
         * human reason (which prerequisite failed) is derived separately in
         * pick() from the raw probe, since this function only sees the already-
         * collapsed avfUsable/protectedOnly booleans.
         *
         * [networkAvailable] is #43's OEM networking check
         * (isFeatureEnabled(com.android.kvm.NETWORK)); it only steers the AUTO
         * path - a forced AVF selection stays AVF even when it's false, since
         * the user explicitly asked for that backend.
         */
        fun decideBackend(
            selection: EngineSelection,
            avfUsable: Boolean,
            protectedOnly: Boolean,
            networkAvailable: Boolean,
            umlStatus: UmlStatus = UmlStatus.NOT_PROBED,
        ): Pair<String, FallbackReason?> = when {
            selection == EngineSelection.QEMU -> "qemu" to null
            // Forced UML: only a passed probe picks it. Otherwise fall back to
            // whatever AUTO would pick and say why UML was skipped.
            selection == EngineSelection.UML && umlStatus == UmlStatus.PASSED -> "uml" to null
            selection == EngineSelection.UML -> {
                val fallbackId = decideBackend(EngineSelection.AUTO, avfUsable, protectedOnly, networkAvailable).first
                fallbackId to (if (umlStatus == UmlStatus.DEMOTED) FallbackReason.UML_DEMOTED else FallbackReason.UML_PROBE_FAILED)
            }
            // Forced AVF, but AVF can't run here → transparent QEMU fallback
            // instead of an Error state.
            selection == EngineSelection.AVF && !avfUsable ->
                "qemu" to (if (protectedOnly) FallbackReason.PROTECTED_ONLY else FallbackReason.UNAVAILABLE)
            selection == EngineSelection.AVF -> "avf" to null
            // AUTO would otherwise pick AVF, but the OEM build compiled out
            // networking - keep the VM online on QEMU instead of booting a
            // NIC-less AVF guest.
            avfUsable && !networkAvailable -> "qemu" to FallbackReason.NETWORK_DISABLED
            avfUsable -> "avf" to null
            else -> "qemu" to null
        }

        /**
         * Pure add/remove diff for the port-forward reconciliation loop:
         * added = rules to push that aren't applied yet, removed = applied rules
         * no longer desired. Extracted for unit testing the cold-start seeding
         * contract (unchanged boot → both empty; rule removed mid-boot → removed).
         */
        fun computeRuleDiff(
            applied: Set<PortForwardRule>,
            desired: Set<PortForwardRule>,
        ): Pair<Set<PortForwardRule>, Set<PortForwardRule>> =
            (desired - applied) to (applied - desired)
    }
}
