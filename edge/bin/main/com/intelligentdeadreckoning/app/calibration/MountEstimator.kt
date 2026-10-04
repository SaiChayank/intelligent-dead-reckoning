package com.intelligentdeadreckoning.app.calibration

import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Severity
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A GNSS fix reduced to the fields calibration can defend using.
 *
 * Nothing here is a position solution: latitude and longitude exist only to measure the
 * direction and length of a straight baseline, which is what validates a course.
 */
data class GnssFix(
    val tNs: Long,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val speedM_S: Double?,
    val bearingDeg: Double?,
    val horizontalAccuracyM: Double?,
)

/** A calibration decision worth telling the operator about, with the reason attached. */
data class CalibrationDiagnostic(val severity: Severity, val code: String, val message: String)

/**
 * One published calibration. [status] and [qVehicleFromDevice] are the contract's fields; the
 * rest is what this engine needs to decide and explain.
 *
 * `yawMeasured` is false when the transform's rotation about vehicle up is a deterministic
 * placeholder rather than something measured, which is the difference between a pending and a
 * valid calibration.
 */
data class CalibrationOutcome(
    val id: String,
    val status: CalibrationStatus,
    val qVehicleFromDevice: Quaternion?,
    val gyroBiasRad_S: Vector3?,
    val accelBiasM_S2: Vector3?,
    val confidence: Double?,
    val yawMeasured: Boolean,
)

/**
 * Thresholds for the mount estimate. Every value is a documented engineering choice for
 * phone-grade MEMS, not a measured constant, and they are injectable so tests drive the gates
 * directly instead of depending on production margins.
 *
 * @param maxGyroBiasSpreadRad_S windows whose bias estimates disagree by more than this are
 *   reported as inconsistent (temperature drift, or a window that was not really at rest)
 * @param minBiasWindows fewest stationary windows before an accelerometer bias is even attempted
 * @param minBiasCondition smallest-to-largest pivot ratio accepted in the bias solve; a
 *   coplanar set of attitudes cannot separate bias from gravity at all
 * @param maxAccelBiasM_S2 a solved bias larger than this is treated as a broken estimate
 * @param minAccelBiasM_S2 magnitude floor for a *justified* bias; mean-of-window sampling noise
 *   on a resting accelerometer is a few thousandths of a m/s^2, so a smaller value is not
 *   distinguishable from that noise and is reported as null instead
 * @param maxGravitySpreadM_S2 consistency required of `|a - bias|` across the bias windows
 * @param movingSpeedM_S ground speed above which a vehicle is considered to be driving
 * @param parkedSpeedM_S ground speed below which handling detection may accumulate rotation
 * @param maxYawRateRad_S yaw rate about local up below which a sample counts as straight motion
 * @param minStraightFraction fraction of a segment's samples that must be straight
 * @param minExcitationM_S2 horizontal specific force that makes a forward direction measurable
 * @param minSpeedSlopeM_S2 minimum ground-speed rate that makes the excitation's sign trustworthy
 * @param maxForceSpeedRatio magnitude agreement between horizontal specific force and speed rate
 * @param maxCourseDeviationDeg how far a segment's reported course may wander from its first
 * @param maxCourseDisplacementDeg disagreement between reported course and measured displacement
 * @param maxGnssAccuracyM worst reported horizontal accuracy accepted inside a segment
 * @param minBaselineM displacement needed before a course is compared against it
 * @param minGnssFixes fewest accurate fixes a segment needs
 * @param minSpeedSamples fewest speed samples behind a slope fit
 * @param maxSegmentS longest segment retained; bounds per-segment memory
 * @param yawAgreementDeg accepted segments whose yaws disagree by more than this are contradictory
 * @param orientationPublishDeg orientation change that makes a new calibration worth publishing
 * @param remountTiltDeg tilt change that cannot be the same mounting
 * @param remountRotationDeg accumulated parked rotation that means the phone was handled
 * @param handlingRateRad_S rate above which a parked gyro sample counts as handling, not idle
 * @param strongRotationRad_S rate above which rotation counts as handling even with no speed
 * @param pendingConfidenceCap ceiling on a pending calibration's confidence, because yaw is unmeasured
 */
data class MountThresholds(
    val maxGyroBiasSpreadRad_S: Double = 0.01,
    val minBiasWindows: Int = 3,
    val minBiasCondition: Double = 0.05,
    val maxAccelBiasM_S2: Double = 0.5,
    val minAccelBiasM_S2: Double = 0.02,
    val maxGravitySpreadM_S2: Double = 0.05,
    val movingSpeedM_S: Double = 3.0,
    val parkedSpeedM_S: Double = 1.0,
    val maxYawRateRad_S: Double = 0.03,
    val minStraightFraction: Double = 0.9,
    val minExcitationM_S2: Double = 0.3,
    val minSpeedSlopeM_S2: Double = 0.2,
    val minForceSpeedRatio: Double = 0.4,
    val maxForceSpeedRatio: Double = 2.5,
    val maxCourseDeviationDeg: Double = 10.0,
    val maxCourseDisplacementDeg: Double = 15.0,
    val maxGnssAccuracyM: Double = 15.0,
    val minBaselineM: Double = 30.0,
    val minGnssFixes: Int = 3,
    val minSpeedSamples: Int = 3,
    val maxSegmentS: Double = 120.0,
    val yawAgreementDeg: Double = 15.0,
    val orientationPublishDeg: Double = 2.0,
    val remountTiltDeg: Double = 10.0,
    val remountRotationDeg: Double = 20.0,
    val handlingRateRad_S: Double = 0.15,
    val strongRotationRad_S: Double = 0.5,
    val pendingConfidenceCap: Double = 0.5,
    val minSegmentsEvaluation: Int = 1,
    val minSegmentsDeployable: Int = 2,
    val maxRecentWindows: Int = 8,
    val maxBiasAttitudes: Int = 8,
    val maxYawEvidence: Int = 16,
)

