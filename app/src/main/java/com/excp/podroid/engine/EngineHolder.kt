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
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.avf.AvfCapabilities
import com.excp.podroid.engine.avf.AvfDiagnostics
import com.excp.podroid.engine.avf.AvfEngine
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
) : VmEngine {

    // Single-threaded dispatcher: confines every appliedRules read-modify-write
    // (swap-reset + diff loop) to one thread so the @Volatile field can't be
    // torn by a swap landing between the diff loop's read and write.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    // First pick is resolved OFF the main thread: getEngineSelectionSnapshot()
    // is a DataStore disk read and pick() does a binder-IPC AVF probe — running
    // either in the field initializer (on whatever thread first injects this
    // @Singleton, i.e. the main thread) is an ANR risk. We seed _currentFlow
    // with a side-effect-free QEMU singleton (its ctor starts no VM) and replace
    // it with the real pick once firstPick resolves. start() awaits firstPick
    // before delegating, so the first Start can NEVER run the seed by mistake.
    private val firstPick: Deferred<VmEngine> =
        scope.async { pick(settings.getEngineSelectionSnapshot()) }

    // start() runs on the Service's IO thread, not the holder scope, so the
    // first-pick publish can be raced by the init coroutine. CAS makes it land
    // exactly once; both callers pass the same resolved firstPick value anyway.
    private val firstPickPublished = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _currentFlow: MutableStateFlow<VmEngine> =
        MutableStateFlow(qemuProvider.get())
    val currentFlow: StateFlow<VmEngine> = _currentFlow.asStateFlow()
    private val current: VmEngine get() = _currentFlow.value

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
                    // Entering Running: launchRules are already baked into the
                    // launch cmdline, so treat them as applied. Unchanged boot →
                    // added/removed empty; a rule removed mid-boot → removed.
                    appliedRules = launchRules
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

    private fun pick(sel: EngineSelection): VmEngine {
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
        return when {
            sel == EngineSelection.QEMU -> qemuProvider.get()
            // Forced AVF, but AVF can't run here → transparent QEMU fallback
            // instead of an Error state. Protected-only keeps its dedicated log.
            sel == EngineSelection.AVF && !avfUsable -> {
                if (capsChoice is AvfCapabilities.ProtectedVmChoice.Unsupported) {
                    android.util.Log.w(
                        TAG,
                        "AVF forced but device is protected-only; falling back to QEMU. " +
                            "caps=${probe.capabilitiesRaw}(${probe.capabilitiesDecoded})"
                    )
                } else {
                    android.util.Log.w(
                        TAG,
                        "AVF forced but unavailable; falling back to QEMU. " +
                            "feature=${probe.featureSupported} " +
                            "perms=${probe.managePermissionGranted}/${probe.customPermissionGranted} " +
                            "reachable=${probe.serviceReachable} " +
                            "customVm=${probe.customVmConfigSupported} " +
                            "caps=${probe.capabilitiesRaw}(${probe.capabilitiesDecoded})"
                    )
                }
                qemuProvider.get()
            }
            sel == EngineSelection.AVF -> avfProvider.get()
            avfUsable -> avfProvider.get()
            else -> qemuProvider.get()
        }.also {
            android.util.Log.i(
                TAG,
                "pick: selection=$sel feature=${probe.featureSupported} " +
                    "perms=${probe.managePermissionGranted}/${probe.customPermissionGranted} " +
                    "customVm=${probe.customVmConfigSupported} " +
                    "caps=${probe.capabilitiesRaw}(${probe.capabilitiesDecoded}) → ${it.backendId}"
            )
        }
    }

    /**
     * Publish the first picked engine into _currentFlow exactly once. Idempotent
     * and safe to call from both the init coroutine and the first start(): the
     * loser is a no-op. We only replace the seed (never an engine a swap already
     * installed) by gating on firstPickPublished and keeping the swap observer
     * the sole writer thereafter — both run on the single-thread scope, so the
     * flag check + write don't interleave.
     */
    private fun publishFirstPick(first: VmEngine) {
        if (!firstPickPublished.compareAndSet(false, true)) return
        if (first !== _currentFlow.value) {
            android.util.Log.i(TAG, "first pick: ${_currentFlow.value.backendId} → ${first.backendId}")
            // Fresh selection: this engine has not been started this cycle, so its
            // surfaced state is normalized to Idle until start() runs.
            startedEngine = null
            _currentFlow.value = first
        }
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
        val next = pick(newSel)
        if (next === currentFlow.value) return
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

    // ── VmEngine: flows that follow the currently-selected engine ──────────
    // A freshly (re)selected engine that hasn't been started this cycle has its
    // retained terminal state (Stopped/Error) normalized to Idle, so a swap-back
    // to a previously-Stopped @Singleton engine doesn't republish an actionable
    // Stopped that PodroidService would treat as a teardown signal. Once start()
    // marks the engine started, its real state (including a later Stopped) passes
    // through unchanged.
    override val state: StateFlow<VmState> = currentFlow
        .flatMapLatest { eng -> eng.state.map { st -> normalizeCycleState(eng, st) } }
        .stateIn(scope, SharingStarted.Eagerly, VmState.Idle)

    override val bootStage: StateFlow<String> = currentFlow
        .flatMapLatest { it.bootStage }
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

    override suspend fun start(portForwards: List<PortForwardRule>, config: VmConfig) {
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
                .getOrElse { _currentFlow.value }
        }
        publishFirstPick(picked)
        // Capture the launch set for the diff loop's →Running seeding, and mark
        // this engine started so its state passes through un-normalized.
        launchRules = portForwards.toSet()
        startedEngine = current
        current.start(portForwards, config)
    }
    override fun stop() = current.stop()
    override fun createTerminalSession(client: TerminalSessionClient) =
        current.createTerminalSession(client)
    override suspend fun addPortForward(rule: PortForwardRule) = current.addPortForward(rule)
    override suspend fun removePortForward(rule: PortForwardRule) = current.removePortForward(rule)
    override fun openHostTransport(): com.excp.podroid.engine.hostbridge.HostTransport? =
        current.openHostTransport()
    override fun diagnosticsReport(): String = current.diagnosticsReport()

    /**
     * Normalize a per-engine state for the holder's surfaced [state] flow. A
     * freshly (re)selected @Singleton engine that has not been started this
     * cycle reports its retained terminal state (Stopped/Error); surface Idle
     * for those until start() marks it [startedEngine]. Any non-terminal state,
     * and every state of the started engine, passes through unchanged.
     */
    private fun normalizeCycleState(eng: VmEngine, st: VmState): VmState =
        if (eng !== startedEngine && (st is VmState.Stopped || st is VmState.Error)) {
            VmState.Idle
        } else {
            st
        }

    companion object {
        private const val TAG = "EngineHolder"

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
