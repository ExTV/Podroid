/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Pure theme-file parsing shared by TerminalAppearanceStore, with no Android
 * framework dependency so it runs in a plain JVM unit test.
 */
package com.excp.podroid.ui.screens.terminal

/**
 * Parse an Alacritty TOML color export into our `.properties` keys.
 * Returns null if required keys (background/foreground/16 ANSI colors) aren't found.
 */
fun parseAlacrittyToml(toml: String): Map<String, String>? {
    // [colors.<section>] key = "#xxxxxx"
    val sectionRe = Regex("""\[colors\.(\w+)]""")
    val kvRe = Regex("""(\w+)\s*=\s*"(#[0-9a-fA-F]{3,8})"""")
    var section = ""
    val byKey = LinkedHashMap<String, String>()
    for (raw in toml.lines()) {
        val line = raw.trim()
        sectionRe.find(line)?.let { section = it.groupValues[1]; return@let }
        val kv = kvRe.find(line) ?: continue
        val key = kv.groupValues[1]
        val value = kv.groupValues[2]
        val mapped = when (section) {
            "primary" -> when (key) {
                "foreground" -> "foreground"
                "background" -> "background"
                else -> null
            }
            "cursor" -> when (key) {
                "cursor" -> "cursor"
                "text" -> null   // foreground-of-cursor, emulator doesn't store separately
                else -> null
            }
            "normal" -> when (key) {
                "black"   -> "color0"; "red"     -> "color1"
                "green"   -> "color2"; "yellow"  -> "color3"
                "blue"    -> "color4"; "magenta" -> "color5"
                "cyan"    -> "color6"; "white"   -> "color7"
                else -> null
            }
            "bright" -> when (key) {
                "black"   -> "color8";  "red"     -> "color9"
                "green"   -> "color10"; "yellow"  -> "color11"
                "blue"    -> "color12"; "magenta" -> "color13"
                "cyan"    -> "color14"; "white"   -> "color15"
                else -> null
            }
            else -> null
        } ?: continue
        byKey[mapped] = value
    }
    // Sanity: must have FG + BG and at least 8 ANSI colors.
    if (!byKey.containsKey("foreground") || !byKey.containsKey("background")) return null
    if ((0..7).any { !byKey.containsKey("color$it") }) return null
    return byKey
}

/** ARGB black, matching android.graphics.Color.BLACK (0xFF000000). */
private const val BLACK = 0xFF000000.toInt()

private fun rgb(red: Int, green: Int, blue: Int): Int =
    0xff000000.toInt() or (red shl 16) or (green shl 8) or blue

private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    (alpha shl 24) or (red shl 16) or (green shl 8) or blue

/**
 * Parse a `#rgb` / `#rrggbb` / `#aarrggbb` hex color into an ARGB int,
 * computed with bit math so it matches android.graphics.Color.rgb/argb
 * exactly without depending on the Android framework (unmocked in a plain
 * JVM unit test). Falls back to BLACK on an unrecognized length or a bad
 * hex digit in the 3-digit form.
 */
fun parseHexColor(hex: String): Int {
    val clean = hex.removePrefix("#")
    return when (clean.length) {
        3 -> {
            val r = clean[0].digitToIntOrNull(16) ?: return BLACK
            val g = clean[1].digitToIntOrNull(16) ?: return BLACK
            val b = clean[2].digitToIntOrNull(16) ?: return BLACK
            rgb(r * 17, g * 17, b * 17)
        }
        6 -> rgb(
            clean.substring(0, 2).toInt(16),
            clean.substring(2, 4).toInt(16),
            clean.substring(4, 6).toInt(16)
        )
        8 -> argb(
            clean.substring(0, 2).toInt(16),
            clean.substring(2, 4).toInt(16),
            clean.substring(4, 6).toInt(16),
            clean.substring(6, 8).toInt(16)
        )
        else -> BLACK
    }
}
