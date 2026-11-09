package com.excp.podroid.engine.uml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UmlProbeStoreTest {
    private val key = UmlProbeKey("fp-1", 34L, "libuml-4k.so:83120344")

    @Test fun noRecordsIsNotProbed() =
        assertEquals(UmlStatus.NOT_PROBED, umlStatus(key, null, null))

    @Test fun matchingPass() =
        assertEquals(UmlStatus.PASSED, umlStatus(key, UmlProbeRecord(key, true, ""), null))

    @Test fun matchingFail() =
        assertEquals(UmlStatus.FAILED, umlStatus(key, UmlProbeRecord(key, false, "x"), null))

    @Test fun versionCodeChangedIsNotProbed() {
        val old = key.copy(versionCode = 33L)
        assertEquals(UmlStatus.NOT_PROBED, umlStatus(key, UmlProbeRecord(old, true, ""), null))
    }

    @Test fun fingerprintChangedIsNotProbed() {
        val old = key.copy(buildFingerprint = "fp-0")
        assertEquals(UmlStatus.NOT_PROBED, umlStatus(key, UmlProbeRecord(old, true, ""), null))
    }

    @Test fun matchingDemotionWins() {
        val d = UmlDemotion(key, "crash", 1L)
        assertEquals(UmlStatus.DEMOTED, umlStatus(key, UmlProbeRecord(key, true, ""), d))
    }

    @Test fun staleDemotionIgnored() {
        val d = UmlDemotion(key.copy(versionCode = 33L), "crash", 1L)
        assertEquals(UmlStatus.PASSED, umlStatus(key, UmlProbeRecord(key, true, ""), d))
    }

    @Test fun probeRoundTrip() {
        val r = UmlProbeRecord(key, false, "bad | pipe 中文测试")
        assertEquals(r, decodeProbeRecord(encodeProbeRecord(r)))
    }

    @Test fun demotionRoundTrip() {
        val d = UmlDemotion(key, "a|b 中文", 1234567890123L)
        assertEquals(d, decodeDemotion(encodeDemotion(d)))
    }

    @Test fun garbageDecodesToNull() {
        assertNull(decodeProbeRecord("garbage"))
        assertNull(decodeProbeRecord(""))
        assertNull(decodeProbeRecord("a|notalong|c|true|!!!"))
        assertNull(decodeDemotion("garbage"))
        assertNull(decodeDemotion("a|b|c"))
        assertNull(decodeDemotion("a|1|c|notnum|!!!"))
    }
}
