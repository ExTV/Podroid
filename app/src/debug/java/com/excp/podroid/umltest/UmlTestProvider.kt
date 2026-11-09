/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * adb-only driver for UmlTestRunner (debug builds only):
 *
 *   C="adb shell content call --uri content://com.excp.podroid.debug.umltest --method"
 *   $C start --arg "net mem=2048M ncpus=4 seccomp=on ubd0=rootfs.ext4 ..."
 *   $C send --arg "uname -a"          one line, newline appended
 *   $C send-file --arg bench.sh       a file in files/uml, sent verbatim
 *   $C mconsole --arg "halt"          UML mconsole command (version, proc X, sysrq X, cad, halt, config ...)
 *   $C status | signal --arg 15 | destroy | kill-tree
 *
 * The kernel always runs under libqnet.so; a leading "net" word in start's
 * arg gives it a SLIRP network. Words "env:KEY=VALUE" become environment for
 * qnet (QNET_CHANNELS=N, QNET_USER_OPTS=hostfwd=...).
 * Same caller gate as ControlProvider: shell, root, or DUMP.
 */
package com.excp.podroid.umltest

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

class UmlTestProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return reply("error no-context")
        val uid = Binder.getCallingUid()
        val dump = ctx.checkCallingPermission(android.Manifest.permission.DUMP) ==
            PackageManager.PERMISSION_GRANTED
        if (uid != Process.ROOT_UID && uid != Process.SHELL_UID && !dump) {
            return reply("error denied")
        }
        return try {
            reply(
                when (method) {
                    "start" -> {
                        val words = arg.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                        val net = words.firstOrNull() == "net"
                        val rest = if (net) words.drop(1) else words
                        val env = rest.filter { it.startsWith("env:") && it.contains('=') }
                            .associate { it.removePrefix("env:").substringBefore('=') to it.substringAfter('=') }
                        UmlTestRunner.start(ctx, rest.filterNot { it.startsWith("env:") }, net, env)
                    }
                    "send" -> UmlTestRunner.send(arg.orEmpty() + "\n")
                    "send-file" -> UmlTestRunner.sendFile(ctx, arg.orEmpty())
                    "mconsole" -> UmlTestRunner.mconsole(ctx, arg.orEmpty())
                    "signal" -> UmlTestRunner.signal(arg?.toIntOrNull() ?: 15)
                    "destroy" -> UmlTestRunner.destroy()
                    "kill-tree" -> UmlTestRunner.killTree()
                    "status" -> UmlTestRunner.status(ctx)
                    else -> "error unknown-method"
                }
            )
        } catch (e: Exception) {
            Log.w("PodroidUmlTest", "call failed: method=$method", e)
            reply("error ${e.message ?: "internal error"}")
        }
    }

    private fun reply(result: String): Bundle = Bundle().apply { putString("result", result) }

    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
