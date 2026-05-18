package com.excp.podroid.ui.screens.terminal

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect as ComposeLaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.components.podroidChipColors
import com.excp.podroid.ui.theme.PodroidTokens
import com.termux.view.TerminalView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onNavigateToX11: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val showQuickSettings by viewModel.showQuickSettings.collectAsStateWithLifecycle()
    val showExtraKeys by viewModel.showExtraKeysFlow.collectAsStateWithLifecycle()
    val hapticsEnabled by viewModel.hapticsEnabledFlow.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            @Suppress("DEPRECATION")
            activity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
        }
    }

    // Forward app-level focus into the VM as xterm focus events (CSI I / CSI O)
    // so nvim's FocusGained/FocusLost autocommands fire. Gated by the emulator's
    // DECSET 1004 mode inside the ViewModel so we never leak literal bytes.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.sendFocusEvent(true)
                Lifecycle.Event.ON_PAUSE  -> viewModel.sendFocusEvent(false)
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val colorTheme by viewModel.terminalColorTheme.collectAsStateWithLifecycle()
    val terminalFont by viewModel.terminalFont.collectAsStateWithLifecycle()

    if (showQuickSettings) {
        QuickSettingsDialog(
            fontSize = fontSize,
            onFontSizeChange = { viewModel.setTerminalFontSize(it) },
            onDismiss = { viewModel.closeQuickSettings() },
            showExtraKeys = showExtraKeys,
            onToggleExtraKeys = { viewModel.updateShowExtraKeys(it) },
            hapticsEnabled = hapticsEnabled,
            onToggleHaptics = { viewModel.updateHapticsEnabled(it) },
            colorTheme = colorTheme,
            onColorThemeChange = { viewModel.setTerminalColorTheme(it) },
            terminalFont = terminalFont,
            onFontChange = { viewModel.setTerminalFont(it) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        PodroidTopBar(
            title = "Terminal",
            navigationIcon = {
                IconButton(onClick = {
                    val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                    (context as? Activity)?.currentFocus?.let {
                        imm.hideSoftInputFromWindow(it.windowToken, 0)
                    }
                    onNavigateBack()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = onNavigateToX11) {
                    Icon(Icons.Default.DesktopWindows, contentDescription = "X11 screen")
                }
                Spacer(Modifier.width(PodroidTokens.Spacing.XS))
                IconButton(onClick = { viewModel.openQuickSettings() }) {
                    Icon(Icons.Default.Tune, contentDescription = "Quick settings")
                }
            },
        )

        when (vmState) {
            is VmState.Idle, is VmState.Stopped -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Stopped",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                        Text(
                            "Start the VM from the Home screen first.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is VmState.Error -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = PodroidTokens.Spacing.XL),
                    ) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                        Text(
                            (vmState as VmState.Error).message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(PodroidTokens.Spacing.LG))
                        PodroidGhostButton(
                            text = "Back to Home",
                            onClick = onNavigateBack,
                            modifier = Modifier.fillMaxWidth(0.6f),
                        )
                    }
                }
            }

            is VmState.Starting -> {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Starting…",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            is VmState.Running -> {
                // Hoisted into its own composable so toggling chrome state in the
                // parent (showQuickSettings, showExtraKeys, hapticsEnabled, modifier
                // keys, etc.) doesn't invalidate the AndroidView slot. TerminalSurface
                // takes only the ViewModel (stable @HiltViewModel) so Compose's
                // restart-scope skipping kicks in: chrome toggles → parent recomposes,
                // surface skips. The terminal pixels never re-route through Compose
                // unless something the surface actually reads has changed.
                TerminalSurface(
                    viewModel = viewModel,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }

        if (showExtraKeys) {
            AdaptiveContainer(
                windowSizeClass = windowSizeClass,
                maxWidth = 800
            ) {
                ExtraKeysRow(
                    onKey = { viewModel.sendExtraKey(it) },
                    ctrlActive = viewModel.extraCtrl,
                    altActive = viewModel.extraAlt,
                )
            }
        }
    }
}

