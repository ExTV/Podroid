package com.excp.podroid.ui.screens.backup

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidListRow
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidSectionLabel
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.theme.PodroidTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerBackupScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    viewModel: ContainerBackupViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.container_backup_title),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodroidTokens.Spacing.XL, vertical = PodroidTokens.Spacing.LG),
                verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
            ) {
                Text(
                    text = stringResource(R.string.container_backup_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                PodroidSectionLabel(stringResource(R.string.container_backup_location))
                PodroidListRow(
                    label = stringResource(R.string.container_backup_guest_path),
                    value = ui.guestPath,
                    mono = true,
                )
                if (ui.storageAccessEnabled) {
                    PodroidListRow(
                        label = stringResource(R.string.container_backup_phone_path),
                        value = stringResource(R.string.container_backup_phone_path_value),
                        mono = true,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.container_backup_downloads_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = PodroidTokens.Amber,
                    )
                }

                PodroidSectionLabel(stringResource(R.string.container_backup_export))
                if (!ui.vmRunning) {
                    Text(
                        text = stringResource(R.string.container_backup_vm_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = ui.containerName,
                    onValueChange = viewModel::setContainerName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.container_backup_container_name)) },
                    singleLine = true,
                )
                PodroidPrimaryButton(
                    text = stringResource(R.string.container_backup_copy_export),
                    onClick = {
                        if (viewModel.copyExportCommand()) {
                            Toast.makeText(context, context.getString(R.string.container_backup_copied), Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = ui.containerName.isNotBlank(),
                )

                PodroidSectionLabel(stringResource(R.string.container_backup_save_image))
                OutlinedTextField(
                    value = ui.imageRef,
                    onValueChange = viewModel::setImageRef,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.container_backup_image_ref)) },
                    placeholder = { Text(stringResource(R.string.container_backup_image_placeholder)) },
                    singleLine = true,
                )
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_copy_save),
                    onClick = {
                        if (viewModel.copySaveCommand()) {
                            Toast.makeText(context, context.getString(R.string.container_backup_copied), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                PodroidSectionLabel(stringResource(R.string.container_backup_tools))
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_copy_list),
                    onClick = {
                        viewModel.copyListCommand()
                        Toast.makeText(context, context.getString(R.string.container_backup_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_copy_all),
                    onClick = {
                        viewModel.copyAllCommand()
                        Toast.makeText(context, context.getString(R.string.container_backup_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.container_backup_terminal_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(Modifier.height(PodroidTokens.Spacing.SM))
                PodroidSectionLabel(stringResource(R.string.container_backup_on_phone))
                PodroidGhostButton(
                    text = stringResource(R.string.container_backup_refresh),
                    onClick = viewModel::refresh,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ui.backupFiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.container_backup_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ui.backupFiles.forEach { file ->
                        PodroidListRow(
                            label = file.name,
                            value = "${viewModel.formatSize(file.sizeBytes)} · ${viewModel.formatDate(file.lastModifiedMs)}",
                            mono = true,
                        )
                    }
                }
            }
        }
    }
}
