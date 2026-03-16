/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 */
package com.excp.podroid.ui.screens.terminal

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.engine.PodroidQemu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val qemu: PodroidQemu,
) : ViewModel() {

    /** Console output text — directly from PodroidQemu singleton */
    val terminalText: StateFlow<String> = qemu.consoleText

    fun sendInput(text: String) {
        val output = qemu.consoleOutput ?: run {
            Log.w(TAG, "consoleOutput is null, VM not running")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                output.write(text.toByteArray())
                output.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send input", e)
            }
        }
    }

    companion object {
        private const val TAG = "TerminalVM"
    }
}
