/*
 * Podroid
 * Copyright (C) 2024 Podroid contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.excp.podroid.ui.theme

import androidx.compose.ui.graphics.Color

// ── Core palette (deep indigo + cyan accent) ──────────────────────────────────

val Purple80       = Color(0xFFD0BCFF)
val PurpleGrey80   = Color(0xFFCCC2DC)
val Cyan80         = Color(0xFF80DEEA)

val Purple40       = Color(0xFF6650A4)
val PurpleGrey40   = Color(0xFF625B71)
val Cyan40         = Color(0xFF00838F)

// ── VM status indicator colors ────────────────────────────────────────────────

/** Shown on the VM card when the machine is actively running. */
val RunningGreen   = Color(0xFF4CAF50)

/** Shown when the VM is paused / saved. */
val PausedAmber    = Color(0xFFFFC107)

/** Error / stopped state. */
val ErrorRed       = Color(0xFFF44336)
