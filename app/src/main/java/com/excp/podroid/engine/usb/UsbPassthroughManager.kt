/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * USB device passthrough into the QEMU guest.
 *
 * An unprivileged Android app cannot reach the /dev/bus/usb device nodes
 * directly, so QEMU's usb-host backend can never open one itself. Instead this
 * manager goes through the only channel Android offers: UsbManager hands back an
 * already-open file descriptor (UsbDeviceConnection.getFileDescriptor()), and
 * that fd is streamed to QEMU over the QMP control socket as SCM_RIGHTS
 * ancillary data (`add-fd`). QEMU then hot-plugs it with
 * `device_add usb-host,hostdevice=/dev/fdset/<id>`.
 *
 * Everything happens at runtime while the VM is up — there is no manifest
 * <receiver> with a device_filter.xml. The BroadcastReceiver is registered in
 * code only for the duration the VM is Running, so a device can only ever be
 * handed over via a live broadcast while the app itself is running.
 */
package com.excp.podroid.engine.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class UsbPassthroughManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VmEngine,
) {

    /** A device currently handed over to the guest. */
    private data class Entry(
        val connection: UsbDeviceConnection,
        val fdSetId: Int,
        val qemuId: String,
    )

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e(TAG, "USB passthrough coroutine failed", e) }
    )
    private val mutex = Mutex()

    /** Keyed by UsbDevice.deviceName (the stable /dev/bus/usb/... path). */
    private val active = ConcurrentHashMap<String, Entry>()

    @Volatile private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        scope.launch { attach(device) }
                    } else {
                        Log.i(TAG, "USB permission denied for ${device.deviceName}")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> requestAndAttach(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scope.launch { detach(device.deviceName) }
            }
        }
    }

    /**
     * Begin watching for USB devices and pass through anything already plugged
     * in. Called once the VM reaches the Running state. Idempotent.
     */
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "USB passthrough armed — scanning ${usbManager.deviceList.size} connected device(s)")
        for (device in usbManager.deviceList.values) requestAndAttach(device)
    }

    /**
     * Stop watching and release every device. Called when the VM leaves the
     * Running state. The QEMU process is being torn down anyway, so the guest
     * side needs no device_del — just unwind the Android-side handles.
     */
    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        val entries = active.values.toList()
        active.clear()
        for (e in entries) runCatching { e.connection.close() }
        // Cancel in-flight attach/detach work but keep the scope itself alive:
        // a later start() (VM stop, then restart within one process) re-arms the
        // receiver and must be able to launch again. scope.cancel() would leave
        // a dead Job behind and silently no-op every future attach.
        scope.coroutineContext.cancelChildren()
        Log.d(TAG, "USB passthrough disarmed — released ${entries.size} device(s)")
    }

    private fun requestAndAttach(device: UsbDevice) {
        if (active.containsKey(device.deviceName)) return
        if (usbManager.hasPermission(device)) {
            scope.launch { attach(device) }
        } else {
            // No manifest device filter — we ask for consent explicitly. The
            // result comes back through `receiver` as ACTION_USB_PERMISSION.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            usbManager.requestPermission(device, pending)
        }
    }

    private suspend fun attach(device: UsbDevice) {
        mutex.withLock {
            if (!started || engine.state.value !is VmState.Running) return
            if (active.containsKey(device.deviceName)) return

            val qmp = engine.qmpClient
            if (qmp == null) {
                Log.w(TAG, "No QMP client — USB passthrough needs the QEMU backend")
                return
            }
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                Log.w(TAG, "openDevice() failed for ${device.deviceName}")
                return
            }

            // fromFd() dups the UsbDeviceConnection fd into one we own. add-fd
            // sends it to QEMU over SCM_RIGHTS, which installs an independent fd
            // in QEMU's table referring to the same open file; that fd lives in a
            // process-wide fdset that outlives this monitor socket. So once addFd
            // returns, QEMU has its own copy and we close this local dup. The
            // UsbDeviceConnection itself stays open in `active` until detach().
            val pfd = ParcelFileDescriptor.fromFd(connection.fileDescriptor)
            try {
                val fdSetId = qmp.addFd(pfd.fileDescriptor).getOrElse { e ->
                    Log.w(TAG, "add-fd failed for ${device.deviceName}", e)
                    connection.close()
                    return
                }
                val qemuId = "podroid_usb_${device.deviceId}"
                val args = JSONObject()
                    .put("driver", "usb-host")
                    .put("id", qemuId)
                    .put("hostdevice", "/dev/fdset/$fdSetId")
                qmp.deviceAdd(args).onFailure { e ->
                    Log.w(TAG, "device_add usb-host failed for ${device.deviceName}", e)
                    qmp.removeFd(fdSetId)
                    connection.close()
                    return
                }
                active[device.deviceName] = Entry(connection, fdSetId, qemuId)
                Log.i(TAG, "Passed USB device ${device.deviceName} -> guest ($qemuId)")
            } finally {
                runCatching { pfd.close() }
            }
        }
    }

    private suspend fun detach(deviceName: String) {
        mutex.withLock {
            val entry = active.remove(deviceName) ?: return
            engine.qmpClient?.let { qmp ->
                qmp.deviceDel(entry.qemuId)
                    .onFailure { Log.w(TAG, "device_del failed for $deviceName (${entry.qemuId})", it) }
                qmp.removeFd(entry.fdSetId)
                    .onFailure { Log.w(TAG, "remove-fd failed for fdset ${entry.fdSetId}", it) }
            }
            runCatching { entry.connection.close() }
            Log.i(TAG, "Released USB device $deviceName from guest")
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "UsbPassthrough"
        private const val ACTION_USB_PERMISSION = "com.excp.podroid.action.USB_PERMISSION"
    }
}
