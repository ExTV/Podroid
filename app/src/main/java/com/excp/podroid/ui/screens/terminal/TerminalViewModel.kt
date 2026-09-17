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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
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
    private val appearanceStore: TerminalAppearanceStore,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state
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

    /** Mirror of the persisted flow for callers that want a synchronous read. */
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

    /** Peek a theme's bg+fg ARGB ints. See [TerminalAppearanceStore.peekThemeColors]. */
    fun peekThemeColors(theme: String): Pair<Int, Int>? = appearanceStore.peekThemeColors(theme)

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
            appearanceStore.readThemeProperties(theme) ?: return null
        }
        TerminalColors.COLOR_SCHEME.updateWith(props)
        // updateWith refreshes the static defaults, but the live session's
        // mCurrentColors is a per-session cache populated at session start
        // and on `\ec` (RIS). Without this push, theme changes only took
        // effect after the user typed `reset` in the shell.
        session?.emulator?.mColors?.reset()
        return (props["background"] as? String)?.let { parseHexColor(it) }
    }

    /** List available themes. See [TerminalAppearanceStore.listAvailableThemes]. */
    fun listAvailableThemes(): List<String> = appearanceStore.listAvailableThemes()

    /** True if `name` was imported by the user. See [TerminalAppearanceStore.isCustomTheme]. */
    fun isCustomTheme(name: String): Boolean = appearanceStore.isCustomTheme(name)

    /** Remove a previously-imported theme. See [TerminalAppearanceStore.deleteCustomTheme]. */
    fun deleteCustomTheme(name: String): Boolean = appearanceStore.deleteCustomTheme(name)

    /** Import a theme from a terminalcolors.com URL. See [TerminalAppearanceStore.importThemeFromUrl]. */
    suspend fun importThemeFromUrl(input: String): String? = appearanceStore.importThemeFromUrl(input)

    /** List available fonts. See [TerminalAppearanceStore.listAvailableFonts]. */
    fun listAvailableFonts(): List<String> = appearanceStore.listAvailableFonts()

    /** True if `name` was imported by the user. See [TerminalAppearanceStore.isCustomFont]. */
    fun isCustomFont(name: String): Boolean = appearanceStore.isCustomFont(name)

    /** Resolve a font name to a Typeface. See [TerminalAppearanceStore.loadFont]. */
    fun loadFont(font: String): Typeface = appearanceStore.loadFont(font)

    /** Import a `.ttf` from a SAF `Uri`. See [TerminalAppearanceStore.importCustomFont]. */
    fun importCustomFont(uri: android.net.Uri): String? = appearanceStore.importCustomFont(uri)

    /** Remove a previously-imported custom font. See [TerminalAppearanceStore.deleteCustomFont]. */
    fun deleteCustomFont(name: String): Boolean = appearanceStore.deleteCustomFont(name)

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
            val mod = xtermModifier(shift, alt, ctrl)

            val bytes = when (keyCode) {
                KeyEvent.KEYCODE_ENTER  -> byteArrayOf(13)
                KeyEvent.KEYCODE_DEL    -> byteArrayOf(127)
                KeyEvent.KEYCODE_TAB    -> if (shift) "\u001b[Z".toByteArray() else byteArrayOf(9)
                KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(27)
                else -> termKeyForKeyCode(keyCode)?.let {
                    encode(it, mod, cursorKeysApplicationMode(session?.emulator))
                }
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
                // Guard: both engines implement createTerminalSession, but a
                // backend can still fail to spawn a session. Don't crash the
                // UI, leave session null.
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
     * nvim's `FocusGained` / `FocusLost` autocommands rely on these. Guarded by
     * the public `TerminalEmulator.isFocusEventsEnabled` (DECSET 1004), no
     * reflection: sending focus bytes to a shell that didn't enable reporting
     * would leak literal "^[[I" noise into the prompt.
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
            "-"    -> if (extraAlt) byteArrayOf(27) + "-".toByteArray() else "-".toByteArray()
            "|"    -> if (extraAlt) byteArrayOf(27) + "|".toByteArray() else "|".toByteArray()
            "/"    -> if (extraAlt) byteArrayOf(27) + "/".toByteArray() else "/".toByteArray()
            else   -> termKeyForLabel(key)?.let {
                val mod = xtermModifier(false, extraAlt, extraCtrl)
                encode(it, mod, cursorKeysApplicationMode(session?.emulator))
            } ?: return
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
