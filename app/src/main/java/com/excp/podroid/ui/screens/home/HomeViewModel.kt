package com.excp.podroid.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.data.repository.UpdateInfo
import com.excp.podroid.data.repository.UpdateRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import com.excp.podroid.service.PodroidService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val podroidQemu: PodroidQemu,
    private val settingsRepository: SettingsRepository,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = podroidQemu.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    val bootStage: StateFlow<String> = podroidQemu.bootStage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val storageAccessEnabled: StateFlow<Boolean> = settingsRepository.storageAccessEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    init {
        checkForUpdate()
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            val info = updateRepository.checkForUpdate(BuildConfig.VERSION_NAME) ?: return@launch
            if (!updateRepository.isDismissed(info.latestVersion)) {
                _updateInfo.value = info
            }
        }
    }

    fun dismissUpdate() {
        val version = _updateInfo.value?.latestVersion ?: return
        _updateInfo.value = null
        viewModelScope.launch {
            updateRepository.dismissUpdate(version)
        }
    }

    fun startPodroid() {
        PodroidService.start(context)
    }

    fun setStorageAccessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setStorageAccessEnabled(enabled)
        }
    }

    fun stopVm() {
        PodroidService.stop(context)
    }

    fun restartVm() {
        PodroidService.stop(context)
        viewModelScope.launch {
            delay(2000)
            PodroidService.start(context)
        }
    }
}
