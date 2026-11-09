/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Debug-only foreground service that keeps the app process at foreground
 * priority while a UML test runs, mirroring how PodroidService hosts QEMU.
 *   adb shell am start-foreground-service -n com.excp.podroid.debug/com.excp.podroid.umltest.UmlTestService
 *   adb shell am stopservice -n com.excp.podroid.debug/com.excp.podroid.umltest.UmlTestService
 */
package com.excp.podroid.umltest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class UmlTestService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "UML test", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("UML test harness")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "umltest"
        const val ID = 7301
    }
}