/**
 * Owns the TerminalView. Isolated as its own composable so chrome state
 * (quick-settings sheet, extra-keys toggle, haptics, etc.) lives in the
 * parent's restart scope and never invalidates this slot. The only reads
 * here drive things the View genuinely needs: typeface, palette, font size.
 *
 * `update = { }` — the View manages its own state. All View setters are
 * driven by narrow-keyed LaunchedEffects so Compose only touches the View
 * when the actual driving input changes, never on incidental recomposition.
 */
@Composable
private fun TerminalSurface(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val fontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val colorTheme by viewModel.terminalColorTheme.collectAsStateWithLifecycle()
    val terminalFont by viewModel.terminalFont.collectAsStateWithLifecycle()

    // Resolve typeface + palette here (cheap: asset reads, font cache lookup).
    // Each is keyed on its specific upstream string so a theme change does not
    // re-resolve the font and vice versa.
    val typeface = remember(terminalFont) { viewModel.loadFont(terminalFont) }
    val themeBg = remember(colorTheme) { viewModel.loadColorTheme(colorTheme) }

    Box(modifier = modifier) {
        // The View is created per-Composition-context (i.e. per Activity).
        // Caching it across config changes used to leak the destroyed Activity.
        val view = remember(context) {
            TerminalView(context, null).apply {
                setTextSize(fontSize)
                keepScreenOn = true
                isFocusable = true
                isFocusableInTouchMode = true
            }
        }

        // Theme background — keyed only on themeBg so font/size changes don't
        // re-fire it. We also force a repaint here because loadColorTheme()
        // pushes the new palette into the live session's mCurrentColors, but
        // doesn't itself trigger an invalidate — without this the screen
        // keeps painting with the old colors until the next PTY byte.
        LaunchedEffect(view, themeBg) {
            view.setBackgroundColor(themeBg ?: android.graphics.Color.BLACK)
            view.onScreenUpdated()
        }

        // Typeface — keyed only on the resolved Typeface. Re-pushes size to the
        // session because cell metrics (charWidth/lineHeight) change with the
        // font, not just with text size. Use only view.updateSize() (its row
        // math subtracts mFontLineSpacingAndAscent); calling
        // forceUpdateSizeFromView in addition disagrees by ±1 row and causes
        // cursor flicker.
        LaunchedEffect(view, typeface) {
            view.setTypeface(typeface)
            view.post { view.updateSize() }
        }

        // Font size — keyed only on the int. Replaces the old guard-in-update
        // pattern: now `update = { }` is a true no-op and Compose touches the
        // View only when fontSize actually changes.
        LaunchedEffect(view, fontSize) {
            view.setTextSize(fontSize)
            view.post { view.updateSize() }
        }

        // Session/client binding. Keyed on `view` only — we are inside the
        // VmState.Running branch so vmState identity is stable while we live.
        // (Previous `DisposableEffect(view, vmState)` was wider than necessary;
        // narrowing means transient VM state churn can't re-bind the session.)
        DisposableEffect(view) {
            viewModel.resetOnRestart()
            viewModel.bindView(view)
            viewModel.createSession()
            view.setTerminalViewClient(viewModel.viewClient)
            val sess = viewModel.session
            if (sess != null) {
                view.mTermSession = sess
                view.mEmulator = sess.emulator
            }
            view.requestFocus()
            view.onScreenUpdated()

            // view.updateSize() only — its row math subtracts
            // mFontLineSpacingAndAscent. forceUpdateSizeFromView disagrees by
            // ±1 row in some sizes and causes a visible cursor flicker on
            // first paint.
            view.post {
                view.updateSize()
                view.onScreenUpdated()
            }

            // Cursor blinker: the host app must opt in — TerminalView ships the
            // mechanism but never starts itself. 500ms is the conventional rate.
            // startOnlyIfCursorEnabled=false because the renderer already gates
            // paint via shouldCursorBeVisible() (which honors ?25l), so it's
            // safe to leave the FrameCallback driving and let the emulator say
            // when the cursor is hidden.
            view.setTerminalCursorBlinkerRate(500)
            view.setTerminalCursorBlinkerState(true, false)

            onDispose { viewModel.bindView(null) }
        }

        // Pause the blinker when the activity is backgrounded. Without this
        // the Choreographer FrameCallback keeps firing every VSYNC in
        // paused-without-detach states (split-screen, dialog occlusion, PiP).
        // onDetachedFromWindow already handles the full-detach case.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(view, lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> view.setTerminalCursorBlinkerState(true, false)
                    Lifecycle.Event.ON_PAUSE  -> view.setTerminalCursorBlinkerState(false, false)
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Layout-change debounce: keyboard slide animation fires ~25 layout
        // events. Coroutine debounce collapses them to one SIGWINCH. We also
        // tell the View to suppress its blink toggle during the settle window
        // so the cursor doesn't visibly flicker on/off mid-animation.
        val scope = rememberCoroutineScope()
        DisposableEffect(view) {
            var pending: kotlinx.coroutines.Job? = null
            val listener = android.view.View.OnLayoutChangeListener {
                v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val w = right - left
                val h = bottom - top
                if (w <= 0 || h <= 0) return@OnLayoutChangeListener
                if (w == oldRight - oldLeft && h == oldBottom - oldTop) return@OnLayoutChangeListener
                pending?.cancel()
                val tv = v as TerminalView
                tv.setLayoutSettling(true)
                pending = scope.launch {
                    // 64 ms ≈ 4 VSYNCs — enough to coalesce a keyboard-slide
                    // burst (~25 layout events over the 200 ms slide animation)
                    // without leaving the prompt visibly lagging behind.
                    kotlinx.coroutines.delay(64)
                    // Just tv.updateSize() — it has the correct row math
                    // (subtracts mFontLineSpacingAndAscent). Calling
                    // forceUpdateSizeFromView too caused a row-count race:
                    // the two computations disagree by 1 in some sizes,
                    // triggering two back-to-back resizes per keyboard
                    // slide and a visible cursor flicker.
                    tv.updateSize()
                    tv.setLayoutSettling(false)
                }
            }
            view.addOnLayoutChangeListener(listener)
            onDispose {
                pending?.cancel()
                view.setLayoutSettling(false)
                view.removeOnLayoutChangeListener(listener)
            }
        }

        AndroidView(
            factory = { view },
            update = { },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ExtraKeysRow(
    onKey: (String) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth()) {
        // 1-px separator so the row reads as chrome, not as terminal content.
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .fadingEdgesHorizontal(scroll)
                .horizontalScroll(scroll)
                .padding(horizontal = PodroidTokens.Spacing.SM, vertical = PodroidTokens.Spacing.SM),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyButton("ESC", onKey)
            KeyButton("TAB", onKey)
            KeyButton("CTRL", onKey, isActive = ctrlActive)
            KeyButton("\u2190", onKey, sendKey = "LEFT",  repeatable = true)
            KeyButton("\u2191", onKey, sendKey = "UP",    repeatable = true)
            KeyButton("\u2193", onKey, sendKey = "DOWN",  repeatable = true)
            KeyButton("\u2192", onKey, sendKey = "RIGHT", repeatable = true)
            KeyButton("ALT", onKey, isActive = altActive)
            KeyButton("-", onKey); KeyButton("/", onKey); KeyButton("|", onKey)
            KeyButton("HOME", onKey); KeyButton("END", onKey)
            KeyButton("PGUP", onKey); KeyButton("PGDN", onKey)
            (1..12).forEach { KeyButton("F$it", onKey) }
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    onKey: (String) -> Unit,
    sendKey: String = label,
    isActive: Boolean = false,
    repeatable: Boolean = false,
) {
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
    val tapModifier = if (repeatable) {
        Modifier.pointerInput(sendKey) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onKey(sendKey)
                pressed = true
                try { waitForUpOrCancellation() } finally { pressed = false }
            }
        }
    } else {
        Modifier.clickable { onKey(sendKey) }
    }
    Text(
        text = label,
        color = if (isActive) PodroidTokens.AccentInk else MaterialTheme.colorScheme.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = PodroidTokens.mono(),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(PodroidTokens.Radius.Chip))
            .background(if (isActive) PodroidTokens.Accent else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(PodroidTokens.Radius.Chip))
            .then(tapModifier)
            .padding(horizontal = 13.dp, vertical = 9.dp),
    )
}

