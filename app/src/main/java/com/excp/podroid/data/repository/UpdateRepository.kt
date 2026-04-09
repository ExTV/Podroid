package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
)

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dismissedKey = stringPreferencesKey("dismissed_update_version")
    private val lastCheckKey = longPreferencesKey("update_check_timestamp")
    private val cacheValidityMs = 24 * 60 * 60 * 1000L

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            val lastCheck = context.dataStore.data.first()[lastCheckKey] ?: 0L

            if (now - lastCheck < cacheValidityMs) {
                return@withContext null
            }

            val connection = URL("https://api.github.com/repos/ExTV/Podroid/releases/latest")
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val json = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val obj = JSONObject(json)
            val tag = obj.getString("tag_name").trimStart('v')
            val url = obj.getString("html_url")

            context.dataStore.edit { it[lastCheckKey] = now }

            if (isNewer(tag, currentVersion)) UpdateInfo(tag, url) else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun isDismissed(version: String): Boolean {
        val dismissed = context.dataStore.data.first()[dismissedKey]
        return dismissed == version
    }

    suspend fun dismissUpdate(version: String) {
        context.dataStore.edit { it[dismissedKey] = version }
    }

    /** Returns true if `latest` is a higher version than `current`. */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(l.size, c.size)
        for (i in 0 until maxLen) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
