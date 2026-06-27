/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 *
 * Unit tests for pure logic extracted from UpdateRepository.
 */
package com.excp.podroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for shouldCheck and isNewer — both are pure functions that can be
 * exercised without Android context or DataStore.
 */
class UpdateRepositoryTest {

    // ------------------------------------------------------------------
    // shouldCheck (cache clock uses currentTimeMillis, not uptimeMillis)
    // ------------------------------------------------------------------

    @Test
    fun `shouldCheck returns true when cache is older than validity window`() {
        val validity = 24 * 60 * 60 * 1000L
        val lastCheck = 1_000_000L
        val now = lastCheck + validity + 1L
        assertTrue(shouldCheck(now, lastCheck, validity))
    }

    @Test
    fun `shouldCheck returns false when cache is within validity window`() {
        val validity = 24 * 60 * 60 * 1000L
        val lastCheck = 1_000_000L
        val now = lastCheck + validity - 1L
        assertFalse(shouldCheck(now, lastCheck, validity))
    }

    @Test
    fun `shouldCheck treats a negative delta as stale (reboot case)`() {
        // After reboot, now (small uptimeMillis) minus a large pre-shutdown
        // currentTimeMillis would give a huge negative number with the old
        // SystemClock.uptimeMillis() approach. With currentTimeMillis the
        // negative case still represents a clock going backwards: treat as stale.
        val validity = 24 * 60 * 60 * 1000L
        val lastCheck = 9_999_999L
        val now = 1L // simulates a clock that was reset or went backwards
        assertTrue(shouldCheck(now, lastCheck, validity))
    }

    @Test
    fun `shouldCheck returns true when lastCheck is zero and now exceeds validity`() {
        val validity = 24 * 60 * 60 * 1000L
        // 0 = never checked; now is more than 24h past epoch, so check is due.
        assertTrue(shouldCheck(validity + 1L, 0L, validity))
    }

    // ------------------------------------------------------------------
    // backoffStamp (transient failures earn a short retry floor, not 24h)
    // ------------------------------------------------------------------

    @Test
    fun `backoffStamp on a transient failure re-opens the gate after the retry floor`() {
        val validity = 24 * 60 * 60 * 1000L
        val floor = 20 * 60 * 1000L
        val now = 1_700_000_000_000L
        val stamp = backoffStamp(now, transient = true, validity, floor)
        // Gate stays closed until the floor elapses, then re-opens — not a full day.
        assertFalse(shouldCheck(now + floor - 1L, stamp, validity))
        assertTrue(shouldCheck(now + floor, stamp, validity))
        // And it would NOT have re-opened this early under the full validity window.
        assertFalse(shouldCheck(now + floor, now, validity))
    }

    @Test
    fun `backoffStamp on a definitive failure backs off the full validity window`() {
        val validity = 24 * 60 * 60 * 1000L
        val floor = 20 * 60 * 1000L
        val now = 1_700_000_000_000L
        val stamp = backoffStamp(now, transient = false, validity, floor)
        assertEquals(now, stamp)
        assertFalse(shouldCheck(now + validity - 1L, stamp, validity))
        assertTrue(shouldCheck(now + validity, stamp, validity))
    }

    // ------------------------------------------------------------------
    // isNewer (prerelease suffix numeric comparison)
    // ------------------------------------------------------------------

    @Test
    fun `isNewer rc10 greater than rc9 (numeric suffix comparison)`() {
        assertTrue(isNewer("1.2.0-rc10", "1.2.0-rc9"))
    }

    @Test
    fun `isNewer rc9 not greater than rc10`() {
        assertFalse(isNewer("1.2.0-rc9", "1.2.0-rc10"))
    }

    @Test
    fun `isNewer release greater than prerelease with same numeric core`() {
        assertTrue(isNewer("1.2.0", "1.2.0-rc1"))
    }

    @Test
    fun `isNewer prerelease not greater than release`() {
        assertFalse(isNewer("1.2.0-rc1", "1.2.0"))
    }

    @Test
    fun `isNewer higher patch version is newer`() {
        assertTrue(isNewer("1.2.1", "1.2.0"))
    }

    @Test
    fun `isNewer same version is not newer`() {
        assertFalse(isNewer("1.2.0", "1.2.0"))
    }

    @Test
    fun `isNewer higher minor version is newer`() {
        assertTrue(isNewer("1.3.0", "1.2.9"))
    }

    @Test
    fun `isNewer higher major version is newer`() {
        assertTrue(isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `isNewer lower version is not newer`() {
        assertFalse(isNewer("1.2.0", "1.2.1"))
    }

    @Test
    fun `isNewer rc2 greater than rc1`() {
        assertTrue(isNewer("1.2.0-rc2", "1.2.0-rc1"))
    }

    @Test
    fun `isNewer same prerelease is not newer`() {
        assertFalse(isNewer("1.2.0-rc1", "1.2.0-rc1"))
    }

    @Test
    fun `isNewer handles an overflowing numeric prerelease chunk without crashing`() {
        // A >18-digit chunk would overflow String.toLong(); must not throw.
        assertFalse(isNewer("1.2.0-rc1", "1.2.0-rc99999999999999999999"))
        assertTrue(isNewer("1.2.0-rc99999999999999999999", "1.2.0-rc1"))
    }
}
