package com.intelligentdeadreckoning.app.fusion

import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3

/**
 * Fusion configuration.
 *
 * ## Where the numbers come from
 *
 * The **timing** limits are the validated baseline's: `maxStepS` and `failureGapS` are the
 * `max_step_s` and `failure_gap_s` of `training/strapdown_ins.py`, which the real-data baseline
 * exercise used to distinguish "no samples arrived" from "the run is broken".
 *
 * The **process-noise densities are chosen engineering values, not measurements of this
 * device.** They are stated as such on purpose: turning them into device figures requires a
 * static Allan-variance run on the phone, which has not been done. They are set at the usual
 * order of magnitude for a consumer MEMS IMU and are the only numbers in this stage that a
 * datasheet or a measurement could replace.
 *
 * The **gate probability** is the false-rejection rate of the innovation test. 0.999 rather than
 * something tighter because the covariance is model-derived and therefore optimistic, so a tight
 * gate would reject good fixes; the acceptance tests report what it actually rejects.
 *
 * The **rejection limits** bound how long the gate may stay closed. An innovation gate is a
 * statistical test against a covariance the filter reports about itself, so it is not robustness
 * against a persistent lie: once the filter honestly admits enough uncertainty, a measurement it
 * was refusing becomes admissible. The opposite failure is just as real and much worse — the
 * solution drifts away from a stream of arriving fixes, the gate refuses all of them, and the
 * position grows without bound while every rejection is individually defensible. A stretch of
 * refusals that long means one of the two is wrong and this filter cannot tell which, so it stops
 * claiming a solution instead of publishing an unbounded one. The stretch is *contiguous*: a pause
 * in the stream resets it, because an outage is not a lie and is handled by coasting.
 */
