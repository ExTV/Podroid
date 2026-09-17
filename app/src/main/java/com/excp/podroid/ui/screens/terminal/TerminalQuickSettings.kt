package com.excp.podroid.ui.screens.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.excp.podroid.R
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.theme.PodroidTokens
import kotlinx.coroutines.launch

/**
 * Quick Settings: minimal top-anchored sheet. Shows a few items per section
 * with ghost buttons that open full pickers (with search) on demand.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun QuickSettingsDialog(
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
    viewModel: TerminalViewModel,
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

    // ── Full-screen pickers and confirm dialogs ─────────────────────
    if (showThemePicker) {
        FullPickerDialog(
            title = stringResource(R.string.color_themes),
            items = themes, selected = colorTheme,
            onPick = { onColorThemeChange(it); showThemePicker = false; onDismiss() },
            onDismiss = { showThemePicker = false },
            isCustom = { viewModel.isCustomTheme(it) },
            onLongPressCustom = { themeToDelete = it },
            renderChip = { name, sel, click, longClick ->
                ThemeSwatch(name, sel, click, longClick, viewModel)
            },
            extraTrailingChip = {
                AddSwatch(label = stringResource(R.string.import_label), subLabel = stringResource(R.string.paste_url), onClick = { showThemeImport = true })
            },
        )
    }
    if (showFontPicker) {
        FullPickerDialog(
            title = stringResource(R.string.fonts),
            items = fonts, selected = terminalFont,
            onPick = { onFontChange(it); showFontPicker = false; onDismiss() },
            onDismiss = { showFontPicker = false },
            isCustom = { viewModel.isCustomFont(it) },
            onLongPressCustom = { fontToDelete = it },
            renderChip = { name, sel, click, longClick ->
                FontSwatch(name, viewModel.isCustomFont(name), sel, click, longClick, viewModel)
            },
            extraTrailingChip = {
                AddSwatch(label = stringResource(R.string.add_btn), subLabel = ".ttf", onClick = { fontImport.launch(fontMimes) })
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
            title = { Text(stringResource(R.string.remove_font_question)) },
            text  = { Text(stringResource(R.string.item_will_be_deleted, prettyName(name))) },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.deleteCustomFont(name)) { if (terminalFont == name) onFontChange("default"); bump++ }
                    fontToDelete = null
                }) { Text(stringResource(R.string.delete_label)) }
            },
            dismissButton = { TextButton(onClick = { fontToDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    themeToDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { themeToDelete = null },
            title = { Text(stringResource(R.string.remove_theme_question)) },
            text  = { Text(stringResource(R.string.item_will_be_deleted, prettyName(name))) },
            confirmButton = {
                TextButton(onClick = {
                    if (viewModel.deleteCustomTheme(name)) { if (colorTheme == name) onColorThemeChange("default"); bump++ }
                    themeToDelete = null
                }) { Text(stringResource(R.string.delete_label)) }
            },
            dismissButton = { TextButton(onClick = { themeToDelete = null }) { Text(stringResource(R.string.cancel)) } },
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
                            stringResource(R.string.settings),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    }

                    PodroidSectionLabel(stringResource(R.string.display_section))

                    // Size slider: sliding doesn't dismiss (you want to adjust),
                    // but releasing the thumb does (matches the "any interaction
                    // closes the drawer" rule).
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = PodroidTokens.Spacing.SM),
                    ) {
                        Text(
                            stringResource(R.string.size_label),
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
                            // Shared with pinch-to-zoom so the two can't disagree.
                            valueRange = TerminalViewModel.MIN_FONT_SIZE.toFloat()..TerminalViewModel.MAX_FONT_SIZE.toFloat(),
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

                    // Theme + Font: full-width ghost buttons so they read as
                    // actions, not list rows. Picker's onPick already calls
                    // onDismiss to close the drawer.
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    PodroidGhostButton(
                        text = stringResource(R.string.theme_with_value, prettyName(colorTheme)),
                        onClick = { showThemePicker = true },
                    )
                    Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                    PodroidGhostButton(
                        text = stringResource(R.string.font_with_value, prettyName(terminalFont)),
                        onClick = { showFontPicker = true },
                    )

                    PodroidSectionLabel(stringResource(R.string.input_section))

                    // Toggles: flipping any of these dismisses the drawer
                    // immediately so the terminal is unblocked.
                    PodroidListRow(
                        label = stringResource(R.string.extra_keys),
                        rightSlot = {
                            PodroidSwitch(
                                checked = showExtraKeys,
                                onCheckedChange = { onToggleExtraKeys(it); onDismiss() },
                            )
                        },
                    )
                    PodroidListRow(
                        label = stringResource(R.string.haptics),
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
 * Theme preview chip: painted in the theme's actual background color so the
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
 * Font preview chip: shows "Aa" rendered in the actual font, plus the name.
 * Custom (user-imported) fonts get a "•" suffix and a long-press → delete.
 */
// "Aa" is a font-rendering preview, not a translatable string: suppress
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
            // "Aa" in the actual typeface: uses AndroidView for direct Typeface support.
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

/** Generic outline chip used for "+ Add" / "Import" actions. */
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
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold)
            Text(subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Full-screen picker shown from the Quick Settings drawer's Theme/Font ghost
 * buttons: search field + grid of preview swatches. Long-press a custom item
 * to delete it.
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
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.search_n_items, items.size)) },
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
    val importErrorMsg = stringResource(R.string.import_theme_error)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.import_theme)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.import_theme_hint),
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
                        else error = importErrorMsg
                    }
                },
            ) { Text(if (busy) stringResource(R.string.importing) else stringResource(R.string.import_label)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
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
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(PodroidTokens.Radius.Card),
            )
            .padding(6.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** "monokai-bright" → "Monokai bright"; trims `.properties`/`.ttf` if present. */
private fun prettyName(raw: String): String =
    raw.substringBeforeLast('.')
        .replace('-', ' ')
        .replace('_', ' ')
        .replaceFirstChar { it.uppercaseChar() }
