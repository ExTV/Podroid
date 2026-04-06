/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Terminal screen using Termux TerminalView wired to the podroid-bridge
 * binary via a real TerminalSession PTY. No reflection, no emulator
 * injection. Window resize propagates through the normal PTY/SIGWINCH path.
 */
package com.excp.podroid.ui.screens.terminal

import android.app.Activity
import android.graphics.Typeface
import android.view.WindowManager
import com.termux.terminal.TerminalColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.excp.podroid.engine.VmState
import com.termux.view.TerminalView

private fun parseColor(hex: String): Int {
    val clean = hex.removePrefix("#")
    val color = when (clean.length) {
        3 -> {
            val r = clean[0].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
            val g = clean[1].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
            val b = clean[2].digitToIntOrNull(16) ?: return android.graphics.Color.BLACK
            android.graphics.Color.rgb(r * 17, g * 17, b * 17)
        }
        6 -> {
            val r = clean.substring(0, 2).toInt(16)
            val g = clean.substring(2, 4).toInt(16)
            val b = clean.substring(4, 6).toInt(16)
            android.graphics.Color.rgb(r, g, b)
        }
        8 -> {
            val a = clean.substring(0, 2).toInt(16)
            val r = clean.substring(2, 4).toInt(16)
            val g = clean.substring(4, 6).toInt(16)
            val b = clean.substring(6, 8).toInt(16)
            android.graphics.Color.argb(a, r, g, b)
        }
        else -> android.graphics.Color.BLACK
    }
    return color
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    onNavigateBack: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsState()
    val bootStage by viewModel.bootStage.collectAsState()
    val fontSize by viewModel.terminalFontSize.collectAsState()

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        TopAppBar(
            title = { Text("Terminal", color = Color.White) },
            navigationIcon = {
                IconButton(onClick = {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    (context as? Activity)?.currentFocus?.let {
                        imm.hideSoftInputFromWindow(it.windowToken, 0)
                    }
                    onNavigateBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A)),
        )

        when (vmState) {
            is VmState.Idle, is VmState.Stopped -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("VM Not Running", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Start the VM from Home screen first",
                            color = Color.Gray, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            is VmState.Starting -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        CircularProgressIndicator(color = Color(0xFF4FC3F7), strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Starting VM...", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            bootStage.ifEmpty { "Initializing..." },
                            color = Color(0xFF4FC3F7), fontSize = 14.sp,
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(0.7f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = Color(0xFF4FC3F7),
                            trackColor = Color(0xFF333333),
                        )
                    }
                }
            }

            is VmState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Error", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            (vmState as VmState.Error).message,
                            color = Color.Gray, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Tap to Retry",
                            color = Color(0xFF4FC3F7), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF333333))
                                .clickable { onNavigateBack() }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                }
            }

            is VmState.Paused, is VmState.Saving, is VmState.Resuming -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF4FC3F7), strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            when (vmState) {
                                is VmState.Paused   -> "VM Paused"
                                is VmState.Saving   -> "Saving state..."
                                is VmState.Resuming -> "Resuming..."
                                else                -> ""
                            },
                            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            is VmState.Running -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val terminalView = remember(vmState) {
                        val colorFile = java.io.File(context.filesDir, "colors.properties")
                        val bgColor = if (colorFile.exists()) {
                            try {
                                val props = java.util.Properties()
                                colorFile.inputStream().use { props.load(it) }
                                TerminalColors.COLOR_SCHEME.updateWith(props)
                                val bgHex = props["background"] as? String
                                if (bgHex != null) parseColor(bgHex) else android.graphics.Color.BLACK
                            } catch (_: Exception) { android.graphics.Color.BLACK }
                        } else {
                            android.graphics.Color.BLACK
                        }
                        val fontFile = java.io.File(context.filesDir, "font.ttf")
                        val typeface = if (fontFile.exists()) {
                            try { Typeface.createFromFile(fontFile) } catch (_: Exception) { Typeface.MONOSPACE }
                        } else { Typeface.MONOSPACE }
                        TerminalView(context, null).apply {
                            setBackgroundColor(bgColor)
                            setTextSize(fontSize)
                            setTypeface(typeface)
                            keepScreenOn = true
                            isFocusable = true
                            isFocusableInTouchMode = true
                        }
                    }

                    LaunchedEffect(terminalView) {
                        viewModel.createSession()
                        viewModel.attachView(terminalView)
                        terminalView.setTerminalViewClient(viewModel.viewClient)
                        val sess = viewModel.session
                        if (sess != null) {
                            terminalView.mTermSession = sess
                            terminalView.mEmulator = sess.emulator
                        }
                        terminalView.requestFocus()
                    }

                    LaunchedEffect(terminalView) {
                        val view = terminalView
                        view.addOnLayoutChangeListener { v, left, top, right, bottom,
                                                    oldLeft, oldTop, oldRight, oldBottom ->
                            val w = right - left
                            val h = bottom - top
                            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
                            if (w == oldRight - oldLeft && h == oldBottom - oldTop) return@addOnLayoutChangeListener
                            (v as TerminalView).updateSize()
                        }
                    }

                    AndroidView(
                        factory = { terminalView },
                        update = { view ->
                            view.setTextSize(fontSize)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Boot progress overlay while VM is still initializing
                    if (bootStage.isNotEmpty() && bootStage != "Ready") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(Color(0xDD000000))
                                .padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF4FC3F7),
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(bootStage, color = Color(0xFF4FC3F7), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        ExtraKeysRow(
            onKey = { viewModel.sendExtraKey(it) },
            ctrlActive = viewModel.extraCtrl,
            altActive = viewModel.extraAlt,
        )
    }
}

@Composable
private fun ExtraKeysRow(
    onKey: (String) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExtraKey("ESC", onKey)
        ExtraKey("TAB", onKey)
        ExtraKey("CTRL", onKey, active = ctrlActive)
        ExtraKey("\u2190", onKey, sendKey = "LEFT")
        ExtraKey("\u2191", onKey, sendKey = "UP")
        ExtraKey("\u2193", onKey, sendKey = "DOWN")
        ExtraKey("\u2192", onKey, sendKey = "RIGHT")
        ExtraKey("ALT", onKey, active = altActive)
        ExtraKey("-", onKey)
        ExtraKey("/", onKey)
        ExtraKey("|", onKey)
        ExtraKey("HOME", onKey)
        ExtraKey("END", onKey)
        ExtraKey("PGUP", onKey)
        ExtraKey("PGDN", onKey)
        ExtraKey("F1", onKey)
        ExtraKey("F2", onKey)
        ExtraKey("F3", onKey)
        ExtraKey("F4", onKey)
        ExtraKey("F5", onKey)
        ExtraKey("F6", onKey)
        ExtraKey("F7", onKey)
        ExtraKey("F8", onKey)
        ExtraKey("F9", onKey)
        ExtraKey("F10", onKey)
        ExtraKey("F11", onKey)
        ExtraKey("F12", onKey)
    }
}

@Composable
private fun ExtraKey(
    label: String,
    onKey: (String) -> Unit,
    sendKey: String = label,
    active: Boolean = false,
) {
    Text(
        text = label,
        color = if (active) Color.Black else Color(0xFFCCCCCC),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Color(0xFF4FC3F7) else Color(0xFF333333))
            .clickable { onKey(sendKey) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
