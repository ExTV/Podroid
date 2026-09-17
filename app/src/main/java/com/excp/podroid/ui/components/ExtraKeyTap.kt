package com.excp.podroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay

/**
 * Tap/repeat behavior shared by the terminal's extra-keys row (`KeyButton`)
 * and the X11 viewer's extra-keys row (`X11KeyButton`). Owns the pressed
 * state and the repeat loop (400ms initial delay, 70ms interval decreasing by
 * 3ms down to 30ms) for repeatable keys (arrows), and exposes button
 * semantics so TalkBack can announce/activate both repeatable and
 * non-repeatable keys. Callers apply their own visual styling on top of the
 * returned modifier.
 *
 * `onKey` is intentionally not read via `rememberUpdatedState`: the repeat
 * loop is keyed on `pressed`/`sendKey`/`repeatable` only, so it keeps using
 * whichever `onKey` was current when the loop started, matching the
 * behavior of the two call sites before this was shared.
 */
@Composable
fun rememberExtraKeyTapModifier(
    sendKey: String,
    repeatable: Boolean,
    onKey: (String) -> Unit,
): Modifier {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, sendKey, repeatable) {
        if (!repeatable || !pressed) return@LaunchedEffect
        delay(400L)
        var interval = 70L
        while (pressed) {
            onKey(sendKey)
            delay(interval)
            if (interval > 30L) interval -= 3L
        }
    }
    return if (repeatable) {
        // The raw pointerInput path is invisible to accessibility services; add
        // button semantics + an onClick action so TalkBack can announce and
        // activate repeatable keys (arrows) like the clickable ones.
        Modifier
            .semantics {
                role = Role.Button
                onClick(label = sendKey) { onKey(sendKey); true }
            }
            .pointerInput(sendKey) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onKey(sendKey)
                    pressed = true
                    try { waitForUpOrCancellation() } finally { pressed = false }
                }
            }
    } else {
        Modifier.clickable(role = Role.Button) { onKey(sendKey) }
    }
}
