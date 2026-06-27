package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
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

/**
 * Returns true when the cache has expired and a fresh network check is warranted.
 *
 * Uses wall-clock milliseconds (currentTimeMillis) — not uptimeMillis, which
 * resets to zero on reboot and causes the post-reboot delta to be a large
 * negative number, permanently skipping the check. A negative delta is clamped
 * to "stale" so a clock going backwards also triggers a re-check rather than
 * suppressing it indefinitely.
 */
internal fun shouldCheck(now: Long, lastCheck: Long, validityMs: Long): Boolean {
    val elapsed = now - lastCheck
    return elapsed < 0L || elapsed >= validityMs
}

/**
 * Timestamp to persist after a failed update check so [shouldCheck] re-opens the
 * gate at the right time. A definitive outcome (GitHub was actually reached)
 * backs off the full [validityMs]. A transient failure (offline / DNS / timeout)
 * is back-dated so the existing validity gate re-opens after only [retryFloorMs]
 * instead of a full day — one offline launch must not suppress the check for 24h.
 */
internal fun backoffStamp(now: Long, transient: Boolean, validityMs: Long, retryFloorMs: Long): Long =
    if (transient) now - validityMs + retryFloorMs else now

/**
 * Returns true if `latest` is a higher version than `current`. Compares the
 * numeric core (`1.2.3`) first; if those are equal, treats a prerelease suffix
 * (`-rc2`) as lower than a release, and compares prerelease numeric chunks
 * numerically so `rc10 > rc9`.
 */
internal fun isNewer(latest: String, current: String): Boolean {
    fun splitCore(v: String) = v.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
    val l = splitCore(latest)
    val c = splitCore(current)
    val maxLen = maxOf(l.size, c.size)
    for (i in 0 until maxLen) {
        val lv = l.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (lv > cv) return true
        if (lv < cv) return false
    }
    // Numeric cores match — break ties by suffix. Empty suffix > any prerelease suffix.
    val ls = latest.substringAfter("-", "")
    val cs = current.substringAfter("-", "")
    return when {
        ls == cs -> false
        ls.isEmpty() -> true   // "1.2.0" is newer than "1.2.0-rc1"
        cs.isEmpty() -> false  // "1.2.0-rc1" is older than "1.2.0"
        else -> comparePrerelease(ls, cs) > 0
    }
}

/**
 * Compares two prerelease strings (the part after `-`) by splitting on
 * non-numeric/numeric boundaries and comparing each chunk: numeric chunks
 * compare as integers, non-numeric chunks compare lexicographically.
 * Example: "rc10" > "rc9" because the numeric chunk 10 > 9.
 */
private fun comparePrerelease(a: String, b: String): Int {
    fun chunks(s: String): List<String> = buildList {
        val sb = StringBuilder()
        var numeric = s.firstOrNull()?.isDigit() ?: false
        for (ch in s) {
            if (ch.isDigit() == numeric) {
                sb.append(ch)
            } else {
                if (sb.isNotEmpty()) add(sb.toString())
                sb.clear()
                sb.append(ch)
                numeric = ch.isDigit()
            }
        }
        if (sb.isNotEmpty()) add(sb.toString())
    }

    val ac = chunks(a)
    val bc = chunks(b)
    val len = maxOf(ac.size, bc.size)
    for (i in 0 until len) {
        val ac_ = ac.getOrElse(i) { "" }
        val bc_ = bc.getOrElse(i) { "" }
        val cmp = when {
            ac_.isEmpty() && bc_.isEmpty() -> 0
            ac_.isEmpty() -> -1
            bc_.isEmpty() -> 1
            ac_.all { it.isDigit() } && bc_.all { it.isDigit() } ->
                // Clamp oversized numeric chunks to lexicographic so a malformed
                // tag (>18 digits, > Long.MAX) can't throw NumberFormatException.
                if (ac_.length <= 18 && bc_.length <= 18) ac_.toLong().compareTo(bc_.toLong())
                else ac_.compareTo(bc_)
            else -> ac_.compareTo(bc_)
        }
        if (cmp != 0) return cmp
    }
    return 0
}

@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dismissedKey = stringPreferencesKey("dismissed_update_version")
    private val lastCheckKey = longPreferencesKey("update_check_timestamp")
    private val cacheValidityMs = 24 * 60 * 60 * 1000L
    // A transient (IOException) failure backs off only this long instead of the
    // full cacheValidityMs, so a single offline launch doesn't suppress the next
    // in-app update check for a full day.
    private val retryFloorMs = 20 * 60 * 1000L

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        // Wall-clock time so the 24h gate survives device reboots and deep sleep.
        val now = System.currentTimeMillis()
        var connection: java.net.HttpURLConnection? = null
        try {
            val lastCheck = context.dataStore.data
                .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
                .first()[lastCheckKey] ?: 0L

            if (!shouldCheck(now, lastCheck, cacheValidityMs)) {
                return@withContext null
            }

            connection = URL("https://api.github.com/repos/ExTV/Podroid/releases/latest")
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            val code = connection.responseCode
            if (code !in 200..299) {
                // Consume error body so the connection can be reused, then back off.
                runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            val json = connection.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            // removePrefix rather than trimStart — trimStart strips repeated 'v' chars.
            val tag = obj.optString("tag_name", "").removePrefix("v")
            val url = obj.optString("html_url", "")

            if (tag.isEmpty() || url.isEmpty()) {
                context.dataStore.edit { it[lastCheckKey] = now }
                return@withContext null
            }

            context.dataStore.edit { it[lastCheckKey] = now }

            // Build-type suffix (`-debug`) is decoration, not a prerelease — strip it so
            // 1.1.7-debug compares equal to the 1.1.7 release tag instead of "older".
            val normalizedCurrent = currentVersion.removeSuffix("-debug")
            if (isNewer(tag, normalizedCurrent)) UpdateInfo(tag, url) else null
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Coroutine cancellation is not a check failure — don't record a
            // timestamp (which would back off a check the user may retry) and
            // don't swallow it; let it propagate.
            throw c
        } catch (e: Exception) {
            // Network/DataStore/JSON error: log it (a changed GitHub response or
            // JSON shape was previously failing invisibly) and record a timestamp
            // to back off on the next launch. A transient network failure
            // (offline / DNS / timeout — all IOException) only earns the short
            // retry floor; a non-IO error means GitHub was reached but returned
            // something unusable, so it keeps the full 24h backoff.
            android.util.Log.w("UpdateRepository", "update check failed", e)
            val stamp = backoffStamp(now, transient = e is java.io.IOException, cacheValidityMs, retryFloorMs)
            runCatching { context.dataStore.edit { it[lastCheckKey] = stamp } }
            null
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun isDismissed(version: String): Boolean {
        // Shield the read so a corrupted store returns "not dismissed" instead of
        // throwing into the caller.
        val dismissed = context.dataStore.data
            .catch { e -> if (e is java.io.IOException) emit(emptyPreferences()) else throw e }
            .first()[dismissedKey]
        return dismissed == version
    }

    suspend fun dismissUpdate(version: String) {
        context.dataStore.edit { it[dismissedKey] = version }
    }
}
