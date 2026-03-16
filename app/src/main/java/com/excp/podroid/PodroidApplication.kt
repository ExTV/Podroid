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
import java.io.RandomAccessFile

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
        ensureStorageImage()
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
            // Always re-extract to ensure latest initrd/kernel is deployed
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

    private fun ensureStorageImage() {
        val storageFile = File(filesDir, "storage.img")
        if (!storageFile.exists()) {
            try {
                // Create a 2GB sparse storage image for persistent container storage
                RandomAccessFile(storageFile, "rw").use { raf ->
                    raf.setLength(2L * 1024 * 1024 * 1024) // 2GB
                }
            } catch (e: Exception) {
                // Failed to create storage image - Podman will work without persistence
            }
        }
    }
}
