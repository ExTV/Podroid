package com.excp.podroid.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable

/**
 * Shared FilterChip color scheme. Used wherever Material3 FilterChip appears so
 * every selection chip in the app behaves identically:
 *
 * - Unselected: filled surface fill (button-like, not outline-only), neutral text
 * - Selected:   lime accent fill, accent-ink text — pops at a glance
 * - Disabled:   same shapes, just dimmer — keeps the selection still recognizable
 *               (M3's default disabled state nukes the accent entirely, which
 *               makes a "selected but locked" chip indistinguishable from the
 *               others)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodroidChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor                  = MaterialTheme.colorScheme.surfaceVariant,
    labelColor                      = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor          = MaterialTheme.colorScheme.primary,
    selectedLabelColor              = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor          = MaterialTheme.colorScheme.surfaceVariant,
    disabledLabelColor              = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledSelectedContainerColor  = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
)
