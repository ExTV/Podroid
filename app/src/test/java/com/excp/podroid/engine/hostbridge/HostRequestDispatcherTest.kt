package com.excp.podroid.engine.hostbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.excp.podroid.data.repository.AddRuleResult
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
    setContainerCount: suspend (Int) -> Unit = {},
    addResult: AddRuleResult = AddRuleResult.ADDED,
) = HostRequestDispatcher(
    notifications = poster,
    addForward = { r ->
        if (addResult == AddRuleResult.ADDED) {
            rules.removeAll { it.hostPort == r.hostPort && it.protocol == r.protocol }
            rules.add(r)
        }
        addResult
    },
    removeForward = { r -> rules.removeAll { it.hostPort == r.hostPort && it.protocol == r.protocol } },
    listForwards = { rules.toList() },
    clearForwards = { val n = rules.size; rules.clear(); n },
    openUrl = openUrl,
    power = power,
    setHeadless = setHeadless,
    setContainerCount = setContainerCount,
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

    @Test fun fwdAddReportsARefusedRuleInsteadOfOk() = runBlocking {
        // The CLI printing OK for a rule the repository dropped is how a user ends
        // up believing a thousand forwards exist when the table stopped taking them.
        val rules = mutableListOf<PortForwardRule>()
        val resp = dispatcher(rules = rules, addResult = AddRuleResult.TABLE_FULL)
            .handle("FWD-ADD 8080 80 tcp")
        assertTrue(resp.startsWith("ERR "))
        // The reason rides base64 like every other free-text field on this wire.
        assertTrue(HostProtocol.dec(resp.removePrefix("ERR "))!!.contains("full"))
        assertEquals(0, rules.size)
    }

    @Test fun fwdAddReportsAReservedPort() = runBlocking {
        val resp = dispatcher(addResult = AddRuleResult.RESERVED).handle("FWD-ADD 5900 5900 tcp")
        assertTrue(resp.startsWith("ERR "))
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

    @Test fun fwdCleanRemovesAllAndReportsCount() = runBlocking {
        val rules = mutableListOf(PortForwardRule(8080, 80, "tcp"), PortForwardRule(9000, 90, "udp"))
        val d = dispatcher(rules = rules)
        assertEquals("OK removed 2", d.handle("FWD-CLEAN"))
        assertEquals(emptyList<PortForwardRule>(), rules)
    }

    @Test fun fwdCleanRejectsExtraTokens() = runBlocking {
        val rules = mutableListOf(PortForwardRule(8080, 80, "tcp"))
        val d = dispatcher(rules = rules)
        assertTrue(d.handle("FWD-CLEAN extra").startsWith("ERR "))
        // Rejected request must not have cleared anything.
        assertEquals(listOf(PortForwardRule(8080, 80, "tcp")), rules)
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

    @Test fun statsPersistsAndReturnsOk() = runBlocking {
        var seen: Int? = null
        val d = dispatcher(setContainerCount = { seen = it })
        assertEquals("OK", d.handle("STATS containers=3"))
        assertEquals(3, seen)
    }

    @Test fun statsRejectsMissingArg() = runBlocking {
        var seen: Int? = null
        val d = dispatcher(setContainerCount = { seen = it })
        assertTrue(d.handle("STATS").startsWith("ERR "))
        assertEquals(null, seen)
    }

    @Test fun statsRejectsNonNumeric() = runBlocking {
        var seen: Int? = null
        val d = dispatcher(setContainerCount = { seen = it })
        assertTrue(d.handle("STATS containers=abc").startsWith("ERR "))
        assertEquals(null, seen)
    }

    @Test fun statsRejectsNegative() = runBlocking {
        var seen: Int? = null
        val d = dispatcher(setContainerCount = { seen = it })
        assertTrue(d.handle("STATS containers=-1").startsWith("ERR "))
        assertEquals(null, seen)
    }

    @Test fun statsRejectsOverRange() = runBlocking {
        var seen: Int? = null
        val d = dispatcher(setContainerCount = { seen = it })
        assertTrue(d.handle("STATS containers=100001").startsWith("ERR "))
        assertEquals(null, seen)
    }

    @Test fun statsRejectsWrongKeyName() = runBlocking {
        var seen: Int? = null
        val d = dispatcher(setContainerCount = { seen = it })
        assertTrue(d.handle("STATS count=3").startsWith("ERR "))
        assertEquals(null, seen)
    }
}
