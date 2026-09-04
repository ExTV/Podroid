package com.excp.podroid.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.excp.podroid.ui.theme.PodroidTokens

/**
 * Canonical horizontal row used across Home, Settings, Setup, drawer, picker.
 * label = left, value = right, 1 px bottom divider drawn unless `divider = false`.
 *
 * `mono = true` renders the value in JetBrains Mono — for IPs, ports, paths.
 * `onClick` makes the row tappable (Settings rows that open a sub-screen pass
 * `value = "Dracula"` + `trailing = "›"`).
 */
@Composable
fun PodroidListRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    trailing: String? = null,
    mono: Boolean = false,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    rightSlot: @Composable (() -> Unit)? = null,
) {
    val rowMod = modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = PodroidTokens.Spacing.MD)

    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (rightSlot != null) {
            rightSlot()
        } else if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (mono) PodroidTokens.mono() else FontFamily.Default,
                textAlign = TextAlign.End,
                // fill = false: a short value still hugs the right edge via SpaceBetween;
                // a long value wraps inside the leftover space instead of starving the label.
                modifier = Modifier.weight(1f, fill = false),
            )
            if (trailing != null) {
                Spacer(Modifier.width(PodroidTokens.Spacing.SM))
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (divider) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp,
        )
    }
}
