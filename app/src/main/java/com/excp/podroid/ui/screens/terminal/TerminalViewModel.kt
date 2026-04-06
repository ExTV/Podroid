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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
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

    @SuppressLint("StaticFieldLeak") // nulled in onCleared(); ViewModel is nav-scoped
    private var terminalView: TerminalView? = null
    private var attached = false

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

    /**
     * Creates the TerminalSession backed by podroid-bridge.
     *
     * Steps:
     * 1. Release PodroidQemu's boot-monitoring socket so bridge can connect.
     * 2. Start the bridge binary as the session subprocess.
     * 3. Termux allocates a real PTY; bridge relays PTY ↔ serial.sock.
     *
     * Resize flow (fully automatic after this):
     *   TerminalView layout change → updateSize(cols, rows)
     *   → TerminalSession.updateSize → ioctl TIOCSWINSZ on PTY master
     *   → SIGWINCH to bridge → bridge writes RESIZE to ctrl.sock
     *   → VM daemon → stty on ttyAMA0 → SIGWINCH in VM
     */
    fun createSession() {
        if (attached) return
        attached = true

        // Hand off the serial socket from boot monitoring to the bridge.
        // shutdownInput() + close() tells the boot monitor to exit; the short
        // sleep lets QEMU detect the disconnect and re-listen before the bridge
        // tries to connect (QEMU serial only serves one client at a time).
        qemu.releaseSerial()
        Thread.sleep(500)

        val bridgeExe = File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")
        if (!bridgeExe.exists()) {
            Log.e(TAG, "podroid-bridge not found at ${bridgeExe.absolutePath}")
            return
        }

        val sess = TerminalSession(
            bridgeExe.absolutePath,
            context.filesDir.absolutePath,
            arrayOf(bridgeExe.absolutePath, qemu.serialSockPath, qemu.ctrlSockPath),
            null,
            2000,
            sessionClient,
        )
        // TerminalSession defers subprocess creation until the first updateSize()
        // call triggers initializeEmulator(). Force it now so the bridge process
        // starts and the PTY file descriptor is allocated.
        sess.updateSize(80, 24)

        // Busybox ash sends \033[6n (cursor position query) on every prompt.
        // The Termux TerminalEmulator responds with \033[row;colR via TerminalOutput
        // → bridge stdin → serial → VM ttyAMA0. Due to bridge round-trip latency,
        // the response arrives after ash's readline has timed out waiting for it,
        // so ash treats it as keyboard input and displays "^[[16;5R" garbage.
        //
        // Fix: replace the session's emulator with one whose TerminalOutput is a
        // no-op — all emulator responses are dropped. Keyboard input is unaffected
        // because it flows through session.write() → PTY master (separate path).
        try {
            val noOpOutput = object : TerminalOutput() {
                override fun write(data: ByteArray, offset: Int, count: Int) {}
                override fun titleChanged(oldTitle: String?, newTitle: String?) {}
                override fun onCopyTextToClipboard(text: String?) {}
                override fun onPasteTextFromClipboard() {}
                override fun onBell() {}
                override fun onColorsChanged() {}
            }
            val displayEmulator = TerminalEmulator(noOpOutput, 80, 24, 2000, sessionClient)
            val field = TerminalSession::class.java.getDeclaredField("mEmulator")
            field.isAccessible = true
            field.set(sess, displayEmulator)
            Log.d(TAG, "Emulator output replaced — CPR responses will be dropped")
        } catch (e: Exception) {
            Log.w(TAG, "Could not replace emulator output: ${e.message}")
        }

        session = sess
        Log.d(TAG, "Bridge session created — PTY connected to ${qemu.serialSockPath}")
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

    /**
     * SYNC button — re-sends the current size through the full chain.
     * Useful if the VM missed a resize event (e.g., during intensive I/O).
     */
    fun syncSize() {
        val emu = session?.emulator ?: return
        session?.updateSize(emu.mColumns, emu.mRows)
    }

    fun sendExtraKey(key: String) {
        when (key) {
            "SYNC" -> { syncSize(); return }
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
        session = null
        attached = false
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}
