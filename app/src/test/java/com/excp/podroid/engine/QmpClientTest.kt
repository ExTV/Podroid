/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

import com.excp.podroid.engine.QmpClient.Companion.QmpVerdict
import com.excp.podroid.engine.QmpClient.Companion.classifyQmpFields
import com.excp.podroid.engine.QmpClient.Companion.readQmpResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

/**
 * Pins QMP response classification. Port-forward commands run via
 * `human-monitor-command`, whose failures arrive as a {"return":"<error text>"}
 * envelope (no QMP-level error, no exception), so a naive
 * Result.success(JSONObject(line)) reports a failed forward as applied.
 *
 * The reader tests use plain string classifiers so they run as JVM unit tests;
 * the pure [classifyQmpFields] tests cover the protocol classification rules.
 */
class QmpClientTest {

    /** Keep reader tests on the plain JVM; field classification is covered below. */
    private fun classifyTestResponse(line: String): Result<String>? = when {
        line.contains("\"event\"") -> null
        line.contains("\"error\"") -> Result.failure(RuntimeException("QMP error"))
        else -> Result.success(line)
    }

    @Test
    fun `reader skips events for capability and command replies`() {
        val reader = BufferedReader(StringReader(
            """
            {"event":"RESET"}
            {"return":{}}
            {"event":"RESUME"}
            {"return":{"ok":true}}
            """.trimIndent()
        ))

        val capabilities = readQmpResponse(reader, "qmp_capabilities", ::classifyTestResponse)
        val command = readQmpResponse(reader, "test-command", ::classifyTestResponse)

        assertEquals("{\"return\":{}}", capabilities.getOrThrow())
        assertEquals("{\"return\":{\"ok\":true}}", command.getOrThrow())
    }

    @Test
    fun `capability ack is consumed separately from following command error`() {
        val reader = BufferedReader(StringReader(
            """
            {"return":{}}
            {"error":{"class":"GenericError","desc":"bad command"}}
            """.trimIndent()
        ))

        assertTrue(readQmpResponse(reader, "qmp_capabilities", ::classifyTestResponse).isSuccess)
        val command = readQmpResponse(reader, "test-command", ::classifyTestResponse)

        assertTrue(command.isFailure)
        assertEquals("QMP error", command.exceptionOrNull()?.message)
    }

    @Test
    fun `top-level error envelope is a failure`() {
        // {"error":{"class":"GenericError","desc":"bad command"}}
        val verdict = classifyQmpFields(hasError = true, hasEvent = false, returnValue = null)
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `successful return object is a success`() {
        // {"return":{}}
        val verdict = classifyQmpFields(hasError = false, hasEvent = false, returnValue = emptyMap<String, Any>())
        assertEquals(QmpVerdict.Success, verdict)
    }

    @Test
    fun `async event line is skipped, not treated as a response`() {
        // {"event":"SHUTDOWN",...} — keep reading for the real reply.
        val verdict = classifyQmpFields(hasError = false, hasEvent = true, returnValue = null)
        assertEquals(QmpVerdict.SkipEvent, verdict)
    }

    @Test
    fun `human-monitor return carrying a hostfwd error is a failure`() {
        // hostfwd_add on a busy port: {"return":"could not set up host forwarding rule ..."}
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "could not set up host forwarding rule 'tcp::8080-:80'\r\n",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor return with capitalized Could not is a failure`() {
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "Could not set up host forwarding rule 'tcp::8080-:80'",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor return with lowercase could not is a failure`() {
        // Casing is not guaranteed across QEMU/SLIRP versions; a lowercase
        // "could not ..." that isn't the "set up" phrasing must still classify
        // as a failure rather than silently reporting the forward as applied.
        val verdict = classifyQmpFields(
            hasError = false,
            hasEvent = false,
            returnValue = "could not find a free port for host forwarding rule",
        )
        assertTrue(verdict is QmpVerdict.Failure)
    }

    @Test
    fun `human-monitor empty return string is a success`() {
        // A successful hostfwd_add returns an empty string.
        val verdict = classifyQmpFields(hasError = false, hasEvent = false, returnValue = "")
        assertEquals(QmpVerdict.Success, verdict)
    }

    @Test
    fun `reader returns capability error without consuming next line`() {
        val reader = BufferedReader(StringReader(
            "{" +
                "\"error\":{\"class\":\"GenericError\",\"desc\":\"capabilities rejected\"}}\n" +
                "{\"return\":{}}\n"
        ))

        assertTrue(readQmpResponse(reader, "qmp_capabilities", ::classifyTestResponse).isFailure)
        assertTrue(readQmpResponse(reader, "test-command", ::classifyTestResponse).isSuccess)
    }

    @Test
    fun `reader returns failure on EOF after events`() {
        val reader = BufferedReader(StringReader("{\"event\":\"RESET\"}\n"))

        val result = readQmpResponse(reader, "test-command", ::classifyTestResponse)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("connection closed") == true)
    }

    @Test
    fun `event takes precedence is not consulted when error present`() {
        // Defensive: an error envelope is terminal even if an event key co-occurs.
        val verdict = classifyQmpFields(hasError = true, hasEvent = true, returnValue = null)
        assertTrue(verdict is QmpVerdict.Failure)
    }
}
