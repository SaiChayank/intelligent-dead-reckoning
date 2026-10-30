package com.intelligentdeadreckoning.app.navigation

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine

/** The fusion engine the phone app builds when calibration and a motion-derived heading are ready. */
object AppFusion {
    const val PUBLICATION_INTERVAL_NS = 100_000_000L

    /**
     * Cross-track corrections are accepted from this match confidence (the engine default is 0.8). Chosen on the development drives of the
     * real-road evaluation (reports/n7_map_comparison.json, docs/MAP_MATCHING_REAL_DATA.md): 0.6 beat 0.8 there, and 0.4 gave exactly the same
     * results as 0.6, so no lower value was tried.
     */
    const val MAP_CROSS_TRACK_MIN_CONFIDENCE = 0.6

    /** The shipped app configuration; [experimentalMapMatching] adds the HMM matcher and its cross-track correction. */
    fun config(experimentalMapMatching: Boolean): FusionConfig {
        val base = FusionConfig(
            aiSpeedEstimationEnabled = true,
            adaptiveNoiseScalingEnabled = true,
            mountSlipDetectionEnabled = true,
            mapHeadingFeedbackEnabled = true,
            adaptiveDecimationEnabled = true,
        )
        return if (experimentalMapMatching) base.copy(
            hmmMapMatchingEnabled = true, mapCrossTrackEnabled = true, mapCrossTrackMinConfidence = MAP_CROSS_TRACK_MIN_CONFIDENCE,
        ) else base
    }

    /** What the factory hands to the engine constructor; a seam so tests can see the choice without running an engine. */
    fun interface Builder {
        fun build(initialHeadingRad: Double, config: FusionConfig, roadGraph: RoadGraph?): NavigationEngine
    }

    /** The production engine for a heading, a configuration and an optional road graph. */
    fun build(initialHeadingRad: Double, config: FusionConfig, roadGraph: RoadGraph?): NavigationEngine =
        FusionNavigationEngine(
            initialHeadingRad = initialHeadingRad,
            publicationIntervalNs = PUBLICATION_INTERVAL_NS,
            config = config,
            roadGraph = roadGraph,
        )

    private val productionBuilder = Builder(::build)

    /**
     * Factory for [CalibratingFusionEngine]. The two providers are read when fusion starts, so a change made in settings
     * applies from the next navigation session and never to a running one. With map matching on but no road graph loaded
     * yet, the engine is built without map feedback rather than with a half-configured matcher.
     */
    fun factory(
        mapMatchingEnabled: () -> Boolean,
        roadGraph: () -> RoadGraph?,
        builder: Builder = productionBuilder,
    ): (Double) -> NavigationEngine = { heading ->
        val graph = if (mapMatchingEnabled()) roadGraph() else null
        builder.build(heading, config(experimentalMapMatching = graph != null), graph)
    }
}
