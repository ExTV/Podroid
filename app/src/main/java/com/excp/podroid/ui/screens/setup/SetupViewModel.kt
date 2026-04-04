package com.excp.podroid.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    fun completeSetup(storageSizeGb: Int, sshEnabled: Boolean, storageAccessEnabled: Boolean) {
        viewModelScope.launch {
            // Write all settings fully before signalling completion
            settingsRepository.setStorageSizeGb(storageSizeGb)
            settingsRepository.setSshEnabled(sshEnabled)
            settingsRepository.setStorageAccessEnabled(storageAccessEnabled)
            settingsRepository.markSetupDone()
            _setupComplete.value = true
        }
    }
}
