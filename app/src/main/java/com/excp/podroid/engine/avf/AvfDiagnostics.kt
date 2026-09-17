/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * AVF (Android Virtualization Framework) diagnostic + smoke-test entry point.
 *
 * The android.system.virtualmachine.* APIs are @SystemApi (only present in
 * the system-stub JAR, not the public SDK). We reach them via reflection so
 * a normal Gradle build still compiles on every device. On phones without
 * pKVM the reflective lookups simply fail and the probe reports "not
 * available" — no crash, no missing-class linker error.
 *
 * Purpose: validate the manifest+`adb pm grant` path on pKVM hardware
 * (Pixel 8/9/10) before investing in a real dual-backend rewrite. Reports
 * what's present, what's granted, whether the service is reachable, and
 * (optionally) attempts to create + start a minimal VM using our existing
 * Alpine kernel/initrd in filesDir.
 */
package com.excp.podroid.engine.avf

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/** One-line entries in the diagnostic report; UI just joins them. */
data class AvfReport(
    val featureSupported: Boolean,
    val managePermissionGranted: Boolean,
    val customPermissionGranted: Boolean,
    val virtApexPresent: Boolean,
    val managerClassPresent: Boolean,
    val serviceReachable: Boolean,
    val customVmConfigSupported: Boolean = false,
    val smokeTestResult: String?,
    val capabilitiesRaw: Int = 0,
    val capabilitiesDecoded: String = "n/a",
    val activeBackend: String = "?",
    val gpuConfigApiPresent: Boolean = false,
    val lastGpuConfigAttempt: String = "not attempted (no AVF VM started this process)",
) {
    fun pretty(): String = buildString {
        appendLine("Active backend")
        appendLine("  $activeBackend")
        appendLine()
        appendLine("Feature: virtualization_framework")
        appendLine("  supported = $featureSupported")
        appendLine()
        appendLine("Permission: MANAGE_VIRTUAL_MACHINE")
        appendLine("  granted = $managePermissionGranted")
        appendLine()
        appendLine("Permission: USE_CUSTOM_VIRTUAL_MACHINE")
        appendLine("  granted = $customPermissionGranted")
        appendLine()
        appendLine("APEX /apex/com.android.virt")
        appendLine("  present = $virtApexPresent")
        appendLine()
        appendLine("API VirtualMachineManager")
        appendLine("  class loadable = $managerClassPresent")
        appendLine()
        appendLine("Service")
        appendLine("  reachable via system service = $serviceReachable")
        appendLine()
        appendLine("Custom-VM API")
        appendLine("  builder present = $customVmConfigSupported")
        appendLine()
        appendLine("Hypervisor capabilities")
        appendLine("  raw = $capabilitiesRaw ($capabilitiesDecoded)")
        appendLine()
        appendLine("Headless GPU declaration (net-capable crosvm workaround)")
        appendLine("  API present = $gpuConfigApiPresent")
        appendLine("  last attempt = $lastGpuConfigAttempt")
        if (smokeTestResult != null) {
            appendLine()
            appendLine("Smoke test")
            appendLine(smokeTestResult.prependIndent("  "))
        }
    }
}

object AvfDiagnostics {

    private const val FEATURE = "android.software.virtualization_framework"
    private const val PERM_MANAGE = "android.permission.MANAGE_VIRTUAL_MACHINE"
    private const val PERM_CUSTOM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE"
    private const val CLS_MANAGER = "android.system.virtualmachine.VirtualMachineManager"
    private const val CLS_CONFIG = "android.system.virtualmachine.VirtualMachineConfig"
    private const val CLS_CUSTOM_CFG = "android.system.virtualmachine.VirtualMachineCustomImageConfig"

    /**
     * True only if this device's AVF build exposes the custom-VM builder API
     * Podroid drives (raw kernel + initrd). A vendor build that ships AVF for
     * system use but omits the custom-image config will return false, so
     * EngineHolder.pick() can fall back to QEMU at selection time instead of
     * erroring at VM start. Never throws: a missing class is a normal "no".
     */
    fun customVmConfigSupported(): Boolean = runCatching {
        val builder = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        builder.getDeclaredMethod("setKernelPath", String::class.java)
        builder.getDeclaredMethod("setInitrdPath", String::class.java)
        Class.forName("$CLS_CONFIG\$Builder")
            .getDeclaredMethod("setCustomImageConfig", Class.forName(CLS_CUSTOM_CFG))
        true
    }.getOrDefault(false)