/**
 * Horizontal gradient fade at the start/end edges of a scrollable row — drawn
 * only when there's actually overflow in that direction. Same pattern as the
 * old fadingEdges() helper but keyed on ScrollState (not LazyListState) so it
 * can wrap a plain Row + horizontalScroll(...).
 */
private fun Modifier.fadingEdgesHorizontal(
    scroll: androidx.compose.foundation.ScrollState,
    fadeWidth: Dp = 24.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = fadeWidth.toPx()
        if (scroll.canScrollBackward) {
            drawRect(
                topLeft = Offset.Zero,
                size = Size(fadePx, size.height),
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = fadePx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                topLeft = Offset(size.width - fadePx, 0f),
                size = Size(fadePx, size.height),
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startX = size.width - fadePx,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Quick Settings — minimal top-anchored sheet. Shows a few items per section
 * with "More" buttons that open full pickers (with search) on demand.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickSettingsDialog(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    showExtraKeys: Boolean,
    onToggleExtraKeys: (Boolean) -> Unit,
    hapticsEnabled: Boolean,
    onToggleHaptics: (Boolean) -> Unit,
    colorTheme: String,
    onColorThemeChange: (String) -> Unit,
    terminalFont: String,
    onFontChange: (String) -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    var bump by remember { mutableIntStateOf(0) }
    val themes = remember(bump) { viewModel.listAvailableThemes() }
    val fonts  = remember(bump) { viewModel.listAvailableFonts() }

    var showThemePicker by remember { mutableStateOf(false) }
    var showFontPicker  by remember { mutableStateOf(false) }
    var showThemeImport by remember { mutableStateOf(false) }
    var fontToDelete    by remember { mutableStateOf<String?>(null) }
    var themeToDelete   by remember { mutableStateOf<String?>(null) }

    val fontImport = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = viewModel.importCustomFont(uri)
            if (name != null) { onFontChange(name); bump++ }
        }
    }
    val fontMimes = remember { arrayOf("font/ttf", "application/x-font-ttf", "application/octet-stream") }

    // ── Sub-dialogs unchanged ───────────────────────────────────────
    if (showThemePicker) {
        FullPickerDialog(
            title = "Color themes",
            items = themes, selected = colorTheme,
            onPick = { onColorThemeChange(it); showThemePicker = false; onDismiss() },
            onDismiss = { showThemePicker = false },
            isCustom = { viewModel.isCustomTheme(it) },
            onLongPressCustom = { themeToDelete = it },
            renderChip = { name, sel, click, longClick ->
                ThemeSwatch(name, sel, click, longClick, viewModel)
            },
            extraTrailingChip = {
                AddSwatch(label = "Import", subLabel = "Paste URL", onClick = { showThemeImport = true })
            },
        )
    }
    if (showFontPicker) {
        FullPickerDialog(
            title = "Fonts",
            items = fonts, selected = terminalFont,
            onPick = { onFontChange(it); showFontPicker = false; onDismiss() },
            onDismiss = { showFontPicker = false },
            isCustom = { viewModel.isCustomFont(it) },
            onLongPressCustom = { fontToDelete = it },
            renderChip = { name, sel, click, longClick ->
                FontSwatch(name, viewModel.isCustomFont(name), sel, click, longClick, viewModel)
            },
            extraTrailingChip = {
                AddSwatch(label = "+ Add", subLabel = ".ttf", onClick = { fontImport.launch(fontMimes) })
            },
        )
    }
    if (showThemeImport) {
        ThemeImportDialog(
            onDismiss = { showThemeImport = false },
            onImported = { name -> onColorThemeChange(name); bump++; showThemeImport = false },
            viewModel = viewModel,
        )
    }
    fontToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { fontToDelete = null },
            title = { Text("Remove font?") },
            text  = { Text("\"${prettyName(name)}\" will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.deleteCustomFont(name)) { if (terminalFont == name) onFontChange("default"); bump++ }
                    fontToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { fontToDelete = null }) { Text("Cancel") } },
        )
    }
    themeToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { themeToDelete = null },
            title = { Text("Remove theme?") },
            text  = { Text("\"${prettyName(name)}\" will be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.deleteCustomTheme(name)) { if (colorTheme == name) onColorThemeChange("default"); bump++ }
                    themeToDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { themeToDelete = null }) { Text("Cancel") } },
        )
    }

    // ── The top-anchored drawer ────────────────────────────────────
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val windowInfo = androidx.compose.ui.platform.LocalWindowInfo.current
            val density = androidx.compose.ui.platform.LocalDensity.current
            val maxSheetHeight = with(density) {
                (windowInfo.containerSize.height * 0.92f).toInt().toDp()
            }
            androidx.compose.material3.Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight),
                shape = RoundedCornerShape(bottomStart = PodroidTokens.Radius.Sheet, bottomEnd = PodroidTokens.Radius.Sheet),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PodroidTokens.Spacing.LG)
                        .padding(top = PodroidTokens.Spacing.SM, bottom = PodroidTokens.Spacing.MD),
                ) {
                    // Drag handle
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = PodroidTokens.Spacing.XS),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 32.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }

                    // Header
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    PodroidSectionLabel("Display")

                    // Size slider — sliding doesn't dismiss (you want to adjust),
                    // but releasing the thumb does (matches the "any interaction
                    // closes the drawer" rule).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = PodroidTokens.Spacing.SM),
                    ) {
                        Text(
                            "Size",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.width(56.dp),
                        )
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = { v ->
                                val rounded = v.toInt()
                                if (rounded != fontSize) onFontSizeChange(rounded)
                            },
                            onValueChangeFinished = onDismiss,
                            valueRange = 10f..36f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "$fontSize",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(36.dp),
                            textAlign = TextAlign.End,
                        )
                    }

                    // Theme + Font — full-width ghost buttons so they read as
                    // actions, not list rows. Picker's onPick already calls
                    // onDismiss to close the drawer.
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    PodroidGhostButton(
                        text = "Theme · ${prettyName(colorTheme)}",
                        onClick = { showThemePicker = true },
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    PodroidGhostButton(
                        text = "Font · ${prettyName(terminalFont)}",
                        onClick = { showFontPicker = true },
                    )

                    PodroidSectionLabel("Input")

                    // Toggles — flipping any of these dismisses the drawer
                    // immediately so the terminal is unblocked.
                    PodroidListRow(
                        label = "Extra keys",
                        rightSlot = {
                            PodroidSwitch(
                                checked = showExtraKeys,
                                onCheckedChange = { onToggleExtraKeys(it); onDismiss() },
                            )
                        },
                    )
                    PodroidListRow(
                        label = "Haptics",
                        rightSlot = {
                            PodroidSwitch(
                                checked = hapticsEnabled,
                                onCheckedChange = { onToggleHaptics(it); onDismiss() },
                            )
                        },
                        divider = false,
                    )
                }
            }
        }
    }
}

