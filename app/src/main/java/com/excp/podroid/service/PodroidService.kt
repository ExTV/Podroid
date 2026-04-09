/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Foreground service that hosts the QEMU process for Podroid.
 * Holds a WakeLock to prevent the device from sleeping while the VM runs.
 */
package com.excp.podroid.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.excp.podroid.MainActivity
import com.excp.podroid.R
import com.excp.podroid.data.repository.PortForwardRepository
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.PodroidQemu
import com.excp.podroid.engine.VmState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class PodroidService : Service() {

    @Inject lateinit var podroidQemu: PodroidQemu
    @Inject lateinit var portForwardRepository: PortForwardRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var launchJob: Job? = null
    private var notificationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification("Starting VM..."),
                    fgType,
                )
                acquireWakeLock()
                launchPodroid()
            }
            ACTION_STOP -> {
                podroidQemu.stop()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        notificationJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App swiped from recents — stop the VM gracefully
        if (podroidQemu.state.value is VmState.Running ||
            podroidQemu.state.value is VmState.Starting) {
            podroidQemu.stop()
        }
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
            launch {
                podroidQemu.state.collect { state ->
                    when (state) {
                        is VmState.Running -> updateNotification("VM is running")
                        is VmState.Starting -> {
                            val stage = podroidQemu.bootStage.value
                            updateNotification(stage.ifEmpty { "Starting VM..." })
                        }
                        is VmState.Stopped, is VmState.Idle, is VmState.Error -> return@collect
                        else -> {}
                    }
                }
            }
            launch {
                // Terminal states: release resources and shut down the service.
                // Error must be included here — otherwise a failed boot leaves the
                // wakelock held and the foreground notification stuck on "Starting...".
                //
                // StateFlow replays its current value on subscription, so this
                // collector fires immediately with whatever state is already set.
                // At service-start time the current state is Idle (QEMU hasn't
                // been launched yet), which used to match this branch and
                // instantly release the wakelock we just acquired. Guard with
                // a "seenActive" flag so cleanup only runs on a real
                // active → terminal transition.
                var seenActive = false
                podroidQemu.state.collect { state ->
                    when (state) {
                        is VmState.Starting, is VmState.Running -> seenActive = true
                        is VmState.Stopped, is VmState.Idle, is VmState.Error -> {
                            if (seenActive) {
                                releaseWakeLock()
                                if (Build.VERSION.SDK_INT >= 33) {
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                } else {
                                    @Suppress("DEPRECATION") stopForeground(true)
                                }
                                stopSelf()
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun launchPodroid() {
        launchJob?.cancel()
        launchJob = serviceScope.launch {
            startNotificationUpdates()
            withContext(Dispatchers.IO) {
                try {
                    val rules = portForwardRepository.getRulesSnapshot().toMutableList()
                    val ramMb = settingsRepository.getVmRamMbSnapshot()
                    val cpus = settingsRepository.getVmCpusSnapshot()
                    val sshEnabled = settingsRepository.getSshEnabledSnapshot()

                    // Auto-inject SSH port forward when SSH is enabled
                    if (sshEnabled && rules.none { it.hostPort == SSH_HOST_PORT }) {
                        rules.add(com.excp.podroid.data.repository.PortForwardRule(SSH_HOST_PORT, 22, "tcp"))
                    }

                    val androidIp = getAndroidLocalIp()
                    podroidQemu.start(rules, ramMb, cpus, sshEnabled, androidIp)
                } catch (e: Exception) {
                    Log.e(TAG, "QEMU failed to start", e)
                }
            }
        }
    }

    private fun getAndroidLocalIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()
                ?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "unknown"
        } catch (_: Exception) { "unknown" }
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

    private fun buildNotification(status: String): Notification {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Podroid")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vm_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
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
