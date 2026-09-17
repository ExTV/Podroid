/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Lists container backup archives written by the guest `podroid-backup` tool
 * into Downloads/Podroid/backups when sharing is enabled.
 */
package com.excp.podroid.data.repository

import android.os.Environment
import com.excp.podroid.util.ShellQuote
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ContainerBackupFile(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val absolutePath: String,
)

@Singleton
class ContainerBackupRepository @Inject constructor() {
    companion object {
        const val BACKUP_SUBDIR = "Podroid/backups"
    }

    fun backupDirectory(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, BACKUP_SUBDIR)
    }

    fun guestBackupPathLabel(downloadsSharingEnabled: Boolean = isDownloadsReachable()): String =
        if (downloadsSharingEnabled) "/mnt/downloads/Podroid/backups"
        else "/var/backups/podroid"

    fun isDownloadsReachable(): Boolean {
        val dir = backupDirectory()
        // Create Downloads/Podroid/backups if absent: the app holds All-files
        // access whenever Downloads sharing is on, so a successful mkdirs both
        // confirms reachability and gives the guest an existing target under the
        // 9p/virtio-9p mount. Nothing else creates this dir, so without it every
        // backup would fall back to the guest-internal path and never surface in
        // Downloads.
        return runCatching { dir.exists() || dir.mkdirs() }.getOrDefault(false)
    }

    fun listBackupFiles(): List<ContainerBackupFile> {
        val dir = backupDirectory()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".tar") || it.name.endsWith(".tar.gz")) }
            ?.map { f ->
                ContainerBackupFile(
                    name = f.name,
                    sizeBytes = f.length(),
                    lastModifiedMs = f.lastModified(),
                    absolutePath = f.absolutePath,
                )
            }
            ?.sortedByDescending { it.lastModifiedMs }
            ?: emptyList()
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatDate(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    fun exportCommand(containerName: String): String {
        val q = ShellQuote.quote(containerName.trim())
        val fb = fallbackExportCommand(containerName)
        return "if command -v podroid-backup >/dev/null 2>&1; then podroid-backup export $q; else $fb; fi"
    }

    fun saveImageCommand(imageRef: String): String {
        val q = ShellQuote.quote(imageRef.trim())
        val root = guestBackupPathLabel()
        return "if command -v podroid-backup >/dev/null 2>&1; then podroid-backup save $q; else mkdir -p $root && podman save $q -o $root/\$(echo $q | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar; fi"
    }

    fun listCommand(): String = "podroid-backup list"

    fun fallbackExportCommand(containerName: String): String {
        val q = ShellQuote.quote(containerName.trim())
        val root = guestBackupPathLabel()
        return "mkdir -p $root && podman export $q -o $root/\$(echo $q | tr -cd 'A-Za-z0-9._-')-\$(date +%Y%m%d-%H%M%S).tar"
    }
}
