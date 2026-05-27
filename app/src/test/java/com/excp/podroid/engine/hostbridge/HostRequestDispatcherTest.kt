package com.excp.podroid.engine.hostbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.excp.podroid.data.repository.PortForwardRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

class HostProtocolTest {
    @Test fun base64RoundTripsUtf8() {
        // Non-ASCII (CJK) + spaces + newline must survive.
        val original = "构建完成 build done\nline2"
        assertEquals(original, HostProtocol.dec(HostProtocol.enc(original)))
    }

    @Test fun decReturnsNullOnGarbage() {
        assertNull(HostProtocol.dec("!!!not base64!!!"))
    }
}

private class FakePoster(var permitted: Boolean = true) : NotificationPoster {
    data class Posted(val title: String?, val body: String, val priority: String, val id: Int?)
    val posts = mutableListOf<Posted>()
    override fun notificationsPermitted() = permitted
    override fun post(title: String?, body: String, priority: String, id: Int?): Int {
        posts.add(Posted(title, body, priority, id))
        return id ?: 7000
    }
}

private fun dispatcher(
    poster: NotificationPoster = FakePoster(),
    rules: MutableList<PortForwardRule> = mutableListOf(),
    openUrl: suspend (String) -> String = { HostProtocol.ok() },
    power: suspend (String) -> String = { HostProtocol.ok() },
    setHeadless: suspend (String) -> String = { HostProtocol.ok() },
) = HostRequestDispatcher(
    notifications = poster,
    addForward = { r -> rules.removeAll { it.hostPort == r.hostPort && it.protocol == r.protocol }; rules.add(r) },
    removeForward = { r -> rules.removeAll { it.hostPort == r.hostPort && it.protocol == r.protocol } },
    listForwards = { rules.toList() },
    openUrl = openUrl,
    power = power,
    setHeadless = setHeadless,
)

class HostRequestDispatcherTest {
    @Test fun notifyPostsAndReturnsId() = runBlocking {
        val poster = FakePoster()
        val d = dispatcher(poster)
        val resp = d.handle("NOTIFY high 42 ${HostProtocol.enc("Podroid")} ${HostProtocol.enc("done")}")
        assertEquals("OK 42", resp)
        assertEquals(1, poster.posts.size)
        assertEquals("Podroid", poster.posts[0].title)
        assertEquals("done", poster.posts[0].body)
        assertEquals(HostProtocol.PRIO_HIGH, poster.posts[0].priority)
        assertEquals(42, poster.posts[0].id)
    }

    @Test fun notifyWithNoTitleAndNoId() = runBlocking {
        val poster = FakePoster()
        val d = dispatcher(poster)
        val resp = d.handle("NOTIFY normal - - ${HostProtocol.enc("hi")}")
        assertEquals("OK 7000", resp)
        assertEquals(null, poster.posts[0].title)
        assertEquals(null, poster.posts[0].id)
    }

    @Test fun notifyDeniedWhenNotPermitted() = runBlocking {
        val d = dispatcher(FakePoster(permitted = false))
        val resp = d.handle("NOTIFY normal - - ${HostProtocol.enc("hi")}")
        assertTrue(resp.startsWith("ERR "))
        assertEquals("notifications not permitted", HostProtocol.dec(resp.removePrefix("ERR ")))
    }

    @Test fun notifyRejectsBadPriority() = runBlocking {
        val d = dispatcher()
        assertTrue(d.handle("NOTIFY urgent - - ${HostProtocol.enc("hi")}").startsWith("ERR "))
    }

    @Test fun fwdAddStoresRule() = runBlocking {
        val rules = mutableListOf<PortForwardRule>()
        val d = dispatcher(rules = rules)
        assertEquals("OK", d.handle("FWD-ADD 8080 80 tcp"))
        assertEquals(listOf(PortForwardRule(8080, 80, "tcp")), rules)
    }

    @Test fun fwdAddAcceptsExplicitTcpLine() = runBlocking {
        // The dispatcher takes a fully-formed line; the tcp-defaulting sugar lives
        // in the CLI, so this checks we accept the exact line `podroid-forward 8080 80` emits.
        val rules = mutableListOf<PortForwardRule>()
        assertEquals("OK", dispatcher(rules = rules).handle("FWD-ADD 8080 80 tcp"))
        assertEquals("tcp", rules.single().protocol)
    }

    @Test fun fwdAddRejectsBadPort() = runBlocking {
        assertTrue(dispatcher().handle("FWD-ADD 0 80 tcp").startsWith("ERR "))
        assertTrue(dispatcher().handle("FWD-ADD 70000 80 tcp").startsWith("ERR "))
        assertTrue(dispatcher().handle("FWD-ADD 8080 80 sctp").startsWith("ERR "))
    }

    @Test fun fwdRemoveByHostPortAndProto() = runBlocking {
        val rules = mutableListOf(PortForwardRule(8080, 80, "tcp"), PortForwardRule(9000, 90, "udp"))
        val d = dispatcher(rules = rules)
        assertEquals("OK", d.handle("FWD-REMOVE 8080 tcp"))
        assertEquals(listOf(PortForwardRule(9000, 90, "udp")), rules)
    }

    @Test fun fwdRemoveUnknownIsError() = runBlocking {
        val d = dispatcher(rules = mutableListOf())
        assertTrue(d.handle("FWD-REMOVE 8080 tcp").startsWith("ERR "))
    }

    @Test fun fwdListReturnsBase64Table() = runBlocking {
        val rules = mutableListOf(PortForwardRule(8080, 80, "tcp"), PortForwardRule(9000, 90, "udp"))
        val resp = dispatcher(rules = rules).handle("FWD-LIST")
        assertTrue(resp.startsWith("OK "))
        val table = HostProtocol.dec(resp.removePrefix("OK "))
        assertEquals("8080 80 tcp\n9000 90 udp", table)
    }

    @Test fun pingPongs() = runBlocking {
        assertEquals("PONG", dispatcher().handle("PING"))
    }

    @Test fun unknownIsBadRequest() = runBlocking {
        assertTrue(dispatcher().handle("FROBNICATE x").startsWith("ERR "))
        assertTrue(dispatcher().handle("").startsWith("ERR "))
    }

    @Test fun openDecodesUrlAndDelegates() = runBlocking {
        var seen: String? = null
        val d = dispatcher(openUrl = { seen = it; HostProtocol.ok() })
        val resp = d.handle("OPEN ${HostProtocol.enc("https://example.com")}")
        assertEquals("OK", resp)
        assertEquals("https://example.com", seen)
    }

    @Test fun openRejectsBadEncoding() = runBlocking {
        val resp = dispatcher().handle("OPEN !!!notbase64!!!")
        assertTrue(resp.startsWith("ERR "))
    }

    @Test fun powerValidatesActionAndDelegates() = runBlocking {
        var seen: String? = null
        val d = dispatcher(power = { seen = it; HostProtocol.ok("Running") })
        assertEquals("OK Running", d.handle("POWER status"))
        assertEquals("status", seen)
        assertTrue(d.handle("POWER explode").startsWith("ERR "))
    }

    @Test fun headlessValidatesActionAndDelegates() = runBlocking {
        var seen: String? = null
        val d = dispatcher(setHeadless = { seen = it; HostProtocol.ok() })
        assertEquals("OK", d.handle("HEADLESS on"))
        assertEquals("on", seen)
        assertTrue(d.handle("HEADLESS sideways").startsWith("ERR "))
    }
}
