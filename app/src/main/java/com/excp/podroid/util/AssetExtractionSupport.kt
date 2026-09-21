/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Small, platform-independent pieces of the asset extraction pipeline.
 */
package com.excp.podroid.util

import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Helpers kept separate so extraction failure and cleanup semantics can be unit-tested. */
internal object AssetExtractionSupport {
    private const val TMP_SUFFIX = ".tmp"

    /** Runs every extraction task and reports whether all tasks completed successfully. */
    fun runTasks(tasks: List<() -> Unit>, onTaskFailure: (Exception) -> Unit = {}): Boolean {
        val pool = Executors.newFixedThreadPool(tasks.size.coerceAtMost(4))
        var allSucceeded = true
        try {
            val futures = pool.invokeAll(tasks.map { task ->
                Callable<Unit> { task() }
            })
            for (future in futures) {
                try {
                    future.get()
                } catch (e: Exception) {
                    onTaskFailure(e)
                    allSucceeded = false
                }
            }
        } finally {
            pool.shutdown()
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow()
                allSucceeded = false
            }
        }
        return allSucceeded
    }

    fun temporaryFileFor(destFile: File): File =
        File(destFile.parentFile, destFile.name + TMP_SUFFIX)

    /**
     * Deletes only the temporary sibling owned by [destFile]. Returns false if
     * a stale sibling exists but cannot be removed; callers may log and continue.
     */
    fun deleteStaleTemporaryFile(destFile: File): Boolean {
        val temporaryFile = temporaryFileFor(destFile)
        return !temporaryFile.exists() || temporaryFile.delete()
    }
}
