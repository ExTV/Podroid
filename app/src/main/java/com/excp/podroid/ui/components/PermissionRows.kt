package com.excp.podroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.excp.podroid.R
import com.excp.podroid.ui.theme.PodroidTokens
import com.excp.podroid.util.AppPermission

/**
 * One row per applicable permission: title + why on the left, a "Granted" label
 * or a compact Grant button on the right. Owns no permission logic - the caller
 * decides which permissions apply, how to check them, and what Grant does.
 */
@Composable
fun PermissionRows(
    permissions: List<AppPermission>,
    isGranted: (AppPermission) -> Boolean,
    onGrant: (AppPermission) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        permissions.forEachIndexed { index, permission ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = PodroidTokens.Spacing.MD),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(permission.titleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(permission.whyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(PodroidTokens.Spacing.SM))
                if (isGranted(permission)) {
                    Text(
                        text = stringResource(R.string.perm_granted),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { onGrant(permission) }) {
                        Text(stringResource(R.string.perm_grant))
                    }
                }
            }
            if (index != permissions.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outline,
                    thickness = 1.dp,
                )
            }
        }
    }
}
