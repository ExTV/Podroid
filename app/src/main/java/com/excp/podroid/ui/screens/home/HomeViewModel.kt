/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Home screen ViewModel for Podroid.
 */
package com.excp.podroid.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import com.excp.podroid.service.PodroidService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val podroidQemu: PodroidQemu,
) : ViewModel() {

    /** Current QEMU lifecycle state — used to disable Start button while a VM is running. */
    val vmState: StateFlow<VmState> = podroidQemu.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    /** Start Podroid (the Podman VM). */
    fun startPodroid() {
        PodroidService.start(context)
    }

    /** Stop the running Podman VM. */
    fun stopVm() {
        PodroidService.stop(context)
    }

    /** Restart the VM (stop then start). */
    fun restartVm() {
        PodroidService.stop(context)
        // Start after a brief delay to allow stop to complete
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            PodroidService.start(context)
        }, 2000)
    }
}
