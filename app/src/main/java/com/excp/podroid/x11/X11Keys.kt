/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure key-mapping helpers for the X11 viewer: X11 keysym constants, the
 * extra-keys-row label table, the hardware-key/IME keycode-to-keysym maps,
 * and the modifier-wrapping / IME-diff logic shared by X11Screen's key paths.
 */
package com.excp.podroid.x11

import android.view.KeyEvent

/** X11 keysyms used outside the label table, and by [labelToKeysym]. */
object X11Keysym {
    const val BackSpace = 0xFF08
    const val Tab = 0xFF09
    const val Return = 0xFF0D
    const val Escape = 0xFF1B
    const val Home = 0xFF50
    const val Left = 0xFF51
    const val Up = 0xFF52
    const val Right = 0xFF53
    const val Down = 0xFF54
    const val PageUp = 0xFF55
    const val PageDown = 0xFF56
    const val End = 0xFF57
    const val Delete = 0xFFFF
    const val F1 = 0xFFBE
    const val F2 = 0xFFBF
    const val F3 = 0xFFC0
    const val F4 = 0xFFC1
    const val F5 = 0xFFC2
    const val F6 = 0xFFC3
    const val F7 = 0xFFC4
    const val F8 = 0xFFC5
    const val F9 = 0xFFC6
    const val F10 = 0xFFC7
    const val F11 = 0xFFC8
    const val F12 = 0xFFC9
    const val Shift_L = 0xFFE1
    const val Control_L = 0xFFE3
    const val Alt_L = 0xFFE9
}

/**
 * Maps the human-readable label used by X11ExtraKeysRow (matching the
 * terminal's ExtraKeysRow vocabulary) to an X11 keysym. Returns null for
 * pure modifier labels (CTRL/ALT) - those are handled as toggles.
 */
fun labelToKeysym(label: String): Int? = when (label) {
    "ESC" -> X11Keysym.Escape
    "TAB" -> X11Keysym.Tab
    "LEFT" -> X11Keysym.Left
    "RIGHT" -> X11Keysym.Right
    "UP" -> X11Keysym.Up
    "DOWN" -> X11Keysym.Down
    "HOME" -> X11Keysym.Home
    "END" -> X11Keysym.End
    "PGUP" -> X11Keysym.PageUp
    "PGDN" -> X11Keysym.PageDown
    "F1" -> X11Keysym.F1
    "F2" -> X11Keysym.F2
    "F3" -> X11Keysym.F3
    "F4" -> X11Keysym.F4
    "F5" -> X11Keysym.F5
    "F6" -> X11Keysym.F6
    "F7" -> X11Keysym.F7
    "F8" -> X11Keysym.F8
    "F9" -> X11Keysym.F9
    "F10" -> X11Keysym.F10
    "F11" -> X11Keysym.F11
    "F12" -> X11Keysym.F12
    "-" -> 0x2D
    "/" -> 0x2F
    "|" -> 0x7C
    else -> null // CTRL / ALT handled by toggles
}

/**
 * Maps a Compose/IME codepoint to an X11 keysym: ASCII 0x20-0x7E matches
 * verbatim, non-ASCII Unicode maps to 0x01000000 | codepoint (X11 protocol
 * extension). Used by the IME path and the sticky-CTRL/ALT + IME-char combo.
 */
fun keysymForCodePoint(cp: Int): Int = if (cp in 0x20..0x7E) cp else 0x01000000 or cp

/**
 * Same X11 protocol extension as [keysymForCodePoint], but for the hardware-
 * keyboard path, which derives its char from `KeyEvent.getUnicodeChar` and
 * historically lets control characters below 0x20 pass through raw (that
 * input never produces a value in 0x20..0x7E for those chars, so the two
 * functions diverge only below 0x20). Kept separate rather than merged so a
 * future change to one path doesn't silently change the other.
 */
fun keysymForHardwareChar(cased: Int): Int = if (cased > 0x7E) 0x01000000 or cased else cased

/**
 * Hardware/external-keyboard special keys that map to a fixed X11 keysym
 * regardless of layout, keyed by the underlying Android keycode (the value
 * behind the Compose `Key` constants X11Screen used to switch on).
 */
