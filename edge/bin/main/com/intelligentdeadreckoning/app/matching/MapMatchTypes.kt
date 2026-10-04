package com.intelligentdeadreckoning.app.matching

/**
 * Result of one causal map-matching step. This record deliberately carries the
 * RAW FUSED POSITION and the MAP-MATCHED POSITION side by side and never overwrites
 * navigation truth: the caller keeps its own `NavigationState` untouched and may
 * present or ignore the matched fields independently.
 *
 * `matchedLatitudeDeg`/`matchedLongitudeDeg`/`matchedEdgeId` are non-null only for
 * [MapMatchStatus.MATCHED]. Every other status leaves them null rather than
 * guessing: no candidate, ambiguous evidence and out-of-coverage positions are
 * reported, never silently projected onto some road.
 */
data class MapMatchResult(
    val timestampNs: Long,
    val rawLatitudeDeg: Double,
    val rawLongitudeDeg: Double,
    val matchedLatitudeDeg: Double?,
    val matchedLongitudeDeg: Double?,
    val matchedEdgeId: String?,
    val confidence: Double,
    val status: MapMatchStatus,
    val distanceToEdgeM: Double?,
    val candidateCount: Int,
    val matcherVersion: String,
)

enum class MapMatchStatus {
    /** Best candidate is unambiguous and inside the uncertainty gate. */
    MATCHED,

    /**
     * Candidates exist but the evidence is ambiguous (parallel roads, huge
     * reported uncertainty) or the best candidate is weak. No matched output.
     */
    LOW_CONFIDENCE,

    /** Inside coverage but no road within the uncertainty-scaled search radius. */
    NO_CANDIDATE,

    /** Outside the road graph's data bounds; nothing can be matched here. */
    OUTSIDE_COVERAGE,
}

/**
 * Matcher tunables. All values are **chosen, not measured**: no ground-truthed
 * drive corpus exists yet to tune against (see reports/ml_dataset_readiness_*).
 */
data class MapMatchConfig(
    /** Search radius floor in metres; small uncertainties still search locally. */
    val minCandidateRadiusM: Double = 15.0,
    /** Search radius cap in metres; never scan the whole graph for one fix. */
    val maxCandidateRadiusM: Double = 60.0,
    /** Search radius = this factor x reported 1-sigma, clamped to the bounds above. */
    val candidateSigmaFactor: Double = 3.0,
    /** Reported 95% radius above which matching refuses to decide at all. */
    val maxAccuracy95M: Double = 50.0,
    /** Best-candidate score share below which the result is LOW_CONFIDENCE. */
    val minConfidence: Double = 0.55,
    /** Distance term width floor in metres (a perfect fix still has map error). */
    val distanceSigmaFloorM: Double = 5.0,
    /** Heading term width; residuals beyond ~2x this kill a candidate. */
    val headingSigmaRad: Double = 0.3490658503988659, // 20 degrees
    /** Below this speed the heading term is dropped: course is not heading. */
    val minHeadingSpeedM_S: Double = 2.0,
    /** Score multiplier for continuing on the previous edge or a connected one. */
    val continuityBonus: Double = 1.5,
    /** Score multiplier for staying on the same OSM way. */
    val sameWayBonus: Double = 1.05,
    /** Slack added to the physically plausible travel distance between fixes. */
    val travelSlackM: Double = 50.0,
    /** Travel plausibility is ignored for gaps longer than this. */
    val maxTravelHorizonS: Double = 30.0,
    /** Data bounds inflation for the coverage decision, degrees (~550 m). */
    val coverageMarginDeg: Double = 0.005,
)
