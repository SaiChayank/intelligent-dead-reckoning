package com.intelligentdeadreckoning.app.matching

import com.intelligentdeadreckoning.app.fusion.Geodesy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Online hidden-Markov map matcher (PS #26168 names "Hidden Markov map matching").
 *
 * The hidden state at each step is the road edge the vehicle is on. Observations are the filter's raw
 * fused positions (and heading), so this works through a GNSS outage on dead-reckoned positions as well.
 *
 * - **Emission** (per candidate edge): Gaussian in the distance from the observation to the edge polyline,
 *   times a Gaussian in the heading residual to the road direction (heading is dropped below a speed).
 * - **Transition** (previous candidate to current candidate): consistency between the straight distance
 *   of the two projected points and the distance the observations moved, `exp(-|d_proj - d_obs| / beta)`,
 *   times a topology factor (same edge, edges sharing a node, same OSM way, unconnected). The scores are not
 *   normalised over destinations; only their relative size matters to the decision. The projection-to-projection
 *   distance stands in for route distance, which is accurate for the short steps used here.
 * - **Inference**: a forward (sum-product) pass gives the filtering posterior, whose best state and share
 *   are the online decision and confidence; a Viterbi pass with back-pointers is kept for a fixed lag, which
 *   re-decides older steps once later evidence has arrived ([HmmMatchResult.smoothedEdgeId]).
 *
 * A step is only added once the observation has moved at least [HmmMatchConfig.minStepDistanceM] from the
 * previous step: matching a stationary or slowly creeping vehicle every sample would let the transition term
 * dominate with noise. In between, the previous result is returned unchanged.
 *
 * Like [MapMatcher], this never edits navigation state: the matched point, road heading and signed cross-track
 * offset are reported, and the caller decides what to feed back.
 */
class HmmMapMatcher(
    private val graph: RoadGraph,
    private val config: HmmMatchConfig = HmmMatchConfig(),
) {
    private class Column(
        val edges: IntArray,
        val proj: Array<RoadGraph.Projection>,
        val logAlpha: DoubleArray,
        val logDelta: DoubleArray,
        val back: IntArray,
        val rawLat: Double,
        val rawLon: Double,
        val timeNs: Long,
    )

    private val columns = ArrayDeque<Column>()
    private var lastResult: HmmMatchResult? = null

    /** Forget the trellis (new drive or a long gap). */
    fun reset() {
        columns.clear()
        lastResult = null
    }

    val steps: Int get() = columns.size

    fun step(
        timestampNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double?,
        accuracy95M: Double,
        speedM_S: Double? = null,
    ): HmmMatchResult {
        require(timestampNs >= 0L && latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0 &&
            longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0 &&
            accuracy95M.isFinite() && accuracy95M > 0.0 &&
            (speedM_S == null || (speedM_S.isFinite() && speedM_S >= 0.0))
        ) { "invalid match input" }
        require(headingRad == null || headingRad.isFinite()) { "invalid heading" }

        fun refused(status: MapMatchStatus, candidates: Int = 0) = HmmMatchResult(
            MapMatchResult(
                timestampNs, latitudeDeg, longitudeDeg, null, null, null, 0.0, status, null, candidates,
                MATCHER_VERSION,
            ),
            smoothedEdgeId = null, crossTrackCorrectionM = null, roadNormalEast = null, roadNormalNorth = null,
        )

        if (!graph.covers(latitudeDeg, longitudeDeg, config.coverageMarginDeg)) {
            return refused(MapMatchStatus.OUTSIDE_COVERAGE)
        }
        if (accuracy95M > config.maxAccuracy95M) return refused(MapMatchStatus.LOW_CONFIDENCE)

        val previous = columns.lastOrNull()
        if (previous != null) {
            val movedM = metresBetween(previous.rawLat, previous.rawLon, latitudeDeg, longitudeDeg)
            val gapS = (timestampNs - previous.timeNs) / 1e9
            if (gapS > config.maxStepGapS) {
                columns.clear()                    // too long a gap for the transition model to mean anything
            } else if (movedM < config.minStepDistanceM && lastResult != null) {
                return lastResult!!.copy(result = lastResult!!.result.copy(timestampNs = timestampNs,
                    rawLatitudeDeg = latitudeDeg, rawLongitudeDeg = longitudeDeg))
            }
        }
        val prior = columns.lastOrNull()

        val sigmaM = accuracy95M / CHI_95_FACTOR
        val radiusM = min(config.maxCandidateRadiusM, maxOf(config.minCandidateRadiusM, config.candidateSigmaFactor * sigmaM))
        val sigmaDistanceM = maxOf(config.distanceSigmaFloorM, sigmaM)
        val useHeading = headingRad != null && (speedM_S == null || speedM_S >= config.minHeadingSpeedM_S)

        data class Cand(val edge: Int, val proj: RoadGraph.Projection)
        val found = graph.candidatesNear(latitudeDeg, longitudeDeg, radiusM)
            .map { Cand(it, graph.project(it, latitudeDeg, longitudeDeg)) }
            .filter { it.proj.distanceM <= radiusM }
            .sortedBy { it.proj.distanceM }
            .take(config.maxCandidates)
        if (found.isEmpty()) return refused(MapMatchStatus.NO_CANDIDATE)

        val n = found.size
        val emission = DoubleArray(n) { j ->
            var e = -0.5 * square(found[j].proj.distanceM / sigmaDistanceM)
            if (useHeading) e += -0.5 * square(headingResidual(headingRad!!, found[j].proj.headingRad) / config.headingSigmaRad)
            e
        }
        val logAlpha = DoubleArray(n)
        val logDelta = DoubleArray(n)
        val back = IntArray(n) { -1 }
        if (prior == null) {
            for (j in 0 until n) { logAlpha[j] = emission[j]; logDelta[j] = emission[j] }
        } else {
            val observed = metresBetween(prior.rawLat, prior.rawLon, latitudeDeg, longitudeDeg)
            for (j in 0 until n) {
                var bestDelta = Double.NEGATIVE_INFINITY
                var bestI = 0
                val terms = DoubleArray(prior.edges.size)
                for (i in prior.edges.indices) {
                    val projected = metresBetween(prior.proj[i].latitudeDeg, prior.proj[i].longitudeDeg,
                        found[j].proj.latitudeDeg, found[j].proj.longitudeDeg)
                    val logT = -abs(projected - observed) / config.transitionBetaM +
                        ln(topologyFactor(prior.edges[i], found[j].edge))
                    terms[i] = prior.logAlpha[i] + logT
                    val d = prior.logDelta[i] + logT
                    if (d > bestDelta) { bestDelta = d; bestI = i }
                }
                logAlpha[j] = emission[j] + logSumExp(terms)
                logDelta[j] = emission[j] + bestDelta
                back[j] = bestI
            }
        }
        val norm = logSumExp(logAlpha)
        for (j in 0 until n) logAlpha[j] -= norm
        val maxDelta = logDelta.max()
        for (j in 0 until n) logDelta[j] -= maxDelta

        columns.addLast(Column(IntArray(n) { found[it].edge }, Array(n) { found[it].proj }, logAlpha, logDelta, back,
            latitudeDeg, longitudeDeg, timestampNs))
        while (columns.size > config.lagSteps + 1) columns.removeFirst()

        var best = 0
        for (j in 1 until n) if (logAlpha[j] > logAlpha[best]) best = j
        val confidence = exp(logAlpha[best])
        val proj = found[best].proj
        val smoothed = smoothedEdge()

        val status = if (confidence >= config.minConfidence) MapMatchStatus.MATCHED else MapMatchStatus.LOW_CONFIDENCE
        val matched = status == MapMatchStatus.MATCHED
        // Cross-track: signed distance along the road normal that would move the raw position onto the road.
        val (mLat, mLon) = metresPerDegree(latitudeDeg)
        val east = (proj.longitudeDeg - longitudeDeg) * mLon
        val north = (proj.latitudeDeg - latitudeDeg) * mLat
        val normalEast = cos(proj.headingRad)
        val normalNorth = -sin(proj.headingRad)
        val result = HmmMatchResult(
            MapMatchResult(
                timestampNs, latitudeDeg, longitudeDeg,
                if (matched) proj.latitudeDeg else null, if (matched) proj.longitudeDeg else null,
                if (matched) graph.edgeId(found[best].edge) else null,
                confidence, status, proj.distanceM, n, MATCHER_VERSION,
                if (matched) proj.headingRad else null,
            ),
            smoothedEdgeId = smoothed,
            crossTrackCorrectionM = if (matched) east * normalEast + north * normalNorth else null,
            roadNormalEast = if (matched) normalEast else null,
            roadNormalNorth = if (matched) normalNorth else null,
        )
        lastResult = result
        return result
    }

    /** Edge the Viterbi path occupied `lagSteps` columns ago, decided with the evidence seen since. */
    private fun smoothedEdge(): String? {
        if (columns.size <= config.lagSteps) return null
        val list = columns.toList()
        var column = list.last()
        var index = column.logDelta.indices.maxByOrNull { column.logDelta[it] } ?: return null
        for (k in list.lastIndex downTo list.size - config.lagSteps) {
            index = list[k].back[index]
            if (index < 0) return null
        }
        val target = list[list.size - 1 - config.lagSteps]
        return graph.edgeId(target.edges[index])
    }

    private fun topologyFactor(previousEdge: Int, edge: Int): Double = when {
        edge == previousEdge -> config.sameEdgeFactor
        graph.sharesNode(edge, previousEdge) -> config.sharedNodeFactor
        graph.sameWay(edge, previousEdge) -> config.sameWayFactor
        else -> config.unconnectedFactor
    }

    private fun headingResidual(headingRad: Double, segmentHeadingRad: Double): Double {
        val difference = headingRad - segmentHeadingRad
        val direct = abs(Math.atan2(sin(difference), cos(difference)))
        return min(direct, Math.PI - direct)
    }

    private fun logSumExp(values: DoubleArray): Double {
        val m = values.max()
        if (m == Double.NEGATIVE_INFINITY) return m
        return m + ln(values.sumOf { exp(it - m) })
    }

    private fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val (mLat, mLon) = metresPerDegree(lat1)
        return hypot((lon2 - lon1) * mLon, (lat2 - lat1) * mLat)
    }

    private fun metresPerDegree(latitudeDeg: Double): Pair<Double, Double> {
        val (rE, rN) = Geodesy.radii(Math.toRadians(latitudeDeg))
        val toRad = Math.PI / 180.0
        return (rN * toRad) to (rE * cos(Math.toRadians(latitudeDeg)) * toRad)
    }

    private fun square(v: Double) = v * v

    companion object {
        const val MATCHER_VERSION = "idr-map-match-hmm/1"
        val CHI_95_FACTOR: Double = sqrt(5.991)
    }
}

