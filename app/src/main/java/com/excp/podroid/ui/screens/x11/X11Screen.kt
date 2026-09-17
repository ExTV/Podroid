/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import android.content.pm.ActivityInfo
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
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
import com.excp.podroid.ui.components.rememberExtraKeyTapModifier
import com.excp.podroid.x11.RenderGeometry
import com.excp.podroid.x11.X11Keysym
import com.excp.podroid.x11.X11SurfaceRenderer
import com.excp.podroid.x11.imeDiffStrokes
import com.excp.podroid.x11.keysymForCodePoint
import com.excp.podroid.x11.keysymForHardwareChar
import com.excp.podroid.x11.labelToKeysym
import com.excp.podroid.x11.specialKeysymForKeyCode
import com.excp.podroid.x11.wrapWithModifiers

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
    val fb by viewModel.fbSize.collectAsStateWithLifecycle()
    val s by viewModel.x11Settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.connect() }

    // Owned by the AndroidView factory below; the ViewModel's onFrame hook
    // (fired from the RFB read thread) just wakes it up to present on the
    // next vsync instead of driving Compose recomposition per frame.
    var renderer by remember { mutableStateOf<X11SurfaceRenderer?>(null) }
    DisposableEffect(Unit) {
        viewModel.onFrame = { renderer?.requestFrame() }
        onDispose { viewModel.onFrame = null }
    }

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

    // Letterbox / pillarbox geometry, pinned to top so the soft keyboard (and
    // the extra-keys row) live in the empty bottom strip. dstX/dstY/dstW/dstH
    // (view px) drive input mapping below; the buffer-px fields drive
    // X11SurfaceRenderer's setFixedSize + drawBitmap.
    val geometry = remember(svWidth, svHeight, fb) {
        RenderGeometry.compute(svWidth, svHeight, fb.w, fb.h)
    }
    val dstX = geometry.dstX
    val dstY = geometry.dstY
    val dstW = geometry.dstW
    val dstH = geometry.dstH

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
    // Held in a small state holder (not a Compose mutableStateOf) because it
    // must survive x11PointerLoop's pointerInput coroutine restarts, same as
    // the remembered state it replaces.
    val pointerLockState = remember { X11PointerLockState() }
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
        wrapWithModifiers(keysym, shift = false, ctrl = ctrl, alt = alt).forEach {
            viewModel.sendKey(it.keysym, it.down)
        }
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
                val special = specialKeysymForKeyCode(native.keyCode)
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
                    keysym = keysymForHardwareChar(cased)
                    shiftWrap = false
                }
                wrapWithModifiers(keysym, shiftWrap, ctrl, alt).forEach {
                    viewModel.sendKey(it.keysym, it.down)
                }
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
                            x11PointerLoop(
                                s = s,
                                lockState = pointerLockState,
                                viewModel = viewModel,
                                requestFocus = { viewerFocus.requestFocus() },
                                fbX = { px -> ((px - currentDstX) / currentDstW.coerceAtLeast(1) * currentFbW).toInt().coerceIn(0, currentFbW - 1) },
                                fbY = { py -> ((py - currentDstY) / currentDstH.coerceAtLeast(1) * currentFbH).toInt().coerceIn(0, currentFbH - 1) },
                                // True only inside the letterbox/pillarbox content rect. Used to
                                // reject DIRECT-touch taps that land in the black bars instead of
                                // clamping them to an edge pixel (which produced a phantom edge click).
                                inContent = { px, py ->
                                    px >= currentDstX && px < currentDstX + currentDstW &&
                                        py >= currentDstY && py < currentDstY + currentDstH
                                },
                            )
                        },
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            val r = X11SurfaceRenderer(
                                holder = holder,
                                frameBufferSize = { viewModel.framebuffer.size },
                                withFrame = { block -> viewModel.withFrame(block) },
                                debugRfbStats = { viewModel.debugSnapshotAndReset() },
                                presentHw = { viewModel.debugPresentHw },
                            )
                            renderer = r
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) { r.onSurfaceCreated() }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { r.onSurfaceChanged() }
                                override fun surfaceDestroyed(h: SurfaceHolder) {}
                            })
                            // holder.setFixedSize (below, via the renderer) makes
                            // SurfaceHolder.Callback.surfaceChanged report the BUFFER
                            // size, not the view size, so the viewport used for
                            // requestResolution must come from layout size instead:
                            // otherwise feeding the buffer size back in would create a
                            // resize feedback loop. Same IME baseline rule as before:
                            // ignore height changes from the soft keyboard opening/
                            // closing (a width change resets the baseline; otherwise
                            // only a height BEYOND the largest non-IME height seen
                            // counts as a real resize; an IME dismiss grows height back
                            // up to the baseline and is correctly skipped).
                            addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                val w = right - left
                                val hh = bottom - top
                                if (w == oldRight - oldLeft && hh == oldBottom - oldTop) return@addOnLayoutChangeListener
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
                        }
                    },
                    update = {
                        renderer?.updateGeometry(fb.w, fb.h, geometry)
                    },
                    onRelease = {
                        renderer?.release()
                        renderer = null
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
                            val cp = addedText[0].code
                            sendWithModifiers(keysymForCodePoint(cp))
                            // Reset buffer after ctrl/alt combo to keep it short.
                            imeBuf = TextFieldValue("")
                        } else {
                            imeDiffStrokes(old.text, new.text).forEach { viewModel.sendKey(it.keysym, it.down) }
                            // Keep the rolling buffer so future deletions are
                            // observable, but cap it to avoid accumulating without bound.
                            imeBuf = if (new.text.length > 128) TextFieldValue(new.text.takeLast(64)) else new
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            viewModel.sendKey(X11Keysym.Return, down = true)
                            viewModel.sendKey(X11Keysym.Return, down = false)
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
    val tapModifier = rememberExtraKeyTapModifier(sendKey, repeatable, onKey)
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
