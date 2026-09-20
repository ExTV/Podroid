package com.excp.podroid.engine.avf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class AvfTcpRelayTest {
    @Test
    fun request_half_close_still_delivers_delayed_reply() = runBlocking {
        val pair = RelaySockets.open()
        val relay = BidirectionalRelay(
            SocketRelayEndpoint(pair.left.endpoint),
        )
        val right = SocketRelayEndpoint(pair.right.endpoint)
        assertTrue(relay.attach(right))
        val job = launch(Dispatchers.Default) { relay.run() }
        try {
            pair.left.peer.outputStream.apply {
                write("request".toByteArray())
                flush()
            }
            pair.left.peer.shutdownOutput()

            withContext(Dispatchers.IO) {
                assertArrayEquals("request".toByteArray(), pair.right.peer.inputStream.readExactly(7))
                assertEquals(-1, pair.right.peer.inputStream.read())
                pair.right.peer.outputStream.apply {
                    write("delayed reply".toByteArray())
                    flush()
                }
                pair.right.peer.shutdownOutput()
            }

            withContext(Dispatchers.IO) {
                assertArrayEquals("delayed reply".toByteArray(), pair.left.peer.inputStream.readExactly(13))
                assertEquals(-1, pair.left.peer.inputStream.read())
            }
            withTimeout(3_000) { job.join() }
        } finally {
            relay.abort()
            job.cancelAndJoin()
            pair.close()
        }
    }

    @Test
    fun both_directions_can_half_close_symmetrically() = runBlocking {
        val pair = RelaySockets.open()
        val relay = BidirectionalRelay(SocketRelayEndpoint(pair.left.endpoint))
        assertTrue(relay.attach(SocketRelayEndpoint(pair.right.endpoint)))
        val job = launch(Dispatchers.Default) { relay.run() }
        try {
            withContext(Dispatchers.IO) {
                pair.left.peer.outputStream.apply { write("left".toByteArray()); flush() }
                pair.left.peer.shutdownOutput()
                pair.right.peer.outputStream.apply { write("right".toByteArray()); flush() }
                pair.right.peer.shutdownOutput()

                assertArrayEquals("left".toByteArray(), pair.right.peer.inputStream.readExactly(4))
                assertEquals(-1, pair.right.peer.inputStream.read())
                assertArrayEquals("right".toByteArray(), pair.left.peer.inputStream.readExactly(5))
                assertEquals(-1, pair.left.peer.inputStream.read())
            }
            withTimeout(3_000) { job.join() }
        } finally {
            relay.abort()
            job.cancelAndJoin()
            pair.close()
        }
    }

    @Test
    fun external_abort_closes_both_idle_sockets() = runBlocking {
        val pair = RelaySockets.open()
        val relay = BidirectionalRelay(SocketRelayEndpoint(pair.left.endpoint))
        assertTrue(relay.attach(SocketRelayEndpoint(pair.right.endpoint)))
        val job = launch(Dispatchers.Default) { relay.run() }
        try {
            relay.abort()
            withTimeout(3_000) { job.join() }
            assertTrue(pair.left.endpoint.isClosed)
            assertTrue(pair.right.endpoint.isClosed)
        } finally {
            relay.abort()
            job.cancelAndJoin()
            pair.close()
        }
    }

    @Test
    fun cancellation_closes_both_idle_sockets() = runBlocking {
        val pair = RelaySockets.open()
        val relay = BidirectionalRelay(SocketRelayEndpoint(pair.left.endpoint))
        assertTrue(relay.attach(SocketRelayEndpoint(pair.right.endpoint)))
        val job = launch(Dispatchers.Default) { relay.run() }
        try {
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(pair.left.endpoint.isClosed)
            assertTrue(pair.right.endpoint.isClosed)
        } finally {
            relay.abort()
            job.cancelAndJoin()
            pair.close()
        }
    }

    @Test
    fun hard_peer_error_aborts_the_other_side() = runBlocking {
        val pair = RelaySockets.open()
        val relay = BidirectionalRelay(SocketRelayEndpoint(pair.left.endpoint))
        assertTrue(relay.attach(SocketRelayEndpoint(pair.right.endpoint)))
        val job = launch(Dispatchers.Default) { relay.run() }
        try {
            pair.right.peer.setSoLinger(true, 0)
            pair.right.peer.close()
            withTimeout(3_000) { job.join() }
            assertTrue(pair.left.endpoint.isClosed)
            assertTrue(pair.right.endpoint.isClosed)
        } finally {
            relay.abort()
            job.cancelAndJoin()
            pair.close()
        }
    }

    private data class RelaySocket(val endpoint: Socket, val peer: Socket)

    private class RelaySockets(
        val left: RelaySocket,
        val right: RelaySocket,
    ) {
        fun close() {
            listOf(left.endpoint, left.peer, right.endpoint, right.peer).forEach {
                runCatching { it.close() }
            }
        }

        companion object {
            fun open(): RelaySockets = RelaySockets(openPair(), openPair())

            private fun openPair(): RelaySocket {
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                    val peer = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
                    val endpoint = listener.accept()
                    endpoint.soTimeout = 2_000
                    peer.soTimeout = 2_000
                    return RelaySocket(endpoint, peer)
                }
            }
        }
    }
}

private fun InputStream.readExactly(length: Int): ByteArray {
    val result = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = read(result, offset, length - offset)
        if (count < 0) error("unexpected EOF after $offset bytes")
        offset += count
    }
    return result
}
