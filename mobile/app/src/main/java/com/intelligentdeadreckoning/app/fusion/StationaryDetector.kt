package com.intelligentdeadreckoning.app.fusion

import com.intelligentdeadreckoning.app.calibration.GRAVITY_STANDARD_M_S2
import com.intelligentdeadreckoning.app.calibration.norm
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Why the detector is or is not reporting stillness. The first failing gate wins, so a
 * diagnostic can say which condition ended a stationary period instead of only that one did.
 */
enum class StationaryVerdict {
    /** The whole window passed every gate. */
    QUIET,

    /** Not enough stillness has accumulated yet — the entry hysteresis. */
    TOO_SOON,

    /** Too few paired samples in the window to judge. */
    TOO_FEW_SAMPLES,

    /** A hole in the stream: the window was discarded rather than interpolated over. */
    GAP,

    /** Accelerometer magnitude left the gravity band. */
    GRAVITY_OUT_OF_BAND,

    /** The specific force moved away from the window's gravity estimate: vibration or motion. */
    VIBRATING,

    /** Gyroscope magnitude above the resting limit: the phone is rotating. */
    ROTATING,

    /** A non-finite component reached the detector. */
    NON_FINITE,
}

/**
 * A causal, O(window) rolling stillness detector for the zero-velocity update.
 *
 * ## Thresholds, and where they come from
 *
 * Every number is the project's already-declared resting rule from
 * `reports/body_frame_conventions.md` (the causal-calibration buffer specification), not a
 * value chosen here: a rolling **2 s** window with at least **20** samples, no timestamp gap
 * above **0.25 s**, gyroscope norm below **0.05 rad/s**, accelerometer magnitude within the
 * gravity band (**9.3–10.3 m/s²**, implemented as ±0.5 about standard gravity), specific force
 * within **0.3 m/s²** of the window mean (the declared "accelerometer-minus-gravity" bound,
 * with the window mean standing in for the gravity channel the fusion input contract does not
 * carry), and an accelerometer standard-deviation norm below **0.1 m/s²** (the declared
 * gravity-vector stability bound).
 *
 * ## Semantics
 *
 * The verdict flips to non-quiet **the moment** any sample violates a gate — a pothole ends a
 * stationary period immediately — and returning to `QUIET` requires the **full 2 s** of clean
 * samples again, because a violation clears the window. That asymmetry is deliberate: a false
 * "moving" costs one update, a false "stationary" can freeze a creeping vehicle.
 *
 * ## What this cannot know
 *
 * A vehicle creeping at constant velocity on a smooth road produces exactly the inertial
 * signature of rest: gravity-only specific force, no rotation. No IMU-only detector can
 * separate the two, which is why the ZUPT gate in the engine does not rely on this detector
 * alone — it additionally requires the filter's own speed below a creep bound and that fresh
 * accepted GNSS does not contradict (see `FusionNavigationEngine.applyMotionConstraints`).
 */
class StationaryDetector(private val config: FusionConfig) {

    private class Sample(val tNs: Long, val accelM_S2: Vector3, val gyroRad_S: Vector3)

    private val window = ArrayDeque<Sample>()
    private var lastFeedNs: Long? = null

    /** True only while the whole window passes. Never true on a partial window. */
    var stationary: Boolean = false
        private set

    /** When the current stillness began, or null when not stationary. */
    var stationarySinceNs: Long? = null
        private set

    /** The first gate that is not satisfied right now. */
    var verdict: StationaryVerdict = StationaryVerdict.TOO_SOON
        private set

    fun reset() {
        window.clear()
        lastFeedNs = null
        stationary = false
        stationarySinceNs = null
        verdict = StationaryVerdict.TOO_SOON
    }

    /**
     * Offer one paired accelerometer/gyroscope sample. Causal: the verdict after this call
     * depends only on samples at or before [tNs].
     */
    fun offer(tNs: Long, accelM_S2: Vector3, gyroRad_S: Vector3) {
        if (!isFinite(accelM_S2) || !isFinite(gyroRad_S)) {
            window.clear()
            verdict = StationaryVerdict.NON_FINITE
            updateVerdict()
            lastFeedNs = tNs
            return
        }
        val previous = lastFeedNs
        lastFeedNs = tNs
        if (previous != null) {
            val gapS = (tNs - previous) / 1e9
            if (gapS > config.stationaryMaxGapS || gapS < 0.0) {
                // A hole in the stream invalidates accumulation; the current sample starts fresh.
                window.clear()
                verdict = StationaryVerdict.GAP
            }
        }

        if (gyroRad_S.norm() > config.stationaryGyroNormRad_S) {
            window.clear()
            verdict = StationaryVerdict.ROTATING
            updateVerdict()
            return
        }
        val magnitude = accelM_S2.norm()
        if (abs(magnitude - GRAVITY_STANDARD_M_S2) > config.stationaryGravityBandM_S2) {
            window.clear()
            verdict = StationaryVerdict.GRAVITY_OUT_OF_BAND
            updateVerdict()
            return
        }
        if (window.isNotEmpty()) {
            val mean = meanAccel()
            if (dist(accelM_S2, mean) > config.stationaryAccelDeviationM_S2) {
                window.clear()
                verdict = StationaryVerdict.VIBRATING
                updateVerdict()
                return
            }
        }

        window.addLast(Sample(tNs, accelM_S2, gyroRad_S))
        prune(tNs)
        evaluate()
    }

    private fun prune(nowNs: Long) {
        val cutoff = nowNs - (config.stationaryWindowS * 1e9).toLong()
        while (window.isNotEmpty() && window.first().tNs < cutoff) window.removeFirst()
    }

    private fun evaluate() {
        if (window.isEmpty()) return
        val durationS = (window.last().tNs - window.first().tNs) / 1e9
        // A nanosecond of slack so a window that spans the declared duration numerically
        // qualifies despite timestamp discretization: 2.0000000001 s is 2 s.
        if (durationS < config.stationaryWindowS - 1e-9) {
            verdict = StationaryVerdict.TOO_SOON
            updateVerdict()
            return
        }
        if (window.size < config.stationaryMinSamples) {
            verdict = StationaryVerdict.TOO_FEW_SAMPLES
            updateVerdict()
            return
        }
        // The window-mean standing in for the gravity channel: the declared standard-deviation
        // stability bound over the whole window.
        val mean = meanAccel()
        var ve = 0.0
        var vn = 0.0
        var vu = 0.0
        for (sample in window) {
            val dx = sample.accelM_S2.x - mean.x
            val dy = sample.accelM_S2.y - mean.y
            val dz = sample.accelM_S2.z - mean.z
            ve += dx * dx
            vn += dy * dy
            vu += dz * dz
        }
        val n = window.size - 1
        val stdNorm = sqrt(ve / n + vn / n + vu / n)
        if (stdNorm > config.stationaryAccelStdNormM_S2) {
            verdict = StationaryVerdict.VIBRATING
            updateVerdict()
            return
        }
        verdict = StationaryVerdict.QUIET
        updateVerdict()
    }

    private fun updateVerdict() {
        if (verdict == StationaryVerdict.QUIET) {
            if (!stationary) stationarySinceNs = window.firstOrNull()?.tNs
            stationary = true
        } else {
            stationary = false
            stationarySinceNs = null
        }
    }

    private fun meanAccel(): Vector3 {
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (sample in window) {
            x += sample.accelM_S2.x
            y += sample.accelM_S2.y
            z += sample.accelM_S2.z
        }
        val n = window.size.toDouble()
        return Vector3(x / n, y / n, z / n)
    }

    private fun dist(a: Vector3, b: Vector3): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
