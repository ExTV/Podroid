/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.ui.screens.x11

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.x11.X11Constants

// X11 keysyms for keys that don't appear as printable characters in
// onValueChange diffs. ASCII keys (0x20–0x7E) double as their own keysyms.
private const val XK_BackSpace = 0xFF08
private const val XK_Tab       = 0xFF09
private const val XK_Return    = 0xFF0D
private const val XK_Escape    = 0xFF1B
private const val XK_Left      = 0xFF51
private const val XK_Up        = 0xFF52
private const val XK_Right     = 0xFF53
private const val XK_Down      = 0xFF54

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
fun X11Screen(
    onNavigateBack: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    viewModel: X11ViewModel = hiltViewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val frameCount by viewModel.frameCounter.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.connect() }

    val bitmap = remember {
        Bitmap.createBitmap(X11Constants.FB_WIDTH, X11Constants.FB_HEIGHT, Bitmap.Config.ARGB_8888)
    }

    // SurfaceView size in pixels — captured in surfaceChanged.
    var svWidth  by remember { mutableStateOf(1) }
    var svHeight by remember { mutableStateOf(1) }

    // Letterbox / pillarbox destination rect inside the SurfaceView, recomputed
    // whenever the view size changes. Pinned to the TOP (dY = 0) so the soft
    // keyboard never overlaps the framebuffer — the black bar lives below.
    // Horizontally still centered (pillarbox) in landscape orientation.
    val (dstX, dstY, dstW, dstH) = remember(svWidth, svHeight) {
        val fbW = X11Constants.FB_WIDTH.toFloat()
        val fbH = X11Constants.FB_HEIGHT.toFloat()
        val viewW = svWidth.toFloat().coerceAtLeast(1f)
        val viewH = svHeight.toFloat().coerceAtLeast(1f)
        val scale = minOf(viewW / fbW, viewH / fbH)
        val dW = (fbW * scale).toInt().coerceAtLeast(1)
        val dH = (fbH * scale).toInt().coerceAtLeast(1)
        val dX = ((viewW - dW) / 2f).toInt()
        val dY = 0
        IntArray4(dX, dY, dW, dH)
    }

    // Hidden text field for soft-keyboard input. We give it focus when the
    // keyboard icon is tapped; the IME shows; typed characters arrive via
    // onValueChange and are forwarded to the VM as X11 KeyPress/KeyRelease.
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var imeBuf by remember { mutableStateOf(TextFieldValue("")) }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        TopAppBar(
            title = { Text("X11") },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }) {
                    Icon(Icons.Default.Keyboard, contentDescription = "Keyboard")
                }
                IconButton(onClick = onNavigateToTerminal) {
                    Icon(
                        Icons.Default.DesktopWindows,
                        contentDescription = "Terminal",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val state = connection) {
                X11ConnectionState.Connecting,
                X11ConnectionState.Disconnected -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    Text(
                        "Connecting to X11 server...",
                        modifier = Modifier.padding(top = 80.dp),
                        color = Color.White,
                    )
                }
                is X11ConnectionState.Failed -> {
                    Text(
                        "X11 server not ready — VM still booting?\n${state.message}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                X11ConnectionState.Connected -> {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInteropFilter { ev ->
                                // Map raw touch (in SurfaceView space) into the
                                // letterboxed destination rect, then into the
                                // 1280x720 framebuffer. Taps in the black bars
                                // become coords clamped to the FB edge — feels
                                // OK in practice (mouse moves to nearest edge).
                                val w = dstW.coerceAtLeast(1)
                                val h = dstH.coerceAtLeast(1)
                                val sx = ((ev.x - dstX) / w * X11Constants.FB_WIDTH)
                                    .toInt().coerceIn(0, X11Constants.FB_WIDTH - 1)
                                val sy = ((ev.y - dstY) / h * X11Constants.FB_HEIGHT)
                                    .toInt().coerceIn(0, X11Constants.FB_HEIGHT - 1)
                                val mask = when (ev.actionMasked) {
                                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> 1
                                    else -> 0
                                }
                                viewModel.sendPointer(sx, sy, mask)
                                true
                            },
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(h: SurfaceHolder) {}
                                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {
                                        svWidth = w
                                        svHeight = hh
                                    }
                                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                                })
                            }
                        },
                        update = { sv ->
                            // Re-blit on every frameCounter tick.
                            @Suppress("UNUSED_EXPRESSION")
                            frameCount
                            bitmap.setPixels(
                                viewModel.framebuffer, 0,
                                X11Constants.FB_WIDTH,
                                0, 0,
                                X11Constants.FB_WIDTH, X11Constants.FB_HEIGHT,
                            )
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

                    // Off-screen IME hook. 1.dp + alpha 0 keeps it invisible
                    // but still focusable so the soft keyboard targets it.
                    BasicTextField(
                        value = imeBuf,
                        onValueChange = { new ->
                            forwardImeDiff(imeBuf.text, new.text, viewModel)
                            // Reset to empty so the buffer doesn't grow.
                            imeBuf = TextFieldValue("")
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
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { ev ->
                                // Hardware-key intercept for special keys the
                                // soft keyboard sends as KeyEvents (arrows,
                                // backspace on some IMEs, escape, tab).
                                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val keysym = when (ev.key) {
                                    Key.Backspace  -> XK_BackSpace
                                    Key.Enter,
                                    Key.NumPadEnter -> XK_Return
                                    Key.Tab        -> XK_Tab
                                    Key.Escape     -> XK_Escape
                                    Key.DirectionLeft  -> XK_Left
                                    Key.DirectionRight -> XK_Right
                                    Key.DirectionUp    -> XK_Up
                                    Key.DirectionDown  -> XK_Down
                                    else -> return@onPreviewKeyEvent false
                                }
                                viewModel.sendKey(keysym, down = true)
                                viewModel.sendKey(keysym, down = false)
                                true
                            },
                    )
                }
            }
        }
    }
}

/**
 * Compares old vs new IME buffer content, fires synthetic X11 key events
 * for the diff. Printable characters use their ASCII code as the keysym
 * (X11 keysyms 0x20–0x7E match ASCII verbatim).
 */
private fun forwardImeDiff(old: String, new: String, vm: X11ViewModel) {
    if (new.length > old.length) {
        // Inserted text — send each char as keysym = code.
        new.substring(old.length).forEach { ch ->
            val keysym = ch.code
            vm.sendKey(keysym, down = true)
            vm.sendKey(keysym, down = false)
        }
    } else if (new.length < old.length) {
        // Soft delete — fire that many backspaces.
        repeat(old.length - new.length) {
            vm.sendKey(XK_BackSpace, down = true)
            vm.sendKey(XK_BackSpace, down = false)
        }
    }
}

// Tiny helper so the dst rect destructure reads cleanly.
private data class IntArray4(val a: Int, val b: Int, val c: Int, val d: Int)