/**
 * The phone-to-vehicle calibration estimator: physics first, no learning.
 *
 * ## What it measures, and what it refuses to invent
 *
 * The target vehicle frame is X forward, Y left, Z up, right-handed. The engine identifies the
 * rotation `q_vehicle_from_device` from two independent physical facts:
 *
 * **Stage A — stationary.** An accelerometer at rest measures the gravity reaction, so its mean
 * direction is the world "up" axis expressed in device coordinates. That fixes two of the three
 * rotational degrees of freedom (tilt) and nothing more: rotation about the vertical axis is
 * invisible to an accelerometer. A phone at rest also measures its gyroscope bias directly.
 * The remaining yaw is left at the deterministic minimal-rotation value and is *reported as
 * unmeasured*; a calibration that has only Stage A evidence is `PENDING`, never `VALID`.
 *
 * **Stage B — dynamic.** During straight, well-excited driving, the component of specific force
 * perpendicular to local up is the vehicle's horizontal acceleration, which is directed along
 * the vehicle's longitudinal axis. Its direction in device coordinates therefore fixes the yaw
 * that gravity could not, and the sign is taken from measured ground-speed rate (speeding up
 * means the force points forward; braking means it points backward). GNSS supplies gravity-
 * independent evidence for whether the motion really was straight and which way it pointed:
 * the reported course must agree with the displacement actually measured between fixes.
 *
 * ## Rejections are the product, not an error path
 *
 * Insufficient excitation, sparse fixes, unstable course, course that contradicts displacement,
 * and segments that disagree with each other are all *rejected with a named reason*. The
 * estimator never averages its way past a gate, because a yaw produced from unvalidated
 * evidence is worse than no yaw: it looks like a result.
 *
 * ## Limitations stated rather than hidden
 *
 * - Earth rotation rate is not compensated. At phone gyro bias levels (orders of magnitude
 *   larger) it is below the noise floor; its worst-case contribution to yaw is a few
 *   thousandths of a degree per second.
 * - The magnetometer and the vendor gravity sensor are deliberately unused: magnetic yaw needs
 *   hard/soft-iron calibration plus declination, and the vendor gravity vector is somebody
 *   else's fusion product rather than a measurement this engine can audit.
 * - Yaw evidence only exists for forward travel. Reversing makes course-over-ground point
 *   backward, so segments are gated on sustained speed above `movingSpeedM_S`.
 * - A change in tilt is detectable at rest; a rotation *about* the vertical axis is not, so an
 *   in-place remount is detected from handling rotation (integrated gyro while parked) instead
 *   of from gravity, and requires the same dynamic evidence to re-establish.
 */
