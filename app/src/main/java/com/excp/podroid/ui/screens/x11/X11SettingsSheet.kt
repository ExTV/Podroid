/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Bottom sheet for X11 viewer settings: resolution mode/preset/custom
 * (plus render scale in MATCH mode), rotation lock, touch mode, and
 * display (DPI) sections, in that order down the Column.
 */
package com.excp.podroid.ui.screens.x11

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.ui.components.PodroidChipColors
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidSwitch
import com.excp.podroid.ui.theme.PodroidTokens
import com.excp.podroid.x11.ResolutionMode
import com.excp.podroid.x11.ResolutionPreset
import com.excp.podroid.x11.RotationLock
import com.excp.podroid.x11.TouchMode
import com.excp.podroid.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun X11SettingsSheet(viewModel: X11ViewModel, onDismiss: () -> Unit) {
    val s by viewModel.x11Settings.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = PodroidTokens.Spacing.XL)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── RESOLUTION ────────────────────────────────────────────
            PodroidSectionLabel(stringResource(R.string.resolution))

            Row(
                horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ResolutionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.resolutionMode == mode,
                        onClick = { viewModel.setResolutionMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    ResolutionMode.MATCH  -> stringResource(R.string.match_viewport)
                                    ResolutionMode.PRESET -> stringResource(R.string.preset)
                                    ResolutionMode.CUSTOM -> stringResource(R.string.custom_resolution)
                                }
                            )
                        },
                        shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                        colors = PodroidChipColors(),
                    )
                }
            }

            if (s.resolutionMode == ResolutionMode.PRESET) {
                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ResolutionPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = s.preset == preset,
                            onClick = { viewModel.setPreset(preset) },
                            label = {
                                Text(
                                    when (preset) {
                                        ResolutionPreset.R720P  -> stringResource(R.string.res_720p)
                                        ResolutionPreset.R900P  -> stringResource(R.string.res_900p)
                                        ResolutionPreset.R1080P -> stringResource(R.string.res_1080p)
                                        ResolutionPreset.R1440P -> stringResource(R.string.res_1440p)
                                    }
                                )
                            },
                            shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                            colors = PodroidChipColors(),
                        )
                    }
                }
            }

            if (s.resolutionMode == ResolutionMode.CUSTOM) {
                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                CustomResolutionFields(
                    initialW = s.customW,
                    initialH = s.customH,
                    onCommit = { w, h -> viewModel.setCustom(w, h) },
                )
            }

            if (s.resolutionMode == ResolutionMode.MATCH) {
                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                PodroidSectionLabel(stringResource(R.string.x11_render_scale))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    listOf(100, 75, 50).forEach { scale ->
                        FilterChip(
                            selected = s.renderScale == scale,
                            onClick = { viewModel.setRenderScale(scale) },
                            label = { Text("$scale%") },
                            shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                            colors = PodroidChipColors(),
                        )
                    }
                }
                Text(
                    stringResource(R.string.x11_render_scale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── ROTATION ──────────────────────────────────────────────
            PodroidSectionLabel(stringResource(R.string.rotation))

            Row(
                horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RotationLock.entries.forEach { lock ->
                    FilterChip(
                        selected = s.rotationLock == lock,
                        onClick = { viewModel.setRotation(lock) },
                        label = {
                            Text(
                                when (lock) {
                                    RotationLock.AUTO      -> stringResource(R.string.auto_rotation)
                                    RotationLock.LANDSCAPE -> stringResource(R.string.landscape)
                                    RotationLock.PORTRAIT  -> stringResource(R.string.portrait)
                                }
                            )
                        },
                        shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                        colors = PodroidChipColors(),
                    )
                }
            }

            // ── TOUCH ─────────────────────────────────────────────────
            PodroidSectionLabel(stringResource(R.string.touch))

            Row(
                horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TouchMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.touchMode == mode,
                        onClick = { viewModel.setTouchMode(mode) },
                        label = { Text(if (mode == TouchMode.DIRECT) stringResource(R.string.direct_touch) else stringResource(R.string.trackpad)) },
                        shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                        colors = PodroidChipColors(),
                    )
                }
            }

            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            Text(
                "${stringResource(R.string.pointer_speed)}: ${"%.1f".format(s.trackpadSensitivity)}x",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = s.trackpadSensitivity,
                onValueChange = { viewModel.setTrackpadSensitivity(it) },
                valueRange = 0.5f..3.0f,
                enabled = s.touchMode == TouchMode.TRACKPAD,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.pointer_acceleration), color = MaterialTheme.colorScheme.onSurface)
                PodroidSwitch(
                    checked = s.trackpadAccel,
                    onCheckedChange = { viewModel.setTrackpadAccel(it) },
                    enabled = s.touchMode == TouchMode.TRACKPAD,
                )
            }

            // ── DISPLAY ───────────────────────────────────────────────
            PodroidSectionLabel(stringResource(R.string.display))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.show_extra_keys), color = MaterialTheme.colorScheme.onSurface)
                PodroidSwitch(
                    checked = s.showExtraKeys,
                    onCheckedChange = { viewModel.setShowExtraKeys(it) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.start_fullscreen), color = MaterialTheme.colorScheme.onSurface)
                PodroidSwitch(
                    checked = s.fullscreenDefault,
                    onCheckedChange = { viewModel.setFullscreenDefault(it) },
                )
            }

            Spacer(Modifier.height(PodroidTokens.Spacing.SM))
            Text(stringResource(R.string.server_dpi), color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(PodroidTokens.Spacing.XS))
            Row(
                horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(96, 120, 144, 168, 192).forEach { dpi ->
                    FilterChip(
                        selected = s.dpi == dpi,
                        onClick = { viewModel.setDpi(dpi) },
                        label = { Text("${dpi}") },
                        shape = RoundedCornerShape(PodroidTokens.Radius.Chip),
                        colors = PodroidChipColors(),
                    )
                }
            }
            Text(
                stringResource(R.string.applies_on_next_vm_start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(PodroidTokens.Spacing.XL2))
        }
    }
}

@Composable
private fun CustomResolutionFields(
    initialW: Int,
    initialH: Int,
    onCommit: (Int, Int) -> Unit,
) {
    var wText by remember(initialW) { mutableStateOf(initialW.toString()) }
    var hText by remember(initialH) { mutableStateOf(initialH.toString()) }

    // Commit only on IME Done or focus loss, not on every keystroke. Typing
    // "1920" used to fire four SetDesktopSize renegotiations (1, 19, 192, 1920);
    // a partial value also produced a tiny intermediate desktop. The downstream
    // setter clamps width/height (sane max and <= 0xFFFF) so a wrap is impossible.
    fun commit() {
        val w = wText.toIntOrNull() ?: return
        val h = hText.toIntOrNull() ?: return
        onCommit(w, h)
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = wText,
            onValueChange = { wText = it },
            label = { Text(stringResource(R.string.width)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) commit() },
        )
        OutlinedTextField(
            value = hText,
            onValueChange = { hText = it },
            label = { Text(stringResource(R.string.height)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { if (!it.isFocused) commit() },
        )
    }
}
