/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Single source of truth for the app's user-promptable permissions: what they
 * are, whether they apply on this SDK level, whether they are currently
 * granted, and how to ask for them.
 */
package com.excp.podroid.util

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.excp.podroid.R

/**
 * One permission (or special-access grant) the app may ask the user for.
 *
 * @property manifestPermission the runtime permission to check/request, or
 *   null when this is special access granted through a system Intent rather
 *   than a runtime permission (e.g. battery-optimization exemption).
 * @property minSdk the first SDK_INT this entry applies on; below it the
 *   permission doesn't exist or isn't needed.
 */
enum class AppPermission(
    val manifestPermission: String?,
    val titleRes: Int,
    val whyRes: Int,
    val minSdk: Int,
) {
    NOTIFICATIONS(
        Manifest.permission.POST_NOTIFICATIONS,
        R.string.perm_notifications_title,
        R.string.perm_notifications_why,
        Build.VERSION_CODES.TIRAMISU,
    ),
    BATTERY_OPTIMIZATION(
        null,
        R.string.perm_battery_title,
        R.string.perm_battery_why,
        Build.VERSION_CODES.M,
    ),
}

/**
 * Pure logic for which permissions apply and which are missing. No `Context`
 * and no `Build.VERSION.SDK_INT` read here, so this stays a plain JVM unit
 * test - the Android-side pieces live in the extension functions below.
 */
object AppPermissions {

    /** Entries applicable on [sdkInt], in declaration (display) order. */
    fun applicable(sdkInt: Int): List<AppPermission> =
        AppPermission.entries.filter { it.minSdk <= sdkInt }

    /** Applicable entries for which [isGranted] returns false, in order. */
    fun missing(sdkInt: Int, isGranted: (AppPermission) -> Boolean): List<AppPermission> =
        applicable(sdkInt).filterNot(isGranted)
}

private const val TAG = "AppPermissions"

/** Whether this permission is currently granted on [context]. */
fun AppPermission.isGranted(context: Context): Boolean =
    when (this) {
        AppPermission.NOTIFICATIONS, -> {
            val permission = manifestPermission
                ?: error("NOTIFICATIONS must declare a manifestPermission")
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        AppPermission.BATTERY_OPTIMIZATION -> {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

/** Applicable permissions on this device that are not currently granted, in order. */
fun AppPermissions.missing(context: Context): List<AppPermission> =
    missing(Build.VERSION.SDK_INT) { it.isGranted(context) }

/**
 * Asks the system to exempt this app from battery optimization directly. Some
 * OEMs strip the direct-request screen, so a missing handler falls back to
 * the general exemption list rather than leaving the user stuck.
 */
fun AppPermissions.requestBatteryOptimizationExemption(context: Context) {
    val directIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(directIntent)
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Direct battery-optimization request unavailable, opening the system list", e)
        val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(listIntent)
        } catch (e2: ActivityNotFoundException) {
            Log.e(TAG, "No battery-optimization settings screen available on this device", e2)
        }
    }
}

/**
 * Opens this app's system details screen, the fallback when POST_NOTIFICATIONS
 * was denied without a rationale (permanently denied) - without it the Grant
 * button on the permissions card would be dead after the second denial.
 */
fun AppPermissions.openAppDetailsSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.e(TAG, "No app details settings screen available on this device", e)
    }
}
