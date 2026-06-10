package com.excp.podroid.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Switch wrapper that locks the colors to the Podroid palette. Material 3's
 * default Switch picks up `colorScheme.primary` already — this is mostly for
 * the muted-track look in the "off" state.
 */
@Composable
fun PodroidSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor    = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor    = MaterialTheme.colorScheme.primary,
            checkedBorderColor   = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor  = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor  = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
        ),
    )
}
