/*
 * Podroid
 * Copyright (C) 2024 Podroid contributors
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 */
package com.excp.podroid.engine

/**
 * Lifecycle state of a running (or stopped) virtual machine.
 *
 * State transitions:
 *
 * ```
 *  Idle ──► Starting ──► Running ──► Paused
 *                          │            │
 *                          ▼            ▼
 *                        Saving      Resuming ──► Running
 *                          │
 *                          ▼
 *                        Stopped ──► Idle
 *                          │
 *                          ▼
 *                        Error
 * ```
 */
sealed class VmState {

    /** No VM is active. */
    data object Idle : VmState()

    /** QEMU process is initializing. */
    data object Starting : VmState()

    /** VM is running and the VNC display is available. */
    data object Running : VmState()

    /** VM execution is paused (CPU halted, state preserved). */
    data object Paused : VmState()

    /** Saving VM state to disk via QEMU migration. */
    data object Saving : VmState()

    /** Restoring VM from a saved state snapshot. */
    data object Resuming : VmState()

    /** QEMU process has exited cleanly. */
    data object Stopped : VmState()

    /**
     * An error occurred.
     *
     * @param message Human-readable description of what went wrong.
     */
    data class Error(val message: String) : VmState()
}