/**
 * Theme preview chip — painted in the theme's actual background color so the
 * user sees the look at a glance. Foreground color is the theme's foreground.
 * If the theme can't be parsed we fall back to neutral colors.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemeSwatch(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    viewModel: TerminalViewModel,
) {
    val colors = remember(name) {
        viewModel.peekThemeColors(name) ?: (0xFF101010.toInt() to 0xFFE0E0E0.toInt())
    }
    SwatchBox(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        backgroundColor = Color(colors.first),
    ) {
        Text(
            prettyName(name),
            color = Color(colors.second),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Font preview chip — shows "Aa" rendered in the actual font, plus the name.
 * Custom (user-imported) fonts get a "•" suffix and a long-press → delete.
 */
// "Aa" is a font-rendering preview, not a translatable string — suppress
// SetTextI18n which would otherwise insist on a string resource.
@android.annotation.SuppressLint("SetTextI18n")
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontSwatch(
    name: String,
    isCustom: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    viewModel: TerminalViewModel,
) {
    val typeface = remember(name) { viewModel.loadFont(name) }
    val previewColor = MaterialTheme.colorScheme.onSurface.toArgb()
    SwatchBox(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        backgroundColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // "Aa" in the actual typeface — uses AndroidView for direct Typeface support.
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.TextView(ctx).apply {
                        text = "Aa"
                        textSize = 18f
                        gravity = android.view.Gravity.CENTER
                        includeFontPadding = false
                    }
                },
                update = { tv ->
                    tv.typeface = typeface
                    tv.setTextColor(previewColor)
                },
            )
            Text(
                if (isCustom) "${prettyName(name)} •" else prettyName(name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Generic outline chip used for "More" / "+ Add" / "Import" actions. */
@Composable
private fun AddSwatch(
    label: String,
    subLabel: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(PodroidTokens.Radius.Card))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label,
                style = MaterialTheme.typography.labelLarge,
                color = PodroidTokens.Accent,
                fontWeight = FontWeight.SemiBold)
            Text(subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Full-screen picker shown when the user taps "More" — search field + grid of
 * preview swatches. Long-press a custom item to delete it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FullPickerDialog(
    title: String,
    items: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    isCustom: (String) -> Boolean,
    onLongPressCustom: (String) -> Unit,
    renderChip: @Composable (
        name: String, selected: Boolean,
        onClick: () -> Unit, onLongClick: (() -> Unit)?,
    ) -> Unit,
    extraTrailingChip: @Composable () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(items, query) {
        if (query.isBlank()) items
        else items.filter { it.contains(query, ignoreCase = true) }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
        ),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(8.dp),
            shape = RoundedCornerShape(PodroidTokens.Radius.Sheet),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search ${items.size}") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filtered.forEach { name ->
                            val custom = isCustom(name)
                            renderChip(
                                name,
                                name == selected,
                                { onPick(name) },
                                if (custom) ({ onLongPressCustom(name) }) else null,
                            )
                        }
                        extraTrailingChip()
                    }
                }
            }
        }
    }
}

