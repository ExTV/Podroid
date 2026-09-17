/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * App-wide settings backed by DataStore.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.avf.AvfCpuPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// corruptionHandler: a CorruptionException (subclass of IOException) on read
// otherwise makes every edit{} throw forever with no self-heal. Resetting to
// empty preferences (defaults re-apply on next read) beats a permanently broken
// settings store. This only fires on genuine on-disk corruption, not on normal
// missing keys.
internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        val KEY_DARK_THEME             = booleanPreferencesKey("dark_theme")
        val KEY_VM_RAM                 = intPreferencesKey("vm_ram_mb")
        val KEY_VM_CPUS                = intPreferencesKey("vm_cpus")
        val KEY_FONT_SIZE              = intPreferencesKey("terminal_font_size")
        val KEY_STORAGE_GB             = intPreferencesKey("storage_gb")
        val KEY_STORAGE_ACCESS_ENABLED = booleanPreferencesKey("storage_access_enabled")
        val KEY_SETUP_DONE             = booleanPreferencesKey("setup_done")
        val KEY_SSH_ENABLED            = booleanPreferencesKey("ssh_enabled")
        val KEY_TERMINAL_COLOR_THEME   = stringPreferencesKey("terminal_color_theme")
        val KEY_TERMINAL_FONT          = stringPreferencesKey("terminal_font")
        val KEY_QEMU_EXTRA_ARGS        = stringPreferencesKey("qemu_extra_args")
        val KEY_KERNEL_EXTRA_CMDLINE   = stringPreferencesKey("kernel_extra_cmdline")
        val KEY_SHOW_EXTRA_KEYS        = booleanPreferencesKey("show_extra_keys")
        val KEY_HAPTICS_ENABLED        = booleanPreferencesKey("haptics_enabled")
        val KEY_DYNAMIC_COLOR_ENABLED  = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_LAST_BOOT_DURATION_MS  = longPreferencesKey("last_boot_duration_ms")
        val KEY_LAST_CONTAINER_COUNT   = intPreferencesKey("last_container_count")
        val KEY_ENGINE_SELECTION       = stringPreferencesKey("engine_selection")
        val KEY_AVF_HINT_DISMISSED      = booleanPreferencesKey("avf_hint_dismissed")
        val KEY_AVF_VERBOSE_LOGGING     = booleanPreferencesKey("avf_verbose_logging")
        // Per-device AVF vCPU cap discovered at runtime: on Tensor G3/G4 the
        // MATCH_HOST topology resets the guest in early boot above a certain core
        // count (issue #29). AvfEngine steps this down on an early-boot reset and
        // persists it so later launches go straight to the count that boots.
        // 0 (AvfCpuPolicy.NO_CAP) = none discovered yet; cleared when the user
        // changes the CPU-count setting so the new value is re-probed.
        val KEY_AVF_CPU_CAP             = intPreferencesKey("avf_cpu_cap")
        val KEY_USB_PASSTHROUGH_ENABLED = booleanPreferencesKey("usb_passthrough_enabled")
        val KEY_LOAD_BALANCE_ENABLED    = booleanPreferencesKey("load_balance_enabled")
        /** Guest egress cap via `podroid.bandwidth=` cmdline + tc. 0 = unlimited. */
        val KEY_BANDWIDTH_MBPS          = intPreferencesKey("bandwidth_mbps")
        /** Start the VM automatically on BOOT_COMPLETED. Read by VmControlReceiver. */
        val KEY_AUTOSTART_ON_BOOT       = booleanPreferencesKey("autostart_on_boot")

        val KEY_X11_RES_MODE        = stringPreferencesKey("x11_resolution_mode")
        val KEY_X11_RES_PRESET      = stringPreferencesKey("x11_resolution_preset")
        val KEY_X11_CUSTOM_W        = intPreferencesKey("x11_custom_w")
        val KEY_X11_CUSTOM_H        = intPreferencesKey("x11_custom_h")
        val KEY_X11_TOUCH_MODE      = stringPreferencesKey("x11_touch_mode")
        val KEY_X11_TP_SENSITIVITY  = stringPreferencesKey("x11_tp_sensitivity") // float as string, DataStore has no float key helper used here
        val KEY_X11_TP_ACCEL        = booleanPreferencesKey("x11_tp_accel")
        val KEY_X11_FULLSCREEN      = booleanPreferencesKey("x11_fullscreen_default")
        val KEY_X11_ROTATION        = stringPreferencesKey("x11_rotation_lock")
        val KEY_X11_SHOW_EXTRA_KEYS = booleanPreferencesKey("x11_show_extra_keys")
        val KEY_X11_DPI             = intPreferencesKey("x11_dpi")
        val KEY_X11_RENDER_SCALE    = intPreferencesKey("x11_render_scale")
        val KEY_LANGUAGE              = stringPreferencesKey("language")

        /**
         * Default tunable QEMU args — CPU model, accel tuning, RNG source, overcommit.
         *
         * `-cpu max,sve=off`: keeps LSE atomics, AES/SHA crypto, BTI, PAC, CRC32 —
         * everything Node/Podman/etc. actually use — but drops SVE's variable-length
         * vector instructions (and SVE2 with it), which are expensive to TCG-translate
         * (every SVE op has many length-encoded variants) and rarely used outside HPC.
         *
         * `tb-size=512`: 512 MiB translation block cache (was 256 MiB). JIT-heavy
         * guests like V8 recompile their own bytecode often, and TCG re-translates
         * that machine code each time it falls out of cache. Larger cache = fewer
         * re-translations. 512 MiB picked as a balance — enough for Node + npm hot
         * paths without ballooning the QEMU process on phones with tight RAM.
         */
        const val DEFAULT_QEMU_EXTRA_ARGS =
            "-cpu max,sve=off,pauth-impdef=on " +
            "-accel tcg,thread=multi,tb-size=512 " +
            "-object rng-random,id=rng0,filename=/dev/urandom " +
            "-device virtio-rng-pci,rng=rng0 " +
            "-overcommit mem-lock=off"

        /**
         * Default extra kernel cmdline — quiet boot + TCG-safe mitigations.
         * `elevator=` is deprecated since Linux 5.0; podroid-bootstrap sets the I/O
         * scheduler per-device via sysfs instead.
         */
        const val DEFAULT_KERNEL_EXTRA_CMDLINE = "loglevel=1 quiet mitigations=off"
    }

    private fun <T> pref(key: Preferences.Key<T>, default: T): Flow<T> =
        context.dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it[key] ?: default }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) =
        context.dataStore.edit { it[key] = value }

    val darkTheme            = pref(KEY_DARK_THEME, true)
    val vmRamMb              = pref(KEY_VM_RAM, 512)
    // 2 vCPUs lets multi-threaded TCG (`thread=multi`) actually schedule
    // across two host threads. With 1 vCPU the accel falls back to
    // single-thread translation regardless of the flag, so most CPU-bound
    // guest workloads (Node, npm install, Podman/Docker layer extraction)
    // see a meaningful uplift just from this default change.
    val vmCpus               = pref(KEY_VM_CPUS, 2)
    val terminalFontSize     = pref(KEY_FONT_SIZE, 20)
    val storageSizeGb        = pref(KEY_STORAGE_GB, 2)
    val storageAccessEnabled = pref(KEY_STORAGE_ACCESS_ENABLED, false)
    val isSetupDone          = pref(KEY_SETUP_DONE, false)
    val sshEnabled           = pref(KEY_SSH_ENABLED, false)
    val terminalColorTheme   = pref(KEY_TERMINAL_COLOR_THEME, "default")
    val terminalFont         = pref(KEY_TERMINAL_FONT, "default")
    val qemuExtraArgs        = pref(KEY_QEMU_EXTRA_ARGS, DEFAULT_QEMU_EXTRA_ARGS)
    val kernelExtraCmdline   = pref(KEY_KERNEL_EXTRA_CMDLINE, DEFAULT_KERNEL_EXTRA_CMDLINE)
    val showExtraKeys        = pref(KEY_SHOW_EXTRA_KEYS, true)
    val hapticsEnabled       = pref(KEY_HAPTICS_ENABLED, true)
    val dynamicColorEnabled  = pref(KEY_DYNAMIC_COLOR_ENABLED, false)
    val lastBootDurationMs   = pref(KEY_LAST_BOOT_DURATION_MS, 0L)
    // Routed through pref() so a corrupted store emits the "auto" default instead
    // of throwing into LanguageManager's locale collector.
    val language: Flow<String> = pref(KEY_LANGUAGE, "auto")
    val avfHintDismissed     = pref(KEY_AVF_HINT_DISMISSED, false)
    val avfCpuCap            = pref(KEY_AVF_CPU_CAP, 0)
    val usbPassthroughEnabled = pref(KEY_USB_PASSTHROUGH_ENABLED, false)
    val loadBalanceEnabled    = pref(KEY_LOAD_BALANCE_ENABLED, false)
    val bandwidthMbps         = pref(KEY_BANDWIDTH_MBPS, 0)
    val autostartOnBoot       = pref(KEY_AUTOSTART_ON_BOOT, false)
    val avfVerboseLogging: Flow<Boolean> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { prefs -> prefs[KEY_AVF_VERBOSE_LOGGING] ?: false }
    val engineSelection: Flow<EngineSelection> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { prefs ->
            runCatching { EngineSelection.valueOf(prefs[KEY_ENGINE_SELECTION] ?: "AUTO") }
                .getOrDefault(EngineSelection.AUTO)
        }

    suspend fun setDarkTheme(value: Boolean)             = set(KEY_DARK_THEME, value)
    suspend fun setVmRamMb(value: Int)                   = set(KEY_VM_RAM, value)
    suspend fun setVmCpus(value: Int) {
        // One transaction so a collector never observes the new CPU count paired
        // with the stale cap, and a process death between the two writes can't
        // persist that inconsistent pair (which would defeat the re-probe). A
        // manual CPU-count change re-probes from scratch: drop any AVF cap
        // discovered for the previous value (see KEY_AVF_CPU_CAP).
        context.dataStore.edit {
            it[KEY_VM_CPUS] = value
            it[KEY_AVF_CPU_CAP] = AvfCpuPolicy.NO_CAP
        }
    }
    suspend fun setTerminalFontSize(value: Int)          = set(KEY_FONT_SIZE, value)
    suspend fun setStorageSizeGb(value: Int)             = set(KEY_STORAGE_GB, value)
    suspend fun setStorageAccessEnabled(value: Boolean)  = set(KEY_STORAGE_ACCESS_ENABLED, value)
    suspend fun setSshEnabled(value: Boolean)            = set(KEY_SSH_ENABLED, value)
    suspend fun setTerminalColorTheme(value: String)     = set(KEY_TERMINAL_COLOR_THEME, value)
    suspend fun setTerminalFont(value: String)           = set(KEY_TERMINAL_FONT, value)
    suspend fun setQemuExtraArgs(value: String)          = set(KEY_QEMU_EXTRA_ARGS, value)
    suspend fun setKernelExtraCmdline(value: String)     = set(KEY_KERNEL_EXTRA_CMDLINE, value)
    suspend fun setShowExtraKeys(value: Boolean)         = set(KEY_SHOW_EXTRA_KEYS, value)
    suspend fun setHapticsEnabled(value: Boolean)        = set(KEY_HAPTICS_ENABLED, value)
    suspend fun setDynamicColorEnabled(value: Boolean)   = set(KEY_DYNAMIC_COLOR_ENABLED, value)
    suspend fun setLastBootDurationMs(value: Long)       = set(KEY_LAST_BOOT_DURATION_MS, value)
    suspend fun setLastContainerCount(value: Int)        = set(KEY_LAST_CONTAINER_COUNT, value)
    suspend fun getLastContainerCount(): Int? {
        val v = context.dataStore.data.first()[KEY_LAST_CONTAINER_COUNT] ?: -1
        return if (v < 0) null else v
    }
    suspend fun setLanguage(value: String)               = set(KEY_LANGUAGE, value)
    suspend fun setEngineSelection(value: EngineSelection) = set(KEY_ENGINE_SELECTION, value.name)
    suspend fun setAvfHintDismissed(value: Boolean)      = set(KEY_AVF_HINT_DISMISSED, value)
    suspend fun setAvfVerboseLogging(value: Boolean)     = set(KEY_AVF_VERBOSE_LOGGING, value)
    suspend fun setAvfCpuCap(value: Int)                 = set(KEY_AVF_CPU_CAP, value)
    suspend fun setUsbPassthroughEnabled(value: Boolean) = set(KEY_USB_PASSTHROUGH_ENABLED, value)
    suspend fun setLoadBalanceEnabled(value: Boolean)    = set(KEY_LOAD_BALANCE_ENABLED, value)
    suspend fun setBandwidthMbps(value: Int)             = set(KEY_BANDWIDTH_MBPS, value.coerceAtLeast(0))
    suspend fun setAutostartOnBoot(value: Boolean)       = set(KEY_AUTOSTART_ON_BOOT, value)

    /**
     * Persists all first-run setup choices in a single transaction so a process
     * kill mid-write can't leave a half-completed setup. Centralizes the write of
     * KEY_STORAGE_GB (the only key whose value can trigger storage resize) so it
     * has one choke point for validation; storage is clamped to >= 1 GB.
     */
    suspend fun completeSetup(
        storageSizeGb: Int,
        vmRamMb: Int,
        vmCpus: Int,
        sshEnabled: Boolean,
        storageAccessEnabled: Boolean,
        usbPassthroughEnabled: Boolean,
        loadBalanceEnabled: Boolean,
        bandwidthMbps: Int,
    ) {
        context.dataStore.edit {
            it[KEY_STORAGE_GB] = storageSizeGb.coerceAtLeast(1)
            it[KEY_VM_RAM] = vmRamMb.coerceAtLeast(512)
            it[KEY_VM_CPUS] = vmCpus.coerceAtLeast(1)
            it[KEY_SSH_ENABLED] = sshEnabled
            it[KEY_STORAGE_ACCESS_ENABLED] = storageAccessEnabled
            it[KEY_USB_PASSTHROUGH_ENABLED] = usbPassthroughEnabled
            it[KEY_LOAD_BALANCE_ENABLED] = loadBalanceEnabled
            it[KEY_BANDWIDTH_MBPS] = bandwidthMbps.coerceAtLeast(0)
            it[KEY_SETUP_DONE] = true
        }
    }

    val x11Settings: kotlinx.coroutines.flow.Flow<com.excp.podroid.x11.X11Settings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { p ->
        com.excp.podroid.x11.X11Settings(
            resolutionMode = runCatching { com.excp.podroid.x11.ResolutionMode.valueOf(p[KEY_X11_RES_MODE] ?: "MATCH") }.getOrDefault(com.excp.podroid.x11.ResolutionMode.MATCH),
            preset = runCatching { com.excp.podroid.x11.ResolutionPreset.valueOf(p[KEY_X11_RES_PRESET] ?: "R1080P") }.getOrDefault(com.excp.podroid.x11.ResolutionPreset.R1080P),
            customW = p[KEY_X11_CUSTOM_W] ?: 1280,
            customH = p[KEY_X11_CUSTOM_H] ?: 720,
            touchMode = runCatching { com.excp.podroid.x11.TouchMode.valueOf(p[KEY_X11_TOUCH_MODE] ?: "DIRECT") }.getOrDefault(com.excp.podroid.x11.TouchMode.DIRECT),
            trackpadSensitivity = (p[KEY_X11_TP_SENSITIVITY]?.toFloatOrNull() ?: 1.5f),
            trackpadAccel = p[KEY_X11_TP_ACCEL] ?: true,
            fullscreenDefault = p[KEY_X11_FULLSCREEN] ?: false,
            rotationLock = runCatching { com.excp.podroid.x11.RotationLock.valueOf(p[KEY_X11_ROTATION] ?: "AUTO") }.getOrDefault(com.excp.podroid.x11.RotationLock.AUTO),
            showExtraKeys = p[KEY_X11_SHOW_EXTRA_KEYS] ?: true,
            dpi = p[KEY_X11_DPI] ?: 96,
            renderScale = (p[KEY_X11_RENDER_SCALE] ?: 100).let { if (it == 100 || it == 75 || it == 50) it else 100 },
        )
    }

    suspend fun setX11ResolutionMode(v: String) = set(KEY_X11_RES_MODE, v)
    suspend fun setX11Preset(v: String) = set(KEY_X11_RES_PRESET, v)
    suspend fun setX11Custom(w: Int, h: Int) {
        context.dataStore.edit { it[KEY_X11_CUSTOM_W] = w; it[KEY_X11_CUSTOM_H] = h }
    }
    suspend fun setX11TouchMode(v: String) = set(KEY_X11_TOUCH_MODE, v)
    suspend fun setX11TrackpadSensitivity(v: Float) = set(KEY_X11_TP_SENSITIVITY, v.toString())
    suspend fun setX11TrackpadAccel(v: Boolean) = set(KEY_X11_TP_ACCEL, v)
    suspend fun setX11Fullscreen(v: Boolean) = set(KEY_X11_FULLSCREEN, v)
    suspend fun setX11Rotation(v: String) = set(KEY_X11_ROTATION, v)
    suspend fun setX11ShowExtraKeys(v: Boolean) = set(KEY_X11_SHOW_EXTRA_KEYS, v)
    suspend fun setX11Dpi(v: Int) = set(KEY_X11_DPI, v)
    suspend fun setX11RenderScale(v: Int) = set(KEY_X11_RENDER_SCALE, v)
    suspend fun getX11DpiSnapshot() = (x11Settings.first()).dpi
    suspend fun getLanguageSnapshot()                    = language.first()

    // Snapshots used by non-Compose call sites (PodroidService, exporters).
    suspend fun getSshEnabledSnapshot()           = sshEnabled.first()
    suspend fun getVmRamMbSnapshot()              = vmRamMb.first()
    suspend fun getVmCpusSnapshot()               = vmCpus.first()
    suspend fun getStorageSizeGbSnapshot()        = storageSizeGb.first()
    suspend fun getStorageAccessEnabledSnapshot() = storageAccessEnabled.first()
    suspend fun getTerminalColorThemeSnapshot()   = terminalColorTheme.first()
    suspend fun getTerminalFontSnapshot()         = terminalFont.first()
    suspend fun getQemuExtraArgsSnapshot()        = qemuExtraArgs.first()
    suspend fun getKernelExtraCmdlineSnapshot()   = kernelExtraCmdline.first()
    suspend fun getEngineSelectionSnapshot()      = engineSelection.first()
    suspend fun getAvfVerboseLoggingSnapshot()    = avfVerboseLogging.first()
    suspend fun getAvfCpuCapSnapshot()            = avfCpuCap.first()
    suspend fun getUsbPassthroughEnabledSnapshot() = usbPassthroughEnabled.first()
    suspend fun getBandwidthMbpsSnapshot()         = bandwidthMbps.first()
    suspend fun getAutostartOnBootSnapshot()       = autostartOnBoot.first()
}
