/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Minimal QMP (QEMU Machine Protocol) client for runtime VM management.
 * Used for adding/removing port forwards and hot-plugging USB passthrough
 * devices while the VM is running.
 */
package com.excp.podroid.engine

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.InputStreamReader

class QmpClient(private val socketPath: String) {

    companion object {
        private const val TAG = "QmpClient"
        private const val SOCKET_TIMEOUT_MS = 5000

        /** Verdict for one QMP reply line. */
        sealed class QmpVerdict {
            /** Terminal reply: the command succeeded. */
            object Success : QmpVerdict()
            /** Terminal reply: the command failed (carries a human-readable reason). */
            data class Failure(val reason: String) : QmpVerdict()
            /** Async event line — not the reply to our command; keep reading. */
            object SkipEvent : QmpVerdict()
        }

        /**
         * Pure classification of an already-decomposed QMP reply. No org.json /
         * I/O dependency, so it is directly unit-testable.
         *
         *  - top-level `"error"`             → Failure (QMP-level rejection)
         *  - `"event"` key                   → SkipEvent (async event, keep reading)
         *  - `"return"` string carrying a    → Failure: human-monitor-command never
         *    hostfwd error                      sets a QMP-level error, so a busy
         *                                       port / bad spec arrives as return text
         *  - anything else                   → Success
         *
         * @param hasError   whether the reply object has a top-level "error" key
         * @param hasEvent   whether the reply object has an "event" key
         * @param returnValue the value of the "return" key, if present (the String
         *                    body matters for human-monitor-command failures)
         */
        fun classifyQmpFields(hasError: Boolean, hasEvent: Boolean, returnValue: Any?): QmpVerdict {
            if (hasError) return QmpVerdict.Failure("QMP error")
            // Async events (SHUTDOWN, RESET, ...) can interleave with replies.
            if (hasEvent) return QmpVerdict.SkipEvent
            if (returnValue is String && isHostfwdError(returnValue)) {
                return QmpVerdict.Failure("QMP human-monitor error: ${returnValue.trim()}")
            }
            return QmpVerdict.Success
        }

        /**
         * Classify one parsed QMP reply object. Thin org.json adapter over
         * [classifyQmpFields]; returns null for async-event lines so the read
         * loop knows to keep reading for the real reply.
         */
        fun classifyQmpResponse(json: JSONObject): Result<JSONObject>? =
            when (val v = classifyQmpFields(json.has("error"), json.has("event"), json.opt("return"))) {
                is QmpVerdict.Success -> Result.success(json)
                is QmpVerdict.Failure -> Result.failure(RuntimeException(v.reason))
                is QmpVerdict.SkipEvent -> null
            }

        /**
         * Read through asynchronous events until the next terminal QMP reply.
         * A failure (including EOF) is returned to the caller so it can stop
         * before sending any subsequent command.
         */
        internal fun readQmpResponse(reader: BufferedReader, operation: String): Result<JSONObject> =
            readQmpResponse(reader, operation) { line -> classifyQmpResponse(JSONObject(line)) }

        internal fun <T> readQmpResponse(
            reader: BufferedReader,
            operation: String,
            classifyResponse: (String) -> Result<T>?,
        ): Result<T> {
            while (true) {
                val line = reader.readLine()
                    ?: return Result.failure(
                        RuntimeException("QMP connection closed before a reply to $operation")
                    )
                val result = classifyResponse(line)
                if (result != null) return result
            }
        }

        /**
         * human-monitor-command (hostfwd_add/remove) reports failures as plain
         * text in the `"return"` field. Match the QEMU SLIRP/hostfwd error
         * prefixes ("could not set up host forwarding rule", "Could not ...").
         */
        private fun isHostfwdError(returnText: String): Boolean {
            val t = returnText.trim()
            if (t.isEmpty()) return false
            // Robustly case-insensitive: QEMU/SLIRP casing isn't guaranteed
            // across versions, so a single ignoreCase contains() catches both
            // "Could not set up host forwarding rule ..." and lowercase variants
            // rather than mixing an anchored phrase with a case-sensitive prefix.
            return t.contains("could not", ignoreCase = true)
        }
    }

