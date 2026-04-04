/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Application class for Podroid.
 */
package com.excp.podroid

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import java.io.File

/**
 * Application class for Podroid.
 *
 * Extracts QEMU, kernel, and initrd assets on first run.
 */
@HiltAndroidApp
class PodroidApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        extractAssets()
    }

    private fun extractAssets() {
        // Extract QEMU BIOS/firmware files
        copyAssetDir("qemu", filesDir)

        // Extract kernel and initrd
        copyAssetIfNeeded("vmlinuz-virt", filesDir)
        copyAssetIfNeeded("initrd.img", filesDir)
    }

    private fun copyAssetIfNeeded(assetName: String, destDir: File) {
        val destFile = File(destDir, assetName)

        try {
            // Try to get the uncompressed asset size for a cheap up-to-date check.
            // openFd() throws for compressed entries — fall back to always copying in that case.
            val assetSize = try { assets.openFd(assetName).use { it.length } } catch (_: Exception) { -1L }
            if (assetSize >= 0 && destFile.exists() && destFile.length() == assetSize) return

            assets.open(assetName).use { input ->
                destFile.parentFile?.mkdirs()
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            // Asset might not exist - that's OK, we'll handle missing files at runtime
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val entries = assets.list(assetPath) ?: return
        for (entry in entries) {
            val src = "$assetPath/$entry"
            val dest = File(destDir, entry)
            val subEntries = assets.list(src)
            if (subEntries != null && subEntries.isNotEmpty()) {
                dest.mkdirs()
                copyAssetDir(src, dest)
            } else {
                if (!dest.exists()) {
                    try {
                        assets.open(src).use { input ->
                            dest.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        // Skip if asset doesn't exist
                    }
                }
            }
        }
    }


}
