/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * The touch/mouse/scroll gesture state machine for the X11 viewer surface.
 * Moved out of X11Screen.kt's AndroidView(pointerInput) as-is; see that file
 * for wiring (geometry callbacks, drag-lock state ownership).
 */
package com.excp.podroid.ui.screens.x11

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import com.excp.podroid.x11.TouchMode
import com.excp.podroid.x11.VncClient
import com.excp.podroid.x11.X11Settings
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Holds the drag-lock flag across pointerInput coroutine restarts (a
 * touch-mode / trackpad-setting change restarts the gesture coroutine; the
 * lock must survive that, same as the remembered Compose state it
 * replaces). Owned by the caller via `remember`.
 */
internal class X11PointerLockState {
    var dragLocked: Boolean = false
}

private enum class GestureOutcome { MOVE, TAP, MULTI }

/**
 * Reads events until every pointer has lifted, consuming each one. Used
 * both when a new touch arrives while drag-locked and when a DIRECT-mode
 * touch starts outside the content rect.
 */
private suspend fun AwaitPointerEventScope.drainUntilAllPointersUp() {
    while (true) {
        val e = awaitPointerEvent()
        e.changes.forEach { it.consume() }
        if (e.changes.none { it.pressed }) break
    }
}

/**
 * Runs the gesture state machine on the surface's pointerInput scope.
 * Behavior is unchanged from the original inline version: same thresholds
 * (16f move slop, 500ms long press, 60f scroll step, 28f two-finger tap
 * slop), same consume() calls, same focus-on-touch-down, same letterbox
 * rejection in DIRECT mode, same `finally` release, same order of VM calls.
 *
 * [fbX]/[fbY]/[inContent] read the caller's rememberUpdatedState-backed
 * geometry and framebuffer size fresh on every call, so a resize mid-
 * gesture updates coordinate mapping instead of being stuck with stale
 * values from when the gesture started.
 */
