/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * App-wide settings backed by DataStore.
 */
package com.excp.podroid.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        val KEY_DARK_THEME    = booleanPreferencesKey("dark_theme")
        val KEY_VM_RAM        = intPreferencesKey("vm_ram_mb")
        val KEY_VM_CPUS       = intPreferencesKey("vm_cpus")
        val KEY_FONT_SIZE     = intPreferencesKey("terminal_font_size")
        val KEY_STORAGE_GB    = intPreferencesKey("storage_gb")
        val KEY_STORAGE_ACCESS_ENABLED = booleanPreferencesKey("storage_access_enabled")
        val KEY_SETUP_DONE    = booleanPreferencesKey("setup_done")
        val KEY_SSH_ENABLED   = booleanPreferencesKey("ssh_enabled")
        val KEY_TERMINAL_COLOR_THEME = stringPreferencesKey("terminal_color_theme")
        val KEY_TERMINAL_FONT        = stringPreferencesKey("terminal_font")
    }

    val darkTheme: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DARK_THEME] ?: true }

    val vmRamMb: Flow<Int> = context.dataStore.data
        .map { it[KEY_VM_RAM] ?: 512 }

    val vmCpus: Flow<Int> = context.dataStore.data
        .map { it[KEY_VM_CPUS] ?: 1 }

    val terminalFontSize: Flow<Int> = context.dataStore.data
        .map { it[KEY_FONT_SIZE] ?: 20 }

    val storageSizeGb: Flow<Int> = context.dataStore.data
        .map { it[KEY_STORAGE_GB] ?: 2 }

    val storageAccessEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_STORAGE_ACCESS_ENABLED] ?: false }

    val isSetupDone: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SETUP_DONE] ?: false }

    val sshEnabled: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SSH_ENABLED] ?: false }

    // Terminal appearance flows
    val terminalColorTheme: Flow<String> = context.dataStore.data
        .map { it[KEY_TERMINAL_COLOR_THEME] ?: "default" }

    val terminalFont: Flow<String> = context.dataStore.data
        .map { it[KEY_TERMINAL_FONT] ?: "default" }

    suspend fun setDarkTheme(value: Boolean) =
        context.dataStore.edit { it[KEY_DARK_THEME] = value }

    suspend fun setVmRamMb(value: Int) =
        context.dataStore.edit { it[KEY_VM_RAM] = value }

    suspend fun setVmCpus(value: Int) =
        context.dataStore.edit { it[KEY_VM_CPUS] = value }

    suspend fun setTerminalFontSize(value: Int) =
        context.dataStore.edit { it[KEY_FONT_SIZE] = value }

    suspend fun setStorageSizeGb(value: Int) =
        context.dataStore.edit { it[KEY_STORAGE_GB] = value }

    suspend fun setStorageAccessEnabled(value: Boolean) =
        context.dataStore.edit { it[KEY_STORAGE_ACCESS_ENABLED] = value }

    suspend fun markSetupDone() =
        context.dataStore.edit { it[KEY_SETUP_DONE] = true }

    suspend fun setSshEnabled(value: Boolean) =
        context.dataStore.edit { it[KEY_SSH_ENABLED] = value }

    // Terminal appearance setters
    suspend fun setTerminalColorTheme(value: String) =
        context.dataStore.edit { it[KEY_TERMINAL_COLOR_THEME] = value }

    suspend fun setTerminalFont(value: String) =
        context.dataStore.edit { it[KEY_TERMINAL_FONT] = value }

    suspend fun getSshEnabledSnapshot(): Boolean =
        context.dataStore.data.map { it[KEY_SSH_ENABLED] ?: false }.first()

    suspend fun getVmRamMbSnapshot(): Int =
        context.dataStore.data.map { it[KEY_VM_RAM] ?: 512 }.first()

    suspend fun getVmCpusSnapshot(): Int =
        context.dataStore.data.map { it[KEY_VM_CPUS] ?: 1 }.first()

    suspend fun getStorageSizeGbSnapshot(): Int =
        context.dataStore.data.map { it[KEY_STORAGE_GB] ?: 2 }.first()

    suspend fun getStorageAccessEnabledSnapshot(): Boolean =
        context.dataStore.data.map { it[KEY_STORAGE_ACCESS_ENABLED] ?: false }.first()

    suspend fun isSetupDoneSnapshot(): Boolean =
        context.dataStore.data.map { it[KEY_SETUP_DONE] ?: false }.first()

    // Terminal appearance snapshots
    suspend fun getTerminalColorThemeSnapshot(): String =
        context.dataStore.data.map { it[KEY_TERMINAL_COLOR_THEME] ?: "default" }.first()

    suspend fun getTerminalFontSnapshot(): String =
        context.dataStore.data.map { it[KEY_TERMINAL_FONT] ?: "default" }.first()
}
