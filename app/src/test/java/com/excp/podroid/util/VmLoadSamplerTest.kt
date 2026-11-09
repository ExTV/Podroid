package com.excp.podroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmLoadSamplerTest {

    /** Sampler with injected clock/tick sources so the math is deterministic off-device. */
    private fun sampler(
        clockHz: Long = 100L,
        now: () -> Long,
        ticks: (Int) -> Long?,
    ) = VmLoadSampler(clockHz = clockHz, nowMs = now, ticksReader = ticks)

    @Test
    fun sampleCpuPercent_returnsNullOnFirstSample() {
        val s = sampler(now = { 1_000L }, ticks = { 500L })
        assertNull(s.sampleCpuPercent(pid = 42, vmCpus = 2))
    }

    @Test
    fun sampleCpuPercent_returnsNullWhenProcUnreadable() {
        val s = sampler(now = { 1_000L }, ticks = { null })
        assertNull(s.sampleCpuPercent(pid = 42, vmCpus = 2))
    }

    @Test
    fun sampleCpuPercent_computesNormalizedLoad() {
        var now = 1_000L
        var ticks = 100L
        val s = sampler(now = { now }, ticks = { ticks })

        assertNull(s.sampleCpuPercent(pid = 42, vmCpus = 2)) // warm-up sample

        // +200 ticks over 1.0s at 100Hz = 2.0 cores used; on 2 vCPUs -> 100%.
        now = 2_000L
        ticks = 300L
        assertEquals(100f, s.sampleCpuPercent(pid = 42, vmCpus = 2)!!, 0.01f)
    }

    @Test
    fun sampleCpuPercent_halfLoadOnOneOfTwoCores() {
        var now = 0L
        var ticks = 0L
        val s = sampler(now = { now }, ticks = { ticks })

        assertNull(s.sampleCpuPercent(pid = 1, vmCpus = 2)) // warm-up

        // +100 ticks over 1.0s = 1.0 core used; on 2 vCPUs -> 50%.
        now = 1_000L
        ticks = 100L
        assertEquals(50f, s.sampleCpuPercent(pid = 1, vmCpus = 2)!!, 0.01f)
    }

    @Test
    fun sampleCpuPercent_clampsAboveFullLoad() {
        var now = 0L
        var ticks = 0L
        val s = sampler(now = { now }, ticks = { ticks })

        assertNull(s.sampleCpuPercent(pid = 1, vmCpus = 1)) // warm-up

        // 4 cores of work reported on a single vCPU still clamps to 100%.
        now = 1_000L
        ticks = 400L
        assertEquals(100f, s.sampleCpuPercent(pid = 1, vmCpus = 1)!!, 0.01f)
    }

    @Test
    fun reset_clearsWarmup() {
        var now = 0L
        val s = sampler(now = { now }, ticks = { 100L })

        assertNull(s.sampleCpuPercent(1, 2)) // warm-up
        now = 1_000L
        s.reset()
        // After reset the next sample is a warm-up again, so it returns null.
        assertNull(s.sampleCpuPercent(1, 2))
    }

    @Test
    fun readProcessCpuTicks_returnsNullForInvalidPid() {
        assertNull(VmLoadSampler.readProcessCpuTicks(-1))
    }

    @Test
    fun sampleCpuPercent_wholeTreeReadsTheTreeTicks() {
        var now = 1_000L
        var tree = 0L
        val s = VmLoadSampler(
            clockHz = 100L,
            nowMs = { now },
            ticksReader = { 0L }, // supervisor alone stays idle (UML)
            treeTicksReader = { tree },
        )
        assertNull(s.sampleCpuPercent(pid = 42, vmCpus = 2, wholeTree = true))
        now = 2_000L
        tree = 100L // one core busy across the kernel and stub processes
        assertEquals(50f, s.sampleCpuPercent(pid = 42, vmCpus = 2, wholeTree = true)!!, 0.01f)
    }

    @Test
    fun sampleCpuPercent_defaultReadsOnlyThePid() {
        var now = 1_000L
        var own = 0L
        val s = VmLoadSampler(clockHz = 100L, nowMs = { now }, ticksReader = { own }, treeTicksReader = { 999_999L })
        assertNull(s.sampleCpuPercent(pid = 42, vmCpus = 1))
        now = 2_000L
        own = 50L
        assertEquals(50f, s.sampleCpuPercent(pid = 42, vmCpus = 1)!!, 0.01f)
    }
}
