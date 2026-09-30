package com.intelligentdeadreckoning.app.fusion

import com.intelligentdeadreckoning.app.acquisition.GnssQualityPolicy
import com.intelligentdeadreckoning.app.acquisition.GnssReason
import com.intelligentdeadreckoning.app.calibration.conjugate
import com.intelligentdeadreckoning.app.calibration.isProperRotation
import com.intelligentdeadreckoning.app.calibration.norm
import com.intelligentdeadreckoning.app.calibration.normalizeDegrees360
import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.calibration.rotate
import com.intelligentdeadreckoning.app.calibration.times
import com.intelligentdeadreckoning.app.calibration.toRotationMatrix
import com.intelligentdeadreckoning.contracts.v1.AltitudeReference
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.ConfidenceState
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GeoOrigin
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuUnit
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Payload
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.Severity
import com.intelligentdeadreckoning.contracts.v1.Vector3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The production GNSS/INS fusion engine: the classical filter behind the frozen [NavigationEngine].
 *
 * It owns the arithmetic of [GnssInsEkf] and the product decisions around it — what is a usable
 * measurement, when the solution is tracking, what the covariance is allowed to claim — and
 * publishes only canonical records: [NavigationState], [Confidence] and [DiagnosticEvent].
 *
 * ## Sequence of a session
 *
 * 1. `initialize` adopts the in-force calibration when it carries a valid proper rotation. Without
 *    one there is no vehicle frame, no vehicle heading and therefore no `TRACKING` state; the
 *    engine says `CALIBRATION_REQUIRED` rather than reporting an attitude it does not have.
 * 2. Inertial samples propagate the nominal state. Before alignment they are counted and
 *    discarded, because with no anchor there is nothing to propagate *from*.
 * 3. The first usable fix **aligns** the filter: it supplies the anchor, which is the only
 *    absolute position reference available. That is alignment, not a measurement update.
 * 4. Every later fix is a gated measurement update. This is the path a returning fix takes after an
 *    outage, and it never overwrites the state — the correction is the Kalman gain times the
 *    innovation, so recovery is a convergence over several fixes rather than a teleport.
 *
 * ## Acceptance, and where its thresholds come from
 *
 * Each fix passes two independent gates. The first is the recorded evidence: a provider this
 * project has a profile for, a horizontal accuracy that is present and inside the bound, and
 * enough satellites where the provider reports them. Those are exactly the numbers in
 * [GnssQualityPolicy] — the *same* validated policy the acquisition layer's quality state machine
 * uses, so the tree holds one accuracy/satellite/staleness policy rather than two.
 *
 * The dependency runs one way. This engine reads the policy's numbers; it never feeds an
 * innovation back into the quality state machine. The one-way `GnssInnovationTrust` seam stays
 * unwired, so no cycle can form between a quality decision and the measurement it gated.
 *
 * ## Publication
 *
 * [NavigationState] and [Confidence] are published at a fixed 5 Hz cadence and immediately on any
 * change of status. The state changes on every inertial sample, so publishing per sample would
 * record the filter's internal rate rather than the solution. Diagnostics are published when
 * something happens, with refusals and rejections rate-limited to one per second carrying a count,
 * so a misconfigured provider cannot flood the diagnostic history.
 */
