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

    /**
     * The shipped app configuration; [experimentalMapMatching] adds the HMM matcher and its cross-track correction.
     *
     * The AI speed estimator and the mount-slip detector are OFF. On 38 real phone-in-car drives (Google Smartphone Decimeter Challenge,
     * development split, reports/gsdc_eval_development*) the configuration that had both on drifted a median 311 % in 30 s outages and 651 % in
     * 60 s outages; with both off it drifted 2.3 % and 6.0 %. The slip detector reads sustained braking or acceleration as a tilt of the mount (about
     * 0.7 m/s^2 crosses its 4 degree threshold) and re-levels the attitude wrongly; the speed estimator (trained on synthetic data) adds error.
     * Both are still in the code and tested on their own; they stay off until they beat this configuration on held-out drives.
     */
    fun config(experimentalMapMatching: Boolean): FusionConfig {
        val base = FusionConfig(
            adaptiveNoiseScalingEnabled = true,
            mapHeadingFeedbackEnabled = true,
            adaptiveDecimationEnabled = true,
        )
        return if (experimentalMapMatching) base.copy(
            hmmMapMatchingEnabled = true, mapCrossTrackEnabled = true, mapCrossTrackMinConfidence = MAP_CROSS_TRACK_MIN_CONFIDENCE,
        ) else base
    }

    /**
     * Whether the app also runs the batch mount calibrator beside the in-drive one. The in-drive calibrator reached VALID on 0 of 38 real phone-in-car
     * trips under every threshold setting tried (reports/gsdc_calibrator_probe_development.json); the batch calibrator found a mount on every trip
     * (38 of 38 development, 26 of 26 held-out) and the engine then drifted as much as with an offline calibration (reports/gsdc_eval_*_inengine).
     * Its result is used only when the in-drive calibrator has not produced one.
     */
    const val BATCH_MOUNT_CALIBRATION_ENABLED = true

    /** The calibrator the app hands to [com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine]. */
    fun calibrator(batchFallback: Boolean = BATCH_MOUNT_CALIBRATION_ENABLED): NavigationEngine {
        val inDrive = com.intelligentdeadreckoning.app.calibration.CalibrationNavigationEngine()
        return if (batchFallback) {
            com.intelligentdeadreckoning.app.calibration.FallbackCalibrator(inDrive, com.intelligentdeadreckoning.app.calibration.BatchMountCalibrator())
        } else {
            inDrive
        }
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
