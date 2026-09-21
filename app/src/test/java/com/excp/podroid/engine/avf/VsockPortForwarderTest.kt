package com.excp.podroid.engine.avf

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class VsockPortForwarderTest {
    @Test
    fun cancellation_before_connection_body_starts_releases_ownership_once() = runBlocking {
        var aborts = 0
        var removals = 0
        var releases = 0
        val bodyStarted = AtomicBoolean(false)
        val connection: Job = launch(start = CoroutineStart.LAZY) {
            bodyStarted.set(true)
        }
        val completion = RelayJobCompletion(
            abort = { aborts++ },
            remove = { removals++ },
            release = { releases++ },
        )

        completion.attach(connection)
        connection.cancel()
        connection.join()
        completion.cleanup()

        assertFalse(bodyStarted.get())
        assertEquals(1, aborts)
        assertEquals(1, removals)
        assertEquals(1, releases)
    }
}
