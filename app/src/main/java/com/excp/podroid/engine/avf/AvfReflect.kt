/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Thin reflective wrappers over android.system.virtualmachine.*. Each call is
 * one Method.invoke with setAccessible(true) — Android 14+ requires
 * HiddenApiBypass (installed at app onCreate) for these to resolve.
 *
 * Returning `Any` keeps the call sites untyped at the framework boundary;
 * call sites pass these handles back into AvfReflect rather than poking at
 * the underlying objects directly.
 */
package com.excp.podroid.engine.avf

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

object AvfReflect {

    private const val PKG = "android.system.virtualmachine"
    private val MGR by lazy { Class.forName("$PKG.VirtualMachineManager") }
    private val CFG by lazy { Class.forName("$PKG.VirtualMachineConfig") }
    private val CFG_B by lazy { Class.forName("$PKG.VirtualMachineConfig\$Builder") }
    private val CUSTOM by lazy { Class.forName("$PKG.VirtualMachineCustomImageConfig") }
    private val CUSTOM_B by lazy { Class.forName("$PKG.VirtualMachineCustomImageConfig\$Builder") }
    private val DISK by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$Disk") }.getOrNull() }
    private val GPU by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$GpuConfig") }.getOrNull() }
    private val GPU_B by lazy { runCatching { Class.forName("$PKG.VirtualMachineCustomImageConfig\$GpuConfig\$Builder") }.getOrNull() }

    fun manager(ctx: Context): Any {
        val m = Context::class.java.getMethod("getSystemService", Class::class.java)
        return m.invoke(ctx.applicationContext, MGR) ?: error("No VirtualMachineManager")
    }

    fun getOrCreate(mgr: Any, name: String, cfg: Any): Any =
        invokeDecl(mgr, "getOrCreate", String::class.java to name, CFG to cfg)
            ?: error("getOrCreate returned null")

    fun create(mgr: Any, name: String, cfg: Any): Any =
        invokeDecl(mgr, "create", String::class.java to name, CFG to cfg)
            ?: error("create returned null")

    /**
     * Attempts to replace an existing VM's config (analogous to `vm.config = config` in Kotlin).
     * Throws if AVF rejects the new config as incompatible — caller should
     * delete + recreate on that path.
     */
    fun setConfig(vm: Any, cfg: Any) {
        // getDeclaredMethod only finds a method declared on the exact runtime
        // class; on OS builds where setConfig is inherited from a supertype it
        // throws NoSuchMethodException, which the caller misreads as "config
        // rejected" and forces a needless delete+recreate every start. Fall back
        // to getMethod (searches supertypes) before concluding it's absent. A
        // genuine absence still throws — the caller's delete+recreate handles it.
        val m = (runCatching { vm.javaClass.getDeclaredMethod("setConfig", CFG) }
            .getOrNull() ?: vm.javaClass.getMethod("setConfig", CFG))
            .apply { isAccessible = true }
        m.invoke(vm, cfg)
    }

    fun delete(mgr: Any, name: String) {
        runCatching { invokeDecl(mgr, "delete", String::class.java to name) }
    }

    fun newVmConfigBuilder(ctx: Context): Any =
        CFG_B.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
            .newInstance(ctx)

    fun setProtectedVm(b: Any, value: Boolean) {
        invokeDecl(b, "setProtectedVm", Boolean::class.javaPrimitiveType!! to value)
    }

    /**
     * VirtualMachineManager.getCapabilities() — bitmask of
     * AvfCapabilities.CAPABILITY_*. Returns 0 if the method is absent on an
     * older AVF revision; that 0 maps to ProtectedVmChoice.Unknown (we then
     * attempt non-protected and let any framework exception surface). This
     * runCatching tolerates a MISSING method only — it is not used to hide a
     * failure on a method that exists.
     */
    fun getCapabilities(mgr: Any): Int =
        runCatching { invokeDecl(mgr, "getCapabilities") as? Int ?: 0 }.getOrDefault(0)