class MountEstimator(
    private val thresholds: MountThresholds = MountThresholds(),
    private val mode: InitializationMode,
    calibrationIdPrefix: String,
) {
    private val idPrefix = calibrationIdPrefix

    // ---- Stage A evidence -------------------------------------------------------------------

    /** Recent resting windows describing the current mounting. Cleared by a remount. */
    private val recentWindows = ArrayDeque<StationaryWindow>()

    /** Device-frame up direction of the mounting in force, from accepted resting windows. */
    private var upDevice: Vector3? = null

    /** Minimal rotation taking [upDevice] to vehicle up; its yaw is a placeholder. */
    private var tiltRotation: Quaternion? = null

    private var tiltQuality = 0.0

    /** Gyroscope bias in device coordinates. A sensor property, so it survives a remount. */
    private var gyroBias: Vector3? = null

    /** Accelerometer bias in device coordinates, only ever set when the solve justified it. */
    private var accelBias: Vector3? = null

    /** Mean resting accelerations from distinct attitudes, for the multi-position bias solve. */
    private val biasAttitudes = ArrayDeque<Vector3>()

    // ---- Stage B evidence -------------------------------------------------------------------

    /** Yaws measured from accepted segments, in radians, bounded by `maxYawEvidence`. */
    private val yawEvidence = ArrayDeque<Double>()

    /**
     * How many independent determinations `yawEvidence` already stands for.
     *
     * An adopted prior calibration is one determination even though it was produced in an earlier
     * session, so a deployable session adopting a prior still has to earn its second segment.
     */
    private var adoptedEvidence = 0

    private var currentSegment: Segment? = null
    private var lastFixTNs: Long? = null
    private var lastMovingFixTNs: Long? = null
    private var lastSpeedM_S: Double? = null
    private var speedKnown = false

    // ---- Remount state ----------------------------------------------------------------------

    private var handlingRotationRad = 0.0
    private var lastPairTNs: Long? = null
    private var calibratedUpDevice: Vector3? = null

    // ---- Output state -----------------------------------------------------------------------

    private var status = CalibrationStatus.PENDING
    private var sequence = 1
    private var currentId = "$idPrefix-cal-1"
    private var live = CalibrationOutcome(currentId, status, null, null, null, null, false)
    private var lastPublished: CalibrationOutcome? = null
    private val outcomes = ArrayDeque<CalibrationOutcome>()
    private val diagnostics = ArrayDeque<CalibrationDiagnostic>()

    /** The latest calibration state, published or not. */
    val current: CalibrationOutcome get() = live

    fun drainOutcomes(): List<CalibrationOutcome> = outcomes.toList().also { outcomes.clear() }

    fun drainDiagnostics(): List<CalibrationDiagnostic> =
        diagnostics.toList().also { diagnostics.clear() }

    /** How many segments this mode needs before yaw may be called measured. */
    val requiredSegments: Int
        get() = if (mode == InitializationMode.DEPLOYABLE) {
            thresholds.minSegmentsDeployable
        } else {
            thresholds.minSegmentsEvaluation
        }

    // -----------------------------------------------------------------------------------------
    // Inputs
    // -----------------------------------------------------------------------------------------

    /**
     * Take over a calibration that is already in force, from an earlier session.
     *
     * The prior supplies the yaw, which cannot be re-derived at rest, and counts as one yaw
     * determination. Its tilt is deliberately *not* trusted: gravity is measurable now, so the
     * first resting window replaces it. The prior's up direction becomes the remount reference,
     * so a phone that was moved between sessions is caught by the first window that disagrees.
     *
     * The prior's **id is not taken over.** It names a transform that another session published
     * from another mounting epoch, and continuing to publish that id while this session refines
     * the transform would change what a consumer already recorded without saying so. This
     * session's calibration is published under its own id, and the caller is expected to report
     * which prior supplied the yaw.
     *
     * @return false when the transform cannot be interpreted as a rigid rotation.
     */
    fun adoptPrior(
        qVehicleFromDevice: Quaternion,
        gyroBiasRad_S: Vector3?,
        accelBiasM_S2: Vector3?,
    ): Boolean {
        if (!isProperRotation(qVehicleFromDevice, 1e-6)) return false
        val up = qVehicleFromDevice.conjugate().rotate(VEHICLE_UP).normalizedOrNull() ?: return false
        val tilt = rotationFromTo(up, VEHICLE_UP)
        // C = Rz(yaw) * tilt, so Rz = C * tilt^-1 and the yaw falls out of its (w, z) pair.
        val yawRotation = qVehicleFromDevice * tilt.conjugate()
        val yaw = 2.0 * atan2(yawRotation.z, yawRotation.w)
        if (!yaw.isFinite()) return false

        upDevice = up
        tiltRotation = tilt
        calibratedUpDevice = up
        // A prior tilt carries no stability evidence from this session, so it is scored modestly
        // rather than assumed to be as good as a window measured here.
        tiltQuality = PRIOR_TILT_QUALITY
        yawEvidence.clear()
        yawEvidence.addLast(yaw)
        adoptedEvidence = 1
        if (gyroBiasRad_S != null && gyroBiasRad_S.isFiniteVector()) gyroBias = gyroBiasRad_S
        if (accelBiasM_S2 != null && accelBiasM_S2.isFiniteVector()) accelBias = accelBiasM_S2
        live = buildOutcome(CalibrationStatus.VALID, yaw, yawMeasured = true)
        // Published immediately: the calibration is in force from this moment, and a consumer
        // that is told about it through a navigation state must also receive the calibration
        // itself, or it would hold an id nothing ever described.
        publishIfChanged()
        return true
    }

    /**
     * Accept one resting period. This is the only Stage A input, and it is also where remount
     * detection happens, because a changed mounting first shows up as a changed up direction.
     */
    fun onStationaryWindow(window: StationaryWindow) {
        val up = window.upDevice ?: return
        // GNSS ground speed is independent evidence that the vehicle was moving, and a moving
        // vehicle is not a resting gravity measurement however steady the mount felt. This is a
        // second line of defence behind the accumulator's own vibration and attitude gates: it
        // costs nothing, and it catches a drive smooth enough that nothing else would.
        if (movingNear(window.endNs)) {
            report(
                Severity.INFO, "CALIBRATION_WINDOW_MOVING",
                "Discarded a quiet window that ended while GNSS reported the vehicle moving: a " +
                    "driven phone is not a resting gravity measurement.",
            )
            return
        }
        if (status == CalibrationStatus.VALID && detectRemount(window, up)) return
        recentWindows.addLast(window)
        while (recentWindows.size > thresholds.maxRecentWindows) recentWindows.removeFirst()
        // Attitudes are kept as a ring: the bias solve needs a spread of orientations, and a
        // fixed first-N sample could be coplanar forever.
        biasAttitudes.addLast(window.meanAccelM_S2)
        while (biasAttitudes.size > thresholds.maxBiasAttitudes) biasAttitudes.removeFirst()
        updateTilt()
        updateGyroBias()
        updateAccelBias()
        if (status == CalibrationStatus.INVALID) {
            // New evidence re-opens a rejected calibration; it is not silently reused.
            setStatus(CalibrationStatus.PENDING)
        }
        reevaluateYaw()
    }

    /**
     * Accept one paired IMU sample.
     *
     * While a segment is open the sample contributes to it only if the vehicle is not yawing, so
     * a turn cannot leak lateral force into the longitudinal direction. Sampling is otherwise
     * untouched: no filtering, no smoothing, no resampling.
     */
    fun onImu(pair: ImuPair) {
        if (!pair.accelM_S2.isFiniteVector() || !pair.gyroRad_S.isFiniteVector()) return
        detectHandling(pair)
        val segment = currentSegment ?: return
        val up = segment.upDevice
        val rate = pair.gyroRad_S - (gyroBias ?: ZERO)
        val yawRate = rate componentAlong up
        segment.samples += 1
        segment.maxYawRateRad_S = max(segment.maxYawRateRad_S, abs(yawRate))
        if (abs(yawRate) > thresholds.maxYawRateRad_S) return
        val specific = pair.accelM_S2 - (accelBias ?: ZERO)
        segment.straightSamples += 1
        segment.forceSum = segment.forceSum + specific.removeComponentAlong(up)
    }

    /**
     * Accept one GNSS fix, opening, extending or closing a candidate straight segment.
     *
     * A segment is a run of fixes above `movingSpeedM_S` with no long gap. Its length is capped
     * by `maxSegmentS` so per-segment memory stays bounded no matter how long a drive is.
     */
    fun onGnss(fix: GnssFix) {
        if (!isPlausible(fix)) return
        lastSpeedM_S = fix.speedM_S
        speedKnown = fix.speedM_S != null
        val up = upDevice ?: return
        if ((fix.speedM_S ?: 0.0) >= thresholds.movingSpeedM_S) lastMovingFixTNs = fix.tNs
        val eligible = isEligible(fix)
        val segment = currentSegment
        if (segment == null) {
            if (eligible) startSegment(up, fix)
            return
        }
        val gapNs = lastFixTNs?.let { fix.tNs - it } ?: 0L
        val timedOut = (fix.tNs - segment.startNs) / 1e9 > thresholds.maxSegmentS
        if (timedOut || gapNs > GNSS_GAP_NS || !eligible) {
            closeSegment()
            if (eligible) startSegment(up, fix)
            return
        }
        acceptFix(segment, fix)
        lastFixTNs = fix.tNs
    }

    private fun startSegment(up: Vector3, fix: GnssFix) {
        val fresh = Segment(up, fix.tNs)
        currentSegment = fresh
        acceptFix(fresh, fix)
        lastFixTNs = fix.tNs
    }

    /** A fix can only contribute to a segment when it proves the vehicle is actually driving. */
    private fun isEligible(fix: GnssFix): Boolean {
        val speed = fix.speedM_S ?: return false
        return speed.isFinite() && speed >= thresholds.movingSpeedM_S
    }

    /** Close anything still open, so a session that ends mid-manoeuvre does not lose evidence. */
    fun flush() {
        closeSegment()
    }

    fun reset() {
        recentWindows.clear()
        biasAttitudes.clear()
        yawEvidence.clear()
        adoptedEvidence = 0
        currentSegment = null
        lastFixTNs = null
        lastMovingFixTNs = null
        lastSpeedM_S = null
        speedKnown = false
        upDevice = null
        tiltRotation = null
        gyroBias = null
        accelBias = null
        tiltQuality = 0.0
        handlingRotationRad = 0.0
        lastPairTNs = null
        calibratedUpDevice = null
        status = CalibrationStatus.PENDING
        sequence = 1
        currentId = "$idPrefix-cal-1"
        outcomes.clear()
        diagnostics.clear()
        live = CalibrationOutcome(currentId, status, null, null, null, null, false)
        lastPublished = null
    }

    // -----------------------------------------------------------------------------------------
    // Stage A: tilt, gyro bias, accelerometer bias
    // -----------------------------------------------------------------------------------------

    /**
     * Refine the up direction from resting windows that agree with the most recent one.
     *
     * Averaging windows at the *same* attitude is what buys precision here: the mean of several
     * independent resting means has less noise than any one of them. Windows that disagree with
     * the latest by more than a small angle describe a different mounting and are excluded
     * rather than blended in.
     */
    private fun updateTilt() {
        val latest = recentWindows.lastOrNull() ?: return
        val reference = latest.upDevice ?: return
        var sum = ZERO
        var count = 0
        var worstQuality = 0.0
        var first = true
        for (window in recentWindows) {
            val up = window.upDevice ?: continue
            val disagreementDeg = Math.toDegrees(angleBetweenVectors(reference, up))
            if (disagreementDeg > TILT_AGREEMENT_DEG) continue
            sum = sum + window.meanAccelM_S2
            count += 1
            val quality = windowQuality(window)
            if (first || quality < worstQuality) worstQuality = quality
            first = false
        }
        if (count == 0) return
        val mean = sum * (1.0 / count)
        val up = mean.normalizedOrNull() ?: return
        // A segment in flight was measured against the previous up direction; if the mounting
        // estimate has moved, that segment's evidence is closed out instead of being mixed.
        if (currentSegment != null &&
            Math.toDegrees(angleBetweenVectors(currentSegment!!.upDevice, up)) > TILT_AGREEMENT_DEG
        ) {
            closeSegment()
        }
        upDevice = up
        tiltRotation = rotationFromTo(up, VEHICLE_UP)
        tiltQuality = worstQuality
        if (status != CalibrationStatus.VALID) {
            // While a calibration is in force its reference tilt must not drift with new windows,
            // or remount detection would compare the phone against itself.
            calibratedUpDevice = up
        }
    }

    /** Stability margin of one window, in [0, 1]; the weakest of the gates decides. */
    private fun windowQuality(window: StationaryWindow): Double = min(
        min(
            1.0 - window.accelStdM_S2 / ACCEL_STD_SCALE,
            1.0 - window.gyroStdRad_S / GYRO_STD_SCALE,
        ),
        min(
            window.durationS / DURATION_SCALE_S,
            window.samples.toDouble() / SAMPLE_SCALE,
        ),
    ).coerceIn(0.0, 1.0)

    /**
     * Gyroscope bias from the mean of each resting window's mean rate.
     *
     * The bias is a device property, so windows at different attitudes must agree; if they do
     * not, the likely cause is temperature drift or a window that was not really at rest, and
     * that is reported instead of being averaged away.
     */
    private fun updateGyroBias() {
        val windows = recentWindows.toList()
        if (windows.isEmpty()) return
        var sum = ZERO
        for (window in windows) sum = sum + window.meanGyroRad_S
        val mean = sum * (1.0 / windows.size)
        var spread = 0.0
        for (window in windows) spread = max(spread, (window.meanGyroRad_S - mean).norm())
        gyroBias = mean
        if (spread > thresholds.maxGyroBiasSpreadRad_S) {
            report(
                Severity.WARNING, "CALIBRATION_GYRO_BIAS_INCONSISTENT",
                "Resting gyroscope estimates differ by ${"%.4f".format(spread)} rad/s; " +
                    "temperature drift or a window that was not at rest.",
            )
        }
    }

    /**
     * Accelerometer bias by multi-position calibration, and only when the evidence justifies it.
     *
     * At rest `|a - b|` equals gravity's magnitude for every attitude, which gives the linear
     * system `(a_i - a_j) . b = (|a_i|^2 - |a_j|^2) / 2` without ever needing gravity's exact
     * value. One attitude cannot solve it at all: a horizontal bias is indistinguishable from a
     * tilt, and a vertical one from a gravity-scale error. So the solve requires at least
     * `minBiasWindows` attitudes that are not near-coplanar, a well-conditioned system, a
     * consistent residual, and a magnitude above the sampling-noise floor. Anything less leaves
     * the bias null, which is the honest answer rather than a fitted number.
     */
    private fun updateAccelBias() {
        val attitudes = biasAttitudes.toList()
        if (attitudes.size < thresholds.minBiasWindows) {
            accelBias = null
            return
        }
        val rows = ArrayList<DoubleArray>(attitudes.size * attitudes.size)
        val rhs = ArrayList<Double>(attitudes.size * attitudes.size)
        for (i in attitudes.indices) {
            for (j in i + 1 until attitudes.size) {
                val difference = attitudes[i] - attitudes[j]
                rows.add(doubleArrayOf(difference.x, difference.y, difference.z))
                rhs.add((attitudes[i].norm() * attitudes[i].norm() -
                    attitudes[j].norm() * attitudes[j].norm()) / 2.0)
            }
        }
        val solution = solveLeastSquares(rows, rhs) ?: run {
            accelBias = null
            return
        }
        if (solution.conditionRatio < thresholds.minBiasCondition) {
            accelBias = null
            report(
                Severity.INFO, "CALIBRATION_ACCEL_BIAS_NOT_JUSTIFIED",
                "Resting attitudes are too nearly coplanar to separate accelerometer bias " +
                    "from gravity; bias is reported as unknown.",
            )
            return
        }
        val bias = solution.value
        var sumMagnitude = 0.0
        for (attitude in attitudes) sumMagnitude += (attitude - bias).norm()
        val meanMagnitude = sumMagnitude / attitudes.size
        var spread = 0.0
        for (attitude in attitudes) {
            spread = max(spread, abs((attitude - bias).norm() - meanMagnitude))
        }
        val justified = bias.norm() <= thresholds.maxAccelBiasM_S2 &&
            bias.norm() >= thresholds.minAccelBiasM_S2 &&
            spread <= thresholds.maxGravitySpreadM_S2
        accelBias = if (justified) bias else null
        if (!justified) {
            report(
                Severity.INFO, "CALIBRATION_ACCEL_BIAS_NOT_JUSTIFIED",
                "Solved accelerometer bias ${"%.4f".format(bias.norm())} m/s^2 is not " +
                    "distinguishable from resting noise or is inconsistent across attitudes; " +
                    "it is reported as unknown.",
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Stage B: yaw from straight-motion and GNSS evidence
    // -----------------------------------------------------------------------------------------

    private fun acceptFix(segment: Segment, fix: GnssFix) {
        if (segment.firstFix == null) segment.firstFix = fix
        segment.lastFix = fix
        segment.fixCount += 1
        if (fix.horizontalAccuracyM != null) {
            segment.worstAccuracyM = max(segment.worstAccuracyM, fix.horizontalAccuracyM)
            segment.haveAccuracy = true
        }
        val course = fix.bearingDeg
        if (course != null) {
            val reference = segment.courseReference
            if (reference == null) {
                segment.courseReference = course
            } else {
                segment.maxCourseDeviationDeg = max(
                    segment.maxCourseDeviationDeg,
                    abs(angularDifferenceDegrees(reference, course)),
                )
            }
        }
        val speed = fix.speedM_S
        if (speed != null) {
            val t = (fix.tNs - segment.startNs) / 1e9
            segment.speedSamples += 1
            segment.sumT += t
            segment.sumV += speed
            segment.sumTV += t * speed
            segment.sumTT += t * t
        }
    }

    /** Close the open segment and let its evidence decide whether the yaw is measurable. */
    private fun closeSegment() {
        val segment = currentSegment ?: return
        currentSegment = null
        val yaw = analyse(segment) ?: return
        if (yawEvidence.size >= thresholds.maxYawEvidence) yawEvidence.removeFirst()
        yawEvidence.addLast(yaw)
        reevaluateYaw()
    }

    /**
     * Evaluate one segment against every gate, in the order that produces the most specific
     * diagnosis. Returns the measured yaw in radians, or null with a reported reason.
     */
    private fun analyse(segment: Segment): Double? {
        val up = segment.upDevice
        if (segment.fixCount < thresholds.minGnssFixes) {
            return reject("CALIBRATION_SEGMENT_GNSS_SPARSE",
                "Segment had ${segment.fixCount} usable fixes; ${thresholds.minGnssFixes} are needed.")
        }
        if (!segment.haveAccuracy || segment.worstAccuracyM > thresholds.maxGnssAccuracyM) {
            return reject("CALIBRATION_SEGMENT_GNSS_INACCURATE",
                "Reported horizontal accuracy reached " +
                    "${"%.1f".format(segment.worstAccuracyM)} m; " +
                    "${"%.1f".format(thresholds.maxGnssAccuracyM)} m is the limit.")
        }
        if (segment.samples == 0) {
            return reject("CALIBRATION_SEGMENT_NO_IMU",
                "Segment carried no usable IMU samples.")
        }
        val straightFraction = segment.straightSamples.toDouble() / segment.samples
        if (straightFraction < thresholds.minStraightFraction) {
            return reject("CALIBRATION_SEGMENT_NOT_STRAIGHT",
                "Only ${"%.0f".format(straightFraction * 100)}% of the segment was free of " +
                    "yaw rotation; straight motion is required to locate the longitudinal axis.")
        }
        if (segment.straightSamples == 0) {
            return reject("CALIBRATION_SEGMENT_NOT_STRAIGHT", "Segment was never straight.")
        }
        val force = segment.forceSum * (1.0 / segment.straightSamples)
        val excitation = force.norm()
        if (excitation < thresholds.minExcitationM_S2) {
            return reject("CALIBRATION_SEGMENT_INSUFFICIENT_EXCITATION",
                "Horizontal specific force was ${"%.3f".format(excitation)} m/s^2; " +
                    "${"%.2f".format(thresholds.minExcitationM_S2)} m/s^2 is needed to " +
                    "locate the forward axis. Constant-speed cruise carries no direction.")
        }
        val slope = speedSlope(segment)
        if (segment.speedSamples < thresholds.minSpeedSamples ||
            slope == null || abs(slope) < thresholds.minSpeedSlopeM_S2
        ) {
            return reject("CALIBRATION_SEGMENT_SPEED_SLOPE_UNRELIABLE",
                "Ground-speed rate is too small or too poorly sampled to orient the force; " +
                    "the forward and backward directions are indistinguishable without it.")
        }
        val ratio = excitation / abs(slope)
        if (ratio < thresholds.minForceSpeedRatio || ratio > thresholds.maxForceSpeedRatio) {
            return reject("CALIBRATION_SEGMENT_FORCE_SPEED_MISMATCH",
                "Horizontal specific force ${"%.3f".format(excitation)} m/s^2 is inconsistent " +
                    "with measured speed rate ${"%.3f".format(abs(slope))} m/s^2.")
        }
        val courseReference = segment.courseReference
        if (courseReference != null) {
            if (segment.maxCourseDeviationDeg > thresholds.maxCourseDeviationDeg) {
                return reject("CALIBRATION_SEGMENT_COURSE_UNSTABLE",
                    "Reported course wandered ${"%.1f".format(segment.maxCourseDeviationDeg)} " +
                        "deg during the segment; it is not straight motion.")
            }
            val displacement = displacementBetween(segment.firstFix, segment.lastFix)
            if (displacement == null || displacement.metres < thresholds.minBaselineM) {
                return reject("CALIBRATION_SEGMENT_BASELINE_TOO_SHORT",
                    "Segment displaced ${"%.1f".format(displacement?.metres ?: 0.0)} m; " +
                        "${"%.0f".format(thresholds.minBaselineM)} m is needed before a course " +
                        "can be checked against it.")
            }
            val disagreement = abs(
                angularDifferenceDegrees(displacement.bearingDeg, courseReference),
            )
            if (disagreement > thresholds.maxCourseDisplacementDeg) {
                return reject("CALIBRATION_SEGMENT_GNSS_COURSE_INCONSISTENT",
                    "Reported course disagrees with measured displacement by " +
                        "${"%.1f".format(disagreement)} deg; the course is not usable.")
            }
        }
        val rotation = tiltRotation ?: run {
            report(
                Severity.INFO, "CALIBRATION_SEGMENT_NO_TILT",
                "Segment ended before any resting window established the gravity direction.",
            )
            return null
        }
        val planar = rotation.rotate(force).let { Vector3(it.x, it.y, 0.0) }.normalizedOrNull()
        if (planar == null) {
            return reject("CALIBRATION_SEGMENT_INSUFFICIENT_EXCITATION",
                "Horizontal specific force had no measurable horizontal direction.")
        }
        val forward = if (slope > 0.0) planar else planar * -1.0
        val yaw = -atan2(forward.y, forward.x)
        report(
            Severity.INFO, "CALIBRATION_SEGMENT_ACCEPTED",
            "Segment accepted: straight for ${"%.0f".format(straightFraction * 100)}%, " +
                "excitation ${"%.2f".format(excitation)} m/s^2, speed rate " +
                "${"%.2f".format(slope)} m/s^2, yaw contribution " +
                "${"%.1f".format(Math.toDegrees(yaw))} deg.",
        )
        return yaw
    }

    private fun reject(code: String, message: String): Double? {
        report(Severity.INFO, code, message)
        return null
    }

    /** Least-squares ground-speed rate in m/s^2, or null when the fit is degenerate. */
    private fun speedSlope(segment: Segment): Double? {
        val n = segment.speedSamples
        if (n < 2) return null
        val denominator = n * segment.sumTT - segment.sumT * segment.sumT
        if (abs(denominator) < 1e-9) return null
        return (n * segment.sumTV - segment.sumT * segment.sumV) / denominator
    }

    /**
     * Decide the yaw from the accumulated segment evidence.
     *
     * Yaws from different segments are combined as a circular mean, and their spread is checked
     * before they are trusted: two straight segments that disagree about which way the vehicle
     * points are contradictory evidence, not noise, so the calibration is rejected as `INVALID`
     * rather than averaged.
     */
    private fun reevaluateYaw() {
        if (yawEvidence.isEmpty()) {
            live = buildOutcome(status = if (status == CalibrationStatus.INVALID) status else CalibrationStatus.PENDING,
                yaw = null, yawMeasured = false)
            publishIfChanged()
            return
        }
        var sumSin = 0.0
        var sumCos = 0.0
        for (yaw in yawEvidence) {
            sumSin += sin(yaw)
            sumCos += cos(yaw)
        }
        if (abs(sumSin) < 1e-12 && abs(sumCos) < 1e-12) {
            yawEvidence.clear()
            setStatus(CalibrationStatus.INVALID)
            report(
                Severity.WARNING, "CALIBRATION_YAW_CONTRADICTORY",
                "Straight-motion segments point in mutually opposite directions; the mounting " +
                    "or the GNSS course is not consistent. Recalibration is required.",
            )
            live = buildOutcome(status = CalibrationStatus.INVALID, yaw = null, yawMeasured = false)
            publishIfChanged()
            return
        }
        val meanYaw = atan2(sumSin, sumCos)
        var maxDeviation = 0.0
        for (yaw in yawEvidence) {
            maxDeviation = max(maxDeviation, abs(angularDifferenceDegrees(
                Math.toDegrees(meanYaw), Math.toDegrees(yaw),
            )))
        }
        if (maxDeviation > thresholds.yawAgreementDeg) {
            yawEvidence.clear()
            setStatus(CalibrationStatus.INVALID)
            report(
                Severity.WARNING, "CALIBRATION_YAW_INCONSISTENT",
                "Accepted segments disagree about vehicle forward by up to " +
                    "${"%.1f".format(maxDeviation)} deg; that is contradictory evidence, so no " +
                    "yaw is published. Recalibration is required.",
            )
            live = buildOutcome(status = CalibrationStatus.INVALID, yaw = null, yawMeasured = false)
            publishIfChanged()
            return
        }
        val measured = yawEvidence.size + adoptedEvidence >= requiredSegments
        live = buildOutcome(
            status = if (measured) CalibrationStatus.VALID else CalibrationStatus.PENDING,
            yaw = meanYaw,
            yawMeasured = measured,
        )
        publishIfChanged()
    }

    // -----------------------------------------------------------------------------------------
    // Remount detection
    // -----------------------------------------------------------------------------------------

    /**
     * A resting window whose up direction has moved cannot be the same mounting, so the current
     * calibration is expired and a new determination starts from this window.
     */
    private fun detectRemount(window: StationaryWindow, up: Vector3): Boolean {
        val reference = calibratedUpDevice ?: return false
        val deviationDeg = Math.toDegrees(angleBetweenVectors(reference, up))
        if (deviationDeg <= thresholds.remountTiltDeg) return false
        report(
            Severity.WARNING, "CALIBRATION_REMOUNT_DETECTED",
            "Phone tilt changed by ${"%.1f".format(deviationDeg)} deg while at rest, past the " +
                "${"%.0f".format(thresholds.remountTiltDeg)} deg limit. The previous " +
                "calibration is expired and recalibration is required.",
        )
        expire()
        return false
    }

    /**
     * Rotation accumulated while the vehicle is parked. A phone cannot rotate that far
     * without being handled, and handling invalidates a mounting even when gravity looks
     * unchanged, which is the only way an in-place yaw change is observable at all.
     */
    private fun detectHandling(pair: ImuPair) {
        val previous = lastPairTNs
        lastPairTNs = pair.tNs
        if (status != CalibrationStatus.VALID) {
            handlingRotationRad = 0.0
            return
        }
        val deltaS = previous?.let { ((pair.tNs - it) / 1e9).coerceIn(0.0, MAX_PAIR_GAP_S) } ?: return
        val rate = (pair.gyroRad_S - (gyroBias ?: ZERO)).norm()
        val parked = when {
            !speedKnown -> rate >= thresholds.strongRotationRad_S
            else -> (lastSpeedM_S ?: 0.0) <= thresholds.parkedSpeedM_S
        }
        if (!parked || rate < thresholds.handlingRateRad_S) return
        handlingRotationRad += rate * deltaS
        if (handlingRotationRad < Math.toRadians(thresholds.remountRotationDeg)) return
        handlingRotationRad = 0.0
        report(
            Severity.WARNING, "CALIBRATION_REMOUNT_DETECTED",
            "Phone rotated " +
                "${"%.0f".format(thresholds.remountRotationDeg)} deg while the vehicle was " +
                "parked, which means it was handled. The previous calibration is expired and " +
                "recalibration is required.",
        )
        expire()
    }

    /**
     * Retire the current calibration.
     *
     * The sensor biases are kept, because they describe the device rather than the mounting, but
     * every mounting-dependent quantity is dropped: a tilt measured before the phone moved says
     * nothing about where it is now. The next calibration gets a new id, so a *retired* id is
     * never resurrected: an id names one calibration episode, and once expired it is never
     * published again. (While an id is in force its transform may still be refined as evidence
     * arrives, and every refinement is announced under that id, so a consumer must treat the most
     * recent record for an id as the authoritative one.)
     */
    private fun expire() {
        val expired = live.copy(status = CalibrationStatus.EXPIRED, confidence = 0.0)
        // The expired calibration is announced with its own id so a consumer holding that id
        // learns it is out of force, before any new calibration exists.
        val previous = lastPublished
        if (previous == null || previous.id != expired.id || previous.status != expired.status) {
            outcomes.addLast(expired)
            lastPublished = expired
        }
        recentWindows.clear()
        yawEvidence.clear()
        adoptedEvidence = 0
        currentSegment = null
        handlingRotationRad = 0.0
        upDevice = null
        tiltRotation = null
        calibratedUpDevice = null
        tiltQuality = 0.0
        status = CalibrationStatus.PENDING
        sequence += 1
        currentId = "$idPrefix-cal-$sequence"
        live = CalibrationOutcome(currentId, status, null, gyroBias, accelBias, null, false)
        // The replacement episode is announced at once, so the audit trail reads "this id expired, a
        // new determination has begun" rather than leaving a consumer to infer it from a gap.
        publishIfChanged()
    }

    // -----------------------------------------------------------------------------------------
    // Publication
    // -----------------------------------------------------------------------------------------

    private fun setStatus(next: CalibrationStatus) {
        status = next
    }

    private fun buildOutcome(
        status: CalibrationStatus,
        yaw: Double?,
        yawMeasured: Boolean,
    ): CalibrationOutcome {
        // The private status tracks what was last published, so remount and handling detection
        // gate on the same state a consumer has already been told about.
        this.status = status
        val rotation = tiltRotation
        val transform = if (rotation != null && yaw != null) {
            quaternionAboutZ(yaw) * rotation
        } else {
            rotation
        }
        return CalibrationOutcome(
            id = currentId,
            status = status,
            qVehicleFromDevice = transform,
            gyroBiasRad_S = gyroBias,
            accelBiasM_S2 = accelBias,
            confidence = confidence(status, yawMeasured),
            yawMeasured = yawMeasured,
        )
    }

    /**
     * Confidence as the weakest link among the measured evidence, in `[0, 1]`.
     *
     * No weights are invented: the returned value is the minimum of independent margins, each
     * already in `[0, 1]`, so it can only be as high as the least-supported part of the estimate.
     * A pending calibration is capped, because an unmeasured yaw must never look like a usable
     * calibration however stable the phone was.
     */
    private fun confidence(status: CalibrationStatus, yawMeasured: Boolean): Double? {
        if (tiltRotation == null) return null
        var value = tiltQuality
        if (status == CalibrationStatus.VALID && yawMeasured) {
            val margin = (yawEvidence.size + adoptedEvidence).toDouble() / requiredSegments
            value = min(value, min(margin, 1.0))
        } else {
            value = min(value, thresholds.pendingConfidenceCap)
        }
        if (!value.isFinite()) return null
        return value.coerceIn(0.0, 1.0)
    }

    /**
     * Announce a calibration when it is new, when its status changed, or when its orientation
     * moved materially. Everything else is the same calibration observed twice, and emitting it
     * again would only burn bounded output capacity.
     */
    private fun publishIfChanged() {
        val previous = lastPublished
        val changed = previous == null ||
            previous.status != live.status ||
            previous.id != live.id ||
            previous.qVehicleFromDevice == null != (live.qVehicleFromDevice == null) ||
            (previous.qVehicleFromDevice != null && live.qVehicleFromDevice != null &&
                Math.toDegrees(rotationAngleBetween(
                    previous.qVehicleFromDevice!!, live.qVehicleFromDevice!!,
                )) > thresholds.orientationPublishDeg) ||
            (previous.gyroBiasRad_S == null && live.gyroBiasRad_S != null) ||
            (previous.accelBiasM_S2 == null && live.accelBiasM_S2 != null)
        if (!changed) return
        outcomes.addLast(live)
        lastPublished = live
        if (live.status == CalibrationStatus.PENDING && !live.yawMeasured &&
            live.qVehicleFromDevice != null
        ) {
            report(
                Severity.INFO, "CALIBRATION_YAW_UNMEASURED",
                "Tilt is measured from gravity; rotation about vehicle up is a deterministic " +
                    "placeholder until straight, well-excited driving with valid GNSS provides it.",
            )
        }
    }

    private fun report(severity: Severity, code: String, message: String) {
        if (diagnostics.size >= MAX_DIAGNOSTICS) return
        diagnostics.addLast(CalibrationDiagnostic(severity, code, message))
    }

    // -----------------------------------------------------------------------------------------
    // Supporting types and helpers
    // -----------------------------------------------------------------------------------------

    /** Running aggregates for one candidate straight segment. Bounded by construction. */
    private class Segment(val upDevice: Vector3, val startNs: Long) {
        var samples = 0
        var straightSamples = 0
        var forceSum = ZERO
        var maxYawRateRad_S = 0.0
        var fixCount = 0
        var firstFix: GnssFix? = null
        var lastFix: GnssFix? = null
        var courseReference: Double? = null
        var maxCourseDeviationDeg = 0.0
        var worstAccuracyM = 0.0
        var haveAccuracy = false
        var speedSamples = 0
        var sumT = 0.0
        var sumV = 0.0
        var sumTV = 0.0
        var sumTT = 0.0
    }

    private class Displacement(val metres: Double, val bearingDeg: Double)

    private class Solve(val value: Vector3, val conditionRatio: Double)

    /** Distance and bearing from the first to the last fix of a segment, locally flat-earth. */
    private fun displacementBetween(first: GnssFix?, last: GnssFix?): Displacement? {
        if (first == null || last == null) return null
        val meanLatRad = Math.toRadians((first.latitudeDeg + last.latitudeDeg) / 2.0)
        val north = Math.toRadians(last.latitudeDeg - first.latitudeDeg) * EARTH_RADIUS_M
        val east = Math.toRadians(last.longitudeDeg - first.longitudeDeg) * EARTH_RADIUS_M *
            cos(meanLatRad)
        val metres = sqrt(north * north + east * east)
        if (!metres.isFinite()) return null
        return Displacement(metres, normalizeDegrees360(Math.toDegrees(atan2(east, north))))
    }

    /**
     * True when a plausible fix close in time to [tNs] reported ground speed above walking pace.
     *
     * A speed older than [GNSS_MOTION_RECENT_NS] is treated as unknown rather than as motion, so
     * a GNSS outage does not suppress resting measurements for the rest of the session.
     */
    private fun movingNear(tNs: Long): Boolean {
        // A later stopped fix cannot retroactively turn the driven window just closed
        // by a change in specific force into a gravity-only resting measurement.
        val fixNs = lastMovingFixTNs ?: return false
        return abs(tNs - fixNs) <= GNSS_MOTION_RECENT_NS
    }

    private fun isPlausible(fix: GnssFix): Boolean {
        if (!fix.latitudeDeg.isFinite() || !fix.longitudeDeg.isFinite()) return false
        if (fix.latitudeDeg < -90.0 || fix.latitudeDeg > 90.0) return false
        if (fix.longitudeDeg < -180.0 || fix.longitudeDeg > 180.0) return false
        if (fix.speedM_S != null && (!fix.speedM_S.isFinite() || fix.speedM_S < 0.0)) return false
        if (fix.bearingDeg != null && !fix.bearingDeg.isFinite()) return false
        if (fix.horizontalAccuracyM != null &&
            (!fix.horizontalAccuracyM.isFinite() || fix.horizontalAccuracyM < 0.0)
        ) {
            return false
        }
        return true
    }

    /**
     * Solve a small over-determined system by normal equations with partial pivoting.
     *
     * `conditionRatio` is the ratio of the smallest to the largest pivot magnitude, a documented
     * near-singularity indicator. It is not the singular-value condition number, and it is used
     * only to refuse coplanar attitude sets, which no amount of arithmetic can separate.
     */
    private fun solveLeastSquares(rows: List<DoubleArray>, rhs: List<Double>): Solve? {
        if (rows.size < 3) return null
        val matrix = Array(3) { DoubleArray(4) }
        for (index in rows.indices) {
            val row = rows[index]
            for (a in 0..2) {
                for (b in 0..2) matrix[a][b] += row[a] * row[b]
                matrix[a][3] += row[a] * rhs[index]
            }
        }
        var smallestPivot = Double.MAX_VALUE
        var largestPivot = 0.0
        for (column in 0..2) {
            var pivot = column
            for (row in column + 1..2) {
                if (abs(matrix[row][column]) > abs(matrix[pivot][column])) pivot = row
            }
            if (pivot != column) {
                val swap = matrix[column]
                matrix[column] = matrix[pivot]
                matrix[pivot] = swap
            }
            val magnitude = abs(matrix[column][column])
            if (magnitude < 1e-12) return null
            smallestPivot = min(smallestPivot, magnitude)
            largestPivot = max(largestPivot, magnitude)
            for (row in column + 1..2) {
                val factor = matrix[row][column] / matrix[column][column]
                for (c in column..3) matrix[row][c] -= factor * matrix[column][c]
            }
        }
        val solution = DoubleArray(3)
        for (row in 2 downTo 0) {
            var sum = matrix[row][3]
            for (c in row + 1..2) sum -= matrix[row][c] * solution[c]
            solution[row] = sum / matrix[row][row]
        }
        val value = Vector3(solution[0], solution[1], solution[2])
        if (!value.isFiniteVector()) return null
        return Solve(value, smallestPivot / largestPivot)
    }

    private fun Vector3.isFiniteVector(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    private companion object {
        val ZERO = Vector3(0.0, 0.0, 0.0)
        val VEHICLE_UP = Vector3(0.0, 0.0, 1.0)
        const val EARTH_RADIUS_M = 6_371_000.0
        const val GNSS_GAP_NS = 5_000_000_000L
        const val GNSS_MOTION_RECENT_NS = 5_000_000_000L
        const val MAX_PAIR_GAP_S = 0.5
        const val MAX_DIAGNOSTICS = 256
        const val TILT_AGREEMENT_DEG = 3.0

        /** Confidence carried by an adopted prior tilt, which has no stability evidence here. */
        const val PRIOR_TILT_QUALITY = 0.5
        const val ACCEL_STD_SCALE = 0.1
        const val GYRO_STD_SCALE = 0.02
        const val DURATION_SCALE_S = 4.0
        const val SAMPLE_SCALE = 200.0
    }
}