internal suspend fun PointerInputScope.x11PointerLoop(
    s: X11Settings,
    lockState: X11PointerLockState,
    viewModel: X11ViewModel,
    requestFocus: () -> Unit,
    fbX: (Float) -> Int,
    fbY: (Float) -> Int,
    inContent: (Float, Float) -> Boolean,
) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue

            // Physical-mouse scroll wheel -> X wheel (buttons 4/5).
            if (event.type == PointerEventType.Scroll) {
                val dy = change.scrollDelta.y
                if (dy != 0f) {
                    viewModel.moveTo(fbX(change.position.x), fbY(change.position.y))
                    viewModel.scroll(up = dy < 0f, ticks = abs(dy).toInt().coerceAtLeast(1))
                }
                event.changes.forEach { it.consume() }
                continue
            }

            // Physical mouse -> absolute move + native buttons. Consuming
            // keeps right-click from falling through to Android Back (which
            // exited fullscreen) and sends it to X as button 3 instead.
            if (change.type == PointerType.Mouse) {
                var mask = 0
                if (event.buttons.isPrimaryPressed) mask = mask or VncClient.BTN_LEFT
                if (event.buttons.isSecondaryPressed) mask = mask or VncClient.BTN_RIGHT
                if (event.buttons.isTertiaryPressed) mask = mask or VncClient.BTN_MIDDLE
                viewModel.mouseUpdate(fbX(change.position.x), fbY(change.position.y), mask)
                event.changes.forEach { it.consume() }
                continue
            }

            // Touch -> finger-gesture state machine (one gesture).
            if (change.type != PointerType.Touch || !change.changedToDown()) continue
            requestFocus()
            change.consume()
            // Pin the primary pointer by ID so finger-order changes don't
            // jump the cursor to a different finger.
            val primaryId: PointerId = change.id
            val sx = change.position.x
            val sy = change.position.y
            var lastX = sx
            var lastY = sy
            var moved = 0f
            var maxPointers = 1
            var scrollAcc = 0f
            var leftHeld = false

            // A new touch while drag-locked drops the lock.
            if (lockState.dragLocked) {
                viewModel.release(VncClient.BTN_LEFT)
                lockState.dragLocked = false
                drainUntilAllPointersUp()
                continue
            }

            // DIRECT touch maps absolute screen coords to the framebuffer,
            // so a tap in the letterbox bars has no valid target: drain it
            // as a no-op rather than clamping to an edge click. (TRACKPAD is
            // relative: any start point is valid, so it's exempt.)
            if (s.touchMode == TouchMode.DIRECT && !inContent(sx, sy)) {
                drainUntilAllPointersUp()
                continue
            }

            if (s.touchMode == TouchMode.DIRECT) viewModel.moveTo(fbX(sx), fbY(sy))

            // Long-press (single finger, no move, ~500ms) => drag-lock.
            var outcome = GestureOutcome.MOVE
            val completed = withTimeoutOrNull(500L) {
                loop@ while (true) {
                    val e = awaitPointerEvent()
                    val pressed = e.changes.filter { it.pressed }
                    if (pressed.isEmpty()) { outcome = GestureOutcome.TAP; break@loop }
                    if (pressed.size >= 2) { maxPointers = 2; outcome = GestureOutcome.MULTI; break@loop }
                    val p = (pressed.firstOrNull { it.id == primaryId } ?: pressed.first()).position
                    if (abs(p.x - sx) + abs(p.y - sy) > 16f) {
                        lastX = p.x
                        lastY = p.y
                        outcome = GestureOutcome.MOVE
                        e.changes.forEach { it.consume() }
                        break@loop
                    }
                    e.changes.forEach { it.consume() }
                }
            }
            if (completed == null) {
                lockState.dragLocked = true
                viewModel.press(VncClient.BTN_LEFT)
                leftHeld = true
            } else if (outcome == GestureOutcome.TAP) {
                viewModel.click(VncClient.BTN_LEFT)
                continue
            }

            try {
                while (true) {
                    val e = awaitPointerEvent()
                    val pressed = e.changes.filter { it.pressed }
                    maxPointers = maxOf(maxPointers, pressed.size)
                    if (pressed.isEmpty()) break
                    // Track the pinned primary pointer, falling back to
                    // first if it lifted (e.g. swapped fingers).
                    val p = (pressed.firstOrNull { it.id == primaryId } ?: pressed.first()).position
                    val dx = p.x - lastX
                    val dy = p.y - lastY
                    moved += abs(dx) + abs(dy)
                    if (pressed.size >= 2) {
                        // Transitioning 1->2 fingers: release left if held
                        // so we don't send a left+scroll chord.
                        if (leftHeld) { viewModel.release(VncClient.BTN_LEFT); leftHeld = false }
                        scrollAcc += dy
                        while (abs(scrollAcc) >= 60f) {
                            viewModel.scroll(scrollAcc < 0, 1)
                            scrollAcc += if (scrollAcc < 0) 60f else -60f
                        }
                    } else when (s.touchMode) {
                        TouchMode.DIRECT -> {
                            viewModel.moveTo(fbX(p.x), fbY(p.y))
                            if (!leftHeld) { viewModel.press(VncClient.BTN_LEFT); leftHeld = true }
                        }
                        TouchMode.TRACKPAD -> {
                            val accel = if (s.trackpadAccel) (1f + (abs(dx) + abs(dy)) * 0.01f) else 1f
                            val c = viewModel.cursor.value
                            viewModel.moveTo(
                                (c.x + dx * s.trackpadSensitivity * accel).toInt(),
                                (c.y + dy * s.trackpadSensitivity * accel).toInt(),
                            )
                        }
                    }
                    lastX = p.x
                    lastY = p.y
                    e.changes.forEach { it.consume() }
                }
            } finally {
                // Release left button on cancellation (e.g. settings-change
                // restarts the pointerInput coroutine mid-drag).
                if (leftHeld && !lockState.dragLocked) { viewModel.release(VncClient.BTN_LEFT); leftHeld = false }
            }

            if (maxPointers >= 2) {
                if (moved < 28f) viewModel.click(VncClient.BTN_RIGHT)
            } else if (s.touchMode == TouchMode.TRACKPAD && !lockState.dragLocked && moved < 16f) {
                viewModel.click(VncClient.BTN_LEFT)
            }
            if (!lockState.dragLocked && leftHeld) { viewModel.release(VncClient.BTN_LEFT); leftHeld = false }
        }
    }
}