    /**
     * Applies the capability-driven protected-VM choice to a VM config builder.
     * Returns the choice so the caller can fall back to QEMU on Unsupported.
     *
     * On the Unknown path we call setProtectedVm(false) WITHOUT catching: if the
     * framework throws UnsupportedOperationException("Non-protected VMs are not
     * supported on this device.") that is the real #31 signal and must surface.
     * On Unsupported we do NOT call the setter — the caller must not proceed to
     * build() (which would throw the misleading "must be called explicitly").
     */
    fun applyProtectedVm(mgr: Any, builder: Any): AvfCapabilities.ProtectedVmChoice {
        val choice = AvfCapabilities.choose(getCapabilities(mgr))
        when (choice) {
            is AvfCapabilities.ProtectedVmChoice.NonProtected -> setProtectedVm(builder, false)
            is AvfCapabilities.ProtectedVmChoice.Unknown      -> setProtectedVm(builder, false)
            is AvfCapabilities.ProtectedVmChoice.Unsupported  -> Unit
        }
        return choice
    }

    fun setMemoryBytes(b: Any, bytes: Long) {
        invokeDecl(b, "setMemoryBytes", Long::class.javaPrimitiveType!! to bytes)
    }

    /** CPU topology values matching VirtualMachineConfig.CPU_TOPOLOGY_*. */
    private const val CPU_TOPOLOGY_ONE_CPU: Int = 0
    private const val CPU_TOPOLOGY_MATCH_HOST: Int = 1

    fun setNumCpus(b: Any, n: Int) {
        // AVF's setCpuTopology takes a CPU_TOPOLOGY_* constant — only 0 (one
        // CPU) or 1 (all host cores) are accepted; there is no fine-grained
        // count setter on the public Builder API. Map the user's requested
        // count: 1 → ONE_CPU; anything > 1 → MATCH_HOST. Where the device
        // supports it, installExplicitCpuCount later rewrites the topology to
        // an exact homogeneous count at the createVm boundary (issue #29).
        val topology = if (n <= 1) CPU_TOPOLOGY_ONE_CPU else CPU_TOPOLOGY_MATCH_HOST
        if (n > 1) android.util.Log.d(
            "AvfReflect",
            "builder topology for $n vCPUs → MATCH_HOST (exact count applied via createVm hook when supported)",
        )
        invokeDecl(b, "setCpuTopology", Int::class.javaPrimitiveType!! to topology)
    }

    // ---- Explicit vCPU count (issue #29) -----------------------------------
    //
    // MATCH_HOST makes virtmgr pass crosvm `--host-cpu-topology`, cloning the
    // host's heterogeneous big.LITTLE topology (real multi-cluster MPIDRs,
    // cpu-map, capacity-dmips-mhz, OPP tables) into the guest device tree.
    // Onlining those cross-cluster vCPUs resets some SoCs' guests in early
    // boot (Tensor G3/G4 — STOP_REASON_REBOOT before the console flushes).
    //
    // The virtualizationservice AIDL (Android 16+ com.android.virt) accepts
    // what the public Builder doesn't: CpuOptions.CpuTopology.cpuCount(N),
    // which virtmgr maps to crosvm `--cpus N` WITHOUT `--host-cpu-topology` —
    // a clean homogeneous topology (contiguous MPIDRs 0..N-1, none of the
    // heterogeneous DT). The framework's own toVsRawConfig only ever emits
    // cpuCount(1) or matchHost(true), so we rewrite the parcelable in flight:
    // VirtualMachine.run() fetches the IVirtualizationService AIDL interface
    // from VirtualizationService.mBinder and hands the fully-built raw config
    // to createVm(). Swapping a java.lang.reflect.Proxy into that field lets
    // every call delegate untouched except createVm, which first replaces
    // rawConfig.cpuOptions — all of run()'s fd/console/instance-id glue runs
    // unmodified.

    private const val VS_PKG = "android.system.virtualizationservice"

    /** Sticky poison: a runtime install/rewrite failure disables the hook so
     *  the relaunch path falls back to the nr_cpus ladder instead of looping. */
    @Volatile private var explicitCpuCountBroken = false

    /** Count the proxy applies at the next createVm (set per launch attempt). */
    @Volatile private var pendingCpuCount = 0

