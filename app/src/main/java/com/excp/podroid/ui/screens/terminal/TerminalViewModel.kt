/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Terminal ViewModel — wires TerminalView to the podroid-bridge binary.
 *
 * The bridge binary (libpodroid-bridge.so) runs as the TerminalSession
 * subprocess. Termux allocates a real PTY for it; the bridge relays that
 * PTY to QEMU's serial Unix socket. Window resize is handled properly:
 *
 *   TerminalSession.updateSize(cols, rows)
 *     → ioctl(pty_master, TIOCSWINSZ)          [Termux JNI]
 *     → SIGWINCH → bridge process
 *     → bridge reads new size via TIOCGWINSZ
 *     → writes "RESIZE rows cols\n" to ctrl.sock
 *     → VM daemon calls stty on /dev/ttyAMA0
 *     → Linux kernel sends SIGWINCH to VM foreground process group
 *     → nvim / htop / btop redraws correctly
 *
 * No reflection, no emulator injection, no sz stdin injection.
 */
package com.excp.podroid.ui.screens.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.termux.terminal.TerminalColors
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val qemu: PodroidQemu,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = qemu.state
    val bootStage: StateFlow<String> = qemu.bootStage
    val terminalFontSize: StateFlow<Int> = settingsRepository.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    @SuppressLint("StaticFieldLeak") // created once per ViewModel lifetime; ViewModel is nav-scoped
    private var terminalView: TerminalView? = null
    private var attached = false

    fun getOrCreateTerminalView(): TerminalView {
        Log.d(TAG, "getOrCreateTerminalView called, current view: $terminalView, this: ${System.identityHashCode(this)}")
        terminalView?.let { return it }

        val colorFile = java.io.File(context.filesDir, "colors.properties")
        val bgColor = if (colorFile.exists()) {
            try {
                val props = java.util.Properties()
                colorFile.inputStream().use { props.load(it) }
                TerminalColors.COLOR_SCHEME.updateWith(props)
                val bgHex = props["background"] as? String
                if (bgHex != null) parseColor(bgHex) else android.graphics.Color.BLACK
            } catch (_: Exception) { android.graphics.Color.BLACK }
        } else {
            android.graphics.Color.BLACK
        }

        val fontFile = java.io.File(context.filesDir, "font.ttf")
        val typeface = if (fontFile.exists()) {
            try { Typeface.createFromFile(fontFile) } catch (_: Exception) { Typeface.MONOSPACE }
        } else { Typeface.MONOSPACE }

        val view = TerminalView(context, null).apply {
            setBackgroundColor(bgColor)
            setTextSize(terminalFontSize.value)
            setTypeface(typeface)
            keepScreenOn = true
            isFocusable = true
            isFocusableInTouchMode = true
        }
        terminalView = view
        return view
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

    var extraCtrl = false
    var extraAlt = false

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
            terminalView?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text == null) return
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
            session?.write(text)
        }
        override fun onBell(session: TerminalSession) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView?.let { view ->
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(view, 0)
            }
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (e == null) return false
            val bytes = when (keyCode) {
                KeyEvent.KEYCODE_ENTER        -> byteArrayOf(13)
                KeyEvent.KEYCODE_DEL          -> byteArrayOf(127)
                KeyEvent.KEYCODE_FORWARD_DEL  -> "\u001b[3~".toByteArray()
                KeyEvent.KEYCODE_TAB          -> byteArrayOf(9)
                KeyEvent.KEYCODE_ESCAPE       -> byteArrayOf(27)
                KeyEvent.KEYCODE_DPAD_UP      -> "\u001b[A".toByteArray()
                KeyEvent.KEYCODE_DPAD_DOWN    -> "\u001b[B".toByteArray()
                KeyEvent.KEYCODE_DPAD_RIGHT   -> "\u001b[C".toByteArray()
                KeyEvent.KEYCODE_DPAD_LEFT    -> "\u001b[D".toByteArray()
                KeyEvent.KEYCODE_MOVE_HOME    -> "\u001b[H".toByteArray()
                KeyEvent.KEYCODE_MOVE_END     -> "\u001b[F".toByteArray()
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
            val bytes: ByteArray
            if ((ctrlDown || extraCtrl) && codePoint in 64..127) {
                bytes = byteArrayOf((codePoint and 0x1f).toByte())
            } else {
                val charBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                bytes = if (extraAlt) byteArrayOf(27) + charBytes else charBytes
            }
            session?.write(bytes, 0, bytes.size)
            extraCtrl = false
            extraAlt = false
            return true
        }

        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    fun attachView(view: TerminalView) {
        terminalView = view
    }

    fun createSession() {
        if (attached) return

        val sess = qemu.createTerminalSession(sessionClient)
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

    /**
     * Triggered by the layout listener in TerminalScreen when the view dimensions
     * change (initial layout, keyboard open/close, rotation).
     *
     * Calls TerminalSession.updateSize which:
     *   1. Resizes the local terminal emulator buffer.
     *   2. Calls ioctl(pty_master_fd, TIOCSWINSZ) via Termux JNI.
     *   3. The kernel sends SIGWINCH to the bridge process.
     *   4. The bridge sends RESIZE to ctrl.sock → VM gets proper SIGWINCH.
     */
    fun updateSize(cols: Int, rows: Int) {
        session?.updateSize(cols, rows)
    }

    fun sendExtraKey(key: String) {
        when (key) {
            "CTRL" -> { extraCtrl = !extraCtrl; return }
            "ALT"  -> { extraAlt = !extraAlt; return }
        }
        val bytes = when (key) {
            "ESC"  -> byteArrayOf(27)
            "TAB"  -> byteArrayOf(9)
            "UP"   -> "\u001b[A".toByteArray()
            "DOWN" -> "\u001b[B".toByteArray()
            "LEFT" -> "\u001b[D".toByteArray()
            "RIGHT"-> "\u001b[C".toByteArray()
            "HOME" -> "\u001b[H".toByteArray()
            "END"  -> "\u001b[F".toByteArray()
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
        terminalView = null
        attached = false
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}