    /**
     * Run one QMP command over a fresh connection. When [sendFd] is non-null it
     * is handed to QEMU as SCM_RIGHTS ancillary data on the command write — the
     * mechanism `add-fd` needs to ingest a file descriptor (e.g. an Android
     * UsbDeviceConnection fd for usb-host passthrough).
     */
    private suspend fun exec(
        command: String,
        arguments: JSONObject?,
        sendFd: FileDescriptor? = null,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM)
                )
                socket.soTimeout = SOCKET_TIMEOUT_MS

                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val out = socket.outputStream

                // Read QMP greeting, then enter command mode.
                Log.v(TAG, "QMP greeting: ${reader.readLine()}")
                out.write("{\"execute\":\"qmp_capabilities\"}\n".toByteArray())
                out.flush()
                val capabilities = readQmpResponse(reader, "qmp_capabilities")
                if (capabilities.isFailure) return@withContext capabilities
                Log.v(TAG, "Capabilities response: ${capabilities.getOrThrow()}")

                val cmd = JSONObject().apply {
                    put("execute", command)
                    if (arguments != null) put("arguments", arguments)
                }
                // The fd (if any) must ride on the SAME write that carries the
                // command JSON: QEMU pairs the SCM_RIGHTS payload with the
                // add-fd command currently being parsed.
                if (sendFd != null) socket.setFileDescriptorsForSend(arrayOf(sendFd))
                out.write((cmd.toString() + "\n").toByteArray())
                out.flush()

                // Read until a terminal reply (return/error), skipping any
                // async {"event":...} lines that interleave with it.
                val result = readQmpResponse(reader, command)
                Log.d(TAG, "Command response ($command): $result")
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "QMP command failed: $command", e)
            Result.failure(e)
        }
    }

    suspend fun execute(command: String, arguments: JSONObject? = null): Result<JSONObject> =
        exec(command, arguments)

    suspend fun addPortForward(
        hostPort: Int,
        guestPort: Int,
        protocol: String = "tcp",
        loopbackOnly: Boolean = false,
    ): Result<JSONObject> {
        // hostaddr empty = 0.0.0.0; 127.0.0.1 keeps a loopback-only rule off the
        // network even when applied live (matches the static buildCommand path).
        val hostAddr = if (loopbackOnly) "127.0.0.1" else ""
        val monitorCmd = "hostfwd_add net0 ${protocol}:${hostAddr}:${hostPort}-:${guestPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }

    suspend fun removePortForward(hostPort: Int, protocol: String = "tcp", loopbackOnly: Boolean = false): Result<JSONObject> {
        // hostfwd_remove must match the hostaddr used at add time.
        val hostAddr = if (loopbackOnly) "127.0.0.1" else ""
        val monitorCmd = "hostfwd_remove net0 ${protocol}:${hostAddr}:${hostPort}"
        return execute(
            "human-monitor-command",
            JSONObject().put("command-line", monitorCmd)
        )
    }

    /**
     * Pass [fd] to QEMU via SCM_RIGHTS and register it in a freshly-created fd
     * set. Returns the new fdset-id, referenceable from device properties as
     * `/dev/fdset/<id>` — used to hot-plug usb-host devices without QEMU ever
     * needing direct access to /dev/bus/usb (which is unreachable to an
     * unprivileged Android app).
     */
    suspend fun addFd(fd: FileDescriptor): Result<Int> =
        exec("add-fd", null, fd).mapCatching {
            it.getJSONObject("return").getInt("fdset-id")
        }

    suspend fun removeFd(fdSetId: Int): Result<JSONObject> =
        execute("remove-fd", JSONObject().put("fdset-id", fdSetId))

    suspend fun deviceAdd(arguments: JSONObject): Result<JSONObject> =
        execute("device_add", arguments)

    suspend fun deviceDel(id: String): Result<JSONObject> =
        execute("device_del", JSONObject().put("id", id))
}