    private val explicitCpuCountProbe: Boolean by lazy {
        runCatching {
            Class.forName("$VS_PKG.CpuOptions\$CpuTopology")
                .getDeclaredMethod("cpuCount", Int::class.javaPrimitiveType)
            Class.forName("$VS_PKG.CpuOptions").getDeclaredField("cpuTopology")
            Class.forName("$VS_PKG.VirtualMachineRawConfig").getDeclaredField("cpuOptions")
            Class.forName("$VS_PKG.IVirtualizationService")
            Class.forName("$PKG.VirtualMachine").getDeclaredField("mVirtualizationService")
            Class.forName("$PKG.VirtualizationService").getDeclaredField("mBinder")
            true
        }.getOrElse {
            android.util.Log.i("AvfReflect",
                "explicit vCPU count unavailable on this AVF revision: ${it.message}")
            false
        }
    }

    /**
     * Whether this device's AVF stack accepts an exact vCPU count via the raw
     * AIDL (Android 16+). The probe exercises every reflective lookup the hook
     * needs, so a true here means installExplicitCpuCount is expected to work;
     * a runtime failure still poisons the hook for the rest of the process.
     */
    fun supportsExplicitCpuCount(): Boolean = !explicitCpuCountBroken && explicitCpuCountProbe

    /**
     * Arms the createVm rewrite for [vm] with an exact count of [n] vCPUs.
     * Call after the VirtualMachine is created and before run(). Returns false
     * (and poisons the hook) on failure — the caller should relaunch so the
     * cmdline-based nr_cpus fallback applies instead.
     */
    fun installExplicitCpuCount(vm: Any, n: Int): Boolean {
        if (!supportsExplicitCpuCount() || n < 1) return false
        return runCatching {
            val vsField = Class.forName("$PKG.VirtualMachine")
                .getDeclaredField("mVirtualizationService").apply { isAccessible = true }
            val vs = vsField.get(vm) ?: error("mVirtualizationService is null")
            val binderField = Class.forName("$PKG.VirtualizationService")
                .getDeclaredField("mBinder").apply { isAccessible = true }
            val real = binderField.get(vs) ?: error("mBinder is null")
            pendingCpuCount = n
            // A fresh VirtualizationService carries the raw AIDL stub; if this
            // one already holds our proxy (same instance is cached per process),
            // updating pendingCpuCount above is all that's needed.
            if (!Proxy.isProxyClass(real.javaClass)) {
                val ivs = Class.forName("$VS_PKG.IVirtualizationService")
                val proxy = Proxy.newProxyInstance(ivs.classLoader, arrayOf(ivs)) { _, method, args ->
                    if (method.name == "createVm") rewriteCpuOptions(args?.getOrNull(0))
                    try {
                        if (args == null) method.invoke(real) else method.invoke(real, *args)
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        // Re-throw the binder's real exception (e.g.
                        // ServiceSpecificException) so run()'s catches see the
                        // original type, not our reflective wrapper.
                        throw e.cause ?: e
                    }
                }
                binderField.set(vs, proxy)
            }
            android.util.Log.i("AvfReflect", "explicit vCPU hook armed: cpuCount=$n")
            true
        }.getOrElse {
            explicitCpuCountBroken = true
            android.util.Log.e("AvfReflect",
                "explicit vCPU hook install failed; falling back to nr_cpus ladder", it)
            false
        }
    }

    /**
     * Disarms the createVm rewrite without removing the installed proxy. The
     * proxy is cached on the process-shared VirtualizationService binder, so it
     * survives across launches; a launch that does NOT want an explicit count
     * (ONE_CPU, or the MATCH_HOST+nr_cpus fallback tier, or the ladder's 1-core
     * rescue rung) MUST clear pendingCpuCount, otherwise the still-installed
     * proxy would rewrite this launch's cpuOptions back to the previous count
     * and silently re-run a failing config. rewriteCpuOptions no-ops at count 0.
     */
    fun disarmExplicitCpuCount() {
        pendingCpuCount = 0
    }