data class FusionConfig(
    /** Accelerometer white-noise density, m/s^2 per sqrt(Hz). Chosen, not measured. */
    val accelNoiseDensityM_S2_RT_HZ: Double = 0.05,
    /** Gyroscope white-noise density, rad/s per sqrt(Hz). Chosen, not measured. */
    val gyroNoiseDensityRad_S_RT_HZ: Double = 0.005,
    /** Accelerometer bias random walk, m/s^2 per sqrt(s). Chosen, not measured. */
    val accelBiasRandomWalkM_S2_RT_S: Double = 1.0e-4,
    /** Gyroscope bias random walk, rad/s per sqrt(s). Chosen, not measured. */
    val gyroBiasRandomWalkRad_S_RT_S: Double = 1.0e-5,
    /** An interval longer than this carries no measurement and the state is held. */
    val maxStepS: Double = 0.2,
    /** An interval longer than this is a broken stream, not a gap. */
    val failureGapS: Double = 5.0,
    /** Below this an interval is a duplicate timestamp, not a step. */
    val minStepS: Double = 1e-9,
    /** Chi-square confidence of the innovation gate. */
    val gateProbability: Double = 0.999,
    /**
     * Longest unbroken stretch of refused measurements before the run is declared lost, seconds.
     * Chosen, not measured: it is long enough for the covariance to legitimately grow enough to
     * reopen the gate on its own (a few tens of seconds in the synthetic cases) and short enough
     * that a runaway is caught while it is still metres rather than kilometres.
     */
    val maxRejectionSpanS: Double = 30.0,
    /**
     * A pause between measurements longer than this starts a new stretch, seconds. Several missed
     * nominal fix intervals: past it the stream stopped, which is an outage rather than a
     * disagreement, and outages are coasted through however long they last.
     */
    val maxRejectionGapS: Double = 5.0,

    // ---------------------------------------------------------------------------------------
    // Vehicle-motion constraints (zero-velocity update and non-holonomic constraints)
    //
    // Approved by the constraints stage. The *policy* — when each constraint may apply and when
    // it must stand down — is the project's own declared rule in
    // `docs/PS26168_Non_ML_Baseline_Navigation_System.md` sections 5 and 6. The stationary
    // thresholds are the already-declared causal resting rule from
    // `reports/body_frame_conventions.md`. The variance/cadence/speed/yaw-rate numbers below are
    // **chosen engineering values, not measurements**, stated as such so a validation run can
    // replace them: the constraint variances are deliberately loose ("tune conservatively",
    // "do not blindly force") so a constraint that fires wrongly degrades the solution slowly
    // instead of freezing it.
    // ---------------------------------------------------------------------------------------

    /** Master switch for both constraints. False gives the plain GNSS/INS filter (the A/B arm). */
    val motionConstraintsEnabled: Boolean = true,

    /** Stillness window the detector must accumulate before zero-velocity may fire, seconds. */
    val stationaryWindowS: Double = 2.0,

    /** Fewest paired samples inside the stillness window. */
    val stationaryMinSamples: Int = 20,

    /** A hole longer than this discards the stillness window, seconds. */
    val stationaryMaxGapS: Double = 0.25,

    /** Gyroscope norm above which the phone is rotating, rad/s. */
    val stationaryGyroNormRad_S: Double = 0.05,

    /** Half-width of the accelerometer gravity band, m/s² (9.3–10.3 around standard gravity). */
    val stationaryGravityBandM_S2: Double = 0.5,

    /** Per-sample specific force may sit at most this far from the window mean, m/s². */
    val stationaryAccelDeviationM_S2: Double = 0.3,

    /** Standard-deviation norm of the accelerometer over the window, m/s². */
    val stationaryAccelStdNormM_S2: Double = 0.1,

    /** Filter speed below which the zero-velocity update may fire, m/s (creep protection). */
    val zuptSpeedThresholdM_S: Double = 0.5,

    /** Zero-velocity measurement variance, (m/s)² — σ 0.2 m/s, deliberately not zero. */
    val zuptVarianceM2: Double = 0.04,

    /** A fresh accepted GNSS speed above this contradicts a stationary claim, m/s. */
    val zuptMaxGnssSpeedM_S: Double = 0.5,

    /** How often constraints may be applied while their conditions hold, seconds. */
    val constraintIntervalS: Double = 0.1,

    /** NHC stands down below this speed: parking, creeping and reversing, m/s. */
    val nhcMinSpeedM_S: Double = 2.0,

    /** NHC stands down above this yaw rate: unusual maneuvers, rad/s. */
    val nhcMaxYawRateRad_S: Double = 0.5,

    /**
     * NHC stands down above this magnitude of longitudinal specific force, m/s².
     *
     * Sustained acceleration, braking and grades are exactly the declared NHC weaknesses
     * ("unusual maneuvers", "slopes"): on a grade the vertical-velocity claim is simply false
     * (a 2° grade at 12 m/s is 0.42 m/s of real vertical velocity), and during acceleration a
     * tight lateral row turns any attitude error into a drain on the forward estimate, because
     * the vehicle axes are recomputed from an attitude that is momentarily wrong while the
     * true velocity is changing fastest. The measured longitudinal force — the accelerometer
     * projected on the calibrated vehicle forward axis, gravity along forward being ~0 at
     * level — is a causal, defensible signal for all three conditions at once. 0.35 m/s² is a
     * 2° grade or a 0–100 km/h run in ~80 s; chosen to sit above sensor noise and below every
     * maneuver that invalidates the constraint.
     */
    val nhcMaxForwardAccelM_S2: Double = 0.35,

    /**
     * Contiguous benign-dynamics time required before NHC may (re)start, seconds.
     *
     * The first constraint after a dynamic phase meets a filter whose attitude/velocity cross-
     * covariances were built while the constraint was off; measured on the development drives,
     * applying immediately after sustained acceleration corrects the accumulated tilt in one
     * violent step, and the following propagations then inject `g·sin(tilt)` as a real lateral
     * acceleration. A short dwell lets the GNSS loop re-stabilize the state first, so the first
     * constraint arrives while its correction is small. Same spirit as the stationary
     * detector's own entry hysteresis.
     */
    val nhcDwellS: Double = 5.0,

    /** After a refused NHC update, stand it down for this long instead of re-offering at the
     * cadence and fighting the filter, seconds. The refusal path advances the cadence, so this
     * is the difference between a bounded disagreement and a hammering loop. */
    val nhcBackoffS: Double = 2.0,

    /**
     * Age of the newest accepted GNSS fix beyond which NHC stands down, seconds.
     *
     * Level cruise and a parked car are IMU-identical (constant specific force along gravity,
     * no rotation) — no detector can separate them, which is the same documented equivalence
     * that forces the zero-velocity gate to consult the filter's speed. For the NHC the
     * corroborating evidence is the GNSS course-velocity row: while fresh fixes agree the
     * vehicle is moving, the vehicle-frame assumption is supported; when the fixes stop, the
     * assumption is unverified, and an outage is exactly where a constraint loop would be
     * invisible. The ZUPT owns the outage stop, so nothing of value is lost by standing down.
     */
    val nhcMaxGnssAgeS: Double = 3.0,

    /** Lateral-velocity constraint variance, (m/s)² — σ 0.3 m/s, allows real sideslip. */
    val nhcLateralVarianceM2: Double = 0.09,

    /** Vertical-velocity constraint variance, (m/s)² — σ 0.5 m/s, allows grade and bumps. */
    val nhcVerticalVarianceM2: Double = 0.25,

    /**
     * Staleness scale of the constraint-variance inflation, seconds. Every this-many seconds
     * without an accepted GNSS fix doubles the constraint variances, so a constraint aids a
     * short gap tightly and a long outage only weakly.
     */
    val constraintStalenessScaleS: Double = 10.0,

    /** Ceiling of the constraint-variance inflation factor (on sigma), so an arbitrarily long
     * outage still leaves the constraints some — but never coercive — authority. */
    val constraintMaxSigmaInflation: Double = 5.0,
)

