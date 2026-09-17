/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalKeyEncoderTest {

    @Test fun `xtermModifier combines shift alt ctrl bits`() {
        assertEquals(1, xtermModifier(shift = false, alt = false, ctrl = false))
        assertEquals(8, xtermModifier(shift = true, alt = true, ctrl = true))
    }

    @Test fun `UP with mod 1 uses CSI or SS3 depending on appCursor`() {
        assertEquals("\u001B[A", String(encode(TermKey.UP, 1, appCursor = false)))
        assertEquals("\u001BOA", String(encode(TermKey.UP, 1, appCursor = true)))
    }

    @Test fun `UP with ctrl modifier ignores appCursor`() {
        val mod = xtermModifier(shift = false, alt = false, ctrl = true)
        assertEquals("\u001B[1;5A", String(encode(TermKey.UP, mod, appCursor = true)))
    }

    @Test fun `HOME with mod 1 and appCursor uses SS3`() {
        assertEquals("\u001BOH", String(encode(TermKey.HOME, 1, appCursor = true)))
    }

    @Test fun `PAGE_UP with mod 1 is unmodified tilde sequence`() {
        assertEquals("\u001B[5~", String(encode(TermKey.PAGE_UP, 1, appCursor = false)))
    }

    @Test fun `PAGE_UP with ctrl adds the modifier to the tilde sequence`() {
        val mod = xtermModifier(shift = false, alt = false, ctrl = true)
        assertEquals("\u001B[5;5~", String(encode(TermKey.PAGE_UP, mod, appCursor = false)))
    }

    @Test fun `F1 with mod 1 uses SS3`() {
        assertEquals("\u001BOP", String(encode(TermKey.F1, 1, appCursor = false)))
    }

    @Test fun `F1 with shift switches to the CSI form`() {
        val mod = xtermModifier(shift = true, alt = false, ctrl = false)
        assertEquals("\u001B[1;2P", String(encode(TermKey.F1, mod, appCursor = false)))
    }

    @Test fun `F12 with alt adds the modifier to the tilde sequence`() {
        val mod = xtermModifier(shift = false, alt = true, ctrl = false)
        assertEquals("\u001B[24;3~", String(encode(TermKey.F12, mod, appCursor = false)))
    }

    @Test fun `DELETE with mod 1 is the unmodified tilde sequence`() {
        assertEquals("\u001B[3~", String(encode(TermKey.DELETE, 1, appCursor = false)))
    }

    @Test fun `termKeyForKeyCode maps hardware key codes`() {
        assertEquals(TermKey.UP, termKeyForKeyCode(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(TermKey.PAGE_DOWN, termKeyForKeyCode(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(TermKey.DELETE, termKeyForKeyCode(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(TermKey.F12, termKeyForKeyCode(KeyEvent.KEYCODE_F12))
    }

    @Test fun `termKeyForKeyCode returns null for an unmapped key code`() {
        assertNull(termKeyForKeyCode(KeyEvent.KEYCODE_A))
    }

    @Test fun `termKeyForLabel maps extra-keys row labels`() {
        assertEquals(TermKey.HOME, termKeyForLabel("HOME"))
        assertEquals(TermKey.PAGE_UP, termKeyForLabel("PGUP"))
        assertEquals(TermKey.F1, termKeyForLabel("F1"))
    }

    @Test fun `termKeyForLabel returns null for an unmapped label`() {
        assertNull(termKeyForLabel("CTRL"))
        assertNull(termKeyForLabel("-"))
    }
}