    /**
     * Replaces rawConfig.cpuOptions with an explicit cpuCount topology on the
     * in-flight createVm parcelable (parceling happens after this returns, at
     * binder transact time). On any failure the VM still launches with the
     * builder's MATCH_HOST topology — poison the hook so AvfEngine's adaptive
     * nr_cpus ladder rescues the likely early-boot reset on relaunch.
     */
    private fun rewriteCpuOptions(cfgUnion: Any?) {
        val n = pendingCpuCount
        if (cfgUnion == null || n < 1) return
        runCatching {
            val raw = cfgUnion.javaClass.getDeclaredMethod("getRawConfig")
                .apply { isAccessible = true }.invoke(cfgUnion) ?: return
            val topo = Class.forName("$VS_PKG.CpuOptions\$CpuTopology")
                .getDeclaredMethod("cpuCount", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(null, n)
            val optsCls = Class.forName("$VS_PKG.CpuOptions")
            val opts = optsCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            optsCls.getDeclaredField("cpuTopology").apply { isAccessible = true }.set(opts, topo)
            raw.javaClass.getDeclaredField("cpuOptions").apply { isAccessible = true }.set(raw, opts)
            android.util.Log.i("AvfReflect", "createVm: cpuOptions rewritten to explicit cpuCount=$n")
        }.onFailure {
            explicitCpuCountBroken = true
            android.util.Log.e("AvfReflect",
                "createVm cpuOptions rewrite failed (VM launches with MATCH_HOST)", it)
        }
    }

    fun setConsoleInputDevice(b: Any, device: String) {
        invokeDecl(b, "setConsoleInputDevice", String::class.java to device)
    }

    fun setConnectVmConsole(b: Any, value: Boolean) {
        invokeDecl(b, "setConnectVmConsole", Boolean::class.javaPrimitiveType!! to value)
    }

    fun setVmOutputCaptured(b: Any, value: Boolean) {
        invokeDecl(b, "setVmOutputCaptured", Boolean::class.javaPrimitiveType!! to value)
    }

    /**
     * AVF debug levels: NONE=0, FULL=1. Console input requires FULL.
     * Constant integer (not a reflective field read) since it's stable in the
     * public SystemApi.
     */
    const val DEBUG_LEVEL_FULL: Int = 1

    fun setDebugLevel(b: Any, level: Int) {
        invokeDecl(b, "setDebugLevel", Int::class.javaPrimitiveType!! to level)
    }

    fun setVmConsoleInputSupported(b: Any, value: Boolean) {
        // Older API revisions may not have this; tolerate absence.
        runCatching {
            invokeDecl(b, "setVmConsoleInputSupported", Boolean::class.javaPrimitiveType!! to value)
        }
    }

    fun setCustomImageConfig(b: Any, cfg: Any) {
        invokeDecl(b, "setCustomImageConfig", CUSTOM to cfg)
    }

    fun build(b: Any): Any = invokeDecl(b, "build")!!

    fun newCustomBuilder(): Any =
        CUSTOM_B.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

    fun setName(b: Any, name: String) { invokeDecl(b, "setName", String::class.java to name) }
    fun setKernelPath(b: Any, p: String) { invokeDecl(b, "setKernelPath", String::class.java to p) }
    fun setInitrdPath(b: Any, p: String) { invokeDecl(b, "setInitrdPath", String::class.java to p) }
    /** Adds each whitespace-separated token of [params] via addParam(String). */
    fun addParams(b: Any, params: String) {
        val tokens = params.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val m = b.javaClass.getDeclaredMethod("addParam", String::class.java)
            .apply { isAccessible = true }
        for (t in tokens) m.invoke(b, t)
    }

    fun addDisk(b: Any, path: String, writable: Boolean) {
        val diskCls = DISK ?: error("VirtualMachineCustomImageConfig\$Disk class not found on this device")
        val factoryName = if (writable) "RWDisk" else "RODisk"
        val factory = diskCls.getDeclaredMethod(factoryName, String::class.java)
            .apply { isAccessible = true }
        val disk = factory.invoke(null, path)
            ?: error("Disk.$factoryName($path) returned null")
        val addM = b.javaClass.getDeclaredMethod("addDisk", diskCls).apply { isAccessible = true }
        addM.invoke(b, disk)
    }

    fun setNetworkSupported(b: Any, value: Boolean) {
        val ok = runCatching { invokeDecl(b, "useNetwork", Boolean::class.javaPrimitiveType!! to value) }.isSuccess
            || runCatching { invokeDecl(b, "setNetworkSupported", Boolean::class.javaPrimitiveType!! to value) }.isSuccess
        if (!ok) android.util.Log.w("AvfReflect", "no useNetwork/setNetworkSupported on this AVF API; VM may have no network")
    }

    /** Result of the last [setGpuConfig] attempt. See that function for what each predicts. */
    enum class GpuConfigOutcome { ATTACHED, UNSUPPORTED, FAILED }

    /** Whether the GpuConfig API classes are present on this device, independent of any VM start. */
    fun gpuConfigApiPresent(): Boolean = GPU_B != null && GPU != null

    private val _lastGpuConfigOutcome = MutableStateFlow<GpuConfigOutcome?>(null)

    /** Outcome of the most recent [setGpuConfig] call; null until one runs in this process. */
    val lastGpuConfigOutcome: StateFlow<GpuConfigOutcome?> = _lastGpuConfigOutcome.asStateFlow()

    /**
     * Attaches a minimal headless virtio-GPU (backend=2d, surfaceless; NO
     * DisplayConfig, so no display surface is required) to the custom-image
     * builder. The purpose is crosvm BINARY SELECTION, not graphics.
     *
     * On Pixel's `com.google.android.virt` APEX, virtmgr runs GPU-less VMs on
     * `/apex/com.android.virt/bin/crosvm_minimal`, which is built WITHOUT the
     * `net` feature. virtmgr still appends `--net tap-fd=N` when networking is
     * requested, so the minimal binary aborts at arg-parse ("Unrecognized
     * argument: --net", exit 35) and the VM never boots. The full `crosvm` has
     * `net`. Google's own Terminal app never hits this because it always declares
     * a GPU; declaring one here routes us onto the same net-capable binary.
     *
     * Outcomes (never throws):
     * - [GpuConfigOutcome.ATTACHED] the declaration took effect; this VM gets the
     *   full net-capable crosvm.
     * - [GpuConfigOutcome.UNSUPPORTED] the GpuConfig API is absent on this AVF
     *   revision. Benign: those revisions run the full crosvm already, so a
     *   headless networked VM is unaffected.
     * - [GpuConfigOutcome.FAILED] the API exists but the call did not take effect
     *   (a reflective step threw, or `build()` returned null). Predicts
     *   crosvm_minimal routing, i.e. no virtio-net in the guest.
     *
     * The result is also published on [lastGpuConfigOutcome] for diagnostics.
     */
    fun setGpuConfig(b: Any): GpuConfigOutcome {
        val outcome = runCatching {
            val gpuBuilderCls = GPU_B ?: return@runCatching GpuConfigOutcome.UNSUPPORTED
            val gpuCls = GPU ?: return@runCatching GpuConfigOutcome.UNSUPPORTED
            val gpuB = gpuBuilderCls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            invokeDecl(gpuB, "setBackend", String::class.java to "2d")
            // Surfaceless keeps the virtio-GPU off any scanout/window; harmless if the
            // API revision ignores it (wrapped so a missing setter is not fatal).
            runCatching { invokeDecl(gpuB, "setRendererUseSurfaceless", Boolean::class.javaPrimitiveType!! to true) }
            // Past the class lookups the API exists, so a null build() is a real
            // failure, not an absent API: route it through the FAILED log below.
            val gpu = invokeDecl(gpuB, "build") ?: error("GpuConfig.Builder.build() returned null")
            invokeDecl(b, "setGpuConfig", gpuCls to gpu)
            GpuConfigOutcome.ATTACHED
        }.getOrElse { e ->
            android.util.Log.w(
                "AvfReflect",
                "setGpuConfig FAILED though the GpuConfig API is present; " +
                    "virtmgr may route this VM to crosvm_minimal (no virtio-net)",
                e,
            )
            GpuConfigOutcome.FAILED
        }
        _lastGpuConfigOutcome.value = outcome
        return outcome
    }

    /**
     * Builds a Proxy of `android.system.virtualmachine.VirtualMachineCallback`.
     * The Java interface can't be implemented directly because it's @SystemApi
     * and we don't compile against the stubs.
     *
     * @param onError invoked when the VM hits a runtime error. Args: errorCode (Int), message (String?).
     * @param onStopped invoked when the VM exits cleanly. Arg: reason (Int).
     * @param onDied invoked for backend-level termination. Arg: reason (Int).
     */
    fun newVmCallback(
        onError: (Int, String?) -> Unit,
        onStopped: (Int) -> Unit,
        onDied: (Int) -> Unit,
    ): Any {
        val cls = Class.forName("$PKG.VirtualMachineCallback")
        val handler = InvocationHandler { _, method: Method, args: Array<Any?>? ->
            when (method.name) {
                "onError" -> {
                    // signature: onError(VirtualMachine vm, int errorCode, String message)
                    val code = args?.getOrNull(1) as? Int ?: -1
                    val msg = args?.getOrNull(2) as? String
                    onError(code, msg)
                }
                "onStopped" -> {
                    val reason = args?.getOrNull(1) as? Int ?: -1
                    onStopped(reason)
                }
                "onDied" -> {
                    // Both documented shapes carry reason at index 1:
                    //   onDied(VirtualMachine vm, int reason)
                    //   onDied(int cid, int reason)
                    // Use index 1 for consistency with onError/onStopped above
                    // (an extra trailing arg in a future signature would otherwise
                    // silently mis-decode under an args[last] strategy).
                    val reason = args?.getOrNull(1) as? Int ?: -1
                    onDied(reason)
                }
                else -> Unit  // ignore onPayload* — those are Microdroid-only
            }
            null
        }
        return Proxy.newProxyInstance(cls.classLoader, arrayOf(cls), handler)
    }

    fun setCallback(vm: Any, executor: Executor, callback: Any) {
        val cbCls = Class.forName("$PKG.VirtualMachineCallback")
        val m = vm.javaClass.getDeclaredMethod("setCallback", Executor::class.java, cbCls)
            .apply { isAccessible = true }
        m.invoke(vm, executor, callback)
    }

    fun getStatus(vm: Any): Int =
        (invokeDecl(vm, "getStatus") as? Int) ?: -1

    fun run(vm: Any) { invokeDecl(vm, "run") }

    /**
     * Signals the framework to stop the VM. Throws on failure rather than
     * swallowing it: AvfEngine.stop() needs to know whether the request was
     * accepted, because a swallowed failure leaves a live VM with a nulled
     * handle (unkillable). Callers wrap in runCatching where they want to react.
     */
    fun stop(vm: Any) { invokeDecl(vm, "stop") }

    /**
     * Opens a vsock connection from the host (Android) to a port the guest is
     * listening on. Returns a ParcelFileDescriptor whose underlying FD is a
     * connected AF_VSOCK socket — wrap it in
     * [android.os.ParcelFileDescriptor.AutoCloseInputStream]/
     * [android.os.ParcelFileDescriptor.AutoCloseOutputStream] for I/O.
     *
     * The guest reaches us at CID 2 (host); our peer is the VM at whatever CID
     * AVF assigned. AVF's connectVsock handles the CID lookup internally; we
     * pass only the port.
     */
    fun connectVsock(vm: Any, port: Long): android.os.ParcelFileDescriptor {
        val m = vm.javaClass.getDeclaredMethod("connectVsock", Long::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
        return m.invoke(vm, port) as? android.os.ParcelFileDescriptor
            ?: error("connectVsock($port) returned null")
    }

    fun consoleOutput(vm: Any): java.io.InputStream =
        invokeDecl(vm, "getConsoleOutput") as? java.io.InputStream
            ?: error("getConsoleOutput returned null/non-stream")

    fun consoleInput(vm: Any): java.io.OutputStream =
        invokeDecl(vm, "getConsoleInput") as? java.io.OutputStream
            ?: error("getConsoleInput returned null/non-stream")

    private fun invokeDecl(target: Any, name: String, vararg typedArgs: Pair<Class<*>, Any?>): Any? {
        val argTypes = typedArgs.map { it.first }.toTypedArray()
        val argVals = typedArgs.map { it.second }.toTypedArray()
        val m = target.javaClass.getDeclaredMethod(name, *argTypes).apply { isAccessible = true }
        return m.invoke(target, *argVals)
    }
}
