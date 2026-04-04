/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Keyboard I/O is intercepted at the View level and forwarded to QEMU.
 * The TerminalSession's local subprocess is a benign dummy.
 */
package com.excp.podroid.ui.screens.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    @SuppressLint("StaticFieldLeak") // Nulled in onCleared(); ViewModel is nav-scoped, no real leak
    private var terminalView: TerminalView? = null
    private var emulator: TerminalEmulator? = null
    private var attached = false
    var session: TerminalSession? = null
        private set

    var extraCtrl = false
    var extraAlt = false

    /** Last terminal size sent to the VM via stty, to avoid redundant commands. */
    private var lastSyncedCols = 0
    private var lastSyncedRows = 0
    private var resizeJob: Job? = null


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
            if (text != null) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
            }
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                qemu.writeToConsole(text.toByteArray(Charsets.UTF_8))
            }
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
                KeyEvent.KEYCODE_ENTER -> byteArrayOf(13) // CR — getty translates to LF
                KeyEvent.KEYCODE_DEL -> byteArrayOf(127)  // Backspace
                KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~".toByteArray()
                KeyEvent.KEYCODE_TAB -> byteArrayOf(9)
                KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(27)
                KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A".toByteArray()
                KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B".toByteArray()
                KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C".toByteArray()
                KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D".toByteArray()
                KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H".toByteArray()
                KeyEvent.KEYCODE_MOVE_END -> "\u001b[F".toByteArray()
                KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~".toByteArray()
                KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~".toByteArray()
                KeyEvent.KEYCODE_INSERT -> "\u001b[2~".toByteArray()
                KeyEvent.KEYCODE_F1 -> "\u001bOP".toByteArray()
                KeyEvent.KEYCODE_F2 -> "\u001bOQ".toByteArray()
                KeyEvent.KEYCODE_F3 -> "\u001bOR".toByteArray()
                KeyEvent.KEYCODE_F4 -> "\u001bOS".toByteArray()
                KeyEvent.KEYCODE_F5 -> "\u001b[15~".toByteArray()
                KeyEvent.KEYCODE_F6 -> "\u001b[17~".toByteArray()
                KeyEvent.KEYCODE_F7 -> "\u001b[18~".toByteArray()
                KeyEvent.KEYCODE_F8 -> "\u001b[19~".toByteArray()
                KeyEvent.KEYCODE_F9 -> "\u001b[20~".toByteArray()
                KeyEvent.KEYCODE_F10 -> "\u001b[21~".toByteArray()
                KeyEvent.KEYCODE_F11 -> "\u001b[23~".toByteArray()
                KeyEvent.KEYCODE_F12 -> "\u001b[24~".toByteArray()
                else -> null
            }

            if (bytes != null) {
                qemu.writeToConsole(bytes)
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
            if ((ctrlDown || extraCtrl) && codePoint in 64..127) {
                qemu.writeToConsole(byteArrayOf((codePoint and 0x1f).toByte()))
                extraCtrl = false
                extraAlt = false
                return true
            }


            val chars = Character.toChars(codePoint)
            val charBytes = String(chars).toByteArray(Charsets.UTF_8)
            if (extraAlt) {
                // Alt sends ESC prefix followed by the character
                qemu.writeToConsole(byteArrayOf(27) + charBytes)
            } else {
                qemu.writeToConsole(charBytes)
            }

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

    fun updateSize(cols: Int, rows: Int) {
        qemu.updateTerminalSize(cols, rows)
        if (cols != lastSyncedCols || rows != lastSyncedRows) {
            lastSyncedCols = cols
            lastSyncedRows = rows
            // Debounce: wait for layout to settle (e.g. keyboard animation)
            // before sending stty to avoid flooding the console.
            resizeJob?.cancel()
            resizeJob = viewModelScope.launch {
                delay(500)
                syncSize()
            }
        }
    }

    fun syncSize() {
        val rows = qemu.terminalRows
        val cols = qemu.terminalCols
        if (rows <= 0 || cols <= 0) return
        // Use the sz helper script which sets stty and erases its own echo.
        val cmd = "\u0015sz $rows $cols\n"
        qemu.writeToConsole(cmd.toByteArray(Charsets.UTF_8))
    }

    fun createSession() {
        if (attached) return
        attached = true

        val sess = TerminalSession(
            "/system/bin/tail",
            context.filesDir.absolutePath,
            arrayOf("tail", "-f", "/dev/null"),
            null,
            2000,
            sessionClient,
        )
        session = sess

        val emu = qemu.terminalEmulator
        emulator = emu

        if (emu == null) {
            Log.e(TAG, "Cannot attach: VM terminal emulator is null")
            return
        }

        sess.updateSize(qemu.terminalCols, qemu.terminalRows)

        // Inject persistent emulator into the session via reflection
        try {
            val emulatorField = TerminalSession::class.java.getDeclaredField("mEmulator")
            emulatorField.isAccessible = true
            emulatorField.set(sess, emu)
            Log.d(TAG, "Session emulator injected OK")
        } catch (e: Exception) {
            Log.e(TAG, "Reflection setup failed", e)
        }

        // QEMU output → trigger UI refresh
        qemu.onConsoleOutput = { _, _ ->
            terminalView?.let { view ->
                view.post {
                    view.onScreenUpdated()
                    view.invalidate()
                }
            }
        }
    }

    fun sendExtraKey(key: String) {
        if (key == "SYNC") {
            syncSize()
            return
        }
        when (key) {
            "CTRL" -> { extraCtrl = !extraCtrl; return }
            "ALT" -> { extraAlt = !extraAlt; return }
        }
        val bytes = when (key) {
            "ESC" -> byteArrayOf(27)
            "TAB" -> byteArrayOf(9)
            "UP" -> "\u001b[A".toByteArray()
            "DOWN" -> "\u001b[B".toByteArray()
            "LEFT" -> "\u001b[D".toByteArray()
            "RIGHT" -> "\u001b[C".toByteArray()
            "HOME" -> "\u001b[H".toByteArray()
            "END" -> "\u001b[F".toByteArray()
            "PGUP" -> "\u001b[5~".toByteArray()
            "PGDN" -> "\u001b[6~".toByteArray()
            "F1" -> "\u001bOP".toByteArray()
            "F2" -> "\u001bOQ".toByteArray()
            "F3" -> "\u001bOR".toByteArray()
            "F4" -> "\u001bOS".toByteArray()
            "F5" -> "\u001b[15~".toByteArray()
            "F6" -> "\u001b[17~".toByteArray()
            "F7" -> "\u001b[18~".toByteArray()
            "F8" -> "\u001b[19~".toByteArray()
            "F9" -> "\u001b[20~".toByteArray()
            "F10" -> "\u001b[21~".toByteArray()
            "F11" -> "\u001b[23~".toByteArray()
            "F12" -> "\u001b[24~".toByteArray()
            "-" -> "-".toByteArray()
            "|" -> "|".toByteArray()
            "/" -> "/".toByteArray()
            else -> return
        }
        qemu.writeToConsole(bytes)
        extraCtrl = false
        extraAlt = false
    }

    override fun onCleared() {
        super.onCleared()
        qemu.onConsoleOutput = null
        emulator = null
        terminalView = null
        session = null
        attached = false
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}
