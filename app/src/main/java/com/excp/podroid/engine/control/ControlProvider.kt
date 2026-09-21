/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * ContentProvider answering `adb shell content call` for on-device test
 * automation: read VM state, switch backend, add/remove a port forward, list
 * backup archives, jump to a screen. Start/stop already exist as broadcast
 * intents (service/PodroidService.kt, VmControlReceiver) and are out of scope.
 *
 * adb shell content call --uri content://<applicationId>.control --method state
 *
 * The framework performs NO permission check on ContentProvider.call() itself,
 * so isCallerAllowed() below is the real gate; the manifest's
 * android:permission="android.permission.DUMP" is defense in depth only.
 */
package com.excp.podroid.engine.control

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.excp.podroid.BuildConfig
import com.excp.podroid.data.repository.AddRuleResult
import com.excp.podroid.data.repository.ContainerBackupRepository
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.PortForwardRule
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.EngineHolder
import com.excp.podroid.engine.EngineSelection
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.navigation.Routes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes `navigate` may jump to. Never `setup` (mid-wizard, jumping there is
 * meaningless) and never `terminal/x11` (a nested destination the terminal
 * screen pushes itself, not a standalone landing point).
 */
internal val ALLOWED_NAVIGATE_ROUTES = setOf(
    Routes.HOME,
    Routes.TERMINAL,
    Routes.SETTINGS,
    Routes.STATUS,
    Routes.CONTAINER_BACKUP,
)

/**
 * One parsed `call()` request. Kept free of Android types so [parseCommand]
 * is unit testable on the plain JVM.
 */
internal sealed class ControlCommand {
    object State : ControlCommand()
    data class SetBackend(val selection: EngineSelection) : ControlCommand()
    data class ForwardAdd(val rule: PortForwardRule) : ControlCommand()
    data class ForwardRemove(val rule: PortForwardRule) : ControlCommand()
    object Backups : ControlCommand()
    data class Navigate(val route: String) : ControlCommand()
    object UnknownMethod : ControlCommand()
    object UnknownRoute : ControlCommand()
    object BadArg : ControlCommand()
}

/** Pure method+arg parser. */
internal fun parseCommand(method: String?, arg: String?): ControlCommand = when (method) {
    "state" -> ControlCommand.State
    "set-backend" -> {
        val selection = arg?.uppercase()?.let { name ->
            runCatching { EngineSelection.valueOf(name) }.getOrNull()
        }
        if (selection == null) ControlCommand.BadArg else ControlCommand.SetBackend(selection)
    }
    "forward-add" -> {
        val rule = arg?.let(PortForwardRule::deserialize)
        if (rule == null) ControlCommand.BadArg else ControlCommand.ForwardAdd(rule)
    }
    "forward-remove" -> {
        val rule = arg?.let(PortForwardRule::deserialize)
        if (rule == null) ControlCommand.BadArg else ControlCommand.ForwardRemove(rule)
    }
    "backups" -> ControlCommand.Backups
    "navigate" -> when {
        arg.isNullOrBlank() -> ControlCommand.BadArg
        arg !in ALLOWED_NAVIGATE_ROUTES -> ControlCommand.UnknownRoute
        else -> ControlCommand.Navigate(arg)
    }
    else -> ControlCommand.UnknownMethod
}

/**
 * Caller check: root, shell, or an app holding android.permission.DUMP. An
 * ordinary installed app is none of these, so it cannot flip the backend or
 * add a port forward (user forwards bind 0.0.0.0) through this provider.
 */
internal fun isCallerAllowed(uid: Int, hasDumpPermission: Boolean): Boolean =
    uid == Process.ROOT_UID || uid == Process.SHELL_UID || hasDumpPermission

/**
 * VmState -> explicit literal. Never javaClass.simpleName/::class.simpleName:
 * R8 obfuscates those in release builds and the obfuscated name would leak
 * into the adb reply.
 */
internal fun vmStateLiteral(state: VmState): String = when (state) {
    is VmState.Idle -> "idle"
    is VmState.Starting -> "starting"
    is VmState.Running -> "running"
    is VmState.Stopped -> "stopped"
    is VmState.Error -> "error"
}

/** Builds the `state` reply from plain inputs, so it's testable without
 *  Android or a live EngineHolder. */
internal fun formatStateReply(
    vmState: VmState,
    backendId: String,
    selection: EngineSelection,
    bootStage: String,
    forwards: List<PortForwardRule>,
    versionCode: Int,
): String {
    val stage = bootStage.replace(' ', '_')
    val forwardsField = if (forwards.isEmpty()) "none" else forwards.joinToString(",") { it.serialize() }
    return "ok vm=${vmStateLiteral(vmState)} backend=$backendId selection=${selection.name} " +
        "stage=$stage forwards=$forwardsField vc=$versionCode"
}

