/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Reads the container count written by the guest `podroid-update-stats` tool
 * into Downloads/Podroid/container-count when sharing is enabled.
 */
package com.excp.podroid.data.repository

import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContainerStatsRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    fun statsFile(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(File(downloads, "Podroid"), "container-count")
    }

    suspend fun readContainerCount(): Int? {
        val file = statsFile()
        val text = withContext(Dispatchers.IO) {
            if (file.isFile) file.readText() else null
        } ?: return settingsRepository.getLastContainerCount()
        val parsed = text.trim().toIntOrNull() ?: return settingsRepository.getLastContainerCount()
        settingsRepository.setLastContainerCount(parsed)
        return parsed
    }
}
