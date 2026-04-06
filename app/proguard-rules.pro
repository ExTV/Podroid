# Podroid ProGuard rules

# TerminalView fields set directly from TerminalScreen to wire the session.
-keepclassmembers class com.termux.view.TerminalView {
    public com.termux.terminal.TerminalSession mTermSession;
    public com.termux.terminal.TerminalEmulator mEmulator;
}

# TerminalSession.mEmulator replaced via reflection in TerminalViewModel
# to install a no-op TerminalOutput (prevents CPR garbage in the VM shell).
-keepclassmembers class com.termux.terminal.TerminalSession {
    com.termux.terminal.TerminalEmulator mEmulator;
}