/** `forward-add` outcome -> reply literal. */
internal fun formatAddRuleReply(result: AddRuleResult): String = when (result) {
    AddRuleResult.ADDED -> "ok added"
    AddRuleResult.RESERVED -> "error reserved"
    AddRuleResult.TABLE_FULL -> "error table-full"
}

/**
 * Buffered, replay-1 route bus: the provider emits, NavGraph collects. A Hilt
 * singleton so both sides reach the same instance without threading a
 * parameter through the whole nav graph.
 */
@Singleton
class ControlNavigator @Inject constructor() {
    private val _routes = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val routes: SharedFlow<String> = _routes.asSharedFlow()
    fun requestNavigate(route: String) {
        _routes.tryEmit(route)
    }
}

/**
 * Dependencies ControlProvider.call() needs, resolved lazily per call: a
 * ContentProvider's onCreate() runs before Application.onCreate(), so Hilt's
 * SingletonComponent isn't installed yet and nothing may be injected there.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ControlProviderEntryPoint {
    fun settingsRepository(): SettingsRepository
    fun portForwardRepository(): PortForwardRepository
    fun containerBackupRepository(): ContainerBackupRepository
    fun engineHolder(): EngineHolder
    fun controlNavigator(): ControlNavigator
}

/**
 * `adb shell content call --uri content://<applicationId>.control --method <m> [--arg <a>]`
 *
 * Methods: state, set-backend, forward-add, forward-remove, backups, navigate.
 * Reply is a Bundle with one String key "result": "ok ..." on success,
 * "error ..." on failure.
 */
class ControlProvider : ContentProvider() {

    // Runs before Application.onCreate() - nothing may be injected or touched
    // here. Dependencies are resolved lazily inside call() instead.
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context
        if (ctx == null || !isCallerAllowed(Binder.getCallingUid(), hasDumpPermission(ctx))) {
            return replyBundle("error denied")
        }
        return try {
            runBlocking { replyBundle(execute(ctx, method, arg)) }
        } catch (e: Exception) {
            Log.w(TAG, "call failed: method=$method", e)
            replyBundle("error ${e.message ?: "internal error"}")
        }
    }

    private fun hasDumpPermission(ctx: Context): Boolean =
        ctx.checkCallingPermission(android.Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    private suspend fun execute(ctx: Context, method: String, arg: String?): String {
        val ep = EntryPointAccessors.fromApplication(ctx, ControlProviderEntryPoint::class.java)
        return when (val command = parseCommand(method, arg)) {
            ControlCommand.State -> handleState(ep)
            is ControlCommand.SetBackend -> handleSetBackend(ep, command.selection)
            is ControlCommand.ForwardAdd -> handleForwardAdd(ep, command.rule)
            is ControlCommand.ForwardRemove -> handleForwardRemove(ep, command.rule)
            ControlCommand.Backups -> handleBackups(ep)
            is ControlCommand.Navigate -> handleNavigate(ep, command.route)
            ControlCommand.UnknownMethod -> "error unknown-method"
            ControlCommand.UnknownRoute -> "error unknown-route"
            ControlCommand.BadArg -> "error bad-arg"
        }
    }

    private suspend fun handleState(ep: ControlProviderEntryPoint): String {
        val holder = ep.engineHolder()
        return formatStateReply(
            vmState = holder.state.value,
            backendId = holder.backendId,
            selection = ep.settingsRepository().getEngineSelectionSnapshot(),
            bootStage = holder.bootStage.value,
            forwards = ep.portForwardRepository().getRulesSnapshot(),
            versionCode = BuildConfig.VERSION_CODE,
        )
    }

    private suspend fun handleSetBackend(ep: ControlProviderEntryPoint, selection: EngineSelection): String {
        ep.settingsRepository().setEngineSelection(selection)
        return "ok selection=${selection.name}"
    }

    // EngineHolder already applies PortForwardRepository changes to the
    // running VM (its live rule-diff loop); calling the engine directly here
    // would double-apply, so go through the repository like the UI does.
    private suspend fun handleForwardAdd(ep: ControlProviderEntryPoint, rule: PortForwardRule): String =
        formatAddRuleReply(ep.portForwardRepository().addRule(rule))

    private suspend fun handleForwardRemove(ep: ControlProviderEntryPoint, rule: PortForwardRule): String {
        ep.portForwardRepository().removeRule(rule)
        return "ok removed"
    }

    private fun handleBackups(ep: ControlProviderEntryPoint): String {
        val files = ep.containerBackupRepository().listBackupFiles()
        return "ok count=${files.size} files=${files.joinToString(",") { it.name }}"
    }

    private fun handleNavigate(ep: ControlProviderEntryPoint, route: String): String {
        ep.controlNavigator().requestNavigate(route)
        return "ok route=$route"
    }

    private fun replyBundle(result: String): Bundle = Bundle().apply { putString(RESULT_FIELD, result) }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun getType(uri: Uri): String? = null

    companion object {
        private const val TAG = "ControlProvider"
        private const val RESULT_FIELD = "result"
    }
}
