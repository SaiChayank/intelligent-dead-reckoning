package com.intelligentdeadreckoning.app.matching

import com.intelligentdeadreckoning.app.fusion.Geodesy
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * MVP causal map matcher. One call per fix, using only the current fix and the
 * previous accepted match — no future fixes, no smoothing, no retro-fit.
 *
 * Evidence per candidate edge:
 *  - distance from the raw fused position to the road polyline,
 *  - heading compatibility with the closest segment (both travel directions),
 *  - previous-edge continuity (same edge, shared node, same OSM way) and
 *    travel plausibility since the last accepted match,
 *  - uncertainty gating: the search radius and the decision both scale with the
 *    reported 95% radius.
 *
 * The RAW FUSED POSITION and the MAP-MATCHED POSITION are separate outputs in
 * [MapMatchResult]; this class never mutates navigation truth and only remembers
 * its own last accepted edge. Non-matching outcomes (ambiguity, no candidate,
 * out of coverage) report their reason and leave the matched fields null.
 */
class MapMatcher(
    private val graph: RoadGraph,
    private val config: MapMatchConfig = MapMatchConfig(),
) {
    private var lastEdge: Int = -1
    private var lastLatDeg: Double = 0.0
    private var lastLonDeg: Double = 0.0
    private var lastTimeNs: Long? = null

    /** Forget the previous-edge continuation state (new drive/session). */
    fun reset() {
        lastEdge = -1
        lastTimeNs = null
    }

    /**
     * Match one raw fused position. `headingRad` is clockwise from north and may be
     * null when absent; `speedM_S` gates the heading term (pass null to assert the
     * heading is fused attitude rather than GNSS course).
     */
    fun match(
        timestampNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        headingRad: Double?,
        accuracy95M: Double,
        speedM_S: Double? = null,
    ): MapMatchResult {
        require(timestampNs >= 0L && latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0 &&
            longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0 &&
            accuracy95M.isFinite() && accuracy95M > 0.0 &&
            (speedM_S == null || (speedM_S.isFinite() && speedM_S >= 0.0))
        ) { "invalid match input" }
        require(headingRad == null || headingRad.isFinite()) { "invalid heading" }

        fun result(
            status: MapMatchStatus,
            confidence: Double = 0.0,
            distanceM: Double? = null,
            candidates: Int = 0,
            matched: RoadGraph.Projection? = null,
            matchedEdge: Int = -1,
        ) = MapMatchResult(
            timestampNs = timestampNs,
            rawLatitudeDeg = latitudeDeg,
            rawLongitudeDeg = longitudeDeg,
            matchedLatitudeDeg = matched?.latitudeDeg,
            matchedLongitudeDeg = matched?.longitudeDeg,
            matchedEdgeId = if (matched != null) graph.edgeId(matchedEdge) else null,
            confidence = confidence,
            status = status,
            distanceToEdgeM = distanceM,
            candidateCount = candidates,
            matcherVersion = MATCHER_VERSION,
        )

        if (!graph.covers(latitudeDeg, longitudeDeg, config.coverageMarginDeg)) {
            return result(MapMatchStatus.OUTSIDE_COVERAGE)
        }
        if (config.coverageMarginDeg < 0.0 || !config.coverageMarginDeg.isFinite()) {
            return result(MapMatchStatus.LOW_CONFIDENCE)
        }
        val sigmaM = accuracy95M / CHI_95_FACTOR
        if (accuracy95M > config.maxAccuracy95M) {
            return result(MapMatchStatus.LOW_CONFIDENCE)
        }
        val radiusM = min(
            config.maxCandidateRadiusM,
            maxOf(config.minCandidateRadiusM, config.candidateSigmaFactor * sigmaM),
        )
        val sigmaDistanceM = maxOf(config.distanceSigmaFloorM, sigmaM)

        val useHeading = headingRad != null &&
            (speedM_S == null || speedM_S >= config.minHeadingSpeedM_S)
        val candidates = graph.candidatesNear(latitudeDeg, longitudeDeg, radiusM)
        var scored = 0
        var bestEdge = -1
        var bestScore = 0.0
        var bestEmission = 0.0
        var bestProjection: RoadGraph.Projection? = null
        var totalScore = 0.0
        for (edge in candidates) {
            val projection = graph.project(edge, latitudeDeg, longitudeDeg)
            if (projection.distanceM > radiusM) continue
            var emission = exp(-0.5 * square(projection.distanceM / sigmaDistanceM))
            if (useHeading) {
                val residual = headingResidual(headingRad!!, projection.headingRad)
                emission *= exp(-0.5 * square(residual / config.headingSigmaRad))
            }
            val score = emission * continuityFactor(edge, projection, timestampNs, speedM_S, sigmaDistanceM)
            scored++
            totalScore += score
            if (score > bestScore) {
                bestScore = score
                bestEmission = emission
                bestEdge = edge
                bestProjection = projection
            }
        }
        if (scored == 0) return result(MapMatchStatus.NO_CANDIDATE)

        // Confidence is the best candidate's share of total evidence: it measures
        // ambiguity (parallel roads, crossings) and is 1.0 for a lone candidate.
        // Absolute fit is expressed by distanceToEdgeM and bounded by the
        // uncertainty-scaled search radius.
        val confidence = if (totalScore > 0.0) bestScore / totalScore else 0.0
        val best = bestProjection!!
        if (confidence < config.minConfidence) {
            return result(
                MapMatchStatus.LOW_CONFIDENCE,
                confidence = confidence,
                distanceM = best.distanceM,
                candidates = scored,
            )
        }
        lastEdge = bestEdge
        lastLatDeg = best.latitudeDeg
        lastLonDeg = best.longitudeDeg
        lastTimeNs = timestampNs
        return result(
            MapMatchStatus.MATCHED,
            confidence = confidence,
            distanceM = best.distanceM,
            candidates = scored,
            matched = best,
            matchedEdge = bestEdge,
        )
    }

    private fun continuityFactor(
        edge: Int,
        projection: RoadGraph.Projection,
        timestampNs: Long,
        speedM_S: Double?,
        sigmaDistanceM: Double,
    ): Double {
        if (lastEdge < 0) return 1.0
        var factor = 1.0
        if (edge == lastEdge || graph.sharesNode(edge, lastEdge)) {
            factor *= config.continuityBonus
        } else if (graph.sameWay(edge, lastEdge)) {
            factor *= config.sameWayBonus
        }
        val dtS = lastTimeNs?.let { (timestampNs - it) / 1e9 }
        if (dtS != null && dtS >= 0.0 && dtS <= config.maxTravelHorizonS) {
            val allowed = config.travelSlackM + (speedM_S ?: DEFAULT_TRAVEL_SPEED_M_S) * dtS
            val separation = metresBetween(lastLatDeg, lastLonDeg, projection.latitudeDeg, projection.longitudeDeg)
            if (separation > allowed) {
                factor *= exp(-0.5 * square((separation - allowed) / sigmaDistanceM))
            }
        }
        return factor
    }

    /** Smallest angle between a heading and an undirected road direction, [0, pi/2]. */
    private fun headingResidual(headingRad: Double, segmentHeadingRad: Double): Double {
        val difference = headingRad - segmentHeadingRad
        val direct = abs(Math.atan2(Math.sin(difference), Math.cos(difference)))
        return min(direct, Math.PI - direct)
    }

    private fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val (mPerDegLat, mPerDegLon) = metresPerDegree(lat1)
        val dy = (lat2 - lat1) * mPerDegLat
        val dx = (lon2 - lon1) * mPerDegLon
        return hypot(dx, dy)
    }

    private fun metresPerDegree(latitudeDeg: Double): Pair<Double, Double> {
        val (rE, rN) = Geodesy.radii(Math.toRadians(latitudeDeg))
        val toRad = Math.PI / 180.0
        return (rN * toRad) to (rE * cos(Math.toRadians(latitudeDeg)) * toRad)
    }

    private fun square(value: Double): Double = value * value

    companion object {
        const val MATCHER_VERSION = "idr-map-match/1"

        /** sqrt(5.991): stated 1-sigma to 95% radius conversion (circular Gaussian). */
        val CHI_95_FACTOR: Double = sqrt(5.991)

        private const val DEFAULT_TRAVEL_SPEED_M_S = 30.0
    }
}
