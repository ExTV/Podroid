package com.excp.podroid.engine.hostbridge

import com.excp.podroid.data.repository.AddRuleResult
import com.excp.podroid.data.repository.PortForwardRule
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRequestServerTest {
    @Test(timeout = 5000) fun stopClosesAReadBlockedTransportExactlyOnce() {
        val scope = testScope()
        try {
            val transport = BlockingTransport()
            val server = HostRequestServer({ transport }, dispatcher(), scope, log = { _, _ -> })
            server.start()
            assertTrue(transport.readStarted.await(1, TimeUnit.SECONDS))

            server.stop()

            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
            awaitChildren(scope)
            assertEquals(1, transport.closeCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun parentCancellationClosesAndFinishesBlockedReadWithoutStop() {
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Default)
        try {
            val transport = BlockingTransport()
            val server = HostRequestServer({ transport }, dispatcher(), scope, log = { _, _ -> })
            server.start()
            assertTrue(transport.readStarted.await(1, TimeUnit.SECONDS))

            parent.cancel()
            runBlocking { withTimeout(1000) { parent.join() } }

            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
            assertTrue(transport.readFinished.await(1, TimeUnit.SECONDS))
            assertEquals(1, transport.closeCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun restartUsesANewGenerationAfterStop() {
        val scope = testScope()
        try {
            val first = BlockingTransport()
            val second = OneShotTransport("OPEN ${HostProtocol.enc("https://example.com")}")
            val opens = AtomicInteger(0)
            val handled = CountDownLatch(1)
            val server = HostRequestServer(
                openTransport = { if (opens.getAndIncrement() == 0) first else second },
                dispatcher = dispatcher(openUrl = { handled.countDown(); HostProtocol.ok() }),
                scope = scope,
                log = { _, _ -> },
            )
            server.start()
            assertTrue(first.readStarted.await(1, TimeUnit.SECONDS))
            server.stop()
            server.start()

            assertTrue(handled.await(1, TimeUnit.SECONDS))
            assertEquals(1, first.closeCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun transportOpenedAfterStopIsClosedAndNotReconnected() {
        val scope = testScope()
        try {
            val openEntered = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val late = OneShotTransport("PING")
            val server = HostRequestServer(
                openTransport = {
                    openEntered.countDown()
                    releaseOpen.await()
                    late
                },
                dispatcher = dispatcher(),
                scope = scope,
                log = { _, _ -> },
            )
            server.start()
            assertTrue(openEntered.await(1, TimeUnit.SECONDS))
            server.stop()
            releaseOpen.countDown()
            awaitChildren(scope)

            assertTrue(late.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, late.closeCount.get())
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun requestReleasedByStopIsNotDispatched() {
        val scope = testScope()
        try {
            val transport = BlockingTransport(resultAfterClose = "OPEN ${HostProtocol.enc("https://example.com")}")
            val dispatched = AtomicInteger(0)
            val server = HostRequestServer(
                openTransport = { transport },
                dispatcher = dispatcher(openUrl = { dispatched.incrementAndGet(); HostProtocol.ok() }),
                scope = scope,
                log = { _, _ -> },
            )
            server.start()
            assertTrue(transport.readStarted.await(1, TimeUnit.SECONDS))
            server.stop()

            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
            assertTrue(transport.readFinished.await(1, TimeUnit.SECONDS))
            awaitChildren(scope)
            assertEquals(0, dispatched.get())
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun ioExceptionFromReadLogsWithoutAThrowable() {
        val scope = testScope()
        try {
            val logged = CountDownLatch(1)
            var loggedMessage: String? = null
            var loggedError: Throwable? = null
            val transport = ThrowingTransport(IOException("read failed: ECONNRESET"))
            val server = HostRequestServer(
                openTransport = { transport },
                dispatcher = dispatcher(),
                scope = scope,
                log = { message, error ->
                    if (message.startsWith("host bridge peer closed")) {
                        loggedMessage = message
                        loggedError = error
                        logged.countDown()
                    }
                },
            )
            server.start()

            assertTrue(logged.await(1, TimeUnit.SECONDS))
            assertEquals("host bridge peer closed: read failed: ECONNRESET", loggedMessage)
            assertEquals(null, loggedError)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun nonIoExceptionFromReadLogsWithItsThrowable() {
        val scope = testScope()
        try {
            val logged = CountDownLatch(1)
            var loggedMessage: String? = null
            var loggedError: Throwable? = null
            val failure = IllegalStateException("boom")
            val transport = ThrowingTransport(failure)
            val server = HostRequestServer(
                openTransport = { transport },
                dispatcher = dispatcher(),
                scope = scope,
                log = { message, error ->
                    if (message.startsWith("host bridge loop error")) {
                        loggedMessage = message
                        loggedError = error
                        logged.countDown()
                    }
                },
            )
            server.start()

            assertTrue(logged.await(1, TimeUnit.SECONDS))
            assertEquals("host bridge loop error: boom", loggedMessage)
            assertEquals(failure, loggedError)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun protocolViolationFromReadLogsWithItsThrowable() {
        val scope = testScope()
        try {
            val logged = CountDownLatch(1)
            var loggedMessage: String? = null
            var loggedError: Throwable? = null
            val failure = RequestLineTooLongException()
            val transport = ThrowingTransport(failure)
            val server = HostRequestServer(
                openTransport = { transport },
                dispatcher = dispatcher(),
                scope = scope,
                log = { message, error ->
                    if (message.startsWith("host bridge loop error")) {
                        loggedMessage = message
                        loggedError = error
                        logged.countDown()
                    }
                },
            )
            server.start()

            assertTrue(logged.await(1, TimeUnit.SECONDS))
            assertEquals("host bridge loop error: ${failure.message}", loggedMessage)
            assertEquals(failure, loggedError)
        } finally {
            scope.cancel()
        }
    }

    private fun awaitChildren(scope: CoroutineScope) = runBlocking {
        withTimeout(1000) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
        assertTrue(scope.coroutineContext[Job]!!.children.none())
    }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun dispatcher(
        openUrl: suspend (String) -> String = { HostProtocol.ok() },
    ) = HostRequestDispatcher(
        notifications = object : NotificationPoster {
            override fun notificationsPermitted() = true
            override fun post(title: String?, body: String, priority: String, id: Int?) = id ?: 1
        },
        addForward = { AddRuleResult.ADDED },
        removeForward = {},
        listForwards = { emptyList<PortForwardRule>() },
        clearForwards = { 0 },
        openUrl = openUrl,
        power = { HostProtocol.ok() },
        setHeadless = { HostProtocol.ok() },
    )

    private class BlockingTransport(private val resultAfterClose: String? = null) : HostTransport {
        val readStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val readFinished = CountDownLatch(1)
        val closeCount = AtomicInteger(0)

        override fun readRequest(): String? {
            readStarted.countDown()
            closed.await(2, TimeUnit.SECONDS)
            readFinished.countDown()
            return resultAfterClose
        }

        override fun writeResponse(line: String) = Unit

        override fun close() {
            if (closeCount.incrementAndGet() == 1) closed.countDown()
        }
    }

    private class OneShotTransport(private val request: String) : HostTransport {
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        private val returned = AtomicInteger(0)

        override fun readRequest(): String? = if (returned.getAndIncrement() == 0) request else null
        override fun writeResponse(line: String) = Unit
        override fun close() {
            if (closeCount.incrementAndGet() == 1) closed.countDown()
        }
    }
}

    /** Throws [failure] from its first read, then reports EOF so the loop does not spin. */
    private class ThrowingTransport(private val failure: Exception) : HostTransport {
        val closed = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        private val returned = AtomicInteger(0)

        override fun readRequest(): String? {
            if (returned.getAndIncrement() == 0) throw failure
            return null
        }

        override fun writeResponse(line: String) = Unit
        override fun close() {
            if (closeCount.incrementAndGet() == 1) closed.countDown()
        }
    }
