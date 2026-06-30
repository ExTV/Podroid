/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.ui.components.PodroidTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.excp.podroid.x11.TouchMode
import com.excp.podroid.x11.VncClient

// X11 keysyms used outside the label table.
private const val XK_BackSpace = 0xFF08
private const val XK_Tab       = 0xFF09
private const val XK_Return    = 0xFF0D
private const val XK_Escape    = 0xFF1B
private const val XK_Left      = 0xFF51
private const val XK_Up        = 0xFF52
private const val XK_Right     = 0xFF53
private const val XK_Down      = 0xFF54
private const val XK_Shift_L   = 0xFFE1
private const val XK_Control_L = 0xFFE3
private const val XK_Alt_L     = 0xFFE9

/**
 * Maps the human-readable label used by [X11ExtraKeysRow] (matching the
 * terminal's ExtraKeysRow vocabulary) to an X11 keysym. Returns null for
 * pure modifier labels (CTRL/ALT) — those are handled as toggles.
 */
private fun labelToKeysym(label: String): Int? = when (label) {
    "ESC"     -> XK_Escape
    "TAB"     -> XK_Tab
    "LEFT"    -> XK_Left
    "RIGHT"   -> XK_Right
    "UP"      -> XK_Up
    "DOWN"    -> XK_Down
    "HOME"    -> 0xFF50
    "END"     -> 0xFF57
    "PGUP"    -> 0xFF55
    "PGDN"    -> 0xFF56
    "F1"      -> 0xFFBE
    "F2"      -> 0xFFBF
    "F3"      -> 0xFFC0
    "F4"      -> 0xFFC1
    "F5"      -> 0xFFC2
    "F6"      -> 0xFFC3
    "F7"      -> 0xFFC4
    "F8"      -> 0xFFC5
    "F9"      -> 0xFFC6
    "F10"     -> 0xFFC7
    "F11"     -> 0xFFC8
    "F12"     -> 0xFFC9
    "-"       -> 0x2D
    "/"       -> 0x2F
    "|"       -> 0x7C
    else      -> null   // CTRL / ALT handled by toggles
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalComposeUiApi::class,
)
@Composable
fun X11Screen(
    onNavigateBack: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    viewModel: X11ViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val frameCount by viewModel.frameCounter.collectAsStateWithLifecycle()
    val fb by viewModel.fbSize.collectAsStateWithLifecycle()
    val bitmap = remember(fb) { Bitmap.createBitmap(fb.w, fb.h, Bitmap.Config.ARGB_8888) }
    val s by viewModel.x11Settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.connect() }

    val activity = LocalActivity.current
    // Restore orientation when leaving; without this the lock persists onto
    // terminal/home until process restart.
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(s.rotationLock) {
        activity?.requestedOrientation = when (s.rotationLock) {
            com.excp.podroid.x11.RotationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            com.excp.podroid.x11.RotationLock.PORTRAIT  -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            com.excp.podroid.x11.RotationLock.AUTO      -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val view = LocalView.current
    // Key on s.fullscreenDefault so the setting is picked up once DataStore
    // delivers its first non-default emission; manual toggles still work because
    // a toggle changes `fullscreen` without changing the key.
    var fullscreen by remember(s.fullscreenDefault) { mutableStateOf(s.fullscreenDefault) }
    LaunchedEffect(fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val ctrl = WindowInsetsControllerCompat(window, view)
        if (fullscreen) {
            ctrl.hide(WindowInsetsCompat.Type.systemBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Keep the display awake while the X11 viewer is open, matching the
    // terminal (TerminalScreen adds the same flag). The VM-lifetime WakeLock in
    // PodroidService is partial/CPU-only; this is the screen-on counterpart.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // Ensure disconnect + heldButtons reset on screen exit even if the ViewModel
    // lives longer than this nav entry (onCleared would also call it, but that
    // fires later; the immediate dispose prevents stuck buttons across navigation).
    DisposableEffect(Unit) {
        onDispose { viewModel.disconnect() }
    }

    // Back exits fullscreen first (no on-screen exit button); a second Back
    // leaves the viewer through normal navigation.
    BackHandler(enabled = fullscreen) { fullscreen = false }

    var showSettings by remember { mutableStateOf(false) }

    var svWidth  by remember { mutableIntStateOf(1) }
    var svHeight by remember { mutableIntStateOf(1) }
    // Largest surface height seen for the current width: the "genuine" surface
    // size with no IME inset. An IME dismiss grows the surface back UP TO this
    // value, which must NOT trigger a desktop-size renegotiation; only a height
    // beyond this baseline (or a width change) is a real surface resize.
    var svGenuineHeight by remember { mutableIntStateOf(0) }

    // Letterbox / pillarbox dst rect, pinned to top so the soft keyboard
    // (and the extra-keys row) live in the empty bottom strip.
    val (dstX, dstY, dstW, dstH) = remember(svWidth, svHeight, fb) {
        val fbW = fb.w.toFloat()
        val fbH = fb.h.toFloat()
        val viewW = svWidth.toFloat().coerceAtLeast(1f)
        val viewH = svHeight.toFloat().coerceAtLeast(1f)
        val scale = minOf(viewW / fbW, viewH / fbH)
        val dW = (fbW * scale).toInt().coerceAtLeast(1)
        val dH = (fbH * scale).toInt().coerceAtLeast(1)
        val dX = ((viewW - dW) / 2f).toInt()
        val dY = 0
        IntArray4(dX, dY, dW, dH)
    }

    val focusRequester = remember { FocusRequester() }
    val viewerFocus = remember { FocusRequester() }
    // Hold focus on the (non-editable) viewer while connected so a hardware
    // keyboard's keys reach onPreviewKeyEvent WITHOUT popping the soft keyboard
    // (a focused editable field would). The on-screen keyboard is summoned
    // explicitly via the keyboard button.
    LaunchedEffect(connection) {
        if (connection == X11ConnectionState.Connected) runCatching { viewerFocus.requestFocus() }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeBuf by remember { mutableStateOf(TextFieldValue("")) }

    // Sticky modifier state — tap CTRL once, the next key is sent with
    // Control_L held; the modifier auto-clears after that one keypress
    // (one-shot semantics, matches Termux convention).
    // Drag-lock: a long-press engages a held left button that persists across
    // gestures until the next tap drops it (move heavy GUI windows one-handed).
    var dragLocked by remember { mutableStateOf(false) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive  by remember { mutableStateOf(false) }

    fun sendWithModifiers(keysym: Int) {
        // Snapshot then clear the one-shot modifiers BEFORE emitting, so the
        // wrap decision and the flag reset are atomic from the caller's view: a
        // re-entrant call (e.g. an IME diff arriving while a hardware key is mid-
        // emit) can't observe the flag still set and double-wrap, and the up
        // events are computed from the same snapshot that produced the downs.
        val ctrl = ctrlActive
        val alt  = altActive
        ctrlActive = false
        altActive  = false
        if (ctrl) viewModel.sendKey(XK_Control_L, down = true)
        if (alt)  viewModel.sendKey(XK_Alt_L,     down = true)
        viewModel.sendKey(keysym, down = true)
        viewModel.sendKey(keysym, down = false)
        if (alt)  viewModel.sendKey(XK_Alt_L,     down = false)
        if (ctrl) viewModel.sendKey(XK_Control_L, down = false)
    }

    fun onExtraKey(label: String) {
        when (label) {
            "CTRL" -> ctrlActive = !ctrlActive
            "ALT"  -> altActive  = !altActive
            else -> labelToKeysym(label)?.let(::sendWithModifiers)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(viewerFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                // Hardware/external keyboard. Lives on the (non-editable) Box so
                // it never pops the soft keyboard; the preview pass means it
                // fires for all key events while focus is anywhere in this
                // subtree (including when the on-screen keyboard field is up).
                val native = ev.nativeKeyEvent
                // Mouse right-click makes Android synthesize a BACK key. The
                // pointer handler already sent it to X as button 3, so swallow
                // the mouse-sourced Back (both down + up) to stop it exiting
                // fullscreen. A real Back (gesture/keyboard) passes through to
                // the BackHandler. Only intercept when fullscreen; in windowed
                // mode Back should navigate out normally.
                if (ev.key == Key.Back && fullscreen) {
                    return@onPreviewKeyEvent (native.source and android.view.InputDevice.SOURCE_MOUSE) ==
                        android.view.InputDevice.SOURCE_MOUSE
                }
                if (android.view.KeyEvent.isModifierKey(native.keyCode)) {
                    return@onPreviewKeyEvent true
                }
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val special = when (ev.key) {
                    Key.Backspace      -> XK_BackSpace
                    Key.Enter, Key.NumPadEnter -> XK_Return
                    Key.Tab            -> XK_Tab
                    Key.Escape         -> XK_Escape
                    Key.DirectionLeft  -> XK_Left
                    Key.DirectionRight -> XK_Right
                    Key.DirectionUp    -> XK_Up
                    Key.DirectionDown  -> XK_Down
                    Key.MoveHome       -> 0xFF50
                    Key.MoveEnd        -> 0xFF57
                    Key.PageUp         -> 0xFF55
                    Key.PageDown       -> 0xFF56
                    Key.Delete         -> 0xFFFF
                    else               -> null
                }
                val ctrl = native.isCtrlPressed || ctrlActive
                val alt  = native.isAltPressed  || altActive
                val keysym: Int
                val shiftWrap: Boolean
                if (special != null) {
                    keysym = special
                    shiftWrap = native.isShiftPressed
                } else {
                    val cased = native.getUnicodeChar(
                        native.metaState and
                            (android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_CAPS_LOCK_ON)
                    )
                    if (cased == 0) return@onPreviewKeyEvent false
                    // X keysyms 0x20-0x7E match ASCII verbatim; non-ASCII Unicode
                    // maps to 0x01000000 | codepoint (X11 protocol extension).
                    keysym = if (cased > 0x7E) 0x01000000 or cased else cased
                    shiftWrap = false
                }
                if (shiftWrap) viewModel.sendKey(XK_Shift_L, down = true)
                if (ctrl) viewModel.sendKey(XK_Control_L, down = true)
                if (alt)  viewModel.sendKey(XK_Alt_L, down = true)
                viewModel.sendKey(keysym, down = true)
                viewModel.sendKey(keysym, down = false)
                if (alt)  viewModel.sendKey(XK_Alt_L, down = false)
                if (ctrl) viewModel.sendKey(XK_Control_L, down = false)
                if (shiftWrap) viewModel.sendKey(XK_Shift_L, down = false)
                ctrlActive = false
                altActive  = false
                true
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Push the bottom of the layout up by the IME height when the
                // soft keyboard opens. Effect: extra-keys row rides above the
                // keyboard, AndroidView (weight=1) shrinks to fill the gap.
                .windowInsetsPadding(WindowInsets.ime),
        ) {
            if (!fullscreen) {
                PodroidTopBar(
                    title = stringResource(R.string.x11_title),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { fullscreen = true }) {
                            Icon(Icons.Default.Fullscreen, contentDescription = stringResource(R.string.fullscreen))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.settings))
                        }
                        IconButton(onClick = {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }) {
                            Icon(Icons.Default.Keyboard, contentDescription = stringResource(R.string.keyboard))
                        }
                        IconButton(onClick = onNavigateToTerminal) {
                            Icon(
                                Icons.Default.DesktopWindows,
                                contentDescription = stringResource(R.string.terminal),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }

            if (showSettings) {
                X11SettingsSheet(viewModel = viewModel, onDismiss = { showSettings = false })
            }

        when (val state = connection) {
            X11ConnectionState.Connecting,
            X11ConnectionState.Disconnected -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.x11_connecting),
                        modifier = Modifier.padding(top = 80.dp),
                        color = Color.White,
                    )
                }
            }
            is X11ConnectionState.Failed -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            "${stringResource(R.string.x11_not_ready)}\n${state.message}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        // Xvnc often isn't up yet right after boot; connect() is
                        // idempotent so a manual retry is safe instead of forcing
                        // the user to leave and re-enter the screen.
                        Button(onClick = { viewModel.connect() }) {
                            Text(stringResource(R.string.try_again))
                        }
                    }
                }
            }
            X11ConnectionState.Connected -> {
                // rememberUpdatedState lets the pointerInput lambda always read the
                // latest layout values without being in the key list, so a resize
                // during an in-flight gesture updates coordinate mapping rather than
                // cancelling the gesture coroutine mid-drag.
                val currentDstX by rememberUpdatedState(dstX)
                val currentDstY by rememberUpdatedState(dstY)
                val currentDstW by rememberUpdatedState(dstW)
                val currentDstH by rememberUpdatedState(dstH)
                val currentFbW  by rememberUpdatedState(fb.w)
                val currentFbH  by rememberUpdatedState(fb.h)

                AndroidView(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(
                            s.touchMode,
                            s.trackpadSensitivity, s.trackpadAccel,
                        ) {
                            fun fbX(px: Float) = ((px - currentDstX) / currentDstW.coerceAtLeast(1) * currentFbW).toInt().coerceIn(0, currentFbW - 1)
                            fun fbY(py: Float) = ((py - currentDstY) / currentDstH.coerceAtLeast(1) * currentFbH).toInt().coerceIn(0, currentFbH - 1)
                            // True only inside the letterbox/pillarbox content rect. Used to
                            // reject DIRECT-touch taps that land in the black bars instead of
                            // clamping them to an edge pixel (which produced a phantom edge click).
                            fun inContent(px: Float, py: Float) =
                                px >= currentDstX && px < currentDstX + currentDstW &&
                                    py >= currentDstY && py < currentDstY + currentDstH
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue

                                        // Physical-mouse scroll wheel → X wheel (buttons 4/5).
                                        if (event.type == PointerEventType.Scroll) {
                                            val dy = change.scrollDelta.y
                                            if (dy != 0f) {
                                                viewModel.moveTo(fbX(change.position.x), fbY(change.position.y))
                                                viewModel.scroll(up = dy < 0f, ticks = abs(dy).toInt().coerceAtLeast(1))
                                            }
                                            event.changes.forEach { it.consume() }
                                            continue
                                        }

                                        // Physical mouse → absolute move + native buttons.
                                        // Consuming keeps right-click from falling through to
                                        // Android Back (which exited fullscreen) and sends it
                                        // to X as button 3 instead.
                                        if (change.type == PointerType.Mouse) {
                                            var mask = 0
                                            if (event.buttons.isPrimaryPressed)   mask = mask or VncClient.BTN_LEFT
                                            if (event.buttons.isSecondaryPressed) mask = mask or VncClient.BTN_RIGHT
                                            if (event.buttons.isTertiaryPressed)  mask = mask or VncClient.BTN_MIDDLE
                                            viewModel.mouseUpdate(fbX(change.position.x), fbY(change.position.y), mask)
                                            event.changes.forEach { it.consume() }
                                            continue
                                        }

                                    // Touch → finger-gesture state machine (one gesture).
                                    if (change.type != PointerType.Touch || !change.changedToDown()) continue
                                    viewerFocus.requestFocus()
                                    change.consume()
                                    // Pin the primary pointer by ID so finger-order changes
                                    // don't jump the cursor to a different finger.
                                    val primaryId: PointerId = change.id
                                    val sx = change.position.x; val sy = change.position.y
                                    var lastX = sx; var lastY = sy
                                    var moved = 0f
                                    var maxPointers = 1
                                    var scrollAcc = 0f
                                    var leftHeld = false

                                    // A new touch while drag-locked drops the lock.
                                    if (dragLocked) {
                                        viewModel.release(VncClient.BTN_LEFT)
                                        dragLocked = false
                                        while (true) {
                                            val e = awaitPointerEvent(); e.changes.forEach { it.consume() }
                                            if (e.changes.none { it.pressed }) break
                                        }
                                        continue
                                    }

                                    // DIRECT touch maps absolute screen coords to the framebuffer,
                                    // so a tap in the letterbox bars has no valid target: drain it
                                    // as a no-op rather than clamping to an edge click. (TRACKPAD is
                                    // relative: any start point is valid, so it's exempt.)
                                    if (s.touchMode == TouchMode.DIRECT && !inContent(sx, sy)) {
                                        while (true) {
                                            val e = awaitPointerEvent(); e.changes.forEach { it.consume() }
                                            if (e.changes.none { it.pressed }) break
                                        }
                                        continue
                                    }

                                        if (s.touchMode == TouchMode.DIRECT) viewModel.moveTo(fbX(sx), fbY(sy))

                                    // Long-press (single finger, no move, ~500ms) => drag-lock.
                                    var outcome = "move"
                                    val completed = withTimeoutOrNull(500L) {
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            val pressed = e.changes.filter { it.pressed }
                                            if (pressed.isEmpty()) { outcome = "tap"; return@withTimeoutOrNull Unit }
                                            if (pressed.size >= 2) { maxPointers = 2; outcome = "multi"; return@withTimeoutOrNull Unit }
                                            val p = (pressed.firstOrNull { it.id == primaryId } ?: pressed.first()).position
                                            if (abs(p.x - sx) + abs(p.y - sy) > 16f) {
                                                lastX = p.x; lastY = p.y; outcome = "move"
                                                e.changes.forEach { it.consume() }
                                                return@withTimeoutOrNull Unit
                                            }
                                            e.changes.forEach { it.consume() }
                                        }
                                        @Suppress("UNREACHABLE_CODE") Unit
                                    }
                                    if (completed == null) {
                                        dragLocked = true; viewModel.press(VncClient.BTN_LEFT); leftHeld = true
                                    } else if (outcome == "tap") {
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
                                            val dx = p.x - lastX; val dy = p.y - lastY
                                            moved += abs(dx) + abs(dy)
                                            if (pressed.size >= 2) {
                                                // Transitioning 1→2 fingers: release left if held
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
                                            lastX = p.x; lastY = p.y
                                            e.changes.forEach { it.consume() }
                                        }
                                    } finally {
                                        // Release left button on cancellation (e.g. settings-change
                                        // restarts the pointerInput coroutine mid-drag).
                                        if (leftHeld && !dragLocked) { viewModel.release(VncClient.BTN_LEFT); leftHeld = false }
                                    }

                                    if (maxPointers >= 2) {
                                        if (moved < 28f) viewModel.click(VncClient.BTN_RIGHT)
                                    } else if (s.touchMode == TouchMode.TRACKPAD && !dragLocked && moved < 16f) {
                                        viewModel.click(VncClient.BTN_LEFT)
                                    }
                                    if (!dragLocked && leftHeld) { viewModel.release(VncClient.BTN_LEFT); leftHeld = false }
                                }
                            }
                        },
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {}
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                    // Ignore height changes caused by the IME opening/closing;
                                    // those reflow the layout but don't change the genuine surface
                                    // size. A width change resets the baseline (rotation/relayout);
                                    // otherwise only a height BEYOND the largest non-IME height seen
                                    // counts as a real resize. An IME dismiss grows hh back up to the
                                    // baseline and is correctly skipped.
                                    val widthChanged = w != svWidth
                                    if (widthChanged) svGenuineHeight = 0
                                    val heightGrew = hh > svGenuineHeight
                                    svWidth = w
                                    svHeight = hh
                                    if (heightGrew) svGenuineHeight = hh
                                    if (widthChanged || heightGrew) {
                                        viewModel.requestResolution(w, hh)
                                    }
                                }
                                override fun surfaceDestroyed(h: SurfaceHolder) {}
                            })
                        }
                    },
                    update = { sv ->
                        @Suppress("UNUSED_EXPRESSION")
                        frameCount
                        // Lock the IntArray for the copy into Bitmap pixels so
                        // we never observe a half-written frame from the RFB
                        // decoder thread (paired with synchronized(fbLock)
                        // in X11ViewModel.connect).
                        synchronized(viewModel.fbLock) {
                            val src = viewModel.framebuffer
                            val bw = bitmap.width
                            val bh = bitmap.height
                            // During a resolution change the framebuffer array is
                            // reallocated on the RFB thread while the Bitmap is
                            // recreated on a (slightly later) recomposition. Blit
                            // only when array and Bitmap agree in size, and clamp
                            // against the Bitmap's OWN dimensions — otherwise skip
                            // this frame (the next is consistent). Guards the
                            // "y + height must be <= bitmap.height()" crash on open.
                            if (src.size == bw * bh) {
                                val damage = viewModel.lastDamage
                                if (damage.isEmpty()) {
                                    bitmap.setPixels(src, 0, bw, 0, 0, bw, bh)
                                } else {
                                    for (r in damage) {
                                        val rx = r.x.coerceIn(0, bw)
                                        val ry = r.y.coerceIn(0, bh)
                                        val rw = (r.x + r.w).coerceAtMost(bw) - rx
                                        val rh = (r.y + r.h).coerceAtMost(bh) - ry
                                        if (rw <= 0 || rh <= 0) continue
                                        bitmap.setPixels(src, ry * bw + rx, bw, rx, ry, rw, rh)
                                    }
                                }
                            }
                        }
                        val holder = sv.holder
                        val canvas = holder.lockCanvas() ?: return@AndroidView
                        try {
                            canvas.drawColor(android.graphics.Color.BLACK)
                            val dst = Rect(dstX, dstY, dstX + dstW, dstY + dstH)
                            canvas.drawBitmap(bitmap, null, dst, null)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    },
                )

                    if (s.showExtraKeys && !fullscreen) {
                        X11ExtraKeysRow(
                            onKey = ::onExtraKey,
                            ctrlActive = ctrlActive,
                            altActive  = altActive,
                        )
                    }

                // Hidden IME hook (must stay in the layout while connected so
                // the requestFocus/show sequence has a target).
                BasicTextField(
                    value = imeBuf,
                    onValueChange = { new ->
                        val old = imeBuf
                        // Compute the added text based on the actual old buffer so
                        // deletions (Backspace) are observable as shrinks. Do NOT
                        // reset to empty before the diff — that's what killed Backspace.
                        val addedText = if (new.text.length > old.text.length)
                            new.text.substring(old.text.length) else ""
                        if ((ctrlActive || altActive) && addedText.length == 1) {
                            // Combine the sticky CTRL/ALT with the typed character
                            // (e.g. tap CTRL then type L → Ctrl+L to clear the
                            // terminal). sendWithModifiers clears the one-shot after.
                            sendWithModifiers(addedText[0].code)
                            // Reset buffer after ctrl/alt combo to keep it short.
                            imeBuf = TextFieldValue("")
                        } else {
                            forwardImeDiff(old.text, new.text, viewModel)
                            // Keep the rolling buffer so future deletions are
                            // observable, but cap it to avoid accumulating without bound.
                            imeBuf = if (new.text.length > 128) TextFieldValue(new.text.takeLast(64)) else new
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            viewModel.sendKey(XK_Return, down = true)
                            viewModel.sendKey(XK_Return, down = false)
                        },
                    ),
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .focusRequester(focusRequester),
                )
            }
        } // end when
        } // end Column

    } // end Box
}

