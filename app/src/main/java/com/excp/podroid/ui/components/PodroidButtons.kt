package com.excp.podroid.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.excp.podroid.ui.theme.PodroidTokens

@Composable
fun PodroidPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(PodroidTokens.Radius.Button),
        colors = ButtonDefaults.buttonColors(
            // colorScheme.primary/onPrimary, not the fixed Accent literal, so the
            // primary button re-tints when the user enables dynamic color (the
            // fixed theme maps primary = Accent, so the default look is identical).
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor   = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(PodroidTokens.Spacing.SM))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PodroidGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(PodroidTokens.Radius.Button),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(PodroidTokens.Spacing.SM))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PodroidDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(PodroidTokens.Radius.Button),
        border = BorderStroke(1.dp, PodroidTokens.Red.copy(alpha = 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = PodroidTokens.Red,
        ),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(PodroidTokens.Spacing.SM))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}