/** Matcher tunables. Chosen, not measured; `reports/map_matching_eval.json` is the evidence for these values. */
data class HmmMatchConfig(
    val maxCandidates: Int = 8,
    val minCandidateRadiusM: Double = 15.0,
    val maxCandidateRadiusM: Double = 60.0,
    val candidateSigmaFactor: Double = 3.0,
    val maxAccuracy95M: Double = 50.0,
    val distanceSigmaFloorM: Double = 5.0,
    val headingSigmaRad: Double = 0.3490658503988659,           // 20 degrees
    val minHeadingSpeedM_S: Double = 2.0,
    /** Scale of the exponential penalty on |projected distance - observed distance|, metres. */
    val transitionBetaM: Double = 8.0,
    val sameEdgeFactor: Double = 1.0,
    val sharedNodeFactor: Double = 0.7,
    val sameWayFactor: Double = 0.5,
    val unconnectedFactor: Double = 0.02,
    /** A new trellis column needs the observation to have moved this far. */
    val minStepDistanceM: Double = 5.0,
    /** A longer gap between columns clears the trellis. */
    val maxStepGapS: Double = 30.0,
    val lagSteps: Int = 5,
    val minConfidence: Double = 0.6,
    val coverageMarginDeg: Double = 0.005,
) {
    init {
        require(maxCandidates >= 1 && lagSteps >= 1 && minStepDistanceM >= 0.0 && transitionBetaM > 0.0)
    }
}

/**
 * One HMM step. [result] is the same record the greedy matcher returns, so existing consumers keep working;
 * the extra fields carry what only the HMM provides.
 */
data class HmmMatchResult(
    val result: MapMatchResult,
    /** Edge decided `lagSteps` steps ago with later evidence; null until enough steps exist. */
    val smoothedEdgeId: String?,
    /** Signed metres along the road normal from the raw position to the road; null unless MATCHED. */
    val crossTrackCorrectionM: Double?,
    /** Unit road normal in (east, north); the correction is measured along it. */
    val roadNormalEast: Double?,
    val roadNormalNorth: Double?,
)
