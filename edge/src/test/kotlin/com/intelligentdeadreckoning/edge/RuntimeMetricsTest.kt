package com.intelligentdeadreckoning.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeMetricsTest {
    @Test
    fun latencyPercentilesUseNearestRankAndStayWithinConfiguredCapacity() {
        val samples = BoundedLatencies(4)
        listOf(10L, 20L, 30L, 40L, 50L).forEach(samples::add)
        val summary = samples.snapshot()
        assertEquals(4, summary.count)
        assertEquals(30L, summary.p50Ns)
        assertEquals(50L, summary.p95Ns)
        assertEquals(50L, summary.p99Ns)
        assertEquals(50L, summary.maxNs)
    }

    @Test
    fun ratesUseSourceClockAndIgnoreDuplicateOrBackwardsTimestamps() {
        val rate = RateAccumulator()
        rate.add(1_000_000_000L)
        rate.add(1_010_000_000L)
        rate.add(1_020_000_000L)
        rate.add(1_020_000_000L)
        rate.add(1_015_000_000L)
        val result = rate.snapshot()
        assertEquals(3L, result.count)
        assertEquals(100.0, result.hz!!, 1e-9)
        assertEquals(10_000_000L, result.minimumIntervalNs)
        assertEquals(10_000_000L, result.maximumIntervalNs)
    }

    @Test
    fun emptyRateAndLatencyAreUnavailableRatherThanZero() {
        assertNull(RateAccumulator().snapshot().hz)
        val latency = BoundedLatencies(3).snapshot()
        assertEquals(0, latency.count)
        assertNull(latency.p50Ns)
        assertNull(latency.p95Ns)
    }
}
