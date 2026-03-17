/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * No host binaries used — TerminalSession is created but its subprocess
 * is bypassed via reflection. Keyboard I/O goes to QEMU serial console.
 */
package com.excp.podroid.ui.screens.terminal

import android.content.Context
import android.system.Os
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.io.OutputStream
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 24)

    private var terminalView: TerminalView? = null
    private var emulator: TerminalEmulator? = null
    private var qemuStdin: OutputStream? = null
    private var attached = false
    var session: TerminalSession? = null
        private set

    var extraCtrl = false
    var extraAlt = false

    val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView?.onScreenUpdated()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text != null) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Terminal", text))
            }
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context).toString()
                val bytes = text.toByteArray()
                try {
                    qemuStdin?.write(bytes)
                    qemuStdin?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to paste", e)
                }
            }
        }
        override fun onBell(session: TerminalSession) {}
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
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
            // Reset Ctrl/Alt toggles after a key is processed
            if (extraCtrl || extraAlt) {
                extraCtrl = false
                extraAlt = false
            }
            return false
        }
        override fun onLongPress(event: MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = extraCtrl
        override fun readAltKey(): Boolean = extraAlt
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            // If CTRL extra key is active, send the control character directly
            if (extraCtrl && codePoint in 64..127) {
                // Ctrl+A=1, Ctrl+B=2, ..., Ctrl+Z=26, etc.
                val ctrlByte = (codePoint and 0x1f).toByte()
                try {
                    qemuStdin?.write(byteArrayOf(ctrlByte))
                    qemuStdin?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send ctrl key", e)
                }
                extraCtrl = false
                extraAlt = false
                return true // consumed
            }
            if (extraCtrl && codePoint in 96..122) {
                // lowercase ctrl+a through ctrl+z
                val ctrlByte = (codePoint - 96).toByte()
                try {
                    qemuStdin?.write(byteArrayOf(ctrlByte))
                    qemuStdin?.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send ctrl key", e)
                }
                extraCtrl = false
                extraAlt = false
                return true
            }
            return false
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
     * Create a TerminalSession wired to QEMU I/O instead of a local subprocess.
     *
     * We create a real pipe fd so JNI.setPtyWindowSize() doesn't crash,
     * inject our emulator via reflection, and drain the session's keyboard
     * queue to forward input to QEMU stdin.
     */
    fun createSession() {
        if (attached) return
        attached = true

        qemuStdin = qemu.consoleOutput

        // Create session — constructor just stores fields, no subprocess started
        val sess = TerminalSession(
            "/dev/null",
            context.filesDir.absolutePath,
            emptyArray(),
            emptyArray(),
            2000,
            sessionClient,
        )
        session = sess

        // Create emulator that sends keyboard output to QEMU stdin
        val qemuOutput = object : com.termux.terminal.TerminalOutput() {
            override fun write(data: ByteArray, offset: Int, count: Int) {
                try {
                    qemuStdin?.let {
                        it.write(data, offset, count)
                        it.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write to QEMU", e)
                }
            }
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
        }

        val emu = TerminalEmulator(qemuOutput, 80, 24, 2000, sessionClient)
        emulator = emu

        // Create a real pipe so JNI.setPtyWindowSize() gets a valid fd
        // (ioctl will fail with ENOTTY but won't crash)
        var pipeFd = -1
        try {
            val fds = Os.pipe()
            // Get raw int fd via reflection (FileDescriptor doesn't expose it directly)
            val fdIntField = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
            fdIntField.isAccessible = true
            pipeFd = fdIntField.getInt(fds[1])
            Os.close(fds[0]) // close read end
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create pipe: ${e.message}")
        }

        // Inject our emulator and fake pid into session via reflection
        try {
            val cls = TerminalSession::class.java

            val emulatorField = cls.getDeclaredField("mEmulator")
            emulatorField.isAccessible = true
            emulatorField.set(sess, emu)

            val pidField = cls.getDeclaredField("mShellPid")
            pidField.isAccessible = true
            pidField.setInt(sess, 1)

            val fdField = cls.getDeclaredField("mTerminalFileDescriptor")
            fdField.isAccessible = true
            fdField.setInt(sess, pipeFd)

            Log.d(TAG, "Session reflection setup OK, pipeFd=$pipeFd")
        } catch (e: Exception) {
            Log.e(TAG, "Reflection setup failed", e)
        }

        // Thread to drain keyboard input from session's queue → QEMU stdin
        try {
            val queueField = TerminalSession::class.java.getDeclaredField("mTerminalToProcessIOQueue")
            queueField.isAccessible = true
            val queue = queueField.get(sess)!!

            val readMethod = queue.javaClass.getDeclaredMethod(
                "read", ByteArray::class.java, Boolean::class.javaPrimitiveType
            )
            readMethod.isAccessible = true

            Thread({
                val buf = ByteArray(4096)
                try {
                    while (true) {
                        val n = readMethod.invoke(queue, buf, true) as Int
                        if (n > 0) {
                            if (extraCtrl) {
                                // Apply Ctrl modifier: a-z → 1-26, A-Z → 1-26
                                val out = ByteArray(n)
                                for (i in 0 until n) {
                                    val b = buf[i].toInt() and 0xFF
                                    out[i] = if (b in 0x40..0x7F) (b and 0x1F).toByte() else buf[i]
                                }
                                qemuStdin?.write(out, 0, n)
                                extraCtrl = false
                                extraAlt = false
                            } else {
                                qemuStdin?.write(buf, 0, n)
                            }
                            qemuStdin?.flush()
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Keyboard→QEMU thread ended: ${e.message}")
                }
            }, "keyboard-to-qemu").apply {
                isDaemon = true
                start()
            }
            Log.d(TAG, "Keyboard→QEMU thread started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start keyboard→QEMU thread", e)
        }

        // QEMU output → feed to emulator
        qemu.onConsoleOutput = { data, len ->
            emu.append(data, len)
            terminalView?.post { terminalView?.onScreenUpdated() }
        }

        // Feed buffered output
        val existing = qemu.consoleText.value
        if (existing.isNotEmpty()) {
            val bytes = existing.toByteArray()
            emu.append(bytes, bytes.size)
        }
    }

    fun sendExtraKey(key: String) {
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
            "-" -> "-".toByteArray()
            "|" -> "|".toByteArray()
            "/" -> "/".toByteArray()
            else -> return
        }
        try {
            qemuStdin?.write(bytes)
            qemuStdin?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send extra key", e)
        }
        extraCtrl = false
        extraAlt = false
    }

    override fun onCleared() {
        super.onCleared()
        qemu.onConsoleOutput = null
        emulator = null
        terminalView = null
        qemuStdin = null
        session = null
        attached = false
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}
