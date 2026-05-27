/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Foreground service that hosts the QEMU process for Podroid.
 * Holds a WakeLock to prevent the device from sleeping while the VM runs.
 */
package com.excp.podroid.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.excp.podroid.MainActivity
import com.excp.podroid.PodroidApplication
import com.excp.podroid.R
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmConfig
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.engine.usb.UsbPassthroughManager
import com.excp.podroid.util.NetworkUtils
import com.excp.podroid.x11.X11Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

@AndroidEntryPoint
class PodroidService : Service() {

    @Inject lateinit var engine: VmEngine
    @Inject lateinit var portForwardRepository: PortForwardRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var usbPassthroughManager: UsbPassthroughManager
    @Inject lateinit var notificationPoster: com.excp.podroid.engine.hostbridge.AndroidNotificationPoster
    @Inject lateinit var headlessModeManager: com.excp.podroid.engine.hostbridge.HeadlessModeManager
    private var hostRequestServer: com.excp.podroid.engine.hostbridge.HostRequestServer? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var notificationBuilder: NotificationCompat.Builder? = null
    private var stopPendingIntent: PendingIntent? = null
    private var openPendingIntent: PendingIntent? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Android 14+ (API 34) requires the foregroundServiceType argument
                // when the manifest declares foregroundServiceType="specialUse";
                // otherwise Android throws MissingForegroundServiceTypeException.
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
                // Non-fatal diagnostic: on API 33+ a missing POST_NOTIFICATIONS
                // grant makes the persistent notification (and its Stop action)
                // invisible while the WakeLock is held. We do NOT gate VM start on
                // this, just log so the invisible-notification state is
                // diagnosable. (The setup screen requests the permission.)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "POST_NOTIFICATIONS not granted; foreground notification " +
                        "and its Stop action will be invisible while the VM holds the WakeLock")
                }
                // Always (re-)assert foreground within the start window — required
                // even on a redundant ACTION_START so the system doesn't fault us
                // for not calling startForeground.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Starting VM..."),
                    fgType,
                )
                // De-dup: if the VM is already active, do NOT re-acquire/relaunch.
                // launchPodroid relaunches the observers (resetting their
                // seenActive latch); doing that on a double-tap could strand the
                // teardown path. The engine's own start() is also a no-op here.
                val alreadyActive = engine.state.value is VmState.Starting ||
                    engine.state.value is VmState.Running
                if (!alreadyActive) {
                    acquireWakeLock()
                    launchPodroid()
                }
            }
            ACTION_STOP -> {
                // engine.stop() is a no-op if nothing is running. Either way the
                // shutdown observer only fires after an active state, so on the
                // "stop while idle" path (e.g. PodroidService.stop() spinning up a
                // fresh service) we must release + stop here so this doesn't
                // linger as a started-but-never-foregrounded orphan.
                engine.stop()
                releaseWakeLock()
                stopForegroundCompat()
                stopSelf()
            }
            else -> {
                // Null/unrecognized action (e.g. a system redelivery): we never
                // called startForeground for this start, so just stop to avoid a
                // started-but-not-foregrounded service.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationJob?.cancel()
        hostRequestServer?.stop()
        usbPassthroughManager.stop()
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App swiped from recents — stop the VM gracefully and tear the service
        // down unconditionally. engine.stop() is a no-op if idle; doing this even
        // in the Idle-before-Starting window prevents a swipe during early start
        // from leaving a VM that starts with no UI, holding the WakeLock.
        engine.stop()
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Podroid::VmWakeLock"
            ).apply {
                @SuppressLint("WakelockTimeout")
                acquire() // VM must run indefinitely — timeout would kill it
            }
            Log.d(TAG, "WakeLock acquired")
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun startNotificationUpdates() {
        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            launch { observeStateForNotification() }
            launch { observeStateForShutdown() }
        }
    }

    /** Updates the notification text from a combined view of state + bootStage. */
    private suspend fun observeStateForNotification() {
        combine(engine.state, engine.bootStage) { state, stage -> state to stage }
            .collect { (state, stage) ->
                when (state) {
                    is VmState.Running  -> updateNotification("VM is running")
                    is VmState.Starting -> updateNotification(stage.ifEmpty { "Starting VM..." })
                    else -> {} // Terminal states handled by the shutdown observer
                }
            }
    }

    /**
     * Releases the wakelock and stops the service when the VM reaches a
     * terminal state.
     *
     * `Error`/`Stopped` are treated as always-actionable: an engine that goes
     * straight to `Error` without ever emitting `Starting` (e.g. a missing QEMU
     * binary), or a conflated `Idle→Starting→Error` that the Main collector only
     * observes as `Error`, must still release the no-timeout WakeLock and stop
     * the foreground service. The `seenActive` latch exists only to skip the
     * initial `Idle` replay before the VM has been launched, so the guard now
     * gates `Idle` alone — terminal failures are never silently swallowed.
     */
    private suspend fun observeStateForShutdown() {
        var seenActive = false
        // The engine is a process-lifetime @Singleton, so its StateFlow replays
        // the PRIOR cycle's retained terminal state (Stopped/Error) the instant
        // this fresh collector subscribes — before the engine.start() that
        // launchPodroid is about to run flips it to Starting. Acting on that
        // replayed value would teardown (stopSelf → onDestroy → serviceScope
        // .cancel) the very start we are kicking off, cancelling it as "Job was
        // cancelled" and leaving a new stale Error that poisons the next attempt
        // too — so the VM never restarts until the process is killed. Consume the
        // first emission as a baseline; only act on transitions that follow it.
        var baselineConsumed = false
        engine.state.collect { state ->
            if (!baselineConsumed) {
                baselineConsumed = true
                if (state is VmState.Starting || state is VmState.Running) seenActive = true
                return@collect
            }
            when (state) {
                is VmState.Starting, is VmState.Running -> seenActive = true
                is VmState.Idle -> {
                    if (!seenActive) return@collect
                    teardown()
                }
                is VmState.Stopped, is VmState.Error -> teardown()
            }
        }
    }

    /** Release the WakeLock, drop the foreground notification, and stop. */
    private fun teardown() {
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
    }

    /**
     * Arms USB passthrough once the VM is Running and tears it down when the VM
     * reaches a terminal state. Only launched when the feature is enabled, so
     * the BroadcastReceiver is live strictly while a VM session is up.
     */
    private suspend fun observeStateForUsb() {
        engine.state.collect { state ->
            when (state) {
                is VmState.Running -> usbPassthroughManager.start()
                is VmState.Stopped, is VmState.Idle, is VmState.Error ->
                    usbPassthroughManager.stop()
                else -> {}
            }
        }
    }

    private fun ensureHostBridge(): com.excp.podroid.engine.hostbridge.HostRequestServer {
        hostRequestServer?.let { return it }
        val dispatcher = com.excp.podroid.engine.hostbridge.HostRequestDispatcher(
            notifications = notificationPoster,
            addForward = { portForwardRepository.addRule(it) },
            removeForward = { portForwardRepository.removeRule(it) },
            listForwards = { portForwardRepository.getRulesSnapshot() },
            openUrl = { handleOpenUrl(it) },
            power = { handlePowerRequest(it) },
            setHeadless = { handleHeadlessRequest(it) },
        )
        return com.excp.podroid.engine.hostbridge.HostRequestServer(
            openTransport = { engine.openHostTransport() },
            dispatcher = dispatcher,
            scope = serviceScope,
        ).also { hostRequestServer = it }
    }

    /** Always-on: starts the guest host bridge on Running, stops it on terminal. */
    private suspend fun observeStateForHostBridge() {
        engine.state.collect { state ->
            when (state) {
                is VmState.Running -> ensureHostBridge().start()
                is VmState.Stopped, is VmState.Idle, is VmState.Error ->
                    hostRequestServer?.stop()
                else -> {}
            }
        }
    }

    private fun launchPodroid() {
        serviceScope.launch {
            startNotificationUpdates()
            withContext(Dispatchers.IO) {
                try {
                    // Asset extraction now runs off the main thread; the engine
                    // reads vmlinuz/initrd/squashfs synchronously in start(), so
                    // we MUST block here until extraction has fully completed or
                    // the VM could launch against a partial/missing file.
                    (application as? PodroidApplication)?.awaitAssetsReady()

                    val rules = portForwardRepository.getRulesSnapshot().toMutableList()
                    val sshEnabled = settingsRepository.getSshEnabledSnapshot()

                    // Auto-inject SSH port forward when SSH is enabled
                    if (sshEnabled && rules.none { it.hostPort == SSH_HOST_PORT }) {
                        rules.add(com.excp.podroid.data.repository.PortForwardRule(SSH_HOST_PORT, 22, "tcp"))
                    }

                    // Always-on X11 viewer forwards. These are implicit, not user-managed —
                    // they back the in-app screen toggle and never appear in the PortForward UI.
                    if (rules.none { it.hostPort == X11Constants.VNC_PORT }) {
                        rules.add(com.excp.podroid.data.repository.PortForwardRule(X11Constants.VNC_PORT, X11Constants.VNC_PORT, "tcp"))
                    }
                    if (rules.none { it.hostPort == X11Constants.AUDIO_PORT }) {
                        rules.add(com.excp.podroid.data.repository.PortForwardRule(X11Constants.AUDIO_PORT, X11Constants.AUDIO_PORT, "tcp"))
                    }

                    val config = VmConfig(
                        ramMb = settingsRepository.getVmRamMbSnapshot(),
                        cpus = settingsRepository.getVmCpusSnapshot(),
                        sshEnabled = sshEnabled,
                        androidIp = NetworkUtils.localIpv4(this@PodroidService),
                        storageSizeGb = settingsRepository.getStorageSizeGbSnapshot(),
                        storageAccessEnabled = settingsRepository.getStorageAccessEnabledSnapshot(),
                        qemuExtraArgs = settingsRepository.getQemuExtraArgsSnapshot(),
                        kernelExtraCmdline = settingsRepository.getKernelExtraCmdlineSnapshot(),
                        verboseLogging = settingsRepository.getAvfVerboseLoggingSnapshot(),
                        x11Dpi = settingsRepository.getX11DpiSnapshot(),
                        usbPassthroughEnabled = settingsRepository.getUsbPassthroughEnabledSnapshot(),
                    )
                    serviceScope.launch { observeStateForHostBridge() }
                    if (config.usbPassthroughEnabled) {
                        serviceScope.launch { observeStateForUsb() }
                    }
                    engine.start(rules, config)
                } catch (e: Exception) {
                    Log.e(TAG, "QEMU failed to start", e)
                    // A Service-side throw here (failed asset extraction, a
                    // snapshot read, or engine.start()) can happen before the
                    // engine state ever leaves Idle. In that window the shutdown
                    // observer's seenActive latch is still false, so its Idle
                    // branch returns without teardown and nothing releases the
                    // no-timeout WakeLock or drops the foreground notification.
                    // Tear down here so a start failure cleans itself up.
                    // teardown() is idempotent (releaseWakeLock guards on isHeld),
                    // and this only runs on a thrown exception, never the success
                    // path. Hop back to Main since teardown touches the service.
                    withContext(Dispatchers.Main) { teardown() }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Podroid Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the status of the Podman VM"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /**
     * Lazily build (and cache) the NotificationCompat.Builder + its PendingIntents
     * once per service lifetime. Boot streams ~5 state-change emits per second, so
     * recreating the Builder + two PendingIntents on every emit is pure churn.
     * After the first call, updateNotification() just mutates contentText on the
     * cached builder.
     */
    private fun getOrCreateNotificationBuilder(): NotificationCompat.Builder {
        notificationBuilder?.let { return it }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PodroidService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        openPendingIntent = openIntent
        stopPendingIntent = stopIntent

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Podroid")
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
        notificationBuilder = builder
        return builder
    }

    private fun buildNotification(status: String): Notification {
        return getOrCreateNotificationBuilder()
            .setContentText(status)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun handleOpenUrl(url: String): String {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme?.lowercase() !in setOf("http", "https")) {
            return com.excp.podroid.engine.hostbridge.HostProtocol.err("only http/https URLs are allowed")
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            applicationContext.startActivity(intent)
            com.excp.podroid.engine.hostbridge.HostProtocol.ok()
        } catch (e: Throwable) {
            com.excp.podroid.engine.hostbridge.HostProtocol.err("no app available to open this URL")
        }
    }

    private fun handleHeadlessRequest(action: String): String = when (action) {
        "on" -> { headlessModeManager.setActive(true); com.excp.podroid.engine.hostbridge.HostProtocol.ok() }
        "off" -> { headlessModeManager.setActive(false); com.excp.podroid.engine.hostbridge.HostProtocol.ok() }
        "status" -> com.excp.podroid.engine.hostbridge.HostProtocol.ok(if (headlessModeManager.active.value) "on" else "off")
        else -> com.excp.podroid.engine.hostbridge.HostProtocol.err("usage: on|off|status")
    }

    // Reply returned now; the stop/restart is posted to the main looper so the
    // bridge flushes the response before the VM (and this service) tear down. The
    // Handler callbacks capture the app-scoped engine + applicationContext (NOT
    // `this`), so they survive this service's death.
    private fun handlePowerRequest(action: String): String {
        val proto = com.excp.podroid.engine.hostbridge.HostProtocol
        return when (action) {
            "status" -> proto.ok(engine.state.value.javaClass.simpleName)
            "stop" -> {
                val ctx = applicationContext
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ PodroidService.stop(ctx) }, 300)
                proto.ok()
            }
            "restart" -> { scheduleRestart(); proto.ok() }
            else -> proto.err("usage: stop|restart|status")
        }
    }

    private fun scheduleRestart() {
        val ctx = applicationContext
        val eng = engine
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.postDelayed({
            PodroidService.stop(ctx)
            var tries = 0
            val poll = object : Runnable {
                override fun run() {
                    val s = eng.state.value
                    // Only Stopped/Error are genuine terminals after engine.stop().
                    // Idle is the engine's pre-start normalized state, so treating
                    // it as terminal could fire start() during a teardown blip.
                    val terminal = s is VmState.Stopped || s is VmState.Error
                    when {
                        terminal -> PodroidService.start(ctx)
                        tries++ >= 40 -> {
                            Log.w(TAG, "restart: VM did not reach a stopped state in time (state=$s); starting anyway")
                            PodroidService.start(ctx)
                        }
                        else -> main.postDelayed(this, 250)
                    }
                }
            }
            main.postDelayed(poll, 500)
        }, 300)
    }

    companion object {
        private const val TAG = "PodroidService"
        private const val CHANNEL_ID = "podroid_service"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START   = "com.excp.podroid.action.START"
        const val ACTION_STOP    = "com.excp.podroid.action.STOP"
        const val SSH_HOST_PORT  = 9922

        fun start(context: Context) {
            val intent = Intent(context, PodroidService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PodroidService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }
}
