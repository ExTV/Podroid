/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Settings ViewModel for Podroid.
 */
package com.excp.podroid.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val portForwardRepository: PortForwardRepository,
    private val podroidQemu: PodroidQemu,
) : ViewModel() {

    val darkTheme: Flow<Boolean> = settingsRepository.darkTheme

    val vmRamMb: StateFlow<Int> = settingsRepository.vmRamMb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1024)

    val vmCpus: StateFlow<Int> = settingsRepository.vmCpus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 4)

    val terminalFontSize: StateFlow<Int> = settingsRepository.terminalFontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 24)

    val portForwardRules: StateFlow<List<PortForwardRule>> = portForwardRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vmState: StateFlow<VmState> = podroidQemu.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VmState.Idle)

    fun setDarkTheme(value: Boolean) {
        viewModelScope.launch { settingsRepository.setDarkTheme(value) }
    }

    fun setVmRamMb(value: Int) {
        viewModelScope.launch { settingsRepository.setVmRamMb(value) }
    }

    fun setVmCpus(value: Int) {
        viewModelScope.launch { settingsRepository.setVmCpus(value) }
    }

    fun setTerminalFontSize(value: Int) {
        viewModelScope.launch { settingsRepository.setTerminalFontSize(value) }
    }

    fun addPortForward(hostPort: Int, guestPort: Int, protocol: String = "tcp") {
        val rule = PortForwardRule(hostPort, guestPort, protocol)
        viewModelScope.launch {
            portForwardRepository.addRule(rule)
            if (podroidQemu.state.value is VmState.Running) {
                podroidQemu.qmpClient.addPortForward(hostPort, guestPort, protocol)
            }
        }
    }

    fun removePortForward(rule: PortForwardRule) {
        viewModelScope.launch {
            portForwardRepository.removeRule(rule)
            if (podroidQemu.state.value is VmState.Running) {
                podroidQemu.qmpClient.removePortForward(rule.hostPort, rule.protocol)
            }
        }
    }

    fun resetVm() {
        val storageFile = File(context.filesDir, "storage.img")
        if (storageFile.exists()) {
            storageFile.delete()
        }
    }

    fun exportConsoleLogs() {
        val logFile = File(context.filesDir, "console.log")
        if (!logFile.exists()) return

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Podroid Console Log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share console log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
