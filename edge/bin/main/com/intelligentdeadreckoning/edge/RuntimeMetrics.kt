package com.intelligentdeadreckoning.edge

import java.util.ArrayDeque
import kotlin.math.ceil

/** Nearest-rank latency summary. */
data class LatencySummary(val count: Int, val p50Ns: Long?, val p95Ns: Long?, val p99Ns: Long?, val maxNs: Long?)

internal class BoundedLatencies(private val capacity: Int) {
    private val values = ArrayDeque<Long>(capacity)
    @Synchronized fun add(valueNs: Long) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(valueNs.coerceAtLeast(0L))
    }
    @Synchronized fun snapshot(): LatencySummary {
        if (values.isEmpty()) return LatencySummary(0, null, null, null, null)
        val sorted = values.sorted()
        fun rank(q: Double) = sorted[(ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        return LatencySummary(sorted.size, rank(.50), rank(.95), rank(.99), sorted.last())
    }
}

/** Constant-space rate, minimum observed period and maximum gap. */
internal class RateAccumulator {
    private var count = 0L
    private var firstNs: Long? = null
    private var lastNs: Long? = null
    private var minIntervalNs = Long.MAX_VALUE
    private var maxIntervalNs = 0L

    @Synchronized fun add(timestampNs: Long) {
        val previous = lastNs
        if (previous != null && timestampNs <= previous) return
        if (previous == null) firstNs = timestampNs else {
            val interval = timestampNs - previous
            minIntervalNs = minOf(minIntervalNs, interval)
            maxIntervalNs = maxOf(maxIntervalNs, interval)
        }
        lastNs = timestampNs
        count++
    }

    @Synchronized fun snapshot(): RateSummary {
        val first = firstNs ?: return RateSummary(0, null, null, null, null, null)
        val last = requireNotNull(lastNs)
        val hz = if (count > 1 && last > first) (count - 1) * 1e9 / (last - first) else null
        return RateSummary(count, first, last, hz,
            minIntervalNs.takeIf { count > 1 }, maxIntervalNs.takeIf { count > 1 })
    }
}

data class RateSummary(
    val count: Long,
    val firstTimestampNs: Long?,
    val lastTimestampNs: Long?,
    val hz: Double?,
    val minimumIntervalNs: Long?,
    val maximumIntervalNs: Long?,
)

data class EdgeRuntimeMetrics(
    val phase: String,
    val accelerometerInput: RateSummary,
    val accelerometerInputWall: RateSummary,
    val gyroscopeInput: RateSummary,
    val gyroscopeInputWall: RateSummary,
    val gnssInput: RateSummary,
    val gnssInputWall: RateSummary,
    val navigationOutput: RateSummary,
    val pairedImuSource: RateSummary,
    val imuProcessing: RateSummary,
    val gnssProcessing: RateSummary,
    val pairedImuProcessing: RateSummary,
    val navigationOutputProcessing: RateSummary,
    val ingressDepth: Int,
    val ingressCapacity: Int,
    val ingressHighWater: Int,
    val outputDepth: Int,
    val outputCapacity: Int,
    val outputHighWater: Int,
    val acceptedImuRecords: Long,
    val acceptedOptionalImuRecords: Long,
    val acceptedGnssRecords: Long,
    val navigationStateCount: Long,
    val dropped: Long,
    val rejected: Long,
    val late: Long,
    val invalid: Long,
    val duplicate: Long,
    val outputDropped: Long,
    val aiAvailable: Boolean,
    val inferenceUnavailableReason: String?,
    val stageLatency: Map<String, LatencySummary>,
    val totalCycleLatency: LatencySummary,
    val workerWallTimeNs: Long?,
    val workerCpuTimeNs: Long?,
    val peakHeapBytesSampled: Long?,
    val peakRssBytesSampled: Long?,
)
