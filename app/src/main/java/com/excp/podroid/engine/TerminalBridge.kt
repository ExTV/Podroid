/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Shared bridge-session plumbing for QemuEngine and AvfEngine: the proxy
 * TerminalSessionClient that lets an engine create the bridge session before
 * the terminal UI exists, and the TerminalSession construction the two
 * engines otherwise duplicated at four call sites.
 */
package com.excp.podroid.engine

import android.content.Context
import com.excp.podroid.util.LogProxy
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Proxy TerminalSessionClient - delegates to whatever real client is set.
 * Lets an engine create the bridge session at boot-complete time (before the
 * terminal UI exists) and plug in the real ViewModel client later.
 */
class ProxySessionClient(private val tag: String) : TerminalSessionClient {
    @Volatile var delegate: TerminalSessionClient? = null

    override fun onTextChanged(s: TerminalSession) { delegate?.onTextChanged(s) }
    override fun onTitleChanged(s: TerminalSession) { delegate?.onTitleChanged(s) }
    override fun onSessionFinished(s: TerminalSession) { delegate?.onSessionFinished(s) }
    override fun onCopyTextToClipboard(s: TerminalSession, text: String?) { delegate?.onCopyTextToClipboard(s, text) }
    override fun onPasteTextFromClipboard(s: TerminalSession?) { delegate?.onPasteTextFromClipboard(s) }
    override fun onBell(s: TerminalSession) { delegate?.onBell(s) }
    override fun onColorsChanged(s: TerminalSession) { delegate?.onColorsChanged(s) }
    override fun onTerminalCursorStateChange(state: Boolean) { delegate?.onTerminalCursorStateChange(state) }
    override fun setTerminalShellPid(s: TerminalSession, pid: Int) { delegate?.setTerminalShellPid(s, pid) }
    override fun getTerminalCursorStyle(): Int = delegate?.terminalCursorStyle ?: 0
    override fun getTerminalVersionString(): String? = delegate?.terminalVersionString
    override fun logError(tag: String?, msg: String?) = LogProxy.error(tag, this.tag, msg)
    override fun logWarn(tag: String?, msg: String?) = LogProxy.warn(tag, this.tag, msg)
    override fun logInfo(tag: String?, msg: String?) = LogProxy.info(tag, this.tag, msg)
    override fun logDebug(tag: String?, msg: String?) = LogProxy.debug(tag, this.tag, msg)
    override fun logVerbose(tag: String?, msg: String?) = LogProxy.verbose(tag, this.tag, msg)
    override fun logStackTraceWithMessage(tag: String?, msg: String?, e: Exception?) =
        LogProxy.stackTraceWithMessage(tag, this.tag, msg, e)
    override fun logStackTrace(tag: String?, e: Exception?) = LogProxy.stackTrace(tag, this.tag, e)
}

/**
 * Builds the podroid-bridge TerminalSession shared by both engines: same
 * executable lookup, argv, cwd, transcript size and initial size push. Each
 * call site keeps its own existence-check and missing-exe handling (silent
 * return, log, or throw) - this only covers the part that was identical.
 */
object TerminalBridge {
    fun executable(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libpodroid-bridge.so")

    /**
     * [onResize] non-null builds a [ResizeNotifyingSession] (AVF); null builds
     * a plain [TerminalSession] (QEMU, which has no resize channel here).
     */
    fun newSession(
        context: Context,
        terminalSock: String,
        ctrlSock: String,
        client: TerminalSessionClient,
        onResize: ((rows: Int, cols: Int) -> Unit)?,
    ): TerminalSession {
        val bridgeExe = executable(context)
        val args = arrayOf(bridgeExe.absolutePath, terminalSock, ctrlSock)
        val sess = if (onResize != null) {
            ResizeNotifyingSession(
                shellPath = bridgeExe.absolutePath,
                cwd = context.filesDir.absolutePath,
                args = args,
                env = null,
                transcriptRows = 2000,
                client = client,
                onResize = onResize,
            )
        } else {
            TerminalSession(
                bridgeExe.absolutePath,
                context.filesDir.absolutePath,
                args,
                null,
                2000,
                client,
            )
        }
        // Cell pixel dims default to 0 - TerminalView.updateSize() pushes real values once measured.
        sess.updateSize(80, 24, 0, 0)
        return sess
    }
}
