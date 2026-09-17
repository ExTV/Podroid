package com.excp.podroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.excp.podroid.ui.theme.PodroidDarkTextFaint
import com.excp.podroid.ui.theme.PodroidTokens

/**
 * Colored dot + label — used in Home meta row, top bars, anywhere status
 * needs to be conveyed at a glance. The dot color is decoupled from the
 * accent so a stopped state stays grey even on a lime-themed app.
 */
@Composable
fun PodroidStatus(
    label: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(PodroidTokens.Spacing.SM))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

object PodroidStatusColors {
    val Running = PodroidTokens.Accent
    val Starting = PodroidTokens.Amber
    val Stopped = PodroidDarkTextFaint
}
