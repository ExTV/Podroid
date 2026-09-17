/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalThemeParsingTest {

    private val validToml = """
        [colors.primary]
        background = "#101010"
        foreground = "#e0e0e0"

        [colors.normal]
        black = "#000000"
        red = "#ff0000"
        green = "#00ff00"
        yellow = "#ffff00"
        blue = "#0000ff"
        magenta = "#ff00ff"
        cyan = "#00ffff"
        white = "#ffffff"

        [colors.bright]
        black = "#111111"
        red = "#ff1111"
        green = "#11ff11"
        yellow = "#ffff11"
        blue = "#1111ff"
        magenta = "#ff11ff"
        cyan = "#11ffff"
        white = "#eeeeee"
    """.trimIndent()

    @Test fun `minimal valid toml parses background foreground and ansi colors`() {
        val parsed = parseAlacrittyToml(validToml)
        assertEquals("#101010", parsed?.get("background"))
        assertEquals("#e0e0e0", parsed?.get("foreground"))
        assertEquals("#000000", parsed?.get("color0"))
        assertEquals("#ffffff", parsed?.get("color7"))
    }

    @Test fun `missing background returns null`() {
        val toml = validToml.replace("background = \"#101010\"\n", "")
        assertNull(parseAlacrittyToml(toml))
    }

    @Test fun `missing a normal color returns null`() {
        val toml = validToml.replace("white = \"#ffffff\"\n", "")
        assertNull(parseAlacrittyToml(toml))
    }

    @Test fun `bright colors mapped to color8 through color15`() {
        val parsed = parseAlacrittyToml(validToml)
        assertEquals("#111111", parsed?.get("color8"))
        assertEquals("#ff1111", parsed?.get("color9"))
        assertEquals("#eeeeee", parsed?.get("color15"))
    }

    @Test fun `parseHexColor expands 3-digit hex by x17`() {
        // r=0, g=15*17=255, b=8*17=136
        assertEquals(0xFF00FF88.toInt(), parseHexColor("#0f8"))
    }

    @Test fun `parseHexColor reads 6-digit hex as rgb`() {
        assertEquals(0xFF112233.toInt(), parseHexColor("#112233"))
    }

    @Test fun `parseHexColor reads 8-digit hex as aarrggbb`() {
        assertEquals(0x80112233.toInt(), parseHexColor("#80112233"))
    }

    @Test fun `parseHexColor falls back to black on junk input`() {
        assertEquals(0xFF000000.toInt(), parseHexColor("#zzz"))
        assertEquals(0xFF000000.toInt(), parseHexColor("#12"))
    }
}
