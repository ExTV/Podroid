/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Application class — extracts QEMU, kernel, and initrd assets on first run
 * (and on app upgrade when the install-time stamp drifts).
 */
package com.excp.podroid

import android.app.Application
import android.os.Build
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.excp.podroid.util.AssetExtractionSupport
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

@HiltAndroidApp
class PodroidApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Completion signal for asset extraction. The VM launch path
    // (PodroidService.launchPodroid) reads the extracted files synchronously,
    // so it MUST await this before starting the engine — see awaitAssetsReady.
    // Completes successfully only after every extraction task succeeds. A failed
    // extraction completes exceptionally so the service does not start a VM with
    // missing or partial assets.
    private val assetsReady = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()
        exemptHiddenApi()
        // Extract off the main thread: the squashfs alone is ~225 MB and
        // blocking onCreate on first install/upgrade would ANR the cold start.
        appScope.launch { extractAssets() }
    }

    /**
     * Suspends until the bundled assets (qemu/, kernel, initrd, squashfs) have
     * finished extracting to [filesDir]. The foreground service awaits this
     * before launching the VM so QEMU/AVF never read a partial or missing file.
     */
    suspend fun awaitAssetsReady() = assetsReady.await()

    // Android 14+ hides @SystemApi reflection lookups (returning NoSuchMethod
    // even via getDeclared*). Prefixes needing exemption:
    //   - Landroid/system/virtualmachine/ — AVF framework (AvfDiagnostics + AvfEngine)
    //   - Landroid/system/virtualizationservice/ — AVF AIDL parcelables
    //     (CpuOptions, VirtualMachineRawConfig, IVirtualizationService) used by
    //     AvfReflect's explicit-vCPU-count hook (issue #29).
    //   - Ljava/net/UnixDomainSocketAddress — ConsoleFanout needs UDS.of(String)
    //     which Android marks BLOCKED for untrusted_app even though the class
    //     itself is on the bootclasspath.
    // No-op on sub-P; the exemption itself never throws.
    private fun exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/system/virtualmachine/",
                "Landroid/system/virtualizationservice/",
                "Landroid/system/UnixSocketAddress",
                "Ljava/net/UnixDomainSocketAddress",
            )
        }.onFailure { Log.w(TAG, "HiddenApiBypass exemption failed", it) }
    }

    private fun extractAssets() {
        try {
            // Asset extraction has a self-healing version stamp: on every install
            // or upgrade `packageInfo.lastUpdateTime` changes, so we record it in
            // `.assets_stamp` and force a re-copy on mismatch. Pure size checks
            // are deceiving because `mksquashfs -all-root -noappend` is
            // deterministic — changing service scripts inside the rootfs can
            // produce a byte-identical-size file with different content, which
            // older extraction logic silently kept stale.
            val stampFile = File(filesDir, ".assets_stamp")
            val currentStamp = runCatching {
                packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            }.getOrDefault(0L).toString()
            val previousStamp = runCatching { stampFile.readText() }.getOrDefault("")
            val forceCopy = previousStamp != currentStamp
            if (forceCopy) {
                Log.i(TAG, "asset stamp drift ($previousStamp → $currentStamp) — forcing re-extract")
            }

            // Fan out the four top-level extractions across a small thread pool.
            // Disk-write throughput is the bottleneck for the squashfs (~225 MB),
            // but decompression, asset-FD lookup, and the skip-when-already-
            // complete check all overlap usefully across threads. Runs on a
            // background coroutine (not the main thread); the VM launch path
            // awaits awaitAssetsReady.
            val tasks: List<() -> Unit> = listOf(
                { copyAssetDir("qemu", filesDir, forceCopy) },
                { copyAssetIfNeeded("vmlinuz-virt", File(filesDir, "vmlinuz-virt"), forceCopy) },
                { copyAssetIfNeeded("initrd.img", File(filesDir, "initrd.img"), forceCopy) },
                { copyAssetIfNeeded("alpine-rootfs.squashfs", File(filesDir, "alpine-rootfs.squashfs"), forceCopy) },
            )
            // runTasks waits for every task so one failed asset does not leave
            // the rest of the extraction work or its executor behind.
            val allSucceeded = AssetExtractionSupport.runTasks(tasks) { e ->
                Log.w(TAG, "Asset extraction task failed", e)
            }

            // Commit the new stamp ONLY if every extraction task succeeded.
            // Writing it after a failed copy (e.g. squashfs copy failed on an
            // upgrade: disk full, killed mid-copy) would mark the OLD file as
            // current — and because mksquashfs is deterministic the size check
            // can't catch it either, so a stale rootfs would boot forever. On
            // failure we leave the stamp stale so the next launch re-extracts.
            if (!allSucceeded) {
                throw IllegalStateException("Asset extraction incomplete")
            }
            runCatching { stampFile.writeText(currentStamp) }
                .onFailure { Log.w(TAG, "Failed to write assets stamp", it) }
        } catch (e: Throwable) {
            // Always expose a regular exception: PodroidService's existing
            // startup failure branch deliberately rethrows cancellation, while
            // ordinary extraction failures must tear down the service cleanly.
            val failure = IllegalStateException("Asset extraction failed", e)
            Log.e(TAG, "Asset extraction failed", failure)
            assetsReady.completeExceptionally(failure)
            return
        }
        assetsReady.complete(Unit)
    }

    /**
     * Copies an asset to destFile unless it's already complete: destFile
     * exists, forceCopy (install-stamp drift) is false, and either the size
     * is unknown (assets.openFd() throws for the compressed assets shipped
     * here: squashfs/initrd/kernel/qemu, so this is the common case) or it
     * matches. Copies are atomic (tmp + fsync + rename), so an existing
     * destination file is always complete, never partial. A stamp mismatch
     * on upgrade is what forces a re-copy of same-size-but-different-content
     * files (`mksquashfs` is deterministic, so size-only checks would
     * silently keep a stale copy and the VM would boot the old rootfs).
     * Exceptions from the copy propagate to the caller so the stamp is not
     * committed after a failed extraction.
     */
    private fun copyAssetIfNeeded(assetPath: String, destFile: File, forceCopy: Boolean) {
        val assetSize = try { assets.openFd(assetPath).use { it.length } } catch (_: Exception) { -1L }
        // Only inspect the temporary sibling owned by this extraction
        // destination. Never scan filesDir recursively: unrelated app data may
        // legitimately use a .tmp suffix.
        AssetExtractionSupport.deleteStaleTemporaryFile(destFile)
        if (!forceCopy && destFile.exists() && (assetSize < 0 || destFile.length() == assetSize)) return

        destFile.parentFile?.mkdirs()
        try {
            copyAssetAtomically(assetPath, destFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract $assetPath", e)
            throw e
        }
    }

    /**
     * Walks an asset directory tree and mirrors it under destDir, delegating
     * each file to [copyAssetIfNeeded]. Exceptions from a file copy propagate
     * up through this walk to the caller.
     */
    private fun copyAssetDir(assetPath: String, destDir: File, forceCopy: Boolean) {
        val entries = assets.list(assetPath)
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Asset directory missing or empty: $assetPath")
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = assets.list(src)
            if (subEntries != null && subEntries.isNotEmpty()) {
                dest.mkdirs()
                copyAssetDir(src, dest, forceCopy)
            } else {
                copyAssetIfNeeded(src, dest, forceCopy)
            }
        }
    }

    /**
     * Streams [assetPath] to `<destFile>.tmp`, fsyncs the data to disk, then
     * atomically renames it onto [destFile]. The final canonical path therefore
     * only ever holds a fully-written file — an async reader (the VM launch)
     * never sees a half-written squashfs/kernel. Throws on any failure so the
     * caller logs it and the extraction gate prevents startup with a missing
     * or partial destination.
     */
    private fun copyAssetAtomically(assetPath: String, destFile: File) {
        val tmpFile = AssetExtractionSupport.temporaryFileFor(destFile)
        try {
            assets.open(assetPath).use { input ->
                java.io.FileOutputStream(tmpFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!tmpFile.renameTo(destFile)) {
                throw java.io.IOException("atomic rename ${tmpFile.name} -> ${destFile.name} failed")
            }
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            throw e
        }
    }

    companion object {
        private const val TAG = "PodroidApp"
    }
}