/**
 * Same vocabulary as the terminal's ExtraKeysRow so muscle memory carries
 * over: ESC, TAB, CTRL, arrows, ALT, punctuation, HOME/END, PGUP/PGDN, F1–F12.
 * CTRL and ALT are sticky one-shot modifiers (highlighted while active).
 */
@Composable
private fun X11ExtraKeysRow(
    onKey: (String) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        X11KeyButton("ESC", onKey)
        X11KeyButton("TAB", onKey)
        X11KeyButton("CTRL", onKey, isActive = ctrlActive)
        X11KeyButton("←", onKey, sendKey = "LEFT",  repeatable = true)
        X11KeyButton("↑", onKey, sendKey = "UP",    repeatable = true)
        X11KeyButton("↓", onKey, sendKey = "DOWN",  repeatable = true)
        X11KeyButton("→", onKey, sendKey = "RIGHT", repeatable = true)
        X11KeyButton("ALT", onKey, isActive = altActive)
        X11KeyButton("-", onKey)
        X11KeyButton("/", onKey)
        X11KeyButton("|", onKey)
        X11KeyButton("HOME", onKey)
        X11KeyButton("END", onKey)
        X11KeyButton("PGUP", onKey)
        X11KeyButton("PGDN", onKey)
        X11KeyButton("F1", onKey)
        X11KeyButton("F2", onKey)
        X11KeyButton("F3", onKey)
        X11KeyButton("F4", onKey)
        X11KeyButton("F5", onKey)
        X11KeyButton("F6", onKey)
        X11KeyButton("F7", onKey)
        X11KeyButton("F8", onKey)
        X11KeyButton("F9", onKey)
        X11KeyButton("F10", onKey)
        X11KeyButton("F11", onKey)
        X11KeyButton("F12", onKey)
    }
}

