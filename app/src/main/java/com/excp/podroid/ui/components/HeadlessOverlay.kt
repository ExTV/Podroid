/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Full-screen pure-black overlay for server mode. Consumes all touches (the app
 * is still foreground, so this prevents stray input), shows a faint exit hint for
 * a few seconds, and exits on a 3-second press-and-hold.
 */
package com.excp.podroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.excp.podroid.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun HeadlessOverlay(onExit: () -> Unit) {
    var hintVisible by remember { mutableStateOf(true) }
    // Hide the hint a few seconds after it (re)appears, keeping the screen black.
    LaunchedEffect(hintVisible) {
        if (hintVisible) { delay(4000); hintVisible = false }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    hintVisible = true
                    // null == the 3s timeout fired while still held -> exit.
                    val releasedEarly = withTimeoutOrNull(3000L) { waitForUpOrCancellation(); true }
                    if (releasedEarly == null) onExit()
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (hintVisible) {
            Text(
                text = stringResource(R.string.server_mode_exit_hint),
                color = Color(0x33FFFFFF),
                modifier = Modifier.padding(bottom = 48.dp),
            )
        }
    }
}