/**
 * Theme-import dialog. Accepts a `terminalcolors.com/themes/<name>/<variant>/` URL
 * (or a direct .toml URL); calls the suspend importer in a coroutine; shows
 * loading + error states.
 */
@Composable
private fun ThemeImportDialog(
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
    viewModel: TerminalViewModel,
) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import theme") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a terminalcolors.com theme URL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    placeholder = { Text("https://terminalcolors.com/themes/dracula/default/") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val name = viewModel.importThemeFromUrl(url)
                        busy = false
                        if (name != null) onImported(name)
                        else error = "Couldn't import — check the URL."
                    }
                },
            ) { Text(if (busy) "Importing…" else "Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwatchBox(
    selected: Boolean,
    onClick: () -> Unit,
    backgroundColor: Color,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    }
    Box(
        modifier = Modifier
            .size(width = 104.dp, height = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .then(clickModifier)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) PodroidTokens.Accent else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(PodroidTokens.Radius.Card),
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QuickChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    // FilterChip doesn't expose onLongClick, so when a long-press handler is
    // supplied we wrap it in a combinedClickable Box that mimics the chip's
    // rounded shape + selected colors.
    if (onLongClick != null) {
        val bg = if (selected) PodroidTokens.Accent
                 else MaterialTheme.colorScheme.surfaceContainerHighest
        val fg = if (selected) PodroidTokens.AccentInk
                 else MaterialTheme.colorScheme.onSurface
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
        return
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        shape = RoundedCornerShape(14.dp),
        colors = podroidChipColors(),
    )
}

/** "monokai-bright" → "Monokai bright"; trims `.properties`/`.ttf` if present. */
private fun prettyName(raw: String): String =
    raw.substringBeforeLast('.')
        .replace('-', ' ')
        .replace('_', ' ')
        .replaceFirstChar { it.uppercaseChar() }

/**
 * Horizontal gradient fade at the edges of a scrollable container, drawn only
 * on the side(s) where more content exists. Uses an offscreen layer + DstIn
 * blend so the fade actually masks the chips rather than overpainting them.
 */
private fun Modifier.fadingEdges(
    state: androidx.compose.foundation.lazy.LazyListState,
    fadeWidth: Dp = 28.dp,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = fadeWidth.toPx()
        if (state.canScrollBackward) {
            drawRect(
                topLeft = Offset.Zero,
                size = Size(fadePx, size.height),
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = fadePx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                topLeft = Offset(size.width - fadePx, 0f),
                size = Size(fadePx, size.height),
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startX = size.width - fadePx,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Thin scrollbar drawn on the LEFT edge of the container. Track is always
 * visible (so users see "scrollable area"), thumb only appears when the
 * content actually overflows. The thumb size is proportional to viewport /
 * total content; its Y offset reflects current scroll position.
 */
private fun Modifier.verticalScrollbar(
    state: androidx.compose.foundation.ScrollState,
    width: Dp = 3.dp,
    thumbColor: Color,
    trackColor: Color,
    minThumbHeight: Dp = 32.dp,
): Modifier = this.drawWithContent {
    drawContent()
    val maxValue = state.maxValue
    if (maxValue <= 0) return@drawWithContent

    val widthPx = width.toPx()
    val viewportH = size.height
    val totalH = viewportH + maxValue
    val thumbH = (viewportH * viewportH / totalH).coerceAtLeast(minThumbHeight.toPx())
    val thumbY = (state.value.toFloat() / maxValue) * (viewportH - thumbH)
    val cornerPx = widthPx / 2f

    drawRoundRect(
        color = trackColor,
        topLeft = Offset(0f, 0f),
        size = Size(widthPx, viewportH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
    )
    drawRoundRect(
        color = thumbColor,
        topLeft = Offset(0f, thumbY),
        size = Size(widthPx, thumbH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerPx, cornerPx),
    )
}

@Composable
private fun ThemeSelectionDialog(
    currentTheme: String,
    onThemeSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val themes = remember { viewModel.listAssetNames("colors", ".properties") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Theme") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(themes.size) { index ->
                    val theme = themes[index]
                    val isSelected = theme == currentTheme
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { onThemeSelect(theme) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = theme.replace('-', ' ').replaceFirstChar { it.uppercase() },
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FontSelectionDialog(
    currentFont: String,
    onFontSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val fonts = remember { viewModel.listAssetNames("fonts", ".ttf") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Font") },
        text = {
            LazyColumn(modifier = Modifier.height(400.dp)) {
                items(fonts.size) { index ->
                    val font = fonts[index]
                    val isSelected = font == currentFont
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { onFontSelect(font) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = font.replace('-', ' ').replaceFirstChar { it.uppercase() },
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