fun specialKeysymForKeyCode(keyCode: Int): Int? = when (keyCode) {
    KeyEvent.KEYCODE_DEL -> X11Keysym.BackSpace
    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> X11Keysym.Return
    KeyEvent.KEYCODE_TAB -> X11Keysym.Tab
    KeyEvent.KEYCODE_ESCAPE -> X11Keysym.Escape
    KeyEvent.KEYCODE_DPAD_LEFT -> X11Keysym.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> X11Keysym.Right
    KeyEvent.KEYCODE_DPAD_UP -> X11Keysym.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> X11Keysym.Down
    KeyEvent.KEYCODE_MOVE_HOME -> X11Keysym.Home
    KeyEvent.KEYCODE_MOVE_END -> X11Keysym.End
    KeyEvent.KEYCODE_PAGE_UP -> X11Keysym.PageUp
    KeyEvent.KEYCODE_PAGE_DOWN -> X11Keysym.PageDown
    KeyEvent.KEYCODE_FORWARD_DEL -> X11Keysym.Delete
    else -> null
}

/** One synthetic X11 key event: [keysym] going down or up. */
data class KeyStroke(val keysym: Int, val down: Boolean)

/**
 * Wraps [keysym] with the requested modifiers in today's order: Shift down,
 * Ctrl down, Alt down, key down, key up, Alt up, Ctrl up, Shift up (absent
 * modifiers are omitted). Used by both the extra-keys/sticky-modifier path
 * (shift is always false there) and the hardware-key handler.
 */
fun wrapWithModifiers(keysym: Int, shift: Boolean, ctrl: Boolean, alt: Boolean): List<KeyStroke> {
    val strokes = mutableListOf<KeyStroke>()
    if (shift) strokes += KeyStroke(X11Keysym.Shift_L, down = true)
    if (ctrl) strokes += KeyStroke(X11Keysym.Control_L, down = true)
    if (alt) strokes += KeyStroke(X11Keysym.Alt_L, down = true)
    strokes += KeyStroke(keysym, down = true)
    strokes += KeyStroke(keysym, down = false)
    if (alt) strokes += KeyStroke(X11Keysym.Alt_L, down = false)
    if (ctrl) strokes += KeyStroke(X11Keysym.Control_L, down = false)
    if (shift) strokes += KeyStroke(X11Keysym.Shift_L, down = false)
    return strokes
}

/**
 * Diffs old vs new IME buffer content into the key strokes needed to bring
 * the guest buffer in sync. Works off the longest common prefix (by
 * codepoint), not a raw length difference: a CJK IME REPLACES composing
 * text on commit (pinyin "nihao" -> "你好"), so the new buffer shares no
 * prefix with the old. Emitting only (oldCp - newCp) backspaces - the old
 * behaviour - left the composing text in the guest and never sent the
 * commit. Backspacing the whole divergent suffix and re-typing the new one
 * keeps the guest buffer in sync with the IME.
 */
fun imeDiffStrokes(old: String, new: String): List<KeyStroke> {
    // Length (in chars) and codepoint count of the shared leading run.
    var common = 0
    var commonCp = 0
    while (common < old.length && common < new.length) {
        val cp = old.codePointAt(common)
        if (cp != new.codePointAt(common)) break
        common += Character.charCount(cp)
        commonCp++
    }
    val strokes = mutableListOf<KeyStroke>()
    // Delete everything in old past the shared prefix.
    val oldCp = old.codePointCount(0, old.length)
    repeat(oldCp - commonCp) {
        strokes += KeyStroke(X11Keysym.BackSpace, down = true)
        strokes += KeyStroke(X11Keysym.BackSpace, down = false)
    }
    // Type everything in new past the shared prefix (codepoint-wise so a
    // surrogate pair is sent as a single keysym).
    var i = common
    while (i < new.length) {
        val cp = new.codePointAt(i)
        val keysym = keysymForCodePoint(cp)
        strokes += KeyStroke(keysym, down = true)
        strokes += KeyStroke(keysym, down = false)
        i += Character.charCount(cp)
    }
    return strokes
}
