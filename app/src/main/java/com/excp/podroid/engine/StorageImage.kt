/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * storage.img preparation shared by the engines that open it as a raw file
 * (QEMU's virtio-blk, UML's ubd0).
 */
package com.excp.podroid.engine

import android.util.Log
import java.io.File

/** Create storage.img at [storageSizeGb], or grow it; never shrink or delete it. */
fun ensureStorageImage(filesDir: File, storageSizeGb: Int, tag: String) {
    val storageFile = File(filesDir, "storage.img")
    val desiredBytes = storageSizeGb.toLong() * 1024L * 1024L * 1024L

    if (storageFile.exists()) {
        val current = storageFile.length()
        when {
            current == desiredBytes -> return
            // NEVER delete: storage.img is the persistent ext4 overlay (every
            // apk add, container, user file). Deleting on mismatch could wipe
            // all guest data if the size ever changes (a corrupted DataStore
            // falling back to the 2GB default, or a future resize UI). Grow in
            // place when larger - the guest's first-boot resize2fs claims the
            // new space; truncating to shrink would corrupt the filesystem.
            desiredBytes > current -> {
                runCatching {
                    java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
                }.onSuccess {
                    Log.i(tag, "storage.img grown ${current / (1024 * 1024)}MB → ${storageSizeGb}GB (guest resize2fs on next boot)")
                }.onFailure { Log.e(tag, "Failed to grow storage.img", it) }
                return
            }
            else -> {
                Log.w(tag, "storage.img (${current / (1024 * 1024)}MB) larger than requested ${storageSizeGb}GB; keeping existing image (shrink would corrupt the filesystem)")
                return
            }
        }
    }

    try {
        java.io.RandomAccessFile(storageFile, "rw").use { it.setLength(desiredBytes) }
        Log.d(tag, "Created storage.img (${storageSizeGb}GB)")
    } catch (e: Exception) {
        // Don't swallow: a 0-byte / missing storage.img would otherwise boot
        // into an opaque early-boot stop. Surface it so start() can report a
        // clear storage error (mirrors the AVF disk-create path).
        Log.e(tag, "Failed to create storage.img", e)
        runCatching { storageFile.delete() }
        throw java.io.IOException("Couldn't create the ${storageSizeGb} GB VM disk image: ${e.message}", e)
    }
}