class FusionNavigationEngine(
    private val config: FusionConfig = FusionConfig(),
    private val qualityPolicy: GnssQualityPolicy = GnssQualityPolicy(),
    /**
     * Initial vehicle heading in radians, or null when no source can supply one.
     *
     * Nothing in this project can currently supply it: GNSS course was null on all 1,782 recorded
     * fixes, the magnetometer is deliberately unused, and motion-based alignment is not
     * implemented. Null is therefore the honest default, and it means the filter starts with a
     * heading variance of half a turn rather than pretending to know which way the vehicle points.
     */
    private val initialHeadingRad: Double? = null,
    private val publicationIntervalNs: Long = 200_000_000L,
) : NavigationEngine {

    private val filter = GnssInsEkf(config)

    /**
     * Causal stillness detector feeding the zero-velocity gate. It observes every paired sample
     * from the first one, including those discarded before alignment, so stillness accumulated
     * while waiting for the first fix is not thrown away.
     */
    private val stationaryDetector = StationaryDetector(config)

    private var header: Header? = null
    private var clockNs = 0L
    private var nextEventId = 1L
    private var mode: InitializationMode = InitializationMode.EVALUATION
    private val pending = ArrayDeque<Record>()

    private var mountingQVehicleFromDevice: Quaternion? = null
    private var calibrationId: String? = null
    private var priorGyroBias: Vector3? = null
    private var priorAccelBias: Vector3? = null

    private var lastAccel: Pair<Long, Vector3>? = null
    private var lastGyro: Pair<Long, Vector3>? = null
    private var pairedSamples = 0L
    private var discardedBeforeAlignment = 0L
    private var unmatchedSamples = 0L

    private var lastAcceptedGnssNs: Long? = null
    private var lastAcceptedGnssProvider: String? = null
    private var lastAcceptedGnssSpeedM_S: Double? = null
    private var gnssUsed = false
    private var lastCorrectionM = 0.0
    private var refusedSinceDiagnostic = 0L
    private var rejectedSinceDiagnostic = 0L
    private var lastDiagnosticGateNs: Long? = null

    private var publishedStatus: NavigationStatus? = null
    private var publishedAtNs = Long.MIN_VALUE
    private var announcedCalibrationRequired = false
    private var announcedHeadingUnobserved = false
    private var announcedPairedWarning = false
    private var outageAnnounced = false
    private var gapAnnounced = false
    private var warnedMismatchedUnit = false
    private var warnedMismatchedFrame = false
    private var failureAnnounced = false

    private var lastConstraintNs = 0L
    private var constraintAcceptedAnnounced = false
    private var constraintRejectedAnnounced = false
    private var nhcBenignSinceNs: Long? = null
    private var nhcSuspendedUntilNs = 0L
    private var zuptEngaged = false

    /** The filter's own state, for diagnostics and for tests. Not a published record. */
    val solution: FusionSolution get() = filter.solution

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
        header = session.header
        clockNs = session.origin_ns
        this.mode = mode
        nextEventId = 1L
        pending.clear()
        filter.reset()
        mountingQVehicleFromDevice = null
        calibrationId = null
        priorGyroBias = null
        priorAccelBias = null
        lastAccel = null
        lastGyro = null
        pairedSamples = 0L
        discardedBeforeAlignment = 0L
        unmatchedSamples = 0L
        lastAcceptedGnssNs = null
        lastAcceptedGnssProvider = null
        gnssUsed = false
        lastCorrectionM = 0.0
        refusedSinceDiagnostic = 0L
        rejectedSinceDiagnostic = 0L
        lastDiagnosticGateNs = null
        publishedStatus = null
        publishedAtNs = Long.MIN_VALUE
        announcedCalibrationRequired = false
        announcedHeadingUnobserved = false
        announcedPairedWarning = false
        outageAnnounced = false
        gapAnnounced = false
        warnedMismatchedUnit = false
        warnedMismatchedFrame = false
        failureAnnounced = false
        stationaryDetector.reset()
        lastConstraintNs = 0L
        constraintAcceptedAnnounced = false
        constraintRejectedAnnounced = false
        nhcBenignSinceNs = null
        nhcSuspendedUntilNs = 0L
        zuptEngaged = false
        lastAcceptedGnssSpeedM_S = null

        adoptCalibration(calibration)
        emitDiagnostic(
            Severity.INFO, "FUSION_SESSION_STARTED",
            "GNSS/INS fusion started in ${mode.wire} mode: a 15-state error-state filter " +
                "(position, velocity, attitude error, gyroscope bias, accelerometer bias) " +
                "predicted by the validated strapdown mechanization and corrected by gated GNSS " +
                "position and, where they are semantically valid, velocity measurements.",
        )
        publish(force = true)
    }

    /**
     * Adopt the calibration in force, or record that there is none.
     *
     * Only a `VALID` status with a proper rotation is usable. A unit quaternion is always a proper
     * rotation — a quaternion cannot represent a reflection at all — so the transform check here
     * is really guarding against a non-unit or non-finite quaternion, which would scale or
     * corrupt every direction it is applied to.
     */
    private fun adoptCalibration(calibration: Record) {
        val result = calibration.event.data as? CalibrationResult
        if (result == null) {
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_REQUIRED",
                "Initialization record was ${calibration.event.data.type}, not a calibration " +
                    "result; the engine has no vehicle frame and cannot reach a tracking state.",
            )
            announcedCalibrationRequired = true
            return
        }
        val transform = result.q_vehicle_from_device_wxyz
        if (result.status != CalibrationStatus.VALID || transform == null ||
            !isProperRotation(transform, 1e-6)
        ) {
            val detail = when {
                transform == null -> "with no transform"
                !isProperRotation(transform, 1e-6) -> "with a transform that is not a proper rotation"
                else -> "with no usable transform"
            }
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_REQUIRED",
                "Calibration ${result.id} is ${result.status.wire} $detail; the engine has no " +
                    "vehicle frame and cannot reach a tracking state.",
            )
            announcedCalibrationRequired = true
            return
        }
        mountingQVehicleFromDevice = transform
        calibrationId = result.id
        priorGyroBias = result.gyro_bias_rad_s
        priorAccelBias = result.accelerometer_bias_m_s2
        emitDiagnostic(
            Severity.INFO, "FUSION_CALIBRATION_ADOPTED",
            "Adopted calibration ${result.id} as the vehicle frame, with a gyroscope bias prior " +
                (if (priorGyroBias != null) "provided" else "absent") +
                " and an accelerometer bias prior " +
                (if (priorAccelBias != null) "provided" else "absent") +
                "; the filter estimates both regardless of what the prior supplies.",
        )
    }

    override fun stop() {
        publish(force = true)
        emitDiagnostic(
            Severity.INFO, "FUSION_SESSION_STOPPED",
            "Fusion stopped after $pairedSamples paired inertial samples, of which " +
                "$discardedBeforeAlignment preceded alignment; " +
                "${filter.solution.acceptedUpdates} GNSS updates were accepted and " +
                "${filter.solution.rejectedUpdates} failed the innovation gate.",
        )
        lastAccel = null
        lastGyro = null
    }

    override fun reset() {
        pending.clear()
        header = null
        clockNs = 0L
        nextEventId = 1L
        filter.reset()
        mountingQVehicleFromDevice = null
        calibrationId = null
        priorGyroBias = null
        priorAccelBias = null
        lastAccel = null
        lastGyro = null
        pairedSamples = 0L
        discardedBeforeAlignment = 0L
        unmatchedSamples = 0L
        lastAcceptedGnssNs = null
        lastAcceptedGnssProvider = null
        lastAcceptedGnssSpeedM_S = null
        gnssUsed = false
        lastCorrectionM = 0.0
        failureAnnounced = false
        stationaryDetector.reset()
        lastConstraintNs = 0L
        constraintAcceptedAnnounced = false
        constraintRejectedAnnounced = false
        nhcBenignSinceNs = null
        nhcSuspendedUntilNs = 0L
        zuptEngaged = false
        publishedStatus = null
        publishedAtNs = Long.MIN_VALUE
    }

    // ---------------------------------------------------------------------------------------
    // Input
    // ---------------------------------------------------------------------------------------

    override fun acceptImu(measurement: Record) {
        val payload = measurement.event.data as? ImuMeasurement ?: run {
            emitDiagnostic(
                Severity.WARNING, "FUSION_RECORD_KIND",
                "Expected an IMU measurement, received ${measurement.event.data.type}; ignored.",
            )
            return
        }
        if (payload.sensor != Sensor.ACCELEROMETER && payload.sensor != Sensor.GYROSCOPE) return
        if (payload.frame != DeviceFrame.ANDROID_DEVICE) {
            if (!warnedMismatchedFrame) {
                warnedMismatchedFrame = true
                emitDiagnostic(
                    Severity.WARNING, "FUSION_IMU_FRAME",
                    "IMU sample arrived in ${payload.frame.wire} rather than the Android device " +
                        "frame; ignored rather than reinterpreted.",
                )
            }
            return
        }
        val expected =
            if (payload.sensor == Sensor.GYROSCOPE) ImuUnit.RADIANS_PER_SECOND
            else ImuUnit.METRES_PER_SECOND_SQUARED
        if (payload.unit != expected) {
            if (!warnedMismatchedUnit) {
                warnedMismatchedUnit = true
                emitDiagnostic(
                    Severity.WARNING, "FUSION_IMU_UNIT",
                    "${payload.sensor.wire} samples arrived in ${payload.unit.wire}; ignored " +
                        "rather than converted.",
                )
            }
            return
        }
        clockNs = maxOf(clockNs, measurement.event.t_ns)
        val sample = measurement.event.t_ns to payload.xyz
        if (payload.sensor == Sensor.ACCELEROMETER) lastAccel = sample else lastGyro = sample
        pairAndPropagate()
    }

    /**
     * Pair the newest accelerometer and gyroscope samples, then propagate on the pair.
     *
     * The two sensors arrive as separate records. Pairing consumes both, which keeps every sample
     * in the propagation exactly once; re-using one gyroscope sample across several accelerometer
     * samples would propagate the same rotation several times.
     */
    private fun pairAndPropagate() {
        val accel = lastAccel ?: return
        val gyro = lastGyro ?: return
        if (abs(accel.first - gyro.first) > MAX_PAIR_SKEW_NS) {
            unmatchedSamples += 1
            if (accel.first < gyro.first) lastAccel = null else lastGyro = null
            if (!announcedPairedWarning && unmatchedSamples >= UNMATCHED_WARN) {
                announcedPairedWarning = true
                emitDiagnostic(
                    Severity.WARNING, "FUSION_IMU_PAIRING",
                    "Accelerometer and gyroscope samples are more than " +
                        "${MAX_PAIR_SKEW_NS / 1_000_000} ms apart and cannot be paired; the " +
                        "filter cannot propagate on unpaired samples.",
                )
            }
            return
        }
        lastAccel = null
        lastGyro = null
        val tNs = maxOf(accel.first, gyro.first)
        clockNs = maxOf(clockNs, tNs)
        pairedSamples += 1
        stationaryDetector.offer(tNs, accel.second, gyro.second)
        if (!filter.aligned) {
            discardedBeforeAlignment += 1
            return
        }
        val outcome = filter.propagate(accel.second, gyro.second, tNs)
        when (outcome) {
            PropagationOutcome.HELD_GAP -> {
                if (!gapAnnounced) {
                    gapAnnounced = true
                    emitDiagnostic(
                        Severity.WARNING, "FUSION_TIME_GAP",
                        "An inertial interval exceeded ${config.maxStepS} s. The state is held and " +
                            "the covariance inflated for the unobserved motion, rather than a " +
                            "trajectory being invented across the gap.",
                    )
                }
            }

            PropagationOutcome.FAILED_GAP, PropagationOutcome.FAILED_NON_FINITE ->
                announceFailureIfAny()

            PropagationOutcome.BACKWARDS_TIMESTAMP -> emitDiagnostic(
                Severity.WARNING, "FUSION_TIMESTAMP",
                "An inertial sample predated the previous one and was rejected rather than " +
                    "integrated backwards; the stream is expected to be monotonic.",
            )

            else -> Unit
        }
        if (outcome == PropagationOutcome.PROPAGATED) {
            applyMotionConstraints(tNs, accel.second, gyro.second)
        }
        publish(force = false)
    }

    override fun acceptGnss(measurement: Record) {
        val payload = measurement.event.data as? GnssMeasurement ?: run {
            emitDiagnostic(
                Severity.WARNING, "FUSION_RECORD_KIND",
                "Expected a GNSS measurement, received ${measurement.event.data.type}; ignored.",
            )
            return
        }
        clockNs = maxOf(clockNs, measurement.event.t_ns)
        val tNs = measurement.event.t_ns
        val refusal = refuse(payload)
        if (refusal != null) {
            recordRefusal(refusal, tNs)
            publish(force = false)
            return
        }
        if (!filter.aligned) align(payload, tNs) else applyFix(payload, tNs)
        publish(force = false)
    }

    /**
     * The fix-level gate: the recorded evidence, before any filtering.
     *
     * Returns the named reason for a refusal, or null when the measurement may be filtered. Every
     * bound is read from [GnssQualityPolicy] rather than restated, so the fusion boundary and the
     * published quality state cannot drift apart. A measurement that carries no horizontal
     * accuracy is refused: there is no honest way to form its covariance, and inventing one would
     * be fabricating the number that decides whether it is believed.
     */
    private fun refuse(payload: GnssMeasurement): String? {
        if (!payload.latitude_deg.isFinite() || !payload.longitude_deg.isFinite() ||
            abs(payload.latitude_deg) > 90.0 || abs(payload.longitude_deg) > 180.0
        ) {
            return "COORDINATES_INVALID"
        }
        val profile = qualityPolicy.profile(payload.provider) ?: return GnssReason.PROVIDER_UNKNOWN.code
        val accuracy = payload.horizontal_accuracy_m
            ?: return GnssReason.HORIZONTAL_ACCURACY_UNKNOWN.code
        if (!accuracy.isFinite() || accuracy <= 0.0) return GnssReason.HORIZONTAL_ACCURACY_UNKNOWN.code
        if (accuracy > qualityPolicy.maximumHorizontalAccuracyM) {
            return GnssReason.HORIZONTAL_ACCURACY_POOR.code
        }
        if (profile.satellitesExpected) {
            val satellites = payload.satellites_used ?: return GnssReason.SATELLITES_UNKNOWN.code
            if (satellites < qualityPolicy.minimumSatellites) return GnssReason.SATELLITES_LOW.code
        }
        return null
    }

    private fun recordRefusal(reason: String, tNs: Long) {
        refusedSinceDiagnostic += 1
        // The gate starts null rather than at a sentinel timestamp, and it is order-aware: a
        // sentinel overflows when subtracted from a real one, and a negative difference would
        // suppress a report that is in fact due.
        val lastGate = lastDiagnosticGateNs
        if (lastGate != null && tNs >= lastGate && tNs - lastGate < REJECTION_DIAGNOSTIC_NS) return
        val count = refusedSinceDiagnostic
        refusedSinceDiagnostic = 0
        lastDiagnosticGateNs = tNs
        emitDiagnostic(
            Severity.WARNING, "GNSS_MEASUREMENT_REFUSED",
            "$count GNSS measurement(s) refused before filtering; the latest reason was $reason. " +
                "A refused measurement never reaches the filter, so no covariance is invented for it.",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Alignment
    // ---------------------------------------------------------------------------------------

    /**
     * Align the filter on the first usable fix.
     *
     * The anchor is the only absolute reference this engine can obtain, so the first usable fix
     * has to supply it. This is the one moment the state is set from outside evidence; every later
     * fix goes through the gated update path instead.
     */
    private fun align(payload: GnssMeasurement, tNs: Long) {
        val mounting = mountingQVehicleFromDevice
        if (mounting == null) {
            if (!announcedCalibrationRequired) {
                announcedCalibrationRequired = true
                emitDiagnostic(
                    Severity.WARNING, "CALIBRATION_REQUIRED",
                    "A usable GNSS fix arrived but no valid calibration is in force, so the vehicle " +
                        "frame and therefore the vehicle heading are unknown. The filter stays " +
                        "uninitialized rather than reporting an attitude it cannot justify.",
                )
            }
            return
        }
        val altitude = payload.altitude_m
        val ellipsoidalAltitude = altitude?.takeIf {
            it.isFinite() && payload.altitude_reference == AltitudeReference.ELLIPSOID
        }
        val anchor = GeodeticAnchor(
            latitudeDeg = payload.latitude_deg,
            longitudeDeg = payload.longitude_deg,
            altitudeM = ellipsoidalAltitude ?: 0.0,
            hasAltitude = ellipsoidalAltitude != null,
        )
        val initial = initialState(payload, anchor, mounting)
        if (!filter.align(initial, tNs)) {
            emitDiagnostic(
                Severity.ERROR, "FUSION_ALIGNMENT_FAILED",
                "The alignment state was rejected because a variance was non-positive or " +
                    "non-finite. The filter stays uninitialized.",
            )
            return
        }
        // The anchor itself came from a fix, so the solution is GNSS-aided from this moment on,
        // and the freshness clock starts here: a fix accepted as the anchor is still a fix the
        // solution is riding on. It stays true through an outage; what reports that no GNSS is
        // currently being used is the status, which falls to DEGRADED.
        gnssUsed = true
        lastAcceptedGnssNs = tNs
        lastAcceptedGnssProvider = payload.provider
        emitDiagnostic(
            Severity.INFO, "FUSION_ALIGNED",
            "Aligned on a ${payload.provider} fix reporting ${number(payload.horizontal_accuracy_m)} m " +
                "horizontal accuracy" +
                (if (ellipsoidalAltitude != null) ""
                else " and no ellipsoidal altitude, so no vertical update is taken") +
                ". Position is relative to that anchor, and the anchor is not revisited by later fixes.",
        )
        if (initialHeadingRad == null && !announcedHeadingUnobserved) {
            announcedHeadingUnobserved = true
            emitDiagnostic(
                Severity.WARNING, "FUSION_HEADING_UNOBSERVED",
                "No initial heading was supplied, so the filter starts with a heading variance of " +
                    "half a turn. GNSS course was absent from every recorded fix, the magnetometer " +
                    "is deliberately unused, and motion alignment is not implemented, so the " +
                    "reported heading is only as good as its covariance says it is.",
            )
        }
    }

    private fun initialState(
        payload: GnssMeasurement,
        anchor: GeodeticAnchor,
        mounting: Quaternion,
    ): FusionInitialState {
        val heading = initialHeadingRad
        // The vehicle frame's forward axis must land on the ENU direction (sin h, cos h, 0), which
        // a rotation about ENU up produces when it is turned by 90 degrees minus the heading.
        val qEnuFromVehicle = quaternionAboutZ(Math.PI / 2.0 - (heading ?: 0.0))
        val qEnuFromDevice = qEnuFromVehicle * mounting

        val speed = payload.speed_m_s
        val course = payload.bearing_deg
        val velocity = if (speed != null && course != null) {
            val courseRad = Math.toRadians(course)
            Vector3(speed * sin(courseRad), speed * kotlin.math.cos(courseRad), 0.0)
        } else {
            Vector3(0.0, 0.0, 0.0)
        }
        // Without a course the speed says how fast but not which way, so the per-axis uncertainty
        // is at least the speed itself rather than a small number pretending to be a direction.
        val velocitySigma = maxOf(speed ?: 0.0, MIN_INITIAL_VELOCITY_SIGMA_M_S)
        val accuracy = payload.horizontal_accuracy_m!!
        val horizontalSigma = accuracy * INITIAL_POSITION_SIGMA_FACTOR
        val vertical = payload.vertical_accuracy_m
        val verticalSigma = if (vertical != null && vertical > 0.0) {
            vertical * VERTICAL_SIGMA_FACTOR
        } else {
            accuracy * VERTICAL_SIGMA_FACTOR
        }
        // Half a turn when no heading was supplied: the filter does not claim to know it.
        val headingVariance = if (heading == null) HALF_TURN_VARIANCE_RAD2 else LEVEL_HEADING_VARIANCE_RAD2
        return FusionInitialState(
            anchor = anchor,
            qEnuFromDevice = qEnuFromDevice,
            velocityEnuM_S = velocity,
            gyroBiasDeviceRad_S = priorGyroBias ?: Vector3(0.0, 0.0, 0.0),
            accelBiasDeviceM_S2 = priorAccelBias ?: Vector3(0.0, 0.0, 0.0),
            positionVarianceM2 = Vector3(
                horizontalSigma * horizontalSigma,
                horizontalSigma * horizontalSigma,
                verticalSigma * verticalSigma,
            ),
            velocityVarianceM2 = Vector3(
                velocitySigma * velocitySigma,
                velocitySigma * velocitySigma,
                velocitySigma * velocitySigma,
            ),
            attitudeVarianceRad2 = Vector3(
                LEVEL_TILT_VARIANCE_RAD2, LEVEL_TILT_VARIANCE_RAD2, headingVariance,
            ),
            gyroBiasVarianceRad2_S2 =
            if (priorGyroBias != null) GYRO_BIAS_PRIOR_VARIANCE else GYRO_BIAS_UNKNOWN_VARIANCE,
            accelBiasVarianceM2_S4 =
            if (priorAccelBias != null) ACCEL_BIAS_PRIOR_VARIANCE else ACCEL_BIAS_UNKNOWN_VARIANCE,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Updates
    // ---------------------------------------------------------------------------------------

    /** Apply the fix's position, and its velocity only in the forms that carry meaning. */
    private fun applyFix(payload: GnssMeasurement, tNs: Long) {
        val accuracy = payload.horizontal_accuracy_m!!
        // Location.getAccuracy() is the provider's own 68% horizontal radius, so it is a
        // one-sigma figure and the variance is its square. No conversion is applied.
        val vertical = payload.vertical_accuracy_m
        val position = filter.updateGnssPosition(
            tNs = tNs,
            latitudeDeg = payload.latitude_deg,
            longitudeDeg = payload.longitude_deg,
            altitudeM = payload.altitude_m,
            horizontalVarianceM2 = accuracy * accuracy,
            verticalVarianceM2 = if (vertical != null && vertical > 0.0) vertical * vertical else null,
        )
        when (position.result) {
            GnssUpdateResult.ACCEPTED -> {
                lastAcceptedGnssNs = tNs
                lastAcceptedGnssProvider = payload.provider
                lastCorrectionM = position.correctionM
                lastAcceptedGnssSpeedM_S = payload.speed_m_s?.takeIf { it.isFinite() && it >= 0.0 }
                gnssUsed = true
            }

            GnssUpdateResult.REJECTED_GATE -> reportRejection("position", position, tNs)

            else -> recordRefusal(
                when (position.result) {
                    GnssUpdateResult.REJECTED_INVALID -> "MEASUREMENT_INVALID"
                    GnssUpdateResult.REJECTED_ILL_CONDITIONED -> "INNOVATION_COVARIANCE_ILL_CONDITIONED"
                    GnssUpdateResult.NOT_ALIGNED -> "FILTER_NOT_ALIGNED"
                    GnssUpdateResult.FAILED -> "FILTER_FAILED"
                    else -> "REFUSED"
                },
                tNs,
            )
        }

        val speed = payload.speed_m_s
        if (speed != null && speed.isFinite() && speed >= 0.0) {
            val speedVariance = maxOf(accuracy * accuracy, SPEED_VARIANCE_FLOOR_M2_S2)
            val course = payload.bearing_deg
            val outcome = if (course != null && course.isFinite()) {
                filter.updateGnssCourseVelocity(tNs, speed, course, speedVariance)
            } else {
                // Speed with no course is a magnitude, so the filter applies it along its own
                // heading. While the vehicle is not moving that direction does not exist and the
                // filter refuses the row, which is correct rather than a defect.
                filter.updateGnssSpeed(tNs, speed, speedVariance)
            }
            if (outcome.result == GnssUpdateResult.REJECTED_GATE) {
                reportRejection("velocity", outcome, tNs)
            }
        }
        announceFailureIfAny()
    }

    /**
     * Apply the zero-velocity update and the non-holonomic constraints when, and only when,
     * their conditions hold.
     *
     * ## The gate, condition by condition
     *
     * Every declared gate input from `docs/PS26168_Non_ML_Baseline_Navigation_System.md`
     * sections 5–6 is checked, and nothing is forced past one:
     *
     * - **stationary state** — the rolling detector must report a full quiet window (declared
     *   2 s resting rule); the ZUPT stands down the instant any sample violates it.
     * - **speed** — the filter's own speed must be below the creep bound, so a vehicle moving
     *   smoothly enough to fool the IMU is not frozen by the constraint ("slow creep
     *   interpreted as stopped" is the declared failure mode); ZUPT additionally stands down
     *   when a fresh accepted GNSS fix reported real speed.
     * - **calibration validity** — the vehicle frame does not exist without a valid calibration
     *   (the engine never reaches TRACKING without one), so the NHC rows — which are vehicle-
     *   frame claims — cannot be formed; the ZUPT needs no frame and remains available.
     * - **low-speed parking/reverse** — below the NHC speed floor the vehicle-frame axes are
     *   unreliable (parking maneuvers and reversing violate the model), so NHC stands down;
     *   the ZUPT does not, because a parked or slowly maneuvering vehicle is exactly where a
     *   zero-velocity claim is strongest.
     * - **unusual maneuvers** — above the yaw-rate bound NHC stands down (sideslip, skids and
     *   aggressive turns violate lateral≈0); again the ZUPT is indifferent.
     * - **cadence** — constraints apply at most once per interval, so a stationary period
     *   does not hammer the filter at sample rate.
     *
     * Nothing here forces a correction: each pseudo-measurement goes through the same joint
     * NIS gate and Joseph update as a GNSS row, with a deliberately loose variance, and a
     * refused constraint is counted as a constraint refusal — never as a GNSS rejection and
     * never as a persistent-rejection span extension. The master switch
     * [FusionConfig.motionConstraintsEnabled] turns the whole path off for the A/B comparison.
     */
    private fun applyMotionConstraints(tNs: Long, accelDeviceM_S2: Vector3, gyroDeviceRad_S: Vector3) {
        if (!config.motionConstraintsEnabled) return
        val solution = filter.solution
        if (solution.status == FusionStatus.FAILED) return
        val due = tNs - lastConstraintNs >= (config.constraintIntervalS * 1e9).toLong()
        if (!due) return

        val detectorQuiet = stationaryDetector.stationary
        val speed = sqrt(
            solution.velocityEnuM_S.x * solution.velocityEnuM_S.x +
                solution.velocityEnuM_S.y * solution.velocityEnuM_S.y,
        )
        // Constraint variances are inflated with GNSS staleness — but only the NHC rows. The
        // zero-velocity claim is *strongest* exactly when GNSS is long gone and the vehicle is
        // parked: bounding the outage-stop drift is the ZUPT's whole purpose, so it keeps its
        // tight variance. The non-holonomic rows are the other way: applied every cadence
        // interval they make the constrained velocity error look short-correlated to the
        // filter, so the position variance they integrally produce grows more slowly than the
        // real cross-track error of a drifting vehicle. Left uncorrected, the filter arrives
        // at the end of a long outage confident and wrong, and the recovery fix fails the
        // gate — observed in the A/B outage evaluation as a full recovery refusal and a guard
        // trip. Inflating the NHC variance with staleness keeps it authoritative over a short
        // fix gap and progressively humbler over a long one, which is the "tune conservatively"
        // direction the design document prescribes.
        val stalenessS = lastAcceptedGnssNs?.let { (tNs - it) / 1e9 } ?: 0.0
        val inflation =
            sqrt(1.0 + stalenessS / config.constraintStalenessScaleS)
                .coerceAtMost(config.constraintMaxSigmaInflation)
        val inflation2 = inflation * inflation
        var appliedAny = false
        var attemptedAny = false
        val gnssFresh = lastAcceptedGnssNs != null &&
            (tNs - lastAcceptedGnssNs!!) <= (config.nhcMaxGnssAgeS * 1e9).toLong()

        // ---- Zero-velocity update -------------------------------------------------------
        // Needs no vehicle frame, so it is available regardless of calibration validity.
        // The speed gate is absolute and hysteretic, not variance-relative: a gate referenced
        // to the filter's own sigma would let the ZUPT "re-arm" on a moving vehicle once an
        // outage inflated that sigma past the innovation threshold — the stage-7 lesson that
        // a gate must never trust the covariance it is gating (observed in the A/B
        // evaluation: every outage cruise run dragged to zero that way). The hysteresis band
        // between the engage threshold and the NHC floor exists because a real stop carries
        // a bounded entry error (~1 m/s of braking-estimate lag): locked strictly below 0.5
        // m/s, the ZUPT is locked out of exactly the stop it should own and the error then
        // integrates freely (observed: 420 m of parked drift). Engage below 0.5, hold the
        // verdict through the band, disengage above the NHC floor — a creeping vehicle below
        // the engage threshold is still frozen, which is the declared IMU-only limitation
        // that only GNSS speed can arbitrate when fixes exist.
        if (speed < config.zuptSpeedThresholdM_S) {
            zuptEngaged = true
        } else if (speed >= config.nhcMinSpeedM_S) {
            zuptEngaged = false
        }
        val zuptAllowed = detectorQuiet &&
            zuptEngaged &&
            !(gnssFresh && (lastAcceptedGnssSpeedM_S ?: 0.0) > config.zuptMaxGnssSpeedM_S)
        if (zuptAllowed) {
            attemptedAny = true
            val outcome = filter.updateZupt(tNs, config.zuptVarianceM2)
            if (outcome.result != GnssUpdateResult.REJECTED_GATE &&
                outcome.result != GnssUpdateResult.REJECTED_INVALID &&
                outcome.result != GnssUpdateResult.REJECTED_ILL_CONDITIONED
            ) {
                appliedAny = true
            }
        }

        // ---- Non-holonomic constraints --------------------------------------------------
        // Vehicle-frame claims: they require the calibration, real motion for the axes to be
        // meaningful, and benign dynamics — no hard turning, and no sustained acceleration,
        // braking or grade, which the measured longitudinal specific force detects causally.
        // While the stationary detector reports a parked vehicle the NHC is semantically
        // void — a car at rest has no motion direction to constrain, and the ZUPT owns that
        // regime. The detector alone cannot be the gate, though: level cruise is IMU-identical
        // to parked. The supporting evidence that separates them is the GNSS course-velocity
        // row — fresh while driving under fixes, absent in an outage. Letting the NHC run
        // without it is not a harmless redundancy: observed in the A/B outage evaluation, a
        // filter whose speed error had grown past the NHC floor while stopped re-opened its
        // own speed gate and constrained a parked vehicle along a corrupted attitude, each
        // acceptance rotating the attitude further in a feedback loop that no IMU-only signal
        // could arrest.
        // The benign conditions must also hold CONTIGUOUSLY for a dwell before NHC (re)starts:
        // the first constraint after a dynamic phase otherwise meets cross-covariances built
        // while it was off and corrects the accumulated tilt in one violent step, whose gravity
        // leakage then appears as real lateral acceleration. After a refused update NHC stands
        // down for a backoff instead of re-offering at the cadence and fighting the filter.
        val mounting = mountingQVehicleFromDevice
        val yawNorm = gyroDeviceRad_S.norm()
        val forwardDevice = mounting?.toRotationMatrix()
        val forwardAccel = forwardDevice?.let {
            it[0] * accelDeviceM_S2.x + it[1] * accelDeviceM_S2.y + it[2] * accelDeviceM_S2.z
        }
        val benign = forwardAccel != null &&
            abs(forwardAccel) <= config.nhcMaxForwardAccelM_S2 &&
            yawNorm <= config.nhcMaxYawRateRad_S
        nhcBenignSinceNs = when {
            !benign -> null
            nhcBenignSinceNs == null -> tNs
            else -> nhcBenignSinceNs
        }
        val dwellSatisfied = nhcBenignSinceNs != null &&
            (tNs - nhcBenignSinceNs!!) >= (config.nhcDwellS * 1e9).toLong()
        if (mounting != null &&
            speed >= config.nhcMinSpeedM_S &&
            gnssFresh &&
            dwellSatisfied &&
            tNs >= nhcSuspendedUntilNs
        ) {
            attemptedAny = true
            val qEnuFromVehicle = solution.qEnuFromDevice * mounting.conjugate()
            val outcome = filter.updateNhc(
                tNs,
                qEnuFromVehicle,
                config.nhcLateralVarianceM2 * inflation2,
                config.nhcVerticalVarianceM2 * inflation2,
            )
            if (outcome.result != GnssUpdateResult.REJECTED_GATE &&
                outcome.result != GnssUpdateResult.REJECTED_INVALID &&
                outcome.result != GnssUpdateResult.REJECTED_ILL_CONDITIONED
            ) {
                appliedAny = true
            } else {
                nhcSuspendedUntilNs = tNs + (config.nhcBackoffS * 1e9).toLong()
            }
        }

        // The cadence advances on every attempt, applied or refused: a constraint the filter
        // keeps refusing must be re-offered at the cadence rate, not hammered at sample rate.
        if (attemptedAny) lastConstraintNs = tNs
        announceConstraintOutcome(solution)
    }

    /** A fresh accepted fix with real speed contradicts a stationary claim. */
    private fun gnssSpeedContradictsStillness(): Boolean {
        val lastAccepted = lastAcceptedGnssNs ?: return false
        val fresh = clockNs - lastAccepted <= DEFAULT_FRESHNESS_NS
        val speed = lastAcceptedGnssSpeedM_S ?: return false
        return fresh && speed > config.zuptMaxGnssSpeedM_S
    }

    /** One diagnostic per run direction, not one per sample. */
    private fun announceConstraintOutcome(solution: FusionSolution) {
        if (solution.constraintAccepted > 0 && !constraintAcceptedAnnounced) {
            constraintAcceptedAnnounced = true
            emitDiagnostic(
                Severity.INFO, "FUSION_CONSTRAINT_APPLIED",
                "Vehicle-motion constraints (zero-velocity update and/or non-holonomic " +
                    "lateral/vertical) began contributing gated pseudo-measurements. Each one " +
                    "passes the same innovation gate as a GNSS row with a deliberately loose " +
                    "variance, so a wrongly-fired constraint degrades the solution instead of " +
                    "freezing it.",
            )
        }
        if (solution.constraintRejected > 0 && !constraintRejectedAnnounced) {
            constraintRejectedAnnounced = true
            emitDiagnostic(
                Severity.WARNING, "FUSION_CONSTRAINT_REJECTED",
                "A vehicle-motion constraint failed its innovation gate and was discarded " +
                    "without touching the state. Constraint refusals never count against the " +
                    "GNSS rejection counters or the persistent-rejection guard: a " +
                    "pseudo-measurement disagreeing with the filter is a model disagreement, " +
                    "not a lying fix.",
            )
        }
    }

    /**
     * Report a run the filter has given up on, once.
     *
     * A refusal stretch long enough to trip the filter's guard is raised inside a measurement
     * update rather than during propagation, so it needs its own announcement; without it the
     * status would fall to `failed` silently.
     */
    private fun announceFailureIfAny() {
        val failure = filter.solution.failure ?: return
        if (failureAnnounced) return
        failureAnnounced = true
        emitDiagnostic(
            Severity.ERROR, "ENGINE_FAILURE",
            "Fusion stopped: ${failure.code}. No state beyond this point is claimed; acquisition " +
                "and recording are unaffected, and recovery requires a deliberate re-alignment.",
        )
    }

    private fun reportRejection(kind: String, outcome: GnssUpdateOutcome, tNs: Long) {
        rejectedSinceDiagnostic += 1
        val lastGate = lastDiagnosticGateNs
        if (lastGate != null && tNs >= lastGate && tNs - lastGate < REJECTION_DIAGNOSTIC_NS) return
        val count = rejectedSinceDiagnostic
        rejectedSinceDiagnostic = 0
        lastDiagnosticGateNs = tNs
        emitDiagnostic(
            Severity.WARNING, "GNSS_UPDATE_REJECTED",
            "$count $kind update(s) failed the innovation gate and were discarded whole. The " +
                "latest normalised innovation squared was ${number(outcome.nis)} against a " +
                "chi-square threshold of ${number(outcome.gateThreshold)} on " +
                "${outcome.dimension} degree(s) of freedom, from an innovation of " +
                "${number(outcome.innovationM)} m. Nothing was applied partially and the state " +
                "was not moved toward the rejected measurement.",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Publication
    // ---------------------------------------------------------------------------------------

    private fun publish(force: Boolean) {
        if (header == null) return
        val solution = filter.solution
        val status = navigationStatus(solution)
        val statusChanged = status != publishedStatus
        val due = solution.tNs - publishedAtNs >= publicationIntervalNs
        if (!force && !due && !statusChanged) return
        publishedAtNs = solution.tNs
        publishedStatus = status
        emit(navigationState(solution, status))
        emit(confidence(solution))
        if (statusChanged) announceStatus(status, solution)
    }

    private fun announceStatus(status: NavigationStatus, solution: FusionSolution) {
        if (status == NavigationStatus.DEGRADED && solution.aligned && lastAcceptedGnssNs != null &&
            solution.failure == null && !outageAnnounced
        ) {
            outageAnnounced = true
            emitDiagnostic(
                Severity.WARNING, "FUSION_GNSS_OUTAGE",
                "No GNSS update has been accepted within this provider's stale bound, so the " +
                    "solution is prediction-only. The covariance grows with the unobserved motion " +
                    "and the position is dead reckoning, not a measurement.",
            )
        }
        if (status == NavigationStatus.TRACKING && outageAnnounced) {
            outageAnnounced = false
            emitDiagnostic(
                Severity.INFO, "FUSION_GNSS_RECOVERED",
                "GNSS returned and updates are being accepted again. The last position correction " +
                    "was ${number(lastCorrectionM)} m, applied through the Kalman gain: the state " +
                    "was corrected toward the fixes, not replaced by one.",
            )
        }
    }

    /**
     * The contract's status, derived from what the filter knows and what the provider promised.
     *
     * `TRACKING` requires the whole solution: an anchor, a position, a velocity, the vehicle
     * attitude and the calibration id, which is why a session without a valid calibration can
     * never reach it however good its fixes are. Freshness uses the same per-provider stale bound
     * the quality state machine uses, so a 0.05 Hz network channel is not called an outage for
     * being slow where a 1 Hz GPS channel would be.
     */
    private fun navigationStatus(solution: FusionSolution): NavigationStatus = when {
        solution.status == FusionStatus.FAILED -> NavigationStatus.FAILED
        !solution.aligned -> NavigationStatus.UNINITIALIZED
        trackingFreshnessSatisfied(solution) -> NavigationStatus.TRACKING
        else -> NavigationStatus.DEGRADED
    }

    private fun trackingFreshnessSatisfied(solution: FusionSolution): Boolean {
        if (mountingQVehicleFromDevice == null || calibrationId == null) return false
        // The engine's own acceptance clock, not the filter's: alignment accepts a fix as the
        // anchor without running an update, and the status must not report an outage in the
        // moments after aligning on a fix the solution is literally standing on.
        val lastUpdate = lastAcceptedGnssNs ?: return false
        val freshness = qualityPolicy.staleAfterNs(lastAcceptedGnssProvider) ?: DEFAULT_FRESHNESS_NS
        return solution.tNs - lastUpdate <= freshness
    }

    private fun navigationState(solution: FusionSolution, status: NavigationStatus): NavigationState {
        val origin = solution.anchor
        if (!solution.aligned || origin == null || status == NavigationStatus.FAILED) {
            // Uninitialized, failed and unaligned all resolve here. A failed run still has an
            // anchor and a state, so it would otherwise publish them; the contract forbids that,
            // and it is right to: the whole point of the failure is that the state has stopped
            // describing the vehicle, and a position that is still finite is not a position that
            // can be trusted.
            return NavigationState(
                status = status,
                initialization_mode = mode,
                origin_wgs84_deg_m = null,
                position_enu_m = null,
                velocity_enu_m_s = null,
                q_enu_from_vehicle_wxyz = null,
                heading_deg = null,
                calibration_id = null,
                gnss_used_after_initialization = gnssUsed,
            )
        }
        // The filter's attitude is ENU-from-device; the contract's is ENU-from-vehicle, which
        // exists only once the mounting transform is known.
        val qEnuFromVehicle = mountingQVehicleFromDevice?.let { solution.qEnuFromDevice * it.conjugate() }
        return NavigationState(
            status = status,
            initialization_mode = mode,
            origin_wgs84_deg_m = GeoOrigin(origin.latitudeDeg, origin.longitudeDeg, origin.altitudeM),
            position_enu_m = solution.positionEnuM,
            velocity_enu_m_s = solution.velocityEnuM_S,
            q_enu_from_vehicle_wxyz = qEnuFromVehicle,
            heading_deg = qEnuFromVehicle?.let { headingDegrees(it) },
            calibration_id = calibrationId,
            gnss_used_after_initialization = gnssUsed,
        )
    }

    /**
     * The covariance restated in the contract's confidence terms.
     *
     * `horizontal_accuracy_95_m` is a **stated conversion from one sigma**, not a provider figure
     * and not a validated error bound. The error model is the standard one: horizontal position
     * error is treated as circular Gaussian with equal-variance-equivalent sigma
     * `sqrt((sE^2 + sN^2)/2)`, and the 95% circular radius is `sqrt(chi2_2(0.95)) * sigma` with
     * `chi2_2(0.95) = 5.991`, so the factor is 2.4477. `probability` stays null because the state
     * cannot be `CALIBRATED`: the covariance is a model output, it has not been validated against
     * an independent truth, and the contract forbids a probability unless the state is calibrated.
     */
    private fun confidence(solution: FusionSolution): Confidence {
        if (!solution.aligned || solution.status == FusionStatus.FAILED) {
            return Confidence(ConfidenceState.UNAVAILABLE, null, null, null)
        }
        val east = solution.positionSigmaM.x
        val north = solution.positionSigmaM.y
        val sigma = sqrt((east * east + north * north) / 2.0)
        val speedStd = sqrt(
            solution.velocitySigmaM_S.x * solution.velocitySigmaM_S.x +
                solution.velocitySigmaM_S.y * solution.velocitySigmaM_S.y,
        )
        return Confidence(ConfidenceState.UNVALIDATED, null, CHI95_FACTOR * sigma, speedStd)
    }

    private fun headingDegrees(qEnuFromVehicle: Quaternion): Double {
        val forward = qEnuFromVehicle.rotate(Vector3(1.0, 0.0, 0.0))
        return normalizeDegrees360(Math.toDegrees(atan2(forward.x, forward.y)))
    }

    private fun emitDiagnostic(severity: Severity, code: String, message: String) {
        emit(DiagnosticEvent(severity, code, message, 0))
    }

    private fun emit(data: Payload) {
        val header = header ?: return
        pending.addLast(Record(header, Event((nextEventId++).toString(), clockNs, clockNs, data)))
    }

    override fun drain(): Sequence<Record> {
        val out = pending.toList()
        pending.clear()
        return out.asSequence()
    }

    private companion object {
        /** Acquisition requests 100 Hz on both sensors; this tolerates jitter between streams. */
        const val MAX_PAIR_SKEW_NS = 30_000_000L
        const val UNMATCHED_WARN = 100L
        const val REJECTION_DIAGNOSTIC_NS = 1_000_000_000L
        const val DEFAULT_FRESHNESS_NS = 3_000_000_000L
        const val MIN_INITIAL_VELOCITY_SIGMA_M_S = 2.0
        const val INITIAL_POSITION_SIGMA_FACTOR = 1.0
        const val VERTICAL_SIGMA_FACTOR = 3.0
        const val SPEED_VARIANCE_FLOOR_M2_S2 = 1.0

        /** Ten degrees of heading uncertainty when a heading was actually supplied. */
        val LEVEL_HEADING_VARIANCE_RAD2 = Math.toRadians(10.0) * Math.toRadians(10.0)
        val LEVEL_TILT_VARIANCE_RAD2 = Math.toRadians(5.0) * Math.toRadians(5.0)

        /** Half a turn, the honest prior when nothing can supply a heading. */
        val HALF_TURN_VARIANCE_RAD2 = Math.PI * Math.PI

        /**
         * Bias priors, scaled to what a real consumer sensor's residual bias is.
         *
         * These variances are load-bearing rather than cosmetic. The accelerometer bias is
         * unobservable during an outage, so its variance integrates into the position covariance
         * without bound, and a loose prior therefore inflates the position uncertainty until the
         * innovation gate opens and a grossly biased fix can drag the state. A measured example:
         * with a 0.5 m/s^2 prior sigma, a 200 m biased fix at 10 m accuracy was accepted after
         * eight seconds of prediction-only, moving the state 207 m. With the realistic values
         * below, the same measurement stays gated out.
         *
         * `PRIOR` is used when the calibration actually supplied a bias; `UNKNOWN` when it did
         * not, which is the common case because a single resting attitude cannot separate an
         * accelerometer bias from tilt.
         */
        const val GYRO_BIAS_PRIOR_VARIANCE = 1.0e-6
        const val GYRO_BIAS_UNKNOWN_VARIANCE = 1.0e-4
        const val ACCEL_BIAS_PRIOR_VARIANCE = 1.0e-3
        const val ACCEL_BIAS_UNKNOWN_VARIANCE = 1.0e-2

        /** `sqrt(chi2_2(0.95))`, the circular 95% factor for a two-dimensional Gaussian. */
        val CHI95_FACTOR = sqrt(5.991)

        fun number(value: Double?): String =
            if (value != null && value.isFinite()) String.format(Locale.US, "%.3f", value) else "n/a"
    }
}
