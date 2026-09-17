/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Terminal ViewModel — wires TerminalView to the podroid-bridge binary.
 *
 * The bridge binary (libpodroid-bridge.so) runs as the TerminalSession
 * subprocess. Termux allocates a real PTY for it; the bridge relays that
 * PTY to QEMU's virtio-console terminal.sock (= /dev/hvc0 in the VM).
 * Window resize is handled out-of-band over a second virtio-console port:
 *
 *   TerminalSession.updateSize(cols, rows)
 *     → ioctl(pty_master, TIOCSWINSZ)          [Termux JNI]
 *     → SIGWINCH → bridge process
 *     → bridge debounces (RESIZE_DEBOUNCE_MS, currently 200 ms) so a
 *       keyboard-slide animation collapses to one event
 *     → reads final size via TIOCGWINSZ
 *     → writes "RESIZE rows cols\n" to ctrl.sock (= /dev/hvc1 in the VM)
 *     → init-podroid resize daemon calls stty on /dev/hvc0
 *     → Linux sends SIGWINCH to the VM's foreground process group
 *     → nvim / htop / btop redraws correctly
 *
 * No reflection, no emulator injection, no sz stdin injection.
 */
package com.excp.podroid.ui.screens.terminal

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalColors
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.util.LogProxy
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VmEngine,
    private val settingsRepository: SettingsRepository,
    private val headlessModeManager: com.excp.podroid.engine.hostbridge.HeadlessModeManager,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state
    val bootStage: StateFlow<String> = engine.bootStage
    val terminalFontSize: StateFlow<Int> = settingsRepository.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    val terminalColorTheme: StateFlow<String> = settingsRepository.terminalColorTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    val terminalFont: StateFlow<String> = settingsRepository.terminalFont
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "default")

    // Persisted across sessions — was previously transient in-memory only.
    val showExtraKeysFlow: StateFlow<Boolean> = settingsRepository.showExtraKeys
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val hapticsEnabledFlow: StateFlow<Boolean> = settingsRepository.hapticsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Mirrors of the persisted flows for callers that want a synchronous read. */
    val showExtraKeys: Boolean get() = showExtraKeysFlow.value
    val hapticsEnabled: Boolean get() = hapticsEnabledFlow.value

    // Trigger for opening the Quick Settings drawer (composable-side reacts via StateFlow)
    private val _showQuickSettings = kotlinx.coroutines.flow.MutableStateFlow(false)
    val showQuickSettings = _showQuickSettings

    // Bumped when the session dies while the VM is still Running, so the screen
    // re-creates and re-attaches a fresh session (dead-session auto-reconnect).
    private val _reconnectSignal = kotlinx.coroutines.flow.MutableStateFlow(0)
    val reconnectSignal: StateFlow<Int> = _reconnectSignal

    // True once auto-reconnect has hit its cap and given up; the screen surfaces
    // a "tap to reconnect" affordance that calls retryConnection().
    private val _reconnectExhausted = kotlinx.coroutines.flow.MutableStateFlow(false)
    val reconnectExhausted: StateFlow<Boolean> = _reconnectExhausted

    // Auto-reconnect governor state (see onSessionFinished + companion bounds).
    private var reconnectAttempts = 0
    private var reconnectWindowStartMs = 0L

    init {
        // Each fresh VM run starts with a clean governor, and a stale "exhausted"
        // from a previous run is cleared so the new run's session attaches.
        viewModelScope.launch {
            engine.state.collect { st ->
                if (st !is VmState.Running) {
                    reconnectAttempts = 0
                    reconnectWindowStartMs = 0L
                    _reconnectExhausted.value = false
                }
            }
        }
    }

    /** Manual reconnect after auto-reconnect gave up (see [reconnectExhausted]). */
    fun retryConnection() {
        reconnectAttempts = 0
        reconnectWindowStartMs = System.currentTimeMillis()
        _reconnectExhausted.value = false
        _reconnectSignal.value += 1
    }

    // Quick settings helpers (non-persistent)
    fun openQuickSettings() { _showQuickSettings.value = true }
    fun closeQuickSettings() { _showQuickSettings.value = false }

    fun enableServerMode() = headlessModeManager.setActive(true)

    fun updateShowExtraKeys(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowExtraKeys(value) }
    }
    fun updateHapticsEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHapticsEnabled(value) }
    }

    // Synchronous mirror of the persisted font size so a fast pinch gesture steps
    // from the value it just set rather than the DataStore-backed StateFlow, which
    // hasn't committed yet (reading it on every callback collapsed several
    // intended steps into one). -1 until the first write seeds it.
    private var liveFontSize: Int = -1

    fun setTerminalFontSize(value: Int) {
        // Clamp here so pinch and the slider (which share MIN/MAX_FONT_SIZE) can
        // never persist a size the other surface would snap away from.
        val clamped = value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        liveFontSize = clamped
        viewModelScope.launch {
            settingsRepository.setTerminalFontSize(clamped)
        }
    }

    fun setTerminalColorTheme(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalColorTheme(value) }
    }

    fun setTerminalFont(value: String) {
        viewModelScope.launch { settingsRepository.setTerminalFont(value) }
    }

    private var attached = false

    /**
     * Weak handle to the currently-attached TerminalView. Used only by client
     * callbacks that need to call onScreenUpdated() / showSoftInput(), which
     * fire from non-UI threads. WeakReference prevents leaking the destroyed
     * Activity across config changes.
     */
    private var viewRef: java.lang.ref.WeakReference<TerminalView>? = null

    fun bindView(view: TerminalView?) {
        viewRef = view?.let { java.lang.ref.WeakReference(it) }
    }

    /**
     * Peek a theme's bg+fg ARGB ints without mutating the active palette.
     * Used by the Settings sheet to render theme preview swatches.
     * Returns null for `default` (caller falls back to neutral colors).
     */
    fun peekThemeColors(theme: String): Pair<Int, Int>? {
        if (theme == "default") return null
        val props = readThemeProperties(theme) ?: return null
        val bg = (props["background"] as? String)?.let { parseColor(it) } ?: return null
        val fg = (props["foreground"] as? String)?.let { parseColor(it) } ?: 0xFFE0E0E0.toInt()
        return bg to fg
    }

    /**
     * Resolve a theme name to the (background, Properties) pair, also pushing
     * the palette into TerminalColors.COLOR_SCHEME so the renderer picks it up.
     * Returns null background for the built-in default.
     */
    fun loadColorTheme(theme: String): Int? {
        // "default" path passes empty props through updateWith → resets
        // COLOR_SCHEME to the built-in defaults and yields a null bg
        // (renderer falls back to BLACK). Previously the "default" branch
        // returned early without resetting, so switching from a custom
        // theme to "default" left the previous palette in place.
        val props = if (theme == "default") {
            java.util.Properties()
        } else {
            readThemeProperties(theme) ?: return null
        }
        TerminalColors.COLOR_SCHEME.updateWith(props)
        // updateWith refreshes the static defaults, but the live session's
        // mCurrentColors is a per-session cache populated at session start
        // and on `\ec` (RIS). Without this push, theme changes only took
        // effect after the user typed `reset` in the shell.
        session?.emulator?.mColors?.reset()
        return (props["background"] as? String)?.let { parseColor(it) }
    }

    /** User dir wins over bundled when names collide. */
    private fun readThemeProperties(theme: String): java.util.Properties? {
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

    /** Lists asset files in `dir` whose name ends with `suffix`, with the suffix stripped. */
    fun listAssetNames(dir: String, suffix: String): List<String> {
        val items = try {
            context.assets.list(dir)?.toList() ?: emptyList()
        } catch (_: Exception) { emptyList() }
        return listOf("default") + items.filter { it.endsWith(suffix) }.map { it.removeSuffix(suffix) }.sorted()
    }

    /**
     * Where user-imported fonts live. App-private external dir means no permission
     * prompts and a clean uninstall, but it's not visible to third-party file
     * managers on Android 11+ — so we drive imports via SAF (`importCustomFont`)
     * rather than asking users to "drop a .ttf into a folder".
     */
    private val userFontsDir: java.io.File by lazy {
        java.io.File(context.getExternalFilesDir(null), "fonts").apply { mkdirs() }
    }

    /** Where user-imported themes live. Same rationale as userFontsDir. */
    private val userThemesDir: java.io.File by lazy {
        java.io.File(context.getExternalFilesDir(null), "colors").apply { mkdirs() }
    }

    /** Bundled .properties themes ∪ user-imported. */
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
     * Accepts pages like `https://terminalcolors.com/themes/dracula/default/` —
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

    /**
     * Parse an Alacritty TOML color export into our `.properties` keys.
     * Returns null if required keys (background/foreground/16 ANSI colors) aren't found.
     */
    private fun parseAlacrittyToml(toml: String): Map<String, String>? {
        // [colors.<section>] key = "#xxxxxx"
        val sectionRe = Regex("""\[colors\.(\w+)]""")
        val kvRe = Regex("""(\w+)\s*=\s*"(#[0-9a-fA-F]{3,8})"""")
        var section = ""
        val byKey = LinkedHashMap<String, String>()
        for (raw in toml.lines()) {
            val line = raw.trim()
            sectionRe.find(line)?.let { section = it.groupValues[1]; return@let }
            val kv = kvRe.find(line) ?: continue
            val key = kv.groupValues[1]
            val value = kv.groupValues[2]
            val mapped = when (section) {
                "primary" -> when (key) {
                    "foreground" -> "foreground"
                    "background" -> "background"
                    else -> null
                }
                "cursor" -> when (key) {
                    "cursor" -> "cursor"
                    "text" -> null   // foreground-of-cursor — emulator doesn't store separately
                    else -> null
                }
                "normal" -> when (key) {
                    "black"   -> "color0"; "red"     -> "color1"
                    "green"   -> "color2"; "yellow"  -> "color3"
                    "blue"    -> "color4"; "magenta" -> "color5"
                    "cyan"    -> "color6"; "white"   -> "color7"
                    else -> null
                }
                "bright" -> when (key) {
                    "black"   -> "color8";  "red"     -> "color9"
                    "green"   -> "color10"; "yellow"  -> "color11"
                    "blue"    -> "color12"; "magenta" -> "color13"
                    "cyan"    -> "color14"; "white"   -> "color15"
                    else -> null
                }
                else -> null
            } ?: continue
            byKey[mapped] = value
        }
        // Sanity: must have FG + BG and at least 8 ANSI colors.
        if (!byKey.containsKey("foreground") || !byKey.containsKey("background")) return null
        if ((0..7).any { !byKey.containsKey("color$it") }) return null
        return byKey
    }

    /** Bundled assets ∪ user-imported TTFs (case-insensitive dedupe — user wins). */
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
            // assets.openFd is a file descriptor — Typeface.createFromFile needs a real path,
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
            // name was previously loaded — next loadFont() should see the new file.
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

    private fun parseColor(hex: String): Int {
        val clean = hex.removePrefix("#")
        return when (clean.length) {
            3 -> {
                val r = clean[0].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
                val g = clean[1].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
                val b = clean[2].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
                android.graphics.Color.rgb(r * 17, g * 17, b * 17)
            }
            6 -> {
                android.graphics.Color.rgb(
                    clean.substring(0, 2).toInt(16),
                    clean.substring(2, 4).toInt(16),
                    clean.substring(4, 6).toInt(16)
                )
            }
            8 -> {
                android.graphics.Color.argb(
                    clean.substring(0, 2).toInt(16),
                    clean.substring(2, 4).toInt(16),
                    clean.substring(4, 6).toInt(16),
                    clean.substring(6, 8).toInt(16)
                )
            }
            else -> android.graphics.Color.BLACK
        }
    }

    var session: TerminalSession? = null
        private set

    var extraCtrl by mutableStateOf(false)
        private set
    var extraAlt by mutableStateOf(false)
        private set

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            viewRef?.get()?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            // Bridge/session died while the VM is still Running (a socket hiccup,
            // a bridge crash) — not a VM shutdown. Signal the screen to drop the
            // dead session and reconnect so the user isn't stranded on a
            // "[Process completed]" buffer. On a real VM shutdown vmState is no
            // longer Running, so we leave teardown to the normal path.
            if (vmState.value !is VmState.Running) return
            if (_reconnectExhausted.value) return  // waiting on a manual retry

            // Governor: a chardev that accepts the connection then immediately
            // EOFs would otherwise respawn the bridge (process + threads) many
            // times per second. Reset the burst window once enough time has
            // passed that the last session was plausibly healthy; a tight failure
            // loop stays inside the window, trips the cap, and falls back to a
            // manual "tap to reconnect" instead of looping forever.
            val now = System.currentTimeMillis()
            if (now - reconnectWindowStartMs > RECONNECT_WINDOW_MS) {
                reconnectWindowStartMs = now
                reconnectAttempts = 0
            }
            if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                reconnectAttempts++
                _reconnectSignal.value += 1
            } else {
                _reconnectExhausted.value = true
            }
        }
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text == null) return
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            // Route through TerminalEmulator.paste() — it wraps in CSI 200~ / 201~
            // when bracketed-paste mode is on (nvim, bash) and sends raw otherwise.
            // Fall back to raw write if the emulator isn't ready yet.
            val emu = session?.emulator
            if (emu != null) emu.paste(text) else session?.write(text)
        }
        override fun onBell(session: TerminalSession) {
            if (hapticsEnabled) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun getTerminalVersionString(): String =
            "Podroid ${com.excp.podroid.BuildConfig.VERSION_NAME}"
        override fun logError(tag: String?, message: String?) = LogProxy.error(tag, TAG, message)
        override fun logWarn(tag: String?, message: String?) = LogProxy.warn(tag, TAG, message)
        override fun logInfo(tag: String?, message: String?) = LogProxy.info(tag, TAG, message)
        override fun logDebug(tag: String?, message: String?) = LogProxy.debug(tag, TAG, message)
        override fun logVerbose(tag: String?, message: String?) = LogProxy.verbose(tag, TAG, message)
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, message, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    val viewClient = object : TerminalViewClient {
        // Pinch-to-zoom → font size. TerminalView accumulates the gesture into
        // the scale passed here; once it crosses ±10% we bump the persisted font
        // size by one step and return 1.0f to reset the accumulator (Termux's
        // canonical pattern). Below the threshold we pass it through unchanged.
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                // Step from the synchronous mirror, not the lagging StateFlow, so
                // rapid callbacks within one gesture don't all read the same stale
                // value and collapse multiple steps into one. Falls back to the
                // persisted value until the first write seeds liveFontSize.
                val base = liveFontSize.takeIf { it in MIN_FONT_SIZE..MAX_FONT_SIZE }
                    ?: terminalFontSize.value
                val step = if (scale < 1f) -1 else 1
                val next = (base + step).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
                if (next != base) setTerminalFontSize(next)
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            val view = viewRef?.get() ?: return
            // OSC 8: tap on a hyperlinked region launches the URL instead of the keyboard.
            if (e != null) {
                val url = view.getHyperlinkAt(e.x, e.y)
                if (!url.isNullOrEmpty()) {
                    runCatching {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                    return
                }
            }
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(view, 0)
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (e == null) return false
            val shift = e.isShiftPressed
            val ctrl = e.isCtrlPressed || extraCtrl
            val alt = e.isAltPressed || extraAlt
            // xterm CSI modifier: 1=none, 2=shift, 3=alt, 4=shift+alt, 5=ctrl,
            // 6=ctrl+shift, 7=ctrl+alt, 8=all. Used for "ESC [1;<m><final>".
            val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
            cursorKeysApplicationMode(session?.emulator)
            fun arrow(final: Char): ByteArray =
                if (mod == 1) {
                    if (cursorKeysApplicationMode(session?.emulator)) "\u001bO$final".toByteArray()
                    else "\u001b[$final".toByteArray()
                } else {
                    "\u001b[1;$mod$final".toByteArray()
                }

            val bytes = when (keyCode) {
                KeyEvent.KEYCODE_ENTER        -> byteArrayOf(13)
                KeyEvent.KEYCODE_DEL          -> byteArrayOf(127)
                KeyEvent.KEYCODE_FORWARD_DEL  -> "\u001b[3~".toByteArray()
                KeyEvent.KEYCODE_TAB          ->
                    if (shift) "\u001b[Z".toByteArray() else byteArrayOf(9)
                KeyEvent.KEYCODE_ESCAPE       -> byteArrayOf(27)
                KeyEvent.KEYCODE_DPAD_UP      -> arrow('A')
                KeyEvent.KEYCODE_DPAD_DOWN    -> arrow('B')
                KeyEvent.KEYCODE_DPAD_RIGHT   -> arrow('C')
                KeyEvent.KEYCODE_DPAD_LEFT    -> arrow('D')
                KeyEvent.KEYCODE_MOVE_HOME    -> arrow('H')
                KeyEvent.KEYCODE_MOVE_END     -> arrow('F')
                KeyEvent.KEYCODE_PAGE_UP      -> "\u001b[5~".toByteArray()
                KeyEvent.KEYCODE_PAGE_DOWN    -> "\u001b[6~".toByteArray()
                KeyEvent.KEYCODE_INSERT       -> "\u001b[2~".toByteArray()
                KeyEvent.KEYCODE_F1           -> "\u001bOP".toByteArray()
                KeyEvent.KEYCODE_F2           -> "\u001bOQ".toByteArray()
                KeyEvent.KEYCODE_F3           -> "\u001bOR".toByteArray()
                KeyEvent.KEYCODE_F4           -> "\u001bOS".toByteArray()
                KeyEvent.KEYCODE_F5           -> "\u001b[15~".toByteArray()
                KeyEvent.KEYCODE_F6           -> "\u001b[17~".toByteArray()
                KeyEvent.KEYCODE_F7           -> "\u001b[18~".toByteArray()
                KeyEvent.KEYCODE_F8           -> "\u001b[19~".toByteArray()
                KeyEvent.KEYCODE_F9           -> "\u001b[20~".toByteArray()
                KeyEvent.KEYCODE_F10          -> "\u001b[21~".toByteArray()
                KeyEvent.KEYCODE_F11          -> "\u001b[23~".toByteArray()
                KeyEvent.KEYCODE_F12          -> "\u001b[24~".toByteArray()
                else -> null
            }
            if (bytes != null) {
                session?.write(bytes, 0, bytes.size)
                if (mod != 1) { extraCtrl = false; extraAlt = false }
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = extraCtrl
        override fun readAltKey(): Boolean = extraAlt
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            val ctrl = ctrlDown || extraCtrl
            // We only transform the canonical Ctrl range (64..127: Ctrl+@, Ctrl+A..Z,
            // Ctrl+[ \ ] ^ _). For any other Ctrl combination — most importantly
            // Ctrl+Space, which must emit NUL (readline set-mark, Emacs C-Space) —
            // return false so TerminalView applies its canonical sub-64 control
            // mapping instead of us writing the raw code point and swallowing it.
            // extraCtrl is left set: when the Ctrl came from the on-screen sticky
            // key (not a hardware modifier) the view reads it back via readControlKey().
            if (ctrl && codePoint !in 64..127) {
                extraAlt = false
                return false
            }
            val bytes: ByteArray = if (ctrl) {
                byteArrayOf((codePoint and 0x1f).toByte())
            } else {
                val charBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                if (extraAlt) byteArrayOf(27) + charBytes else charBytes
            }
            session?.write(bytes, 0, bytes.size)
            extraCtrl = false
            extraAlt = false
            return true
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) = LogProxy.error(tag, TAG, message)
        override fun logWarn(tag: String?, message: String?) = LogProxy.warn(tag, TAG, message)
        override fun logInfo(tag: String?, message: String?) = LogProxy.info(tag, TAG, message)
        override fun logDebug(tag: String?, message: String?) = LogProxy.debug(tag, TAG, message)
        override fun logVerbose(tag: String?, message: String?) = LogProxy.verbose(tag, TAG, message)
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) =
            LogProxy.stackTraceWithMessage(tag, TAG, message, e)
        override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, TAG, e)
    }

    fun createSession() {
        if (attached) return

        val sess = runCatching { engine.createTerminalSession(sessionClient) }
            .onFailure { e ->
                // AvfEngine throws UnsupportedOperationException until Task 11
                // wires the AVF bridge. Don't crash the UI — leave session null.
                android.util.Log.w(TAG, "createTerminalSession failed on ${engine.backendId}: ${e.message}")
            }
            .getOrNull() ?: return
        session = sess
        attached = true
    }

    /**
     * Force-create a new session. Called when the VM restarts to replace the
     * stale session from the previous run with a fresh one.
     */
    fun resetOnRestart() {
        attached = false
        session = null
    }

    // forceUpdateSizeFromView was removed deliberately: every call site was
    // replaced by view.updateSize() (whose row math subtracts
    // mFontLineSpacingAndAscent). The two disagreed by ±1 row, causing a
    // double-resize and cursor flicker on first paint and keyboard slides. Don't
    // reintroduce a paint-metrics size computation here — see the comments at the
    // TerminalView.updateSize() call sites in TerminalScreen.

    /**
     * Emit xterm focus-in/out (CSI I / CSI O) when the app gains/loses focus.
     * nvim's `FocusGained` / `FocusLost` autocommands rely on these. DECSET 1004
     * (`DECSET_BIT_SEND_FOCUS_EVENTS`) and `isDecsetInternalBitSet` are private
     * in the Termux AAR, so we read the `mCurrentDecSetFlags` field reflectively
     * and mask with the known bit. If the reflection ever breaks we silently
     * skip — sending focus bytes to a shell that didn't enable reporting would
     * leak literal "^[[I" noise into the prompt.
     */
    fun sendFocusEvent(focused: Boolean) {
        val sess = session ?: return
        val emu = sess.emulator ?: return
        if (!emu.isFocusEventsEnabled) return
        val seq = if (focused) "\u001b[I".toByteArray() else "\u001b[O".toByteArray()
        sess.write(seq, 0, seq.size)
    }

    /** True when DECCKM (application cursor keys) is on, so arrows send SS3
     *  (ESC O x) instead of CSI (ESC [ x). Public emulator accessor — no
     *  reflection, so it keeps working under R8 in release (the private DECSET
     *  field the old reflection read is not kept by proguard). */
    private fun cursorKeysApplicationMode(emu: TerminalEmulator?): Boolean =
        emu?.isCursorKeysApplicationMode == true

    fun sendExtraKey(key: String) {
        when (key) {
            "CTRL" -> { extraCtrl = !extraCtrl; return }
            "ALT"  -> { extraAlt = !extraAlt; return }
            // Route through the same bracketed-paste-aware path as the long-press
            // menu / middle-click, so the extra-keys row can paste too.
            "PASTE" -> { sessionClient.onPasteTextFromClipboard(session); return }
        }
        val bytes = when (key) {
            "ESC"  -> byteArrayOf(27)
            "TAB"  -> byteArrayOf(9)
            "UP"   -> if (cursorKeysApplicationMode(session?.emulator)) "\u001bOA".toByteArray() else "\u001b[A".toByteArray()
            "DOWN" -> if (cursorKeysApplicationMode(session?.emulator)) "\u001bOB".toByteArray() else "\u001b[B".toByteArray()
            "LEFT" -> if (cursorKeysApplicationMode(session?.emulator)) "\u001bOD".toByteArray() else "\u001b[D".toByteArray()
            "RIGHT"-> if (cursorKeysApplicationMode(session?.emulator)) "\u001bOC".toByteArray() else "\u001b[C".toByteArray()
            "HOME" -> if (cursorKeysApplicationMode(session?.emulator)) "\u001bOH".toByteArray() else "\u001b[H".toByteArray()
            "END"  -> if (cursorKeysApplicationMode(session?.emulator)) "\u001bOF".toByteArray() else "\u001b[F".toByteArray()
            "PGUP" -> "\u001b[5~".toByteArray()
            "PGDN" -> "\u001b[6~".toByteArray()
            "F1"   -> "\u001bOP".toByteArray()
            "F2"   -> "\u001bOQ".toByteArray()
            "F3"   -> "\u001bOR".toByteArray()
            "F4"   -> "\u001bOS".toByteArray()
            "F5"   -> "\u001b[15~".toByteArray()
            "F6"   -> "\u001b[17~".toByteArray()
            "F7"   -> "\u001b[18~".toByteArray()
            "F8"   -> "\u001b[19~".toByteArray()
            "F9"   -> "\u001b[20~".toByteArray()
            "F10"  -> "\u001b[21~".toByteArray()
            "F11"  -> "\u001b[23~".toByteArray()
            "F12"  -> "\u001b[24~".toByteArray()
            "-"    -> "-".toByteArray()
            "|"    -> "|".toByteArray()
            "/"    -> "/".toByteArray()
            else   -> return
        }
        session?.write(bytes, 0, bytes.size)
        extraCtrl = false
        extraAlt = false
    }

    override fun onCleared() {
        super.onCleared()
        // Drop the proxy's pointer to this dead ViewModel — otherwise the singleton
        // VmEngine keeps forwarding session events into a tombstoned client.
        if (engine.sessionClientDelegate === sessionClient) {
            engine.sessionClientDelegate = null
        }
        attached = false
    }

    companion object {
        private const val TAG = "TerminalVM"
        // Font-size bounds (px), shared by BOTH pinch-to-zoom and the Quick
        // Settings slider (TerminalScreen reads these) so the two surfaces can't
        // disagree — the slider would otherwise snap away a size pinch persisted.
        internal const val MIN_FONT_SIZE = 8
        internal const val MAX_FONT_SIZE = 48
        // Dead-session auto-reconnect governor: at most MAX_RECONNECT_ATTEMPTS
        // re-attaches within RECONNECT_WINDOW_MS before falling back to a manual
        // "tap to reconnect", so a chardev that accepts-then-EOFs can't respawn
        // the bridge many times per second.
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_WINDOW_MS = 10_000L
    }
}
