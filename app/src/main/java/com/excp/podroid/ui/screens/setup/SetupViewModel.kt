package com.excp.podroid.ui.screens.setup

import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.data.repository.dataStore
import com.excp.podroid.engine.VmEngine
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VmEngine,
) : ViewModel() {

    private val _setupComplete = MutableStateFlow(false)
    val setupComplete: StateFlow<Boolean> = _setupComplete.asStateFlow()

    /**
     * USB passthrough is QEMU-only (it rides the QMP control socket the AVF
     * backend lacks). Reading backendId here is cheap and side-effect-free.
     * During first-run the engine pick may still be settling on its QEMU seed,
     * so on an AVF device this can briefly read available; the choice is inert
     * on AVF anyway (no QMP, no qemu-xhci) and Settings shows the correct gate.
     */
    fun usbPassthroughAvailable(): Boolean = engine.backendId == "qemu"

    /**
     * Persists all setup choices in a single DataStore transaction so a process
     * kill mid-write can't leave the app in a half-completed setup state.
     */
    fun completeSetup(
        storageSizeGb: Int,
        sshEnabled: Boolean,
        storageAccessEnabled: Boolean,
        usbPassthroughEnabled: Boolean,
    ) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[SettingsRepository.KEY_STORAGE_GB] = storageSizeGb
                prefs[SettingsRepository.KEY_SSH_ENABLED] = sshEnabled
                prefs[SettingsRepository.KEY_STORAGE_ACCESS_ENABLED] = storageAccessEnabled
                prefs[SettingsRepository.KEY_USB_PASSTHROUGH_ENABLED] = usbPassthroughEnabled
                prefs[SettingsRepository.KEY_SETUP_DONE] = true
            }
            _setupComplete.value = true
        }
    }
}