/** The filter's own lifecycle. The contract's navigation status is the engine's to decide. */
enum class FusionStatus { UNINITIALIZED, RUNNING, FAILED }

/** What one inertial sample did. */
enum class PropagationOutcome {
    NOT_ALIGNED,
    PROPAGATED,

    /** Same timestamp as the previous sample: no interval passed, so nothing is propagated. */
    DUPLICATE_TIMESTAMP,

    /** An earlier timestamp than the last sample. A broken clock is not smoothed over. */
    BACKWARDS_TIMESTAMP,

    /** Too long to integrate and too short to be a failure: state held, uncertainty inflated. */
    HELD_GAP,

    /** Too long to be a gap at all: the run is over and no state beyond it is claimed. */
    FAILED_GAP,

    FAILED_NON_FINITE,
    FAILED,
}

/** Why a measurement was not applied. */
enum class GnssUpdateResult {
    NOT_ALIGNED,
    FAILED,
    ACCEPTED,

    /** The innovation failed the chi-square gate and the measurement was discarded. */
    REJECTED_GATE,

    /** The measurement itself was not usable: non-finite, or a variance that is not positive. */
    REJECTED_INVALID,

    /** The innovation covariance could not be decomposed, so gating is meaningless. */
    REJECTED_ILL_CONDITIONED,
}

/** What one measurement did, including the numbers the diagnostics quote. */
data class GnssUpdateOutcome(
    val result: GnssUpdateResult,
    /** Rows in this update: 2 or 3 for position, 2 for a course velocity, 1 for speed alone. */
    val dimension: Int,
    /** Normalised innovation squared: `nu^T S^-1 nu`, the gated statistic. */
    val nis: Double,
    val gateThreshold: Double,
    /** Norm of the raw innovation vector before gating. */
    val innovationM: Double,
    /** Magnitude of the correction this update actually applied, metres. Zero for a rejection. */
    val correctionM: Double,
) {
    val accepted: Boolean get() = result == GnssUpdateResult.ACCEPTED

    companion object {
        internal fun refused(result: GnssUpdateResult, dimension: Int = 0) =
            GnssUpdateOutcome(result, dimension, Double.NaN, Double.NaN, Double.NaN, 0.0)
    }
}

/**
 * The state the filter is aligned to. Every variance must be finite and strictly positive: a
 * zero variance would claim a perfectly known state, which is never true and makes the first
 * update divide by zero.
 */
data class FusionInitialState(
    val anchor: GeodeticAnchor,
    /** ENU-from-device attitude. The device frame is the frame the IMU measures in. */
    val qEnuFromDevice: Quaternion,
    val velocityEnuM_S: Vector3,
    /** Bias prior, in the device frame, matching the calibration contract's frame. */
    val gyroBiasDeviceRad_S: Vector3,
    val accelBiasDeviceM_S2: Vector3,
    /** Per-axis position variance, east/north/up, m^2. */
    val positionVarianceM2: Vector3,
    /** Per-axis velocity variance, east/north/up, (m/s)^2. */
    val velocityVarianceM2: Vector3,
    /** Per-axis attitude error variance, in the navigation frame, rad^2. */
    val attitudeVarianceRad2: Vector3,
    val gyroBiasVarianceRad2_S2: Double,
    val accelBiasVarianceM2_S4: Double,
)

/** A fatal condition. The filter stops claiming state beyond this point. */
enum class FusionFailure(val code: String) {
    NON_FINITE_SAMPLE("NON_FINITE_SAMPLE"),
    TIME_GAP_EXCEEDS_LIMIT("TIME_GAP_EXCEEDS_LIMIT"),

    /**
     * Measurements kept arriving and the gate kept refusing them, for longer than any stretch the
     * covariance can grow its way out of. The solution and the fixes no longer describe the same
     * vehicle, and continuing to dead-reckon would publish a position that grows without bound.
     * Recovery is a deliberate re-alignment, not a silent teleport.
     */
    PERSISTENT_INNOVATION_REJECTION("PERSISTENT_INNOVATION_REJECTION"),
}

/**
 * The filter's state, read out for the engine and for tests.
 *
 * [covariance] is a copy of the 15x15 error covariance in row-major order, exposed so a caller can
 * check that it stayed symmetric and positive-definite rather than taking that on trust.
 */
