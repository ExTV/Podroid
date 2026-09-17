/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.x11

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class X11KeysTest {

    @Test fun `labelToKeysym maps named labels to their X11 keysym`() {
        assertEquals(X11Keysym.Escape, labelToKeysym("ESC"))
        assertEquals(X11Keysym.F12, labelToKeysym("F12"))
        assertEquals(0x7C, labelToKeysym("|"))
    }

    @Test fun `labelToKeysym returns null for the sticky-modifier labels`() {
        assertNull(labelToKeysym("CTRL"))
    }

    @Test fun `specialKeysymForKeyCode maps the hardware special keys`() {
        assertEquals(X11Keysym.BackSpace, specialKeysymForKeyCode(KeyEvent.KEYCODE_DEL))
        assertEquals(X11Keysym.Delete, specialKeysymForKeyCode(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(X11Keysym.Return, specialKeysymForKeyCode(KeyEvent.KEYCODE_NUMPAD_ENTER))
    }

    @Test fun `specialKeysymForKeyCode returns null for an unmapped keycode`() {
        assertNull(specialKeysymForKeyCode(KeyEvent.KEYCODE_A))
    }

    @Test fun `keysymForCodePoint passes ASCII through and maps non-ASCII to the extension range`() {
        assertEquals('a'.code, keysymForCodePoint('a'.code))
        assertEquals('~'.code, keysymForCodePoint('~'.code))
        assertEquals(0x01000000 or 0xE9, keysymForCodePoint(0xE9))
        assertEquals(0x01000000 or 0x1F600, keysymForCodePoint(0x1F600))
    }

    @Test fun `wrapWithModifiers with no modifiers is just down then up`() {
        val strokes = wrapWithModifiers(0x61, shift = false, ctrl = false, alt = false)

        assertEquals(listOf(KeyStroke(0x61, true), KeyStroke(0x61, false)), strokes)
    }

    @Test fun `wrapWithModifiers with all three modifiers wraps in shift ctrl alt order`() {
        val strokes = wrapWithModifiers(0x61, shift = true, ctrl = true, alt = true)

        assertEquals(
            listOf(
                KeyStroke(X11Keysym.Shift_L, true),
                KeyStroke(X11Keysym.Control_L, true),
                KeyStroke(X11Keysym.Alt_L, true),
                KeyStroke(0x61, true),
                KeyStroke(0x61, false),
                KeyStroke(X11Keysym.Alt_L, false),
                KeyStroke(X11Keysym.Control_L, false),
                KeyStroke(X11Keysym.Shift_L, false),
            ),
            strokes,
        )
    }

    @Test fun `imeDiffStrokes for an append only types the added suffix`() {
        val strokes = imeDiffStrokes("ab", "abc")

        assertEquals(listOf(KeyStroke('c'.code, true), KeyStroke('c'.code, false)), strokes)
    }

    @Test fun `imeDiffStrokes for a backspace sends one backspace pair`() {
        val strokes = imeDiffStrokes("abc", "ab")

        assertEquals(listOf(KeyStroke(X11Keysym.BackSpace, true), KeyStroke(X11Keysym.BackSpace, false)), strokes)
    }

    @Test fun `imeDiffStrokes for a CJK commit backspaces the whole old run then types the new one`() {
        // Pinyin "nihao" (5 chars, no shared prefix) commits to "你好" (2 chars).
        val strokes = imeDiffStrokes("nihao", "你好")

        val backspaces = strokes.take(10)
        assertEquals(List(10) { KeyStroke(X11Keysym.BackSpace, it % 2 == 0) }, backspaces)
        assertEquals(
            listOf(
                KeyStroke(0x01000000 or 0x4f60, true),
                KeyStroke(0x01000000 or 0x4f60, false),
                KeyStroke(0x01000000 or 0x597d, true),
                KeyStroke(0x01000000 or 0x597d, false),
            ),
            strokes.drop(10),
        )
    }

    @Test fun `imeDiffStrokes counts an appended surrogate pair as a single keysym`() {
        val emoji = String(Character.toChars(0x1F600))
        val strokes = imeDiffStrokes("", emoji)

        val keysym = 0x01000000 or 0x1F600
        assertEquals(listOf(KeyStroke(keysym, true), KeyStroke(keysym, false)), strokes)
    }

    @Test fun `imeDiffStrokes for identical strings is empty`() {
        assertTrue(imeDiffStrokes("same", "same").isEmpty())
    }
}
