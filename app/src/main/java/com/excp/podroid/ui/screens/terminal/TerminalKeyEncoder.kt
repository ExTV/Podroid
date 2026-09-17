/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure xterm key-sequence encoding shared by TerminalViewModel's onKeyDown
 * (hardware/soft keyboard) and sendExtraKey (the extra keys row), so both
 * apply the sticky CTRL/ALT modifiers the same way.
 */
package com.excp.podroid.ui.screens.terminal

import android.view.KeyEvent

/** xterm CSI modifier: 1=none, 2=shift, 3=alt, 4=shift+alt, 5=ctrl,
 *  6=ctrl+shift, 7=ctrl+alt, 8=all. Used for "ESC [1;<m><final>" /
 *  "ESC [<n>;<m>~" sequences. */
fun xtermModifier(shift: Boolean, alt: Boolean, ctrl: Boolean): Int =
    1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

enum class TermKey {
    UP, DOWN, RIGHT, LEFT, HOME, END,
    PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
}

// Cursor keys and HOME/END: "ESC O x" (application mode) / "ESC [ x" (normal
// mode) when unmodified, "ESC [ 1 ; mod x" when modified.
private val CURSOR_FINAL = mapOf(
    TermKey.UP to 'A', TermKey.DOWN to 'B', TermKey.RIGHT to 'C', TermKey.LEFT to 'D',
    TermKey.HOME to 'H', TermKey.END to 'F',
)

// F1-F4: "ESC O x" when unmodified, "ESC [ 1 ; mod x" when modified.
private val SS3_FINAL = mapOf(
    TermKey.F1 to 'P', TermKey.F2 to 'Q', TermKey.F3 to 'R', TermKey.F4 to 'S',
)

// PAGE_UP/PAGE_DOWN/INSERT/DELETE/F5-F12: "ESC [ n ~" when unmodified,
// "ESC [ n ; mod ~" when modified.
private val TILDE_N = mapOf(
    TermKey.INSERT to 2, TermKey.DELETE to 3, TermKey.PAGE_UP to 5, TermKey.PAGE_DOWN to 6,
    TermKey.F5 to 15, TermKey.F6 to 17, TermKey.F7 to 18, TermKey.F8 to 19, TermKey.F9 to 20,
    TermKey.F10 to 21, TermKey.F11 to 23, TermKey.F12 to 24,
)

/** Encodes [key] as the xterm byte sequence for modifier [mod] (see
 *  [xtermModifier]), honoring [appCursor] (DECCKM) for the cursor keys group. */
fun encode(key: TermKey, mod: Int, appCursor: Boolean): ByteArray {
    CURSOR_FINAL[key]?.let { final ->
        return if (mod == 1) {
            if (appCursor) "\u001BO$final".toByteArray() else "\u001B[$final".toByteArray()
        } else {
            "\u001B[1;$mod$final".toByteArray()
        }
    }
    SS3_FINAL[key]?.let { final ->
        return if (mod == 1) "\u001BO$final".toByteArray() else "\u001B[1;$mod$final".toByteArray()
    }
    val n = TILDE_N.getValue(key)
    return if (mod == 1) "\u001B[$n~".toByteArray() else "\u001B[$n;$mod~".toByteArray()
}

/** Maps a hardware/soft keyboard key code to the [TermKey] it encodes, or
 *  null when [keyCode] is handled elsewhere (ENTER/DEL/TAB/ESCAPE) or not a
 *  terminal key at all. */
fun termKeyForKeyCode(keyCode: Int): TermKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> TermKey.UP
    KeyEvent.KEYCODE_DPAD_DOWN -> TermKey.DOWN
    KeyEvent.KEYCODE_DPAD_RIGHT -> TermKey.RIGHT
    KeyEvent.KEYCODE_DPAD_LEFT -> TermKey.LEFT
    KeyEvent.KEYCODE_MOVE_HOME -> TermKey.HOME
    KeyEvent.KEYCODE_MOVE_END -> TermKey.END
    KeyEvent.KEYCODE_PAGE_UP -> TermKey.PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> TermKey.PAGE_DOWN
    KeyEvent.KEYCODE_INSERT -> TermKey.INSERT
    KeyEvent.KEYCODE_FORWARD_DEL -> TermKey.DELETE
    KeyEvent.KEYCODE_F1 -> TermKey.F1
    KeyEvent.KEYCODE_F2 -> TermKey.F2
    KeyEvent.KEYCODE_F3 -> TermKey.F3
    KeyEvent.KEYCODE_F4 -> TermKey.F4
    KeyEvent.KEYCODE_F5 -> TermKey.F5
    KeyEvent.KEYCODE_F6 -> TermKey.F6
    KeyEvent.KEYCODE_F7 -> TermKey.F7
    KeyEvent.KEYCODE_F8 -> TermKey.F8
    KeyEvent.KEYCODE_F9 -> TermKey.F9
    KeyEvent.KEYCODE_F10 -> TermKey.F10
    KeyEvent.KEYCODE_F11 -> TermKey.F11
    KeyEvent.KEYCODE_F12 -> TermKey.F12
    else -> null
}

/** Maps an extra-keys row label to the [TermKey] it encodes, or null when
 *  [label] is handled elsewhere (CTRL/ALT/PASTE/ESC/TAB/-/|/) or unknown. */
fun termKeyForLabel(label: String): TermKey? = when (label) {
    "UP" -> TermKey.UP
    "DOWN" -> TermKey.DOWN
    "LEFT" -> TermKey.LEFT
    "RIGHT" -> TermKey.RIGHT
    "HOME" -> TermKey.HOME
    "END" -> TermKey.END
    "PGUP" -> TermKey.PAGE_UP
    "PGDN" -> TermKey.PAGE_DOWN
    "F1" -> TermKey.F1
    "F2" -> TermKey.F2
    "F3" -> TermKey.F3
    "F4" -> TermKey.F4
    "F5" -> TermKey.F5
    "F6" -> TermKey.F6
    "F7" -> TermKey.F7
    "F8" -> TermKey.F8
    "F9" -> TermKey.F9
    "F10" -> TermKey.F10
    "F11" -> TermKey.F11
    "F12" -> TermKey.F12
    else -> null
}