    /**
     * Read-only probe — never blocks, never touches the system service for
     * real (just checks reachability). Safe to call from anywhere.
     */
    fun probe(context: Context): AvfReport {
        val pm = context.packageManager
        val featureSupported = pm.hasSystemFeature(FEATURE)
        val managePermissionGranted = pm.checkPermission(PERM_MANAGE, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val customPermissionGranted = pm.checkPermission(PERM_CUSTOM, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
        val virtApexPresent = File("/apex/com.android.virt/bin").exists()
        val managerClassPresent = runCatching { Class.forName(CLS_MANAGER) }.isSuccess
        val serviceReachable = managerClassPresent && managePermissionGranted &&
            runCatching { getVirtualizationManager(context) != null }.getOrDefault(false)

        val capabilitiesRaw = if (serviceReachable) {
            runCatching { AvfReflect.getCapabilities(AvfReflect.manager(context)) }.getOrDefault(0)
        } else 0

        val lastGpuConfigAttempt = when (AvfReflect.lastGpuConfigOutcome.value) {
            null -> "not attempted (no AVF VM started this process)"
            AvfReflect.GpuConfigOutcome.ATTACHED -> "attached"
            AvfReflect.GpuConfigOutcome.UNSUPPORTED -> "unsupported (API absent, benign; full crosvm already in use)"
            AvfReflect.GpuConfigOutcome.FAILED -> "FAILED (API present, call threw; guest networking may not come up)"
        }

        return AvfReport(
            featureSupported = featureSupported,
            managePermissionGranted = managePermissionGranted,
            customPermissionGranted = customPermissionGranted,
            virtApexPresent = virtApexPresent,
            managerClassPresent = managerClassPresent,
            serviceReachable = serviceReachable,
            customVmConfigSupported = customVmConfigSupported(),
            smokeTestResult = null,
            capabilitiesRaw = capabilitiesRaw,
            capabilitiesDecoded = AvfCapabilities.decode(capabilitiesRaw),
            gpuConfigApiPresent = AvfReflect.gpuConfigApiPresent(),
            lastGpuConfigAttempt = lastGpuConfigAttempt,
        )
    }

    /**
     * Attempts a real minimal-VM creation using our existing alpine kernel
     * + initrd. Returns a human-readable result string. Blocks for a few
     * seconds. Call off the UI thread.
     *
     * NOTE: this only validates that *creation* + *run* are accepted by the
     * framework — it does not stream the console or wait for guest boot.
     * The VM is stopped/deleted immediately.
     */
    fun runSmokeTest(context: Context): String {
        val pre = probe(context)
        if (!pre.featureSupported)   return "skipped: feature flag not present (device does not ship AVF)"
        if (!pre.managePermissionGranted) return "skipped: MANAGE_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_MANAGE)"
        if (!pre.customPermissionGranted) return "skipped: USE_CUSTOM_VIRTUAL_MACHINE not granted (run: adb shell pm grant ${context.packageName} $PERM_CUSTOM)"
        if (!pre.managerClassPresent) return "FAILED: $CLS_MANAGER not on the boot classpath — system stub missing"

        val kernelSrc = File(context.filesDir, "vmlinuz-virt")
        val initrd = File(context.filesDir, "initrd.img")
        if (!kernelSrc.exists()) return "FAILED: kernel not extracted yet at ${kernelSrc.absolutePath}"
        if (!initrd.exists()) return "FAILED: initrd not extracted yet at ${initrd.absolutePath}"

        if (AvfCapabilities.choose(pre.capabilitiesRaw) is AvfCapabilities.ProtectedVmChoice.Unsupported) {
            return "not applicable on this device: the hypervisor only supports protected VMs " +
                "(caps=${pre.capabilitiesDecoded}), and Podroid's custom Linux kernel can run only as a " +
                "non-protected VM. This is expected, not a failure: Podroid automatically uses the QEMU " +
                "backend here."
        }

        return try {
            val vmm = getVirtualizationManager(context)
                ?: return "FAILED: VirtualMachineManager system service returned null"

            // crosvm needs the raw ARM64 Image, not the gzip vmlinuz — decompress
            // exactly like AvfEngine.ensureRawKernel (and reuse its cached .raw).
            // Without this, crosvm fails to load the kernel ("invalid magic
            // number") the moment it gets past arg parsing.
            val kernel = ensureRawKernel(kernelSrc)
            val customCfg = buildCustomImageConfig(kernel.absolutePath, initrd.absolutePath)
            val config = buildVirtualMachineConfig(vmm, context, customCfg)

            val name = "podroid-avf-smoke"
            val vm = invokeOrCreate(vmm, name, config)

            // Start + immediately stop. We're proving the framework accepts us,
            // not running a workload.
            runCatching {
                vm.javaClass.getMethod("run").invoke(vm)
            }.onFailure { e ->
                deleteSafely(vmm, name)
                return "FAILED at vm.run(): ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: ${e.cause?.message ?: e.message}"
            }

            // Give the VM 1.5s to actually start (we just want to know whether
            // the framework rejected us — usually fails synchronously).
            Thread.sleep(1500)

            runCatching { vm.javaClass.getMethod("stop").invoke(vm) }
            deleteSafely(vmm, name)

            "SUCCESS: AVF accepted our config, VM started + stopped cleanly. The dev-grant path works on this device."
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            if (cause is UnsupportedOperationException &&
                cause.message?.contains("protected", ignoreCase = true) == true) {
                // Device is protected-only but getCapabilities() reported 0, so the
                // framework rejected our non-protected VM at build/run instead. Same
                // expected outcome as the early check above, not a failure.
                "not applicable on this device: AVF rejected a non-protected VM " +
                    "(${cause.message}). Podroid's custom Linux kernel can run only as a non-protected " +
                    "VM. This is expected, not a failure: Podroid automatically uses the QEMU backend here."
            } else {
                "FAILED: ${cause.javaClass.simpleName}: ${cause.message}"
            }
        }
    }

    private fun getVirtualizationManager(context: Context): Any? {
        // Context.getSystemService(Class) — but the class is loaded reflectively
        // so we can't call the typed overload at compile time.
        val mgrCls = Class.forName(CLS_MANAGER)
        val m = Context::class.java.getMethod("getSystemService", Class::class.java)
        return m.invoke(context, mgrCls)
    }

    /**
     * Mirrors AvfEngine.ensureRawKernel: crosvm requires the raw ARM64 Image
     * (magic `ARM\x64` at 0x38), not the gzip-compressed vmlinuz. Decompress to
     * the same sibling `.raw` file so the cache is shared with the real VM path.
     * Returns the source untouched if it isn't gzip.
     */
    private fun ensureRawKernel(source: File): File {
        val magic = ByteArray(4)
        source.inputStream().use { it.read(magic) }
        if (magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) return source
        val raw = File(source.parentFile, "${source.name}.raw")
        if (raw.exists() && raw.lastModified() >= source.lastModified()) return raw
        java.util.zip.GZIPInputStream(source.inputStream().buffered()).use { gz ->
            raw.outputStream().buffered().use { out -> gz.copyTo(out) }
        }
        raw.setLastModified(System.currentTimeMillis())
        return raw
    }

    private fun buildCustomImageConfig(kernelPath: String, initrdPath: String): Any {
        val builderCls = Class.forName("$CLS_CUSTOM_CFG\$Builder")
        val ctor = builderCls.getDeclaredConstructor().apply { isAccessible = true }
        val builder = ctor.newInstance()
        invokeSetter(builderCls, builder, "setName", String::class.java, "podroid-avf-smoke")
        invokeSetter(builderCls, builder, "setKernelPath", String::class.java, kernelPath)
        invokeSetter(builderCls, builder, "setInitrdPath", String::class.java, initrdPath)
        runCatching {
            AvfReflect.addParams(builder, "console=hvc0 panic=1")
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun buildVirtualMachineConfig(vmm: Any, context: Context, customCfg: Any): Any {
        val builderCls = Class.forName("$CLS_CONFIG\$Builder")
        val ctor = builderCls.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val builder = ctor.newInstance(context)
        val customCfgCls = Class.forName(CLS_CUSTOM_CFG)
        invokeSetter(builderCls, builder, "setCustomImageConfig", customCfgCls, customCfg)
        when (val choice = AvfReflect.applyProtectedVm(vmm, builder)) {
            is AvfCapabilities.ProtectedVmChoice.Unsupported ->
                throw UnsupportedOperationException(choice.reason)
            else -> Unit  // NonProtected/Unknown: setter already applied (or threw, which surfaces)
        }
        runCatching {
            invokeSetter(builderCls, builder, "setMemoryBytes",
                Long::class.javaPrimitiveType!!, 256L * 1024 * 1024)
        }
        val buildM = builderCls.getDeclaredMethod("build").apply { isAccessible = true }
        return buildM.invoke(builder)
    }

    private fun invokeSetter(cls: Class<*>, target: Any, name: String, argType: Class<*>, arg: Any?) {
        val m = cls.getDeclaredMethod(name, argType).apply { isAccessible = true }
        m.invoke(target, arg)
    }

    private fun invokeOrCreate(vmm: Any, name: String, config: Any): Any {
        val cfgCls = Class.forName(CLS_CONFIG)
        return runCatching {
            val m = vmm.javaClass.getDeclaredMethod("getOrCreate", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        }.getOrElse {
            val m = vmm.javaClass.getDeclaredMethod("create", String::class.java, cfgCls)
                .apply { isAccessible = true }
            m.invoke(vmm, name, config)
        } ?: error("getOrCreate returned null")
    }

    private fun deleteSafely(vmm: Any, name: String) {
        runCatching { vmm.javaClass.getMethod("delete", String::class.java).invoke(vmm, name) }
    }
}
