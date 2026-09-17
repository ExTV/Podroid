/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Theme and font file handling for the terminal: bundled assets plus
 * user-imported themes/fonts under getExternalFilesDir. Moved out of
 * TerminalViewModel so the ViewModel stays focused on session wiring;
 * TerminalViewModel keeps one-line delegates to the public methods here so
 * TerminalScreen/TerminalQuickSettings call sites are unchanged.
 */
package com.excp.podroid.ui.screens.terminal

import android.content.Context
import android.graphics.Typeface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalAppearanceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Where user-imported fonts live. App-private external dir means no permission
     * prompts and a clean uninstall, but it's not visible to third-party file
     * managers on Android 11+ - so we drive imports via SAF (`importCustomFont`)
     * rather than asking users to "drop a .ttf into a folder".
     */
    private val userFontsDir: java.io.File by lazy {
        java.io.File(context.getExternalFilesDir(null), "fonts").apply { mkdirs() }
    }

    /** Where user-imported themes live. Same rationale as userFontsDir. */
    private val userThemesDir: java.io.File by lazy {
        java.io.File(context.getExternalFilesDir(null), "colors").apply { mkdirs() }
    }

    /** User dir wins over bundled when names collide. */
    fun readThemeProperties(theme: String): java.util.Properties? {
        val props = java.util.Properties()
        val userFile = java.io.File(userThemesDir, "$theme.properties")
        return try {
            if (userFile.isFile) {
                userFile.inputStream().use { props.load(it) }
            } else {
                context.assets.open("colors/$theme.properties").use { props.load(it) }
            }
            props
        } catch (_: Exception) { null }
    }

    /**
     * Peek a theme's bg+fg ARGB ints without mutating the active palette.
     * Used by the Settings sheet to render theme preview swatches.
     * Returns null for `default` (caller falls back to neutral colors).
     */
    fun peekThemeColors(theme: String): Pair<Int, Int>? {
        if (theme == "default") return null
        val props = readThemeProperties(theme) ?: return null
        val bg = (props["background"] as? String)?.let { parseHexColor(it) } ?: return null
        val fg = (props["foreground"] as? String)?.let { parseHexColor(it) } ?: 0xFFE0E0E0.toInt()
        return bg to fg
    }

    /** Bundled .properties themes union user-imported. */
    fun listAvailableThemes(): List<String> {
        val bundled = runCatching { context.assets.list("colors")?.toList() }
            .getOrNull().orEmpty()
        val user = (userThemesDir.listFiles() ?: emptyArray())
            .filter { it.isFile }.map { it.name }
        val names = (bundled + user)
            .filter { it.endsWith(".properties", ignoreCase = true) }
            .map { it.substringBeforeLast('.') }
            .toSortedSet(String.CASE_INSENSITIVE_ORDER)
        return listOf("default") + names.toList()
    }

    fun isCustomTheme(name: String): Boolean =
        java.io.File(userThemesDir, "$name.properties").isFile

    fun deleteCustomTheme(name: String): Boolean {
        val f = java.io.File(userThemesDir, "$name.properties")
        return f.exists() && f.delete()
    }

    /**
     * Import a theme from a terminalcolors.com URL.
     * Accepts pages like `https://terminalcolors.com/themes/dracula/default/` -
     * we transform the slug into the predictable Alacritty TOML download URL,
     * fetch it, and convert to our `.properties` format.
     *
     * Returns the saved theme name, or null on any failure.
     */
    suspend fun importThemeFromUrl(input: String): String? = withContext(Dispatchers.IO) {
        val url = input.trim()
        // Extract slug from the page URL or accept the .toml URL directly.
        val tomlUrl: String = when {
            url.endsWith(".toml") -> url
            else -> {
                val match = Regex("""terminalcolors\.com/themes/([^/]+)/([^/?#]+)""").find(url)
                    ?: return@withContext null
                val (name, variant) = match.groupValues[1] to match.groupValues[2]
                "https://terminalcolors.com/downloads/alacritty/$name-$variant.toml"
            }
        }
        val toml = runCatching {
            val conn = (java.net.URL(tomlUrl).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty(
                    "User-Agent",
                    "Podroid/${com.excp.podroid.BuildConfig.VERSION_NAME}"
                )
            }
            try {
                if (conn.responseCode != 200) return@runCatching null
                conn.inputStream.bufferedReader().readText()
            } finally { conn.disconnect() }
        }.getOrNull() ?: return@withContext null

        val themeName = sanitizeFontName(
            tomlUrl.substringAfterLast('/').substringBeforeLast('.')
        ) ?: return@withContext null

        val properties = parseAlacrittyToml(toml) ?: return@withContext null

        val dest = java.io.File(userThemesDir, "$themeName.properties")
        dest.bufferedWriter().use { w ->
            for ((k, v) in properties) w.write("$k=$v\n")
        }
        themeName
    }

    /** Bundled assets union user-imported TTFs (case-insensitive dedupe - user wins). */
    fun listAvailableFonts(): List<String> {
        val bundled = runCatching { context.assets.list("fonts")?.toList() }
            .getOrNull().orEmpty()
        val user = (userFontsDir.listFiles() ?: emptyArray())
            .filter { it.isFile }.map { it.name }
        val names = (bundled + user)
            .filter { it.endsWith(".ttf", ignoreCase = true) }
            .map { it.substringBeforeLast('.') }
            .toSortedSet(String.CASE_INSENSITIVE_ORDER)
        return listOf("default") + names.toList()
    }

    /** True if `name` was imported by the user (overrides any bundled font of the same name). */
    fun isCustomFont(name: String): Boolean =
        java.io.File(userFontsDir, "$name.ttf").isFile

    /** Resolve a font name to a Typeface. Returns Typeface.MONOSPACE for default or on error. */
    fun loadFont(font: String): Typeface {
        if (font == "default") return Typeface.MONOSPACE
        // User imports win over bundled with the same name.
        val userFile = java.io.File(userFontsDir, "$font.ttf")
        if (userFile.isFile) {
            return runCatching { Typeface.createFromFile(userFile) }
                .getOrDefault(Typeface.MONOSPACE)
        }
        return try {
            // assets.openFd is a file descriptor - Typeface.createFromFile needs a real path,
            // so we copy on demand into a per-launch cache file.
            val cacheFile = java.io.File(context.cacheDir, "font_$font.ttf")
            if (!cacheFile.exists()) {
                context.assets.open("fonts/$font.ttf").use { inp ->
                    cacheFile.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            Typeface.createFromFile(cacheFile)
        } catch (_: Exception) { Typeface.MONOSPACE }
    }

    /**
     * Import a `.ttf` from a SAF `Uri` into the user fonts dir.
     * Returns the sanitized font name (no extension) on success, null on failure.
     *
     * Safety:
     * - Filename sanitized to ASCII alnum + dash/underscore (max 48 chars).
     * - Capped at 16 MiB; legitimate TTFs are well under 1 MB.
     * - Validates by constructing a Typeface; rejects unloadable files.
     * - Writes to `.tmp` then renames so a partial copy never wins picker enumeration.
     */
    fun importCustomFont(uri: android.net.Uri): String? {
        val rawName = displayNameOf(uri) ?: return null
        val name = sanitizeFontName(rawName) ?: return null
        val tmp = java.io.File(userFontsDir, "$name.ttf.tmp")
        val dest = java.io.File(userFontsDir, "$name.ttf")
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                tmp.outputStream().use { out ->
                    val maxBytes = 16L * 1024 * 1024
                    val buf = ByteArray(8192)
                    var written = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        written += n
                        if (written > maxBytes) return cleanup(tmp, null)
                        out.write(buf, 0, n)
                    }
                }
            }
            // Validate by trying to load it.
            val tf = runCatching { Typeface.createFromFile(tmp) }.getOrNull()
            if (tf == null || tf === Typeface.DEFAULT) return cleanup(tmp, null)
            // Atomic-ish swap.
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) return cleanup(tmp, null)
            // Invalidate the asset-cache copy in case a bundled font of the same
            // name was previously loaded - next loadFont() should see the new file.
            java.io.File(context.cacheDir, "font_$name.ttf").delete()
            name
        } catch (_: Exception) {
            cleanup(tmp, null)
        }
    }

    /** Remove a previously-imported custom font. Returns true if it existed and was removed. */
    fun deleteCustomFont(name: String): Boolean {
        val f = java.io.File(userFontsDir, "$name.ttf")
        val ok = f.exists() && f.delete()
        if (ok) java.io.File(context.cacheDir, "font_$name.ttf").delete()
        return ok
    }

    private fun <T> cleanup(tmp: java.io.File, result: T?): T? {
        runCatching { if (tmp.exists()) tmp.delete() }
        return result
    }

    private fun displayNameOf(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun sanitizeFontName(filename: String): String? {
        val base = filename.substringBeforeLast('.').trim()
        val safe = base.replace(Regex("[^A-Za-z0-9_-]"), "-").trim('-').take(48)
        return safe.takeIf { it.isNotEmpty() }
    }
}