@Composable
private fun X11KeyButton(
    label: String,
    onKey: (String) -> Unit,
    sendKey: String = label,
    isActive: Boolean = false,
    repeatable: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    // Keyed on pressed/sendKey/repeatable only. (Including onKey here restarted the
    // repeat coroutine on every recomposition, breaking key-repeat; applying a sticky
    // modifier across a repeat burst is a deferred minor item.)
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
    val tapModifier = if (repeatable) {
        // Button semantics so TalkBack can announce/activate the repeatable keys
        // (the raw pointerInput path is otherwise invisible to accessibility).
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
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = false
                    }
                }
            }
    } else {
        Modifier.clickable(role = Role.Button) { onKey(sendKey) }
    }
    Text(
        text = label,
        color = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .then(tapModifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * Diffs old vs new IME buffer content and fires synthetic X11 key events for
 * the change. ASCII 0x20-0x7E maps to the same keysym value; non-ASCII Unicode
 * codepoints map to 0x01000000 | codepoint (X11 protocol extension).
 *
 * Works off the longest common prefix (by codepoint), not a raw length
 * difference: a CJK IME REPLACES composing text on commit (pinyin "nihao" ->
 * "你好"), so the new buffer shares no prefix with the old. Emitting only
 * (oldCp - newCp) backspaces — the old behaviour — left the composing text in
 * the guest and never sent the commit. Backspacing the whole divergent suffix
 * and re-typing the new one keeps the guest buffer in sync with the IME.
 */
private fun forwardImeDiff(old: String, new: String, vm: X11ViewModel) {
    // Length (in chars) and codepoint count of the shared leading run.
    var common = 0
    var commonCp = 0
    while (common < old.length && common < new.length) {
        val cp = old.codePointAt(common)
        if (cp != new.codePointAt(common)) break
        common += Character.charCount(cp)
        commonCp++
    }
    // Delete everything in old past the shared prefix.
    val oldCp = old.codePointCount(0, old.length)
    repeat(oldCp - commonCp) {
        vm.sendKey(XK_BackSpace, down = true)
        vm.sendKey(XK_BackSpace, down = false)
    }
    // Type everything in new past the shared prefix (codepoint-wise so a
    // surrogate pair is sent as a single keysym).
    var i = common
    while (i < new.length) {
        val cp = new.codePointAt(i)
        val keysym = if (cp in 0x20..0x7E) cp else 0x01000000 or cp
        vm.sendKey(keysym, down = true)
        vm.sendKey(keysym, down = false)
        i += Character.charCount(cp)
    }
}

private data class IntArray4(val a: Int, val b: Int, val c: Int, val d: Int)