data class FusionSolution(
    val status: FusionStatus,
    val tNs: Long,
    val alignedAtNs: Long,
    val anchor: GeodeticAnchor?,
    val geodetic: GeodeticPoint?,
    val positionEnuM: Vector3,
    val velocityEnuM_S: Vector3,
    val qEnuFromDevice: Quaternion,
    val gyroBiasDeviceRad_S: Vector3,
    val accelBiasDeviceM_S2: Vector3,
    val positionSigmaM: Vector3,
    val velocitySigmaM_S: Vector3,
    val attitudeSigmaRad: Vector3,
    val covariance: DoubleArray,
    val lastGnssUpdateNs: Long?,
    val acceptedUpdates: Long,
    val rejectedUpdates: Long,
    val duplicateTimestamps: Long,
    val backwardsTimestamps: Long,
    val heldGaps: Long,
    val failure: FusionFailure?,
    val lastRejectionNis: Double?,

    /**
     * Vehicle-constraint bookkeeping, kept apart from the GNSS counters on purpose: the
     * persistent-rejection guard watches the *fixes* the solution disagrees with, and a
     * zero-velocity row refused by its own gate is a model disagreement, not a lying GNSS.
     * These therefore never move [rejectedUpdates], [lastRejectionNis] or the guard's span.
     */
    val constraintAccepted: Long,
    val constraintRejected: Long,
) {
    /** True once an anchor and a state exist. Nothing may be propagated or updated before that. */
    val aligned: Boolean
        get() = anchor != null && status != FusionStatus.UNINITIALIZED

    // DoubleArray makes the generated data-class equals and hashCode identity-based, which would
    // be a trap in tests, so they are replaced by explicit ones over the whole value.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FusionSolution) return false
        return status == other.status &&
            tNs == other.tNs &&
            alignedAtNs == other.alignedAtNs &&
            anchor == other.anchor &&
            geodetic == other.geodetic &&
            positionEnuM == other.positionEnuM &&
            velocityEnuM_S == other.velocityEnuM_S &&
            qEnuFromDevice == other.qEnuFromDevice &&
            gyroBiasDeviceRad_S == other.gyroBiasDeviceRad_S &&
            accelBiasDeviceM_S2 == other.accelBiasDeviceM_S2 &&
            positionSigmaM == other.positionSigmaM &&
            velocitySigmaM_S == other.velocitySigmaM_S &&
            attitudeSigmaRad == other.attitudeSigmaRad &&
            covariance.contentEquals(other.covariance) &&
            lastGnssUpdateNs == other.lastGnssUpdateNs &&
            acceptedUpdates == other.acceptedUpdates &&
            rejectedUpdates == other.rejectedUpdates &&
            duplicateTimestamps == other.duplicateTimestamps &&
            backwardsTimestamps == other.backwardsTimestamps &&
            heldGaps == other.heldGaps &&
            failure == other.failure &&
            lastRejectionNis == other.lastRejectionNis &&
            constraintAccepted == other.constraintAccepted &&
            constraintRejected == other.constraintRejected
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + tNs.hashCode()
        result = 31 * result + alignedAtNs.hashCode()
        result = 31 * result + (anchor?.hashCode() ?: 0)
        result = 31 * result + (geodetic?.hashCode() ?: 0)
        result = 31 * result + positionEnuM.hashCode()
        result = 31 * result + velocityEnuM_S.hashCode()
        result = 31 * result + qEnuFromDevice.hashCode()
        result = 31 * result + gyroBiasDeviceRad_S.hashCode()
        result = 31 * result + accelBiasDeviceM_S2.hashCode()
        result = 31 * result + positionSigmaM.hashCode()
        result = 31 * result + velocitySigmaM_S.hashCode()
        result = 31 * result + attitudeSigmaRad.hashCode()
        result = 31 * result + covariance.contentHashCode()
        result = 31 * result + (lastGnssUpdateNs?.hashCode() ?: 0)
        result = 31 * result + acceptedUpdates.hashCode()
        result = 31 * result + rejectedUpdates.hashCode()
        result = 31 * result + duplicateTimestamps.hashCode()
        result = 31 * result + backwardsTimestamps.hashCode()
        result = 31 * result + heldGaps.hashCode()
        result = 31 * result + (failure?.hashCode() ?: 0)
        result = 31 * result + (lastRejectionNis?.hashCode() ?: 0)
        result = 31 * result + constraintAccepted.hashCode()
        result = 31 * result + constraintRejected.hashCode()
        return result
    }
}

/** True when every component is finite. A NaN must never reach the propagation. */
internal fun isFinite(v: Vector3): Boolean =
    v.x.isFinite() && v.y.isFinite() && v.z.isFinite()

/** True when every component is finite and strictly positive. */
internal fun isPositive(v: Vector3): Boolean =
    isFinite(v) && v.x > 0.0 && v.y > 0.0 && v.z > 0.0

/** True when every quaternion component is finite. */
internal fun isFiniteQuaternion(q: Quaternion): Boolean =
    q.w.isFinite() && q.x.isFinite() && q.y.isFinite() && q.z.isFinite()
