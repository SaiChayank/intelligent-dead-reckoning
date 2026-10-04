package com.intelligentdeadreckoning.app.fusion

import com.intelligentdeadreckoning.app.calibration.cross
import com.intelligentdeadreckoning.app.calibration.dot
import com.intelligentdeadreckoning.app.calibration.minus
import com.intelligentdeadreckoning.app.calibration.norm
import com.intelligentdeadreckoning.app.calibration.normalized
import com.intelligentdeadreckoning.app.calibration.plus
import com.intelligentdeadreckoning.app.calibration.quaternionFromRotationVector
import com.intelligentdeadreckoning.app.calibration.times
import com.intelligentdeadreckoning.app.calibration.toRotationMatrix
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Classical GNSS/INS fusion: a 15-state error-state (indirect) Kalman filter.
 *
 * ## Why an error-state filter
 *
 * The nominal state is propagated by the *same* strapdown mechanization the offline baseline uses,
 * so the on-device trajectory is the validated one and the filter only ever estimates a
 * correction to it. That keeps the attitude parameterization linear: the attitude error is a
 * three-parameter rotation vector in the navigation frame, and the quaternion never enters the
 * covariance. It also means the covariance stays meaningful when the vehicle moves fast, which it
 * would not if the full attitude were a filter state.
 *
 * ## State
 *
 * | Block | Indices | Frame | Units |
 * |---|---|---|---|
 * | position | 0-2 | ENU, from the anchor | m |
 * | velocity | 3-5 | ENU | m/s |
 * | attitude error | 6-8 | navigation (global) error, `q_true = exp(delta) * q_hat` | rad |
 * | gyroscope bias | 9-11 | device | rad/s |
 * | accelerometer bias | 12-14 | device | m/s^2 |
 *
 * The nominal state is position, velocity, attitude (ENU-from-device) and the two bias vectors.
 * The measured specific force enters as the vehicle frame's force transformed by the attitude, and
 * the biases are corrected in the device frame before that, matching the calibration contract.
 *
 * ## Prediction
 *
 * Driven entirely by the inertial samples:
 * `a_enu = C(q)(f - b_a) + g_enu - (2 w_ie + w_en) x v`, exactly as in
 * `training/strapdown_ins.py`, including the trapezoid integration, the Earth rate, the transport
 * rate and the WGS-84 gravity model in [Geodesy]. The interval comes from the timestamps, never
 * from a nominal rate. A duplicate timestamp is not propagated and is not a gap, because no time
 * passed. An interval with no samples inside it holds the state **and inflates the covariance**,
 * because unobserved motion is still uncertainty even though it is not state.
 *
 * ## Measurements
 *
 * GNSS position (2 rows horizontally, a third vertically only when the provider gave an
 * ellipsoidal altitude *and* the anchor has one), GNSS course velocity when both speed and
 * bearing are present, and GNSS speed alone when only speed is present and the vehicle is moving.
 * Each is gated on its normalised innovation squared against a chi-square threshold for its own
 * dimension. A rejected measurement is discarded whole and reported; it never partially updates.
 *
 * ## What it will not do
 *
 * - **It never snaps to a returning fix.** The correction is the Kalman gain times the innovation,
 *   so a fix tens of metres away moves the state by a fraction of that and the remaining error is
 *   taken out over subsequent updates. Recovery is a convergence, not a teleport.
 * - **It does not invent measurements.** A missing field means the row is not built. Speed without
 *   bearing is a magnitude, not a direction, so it is applied along the filter's own heading and
 *   only while the vehicle is actually moving.
 * - **It does not smooth over a broken clock.** A backwards timestamp is rejected and counted; too
 *   long an interval fails the run.
 * - It applies no zero-velocity update. That measurement has not been approved anywhere in this
 *   project, so it is not here.
 */
class GnssInsEkf(val config: FusionConfig = FusionConfig()) {

    private var status = FusionStatus.UNINITIALIZED
    private var tNs = 0L
    private var alignedAtNs = 0L
    private var anchor: GeodeticAnchor? = null
    private var positionEnu = Vector3(0.0, 0.0, 0.0)
    private var velocityEnu = Vector3(0.0, 0.0, 0.0)
    private var qEnuFromDevice = Quaternion(1.0, 0.0, 0.0, 0.0)
    private var gyroBias = Vector3(0.0, 0.0, 0.0)
    private var accelBias = Vector3(0.0, 0.0, 0.0)
    private var covariance = identity(DIM)
    private var previousAccelerationEnu = Vector3(0.0, 0.0, 0.0)
    private var havePreviousAcceleration = false
    private var lastGnssUpdateNs: Long? = null

    private var acceptedUpdates = 0L
    private var rejectedUpdates = 0L
    private var constraintAccepted = 0L
    private var constraintRejected = 0L
    private var duplicateTimestamps = 0L
    private var backwardsTimestamps = 0L
    private var heldGaps = 0L
    private var failure: FusionFailure? = null
    private var lastRejectionNis: Double? = null
    private var lastMeasurementAttemptNs: Long? = null
    private var rejectionSpanS = 0.0

    /** Last validated nominal/covariance state, for atomic rejection of numerical failures. */
    private data class NumericalCheckpoint(
        val tNs: Long,
        val positionEnu: Vector3,
        val velocityEnu: Vector3,
        val qEnuFromDevice: Quaternion,
        val gyroBias: Vector3,
        val accelBias: Vector3,
        val covariance: DoubleArray,
        val previousAccelerationEnu: Vector3,
        val havePreviousAcceleration: Boolean,
    )

    private fun checkpoint() = NumericalCheckpoint(
        tNs, positionEnu, velocityEnu, qEnuFromDevice, gyroBias, accelBias,
        covariance, previousAccelerationEnu, havePreviousAcceleration,
    )

    private fun restore(checkpoint: NumericalCheckpoint) {
        tNs = checkpoint.tNs
        positionEnu = checkpoint.positionEnu
        velocityEnu = checkpoint.velocityEnu
        qEnuFromDevice = checkpoint.qEnuFromDevice
        gyroBias = checkpoint.gyroBias
        accelBias = checkpoint.accelBias
        covariance = checkpoint.covariance
        previousAccelerationEnu = checkpoint.previousAccelerationEnu
        havePreviousAcceleration = checkpoint.havePreviousAcceleration
    }

    /** True once an anchor and a state exist. Before that there is nothing to propagate. */
    val aligned: Boolean get() = anchor != null && status != FusionStatus.UNINITIALIZED

    // ---------------------------------------------------------------------------------------
    // Alignment
    // ---------------------------------------------------------------------------------------

    /**
     * Set the initial state and its covariance.
     *
     * This is alignment, not a measurement update: it is the one moment where the state is taken
     * from outside evidence, because a filter with no state has nothing to correct. It is
     * deliberately separate from [updateGnssPosition], which is what a returning fix after an
     * outage goes through — and that one never overwrites the state.
     */
    fun align(initial: FusionInitialState, tNs: Long): Boolean {
        if (tNs < 0L || !isFiniteQuaternion(initial.qEnuFromDevice) || !isFinite(initial.velocityEnuM_S) ||
            !isFinite(initial.gyroBiasDeviceRad_S) || !isFinite(initial.accelBiasDeviceM_S2) ||
            !isPositive(initial.positionVarianceM2) || !isPositive(initial.velocityVarianceM2) ||
            !isPositive(initial.attitudeVarianceRad2) ||
            !(initial.gyroBiasVarianceRad2_S2 > 0.0 && initial.gyroBiasVarianceRad2_S2.isFinite()) ||
            !(initial.accelBiasVarianceM2_S4 > 0.0 && initial.accelBiasVarianceM2_S4.isFinite())
        ) {
            return false
        }
        anchor = initial.anchor
        positionEnu = Vector3(0.0, 0.0, 0.0)
        velocityEnu = initial.velocityEnuM_S
        qEnuFromDevice = initial.qEnuFromDevice.normalized()
        gyroBias = initial.gyroBiasDeviceRad_S
        accelBias = initial.accelBiasDeviceM_S2
        covariance = identity(DIM)
        setDiagonal(covariance, IDX_POS, initial.positionVarianceM2)
        setDiagonal(covariance, IDX_VEL, initial.velocityVarianceM2)
        setDiagonal(covariance, IDX_ATT, initial.attitudeVarianceRad2)
        for (i in 0..2) {
            covariance[(IDX_GBIAS + i) * DIM + IDX_GBIAS + i] = initial.gyroBiasVarianceRad2_S2
            covariance[(IDX_ABIAS + i) * DIM + IDX_ABIAS + i] = initial.accelBiasVarianceM2_S4
        }
        this.tNs = tNs
        alignedAtNs = tNs
        status = FusionStatus.RUNNING
        previousAccelerationEnu = Vector3(0.0, 0.0, 0.0)
        havePreviousAcceleration = false
        // Bias corrections are carried by the state; the covariance started diagonal, so the
        // cross-covariances between position and attitude are honestly zero at this instant.
        lastGnssUpdateNs = null
        acceptedUpdates = 0L
        rejectedUpdates = 0L
        constraintAccepted = 0L
        constraintRejected = 0L
        duplicateTimestamps = 0L
        backwardsTimestamps = 0L
        heldGaps = 0L
        failure = null
        lastRejectionNis = null
        lastMeasurementAttemptNs = null
        rejectionSpanS = 0.0
        return true
    }

    fun reset() {
        status = FusionStatus.UNINITIALIZED
        anchor = null
        tNs = 0L
        alignedAtNs = 0L
        positionEnu = Vector3(0.0, 0.0, 0.0)
        velocityEnu = Vector3(0.0, 0.0, 0.0)
        qEnuFromDevice = Quaternion(1.0, 0.0, 0.0, 0.0)
        gyroBias = Vector3(0.0, 0.0, 0.0)
        accelBias = Vector3(0.0, 0.0, 0.0)
        covariance = identity(DIM)
        previousAccelerationEnu = Vector3(0.0, 0.0, 0.0)
        havePreviousAcceleration = false
        lastGnssUpdateNs = null
        acceptedUpdates = 0L
        rejectedUpdates = 0L
        constraintAccepted = 0L
        constraintRejected = 0L
        duplicateTimestamps = 0L
        backwardsTimestamps = 0L
        heldGaps = 0L
        failure = null
        lastRejectionNis = null
        lastMeasurementAttemptNs = null
        rejectionSpanS = 0.0
    }

    // ---------------------------------------------------------------------------------------
    // Prediction
    // ---------------------------------------------------------------------------------------

    /** Advance the nominal state and the covariance with one bias-corrected inertial sample. */
    fun propagate(accelDeviceM_S2: Vector3, gyroDeviceRad_S: Vector3, tNs: Long): PropagationOutcome {
        if (!aligned) return PropagationOutcome.NOT_ALIGNED
        if (status == FusionStatus.FAILED) return PropagationOutcome.FAILED
        if (tNs < 0L || !stateIsNumericallyUsable()) {
            fail(FusionFailure.NUMERICAL_INVALIDITY)
            return PropagationOutcome.FAILED_NUMERICAL
        }
        if (!isFinite(accelDeviceM_S2) || !isFinite(gyroDeviceRad_S)) {
            fail(FusionFailure.NON_FINITE_SAMPLE)
            return PropagationOutcome.FAILED_NON_FINITE
        }
        if (tNs < this.tNs) {
            backwardsTimestamps += 1
            return PropagationOutcome.BACKWARDS_TIMESTAMP
        }
        // Both timestamps are nonnegative and ordered, so subtraction cannot overflow.
        val dt = (tNs - this.tNs) / NANOS_PER_SECOND
        if (dt < config.minStepS) {
            // No time passed, so nothing propagated and no gap opened. The sample is counted
            // rather than silently absorbed, because a stream full of duplicates is a defect.
            duplicateTimestamps += 1
            return PropagationOutcome.DUPLICATE_TIMESTAMP
        }
        if (dt > config.failureGapS) {
            fail(FusionFailure.TIME_GAP_EXCEEDS_LIMIT)
            return PropagationOutcome.FAILED_GAP
        }
        if (dt > config.maxStepS) {
            // No sample inside the interval, so there is nothing to integrate. The state is held
            // and the covariance is inflated by the same process model, so the unobserved motion
            // becomes uncertainty the next measurement will have to overcome.
            val previousCovariance = covariance
            covariance = symmetrize(add(covariance, processNoise(dt)), DIM)
            if (!covarianceIsNumericallyUsable()) {
                covariance = previousCovariance
                fail(FusionFailure.NUMERICAL_INVALIDITY)
                return PropagationOutcome.FAILED_NUMERICAL
            }
            heldGaps += 1
            this.tNs = tNs
            return PropagationOutcome.HELD_GAP
        }

        val checkpoint = checkpoint()
        return try {
            val outcome = propagateFinite(accelDeviceM_S2, gyroDeviceRad_S, tNs, dt)
            if (outcome == PropagationOutcome.FAILED_NUMERICAL) restore(checkpoint)
            outcome
        } catch (_: RuntimeException) {
            // Finite but extreme inputs can overflow a norm, frame-rate term, or quaternion
            // normalization. Roll back the partially computed state, then expose a named terminal
            // failure rather than leaking a fabricated position or worker exception.
            restore(checkpoint)
            fail(FusionFailure.NUMERICAL_INVALIDITY)
            PropagationOutcome.FAILED_NUMERICAL
        }
    }

    private fun propagateFinite(
        accelDeviceM_S2: Vector3,
        gyroDeviceRad_S: Vector3,
        tNs: Long,
        dt: Double,
    ): PropagationOutcome {
        val geodetic = Geodesy.geodeticFromEnu(anchor!!, positionEnu)
        val latitudeRad = Math.toRadians(geodetic.latitudeDeg)

        // Attitude: the measured body increment first, then the navigation frame's own rotation.
        // The order matters and is the baseline's: getting it backwards makes a stationary run
        // drift at the Earth rate instead of cancelling it.
        val gyroCorrected = gyroDeviceRad_S - gyroBias
        val bodyIncrement = quaternionFromRotationVector(gyroCorrected * dt)
        val navRate = Geodesy.earthRateEnu(latitudeRad) +
            Geodesy.transportRateEnu(latitudeRad, geodetic.altitudeM, velocityEnu)
        val navIncrement = quaternionFromRotationVector(navRate * -dt)
        qEnuFromDevice = (navIncrement * qEnuFromDevice * bodyIncrement).normalized()

        // Specific force to acceleration.
        val accelCorrected = accelDeviceM_S2 - accelBias
        val rotation = qEnuFromDevice.toRotationMatrix()
        val specificForceEnu = apply3(rotation, accelCorrected)
        val gravityEnu = Geodesy.gravityEnu(latitudeRad, geodetic.altitudeM)
        val frameRate = Geodesy.earthRateEnu(latitudeRad) * 2.0 +
            Geodesy.transportRateEnu(latitudeRad, geodetic.altitudeM, velocityEnu)
        val accelerationEnu = specificForceEnu + gravityEnu - (frameRate cross velocityEnu)

        val previousVelocity = velocityEnu
        val deltaVelocity = if (havePreviousAcceleration) {
            (previousAccelerationEnu + accelerationEnu) * (0.5 * dt)
        } else {
            // The first integrated step has no previous acceleration to average. A fabricated
            // midpoint would inject a real error larger than the truncation error it removes.
            accelerationEnu * dt
        }
        velocityEnu = previousVelocity + deltaVelocity
        positionEnu = positionEnu + (previousVelocity + velocityEnu) * (0.5 * dt)
        previousAccelerationEnu = accelerationEnu
        havePreviousAcceleration = true

        val phi = transitionMatrix(rotation, specificForceEnu, navRate, frameRate, dt)
        covariance = symmetrize(
            add(multiplyByTranspose(multiplyMatrix(phi, covariance, DIM), phi, DIM), processNoise(dt)),
            DIM,
        )
        if (!stateIsNumericallyUsable()) {
            fail(FusionFailure.NUMERICAL_INVALIDITY)
            return PropagationOutcome.FAILED_NUMERICAL
        }
        this.tNs = tNs
        return PropagationOutcome.PROPAGATED
    }

    /**
     * `Phi = I + F dt`.
     *
     * A first-order approximation of the discrete transition. With samples at 100 Hz the omitted
     * terms are `O(dt^2)` = 1e-4 relative, far below the process noise they would be added to;
     * the tests assert the covariance stays symmetric and positive-definite, which is what a
     * too-coarse transition would eventually break.
     */
    private fun transitionMatrix(
        rotation: DoubleArray,
        specificForceEnu: Vector3,
        navRate: Vector3,
        frameRate: Vector3,
        dt: Double,
    ): DoubleArray {
        val f = DoubleArray(DIM * DIM)
        // d(position)/d(velocity) = I
        setBlock(f, DIM, IDX_POS, IDX_VEL, identity(3))
        // d(velocity)/d(velocity) = -[2 w_ie + w_en]
        setBlock(f, DIM, IDX_VEL, IDX_VEL, skew(frameRate * -1.0))
        // d(velocity)/d(attitude) = -[C f]
        setBlock(f, DIM, IDX_VEL, IDX_ATT, skew(specificForceEnu * -1.0))
        // d(velocity)/d(accelerometer bias) = -C
        setBlock(f, DIM, IDX_VEL, IDX_ABIAS, scale3(rotation, -1.0))
        // d(velocity)/d(position): gravity falls with height, so the up row sees the gradient.
        // The latitude term is O(1e-8 s^-2) and is deliberately dropped.
        f[(IDX_VEL + 2) * DIM + (IDX_POS + 2)] = -FREE_AIR_GRADIENT_PER_M
        // d(attitude)/d(attitude) = -[w_ie + w_en]
        setBlock(f, DIM, IDX_ATT, IDX_ATT, skew(navRate * -1.0))
        // d(attitude)/d(gyroscope bias) = -C
        setBlock(f, DIM, IDX_ATT, IDX_GBIAS, scale3(rotation, -1.0))
        // Bias blocks are pure random walks: no deterministic dynamics, only Q.

        val phi = identity(DIM)
        for (i in 0 until DIM * DIM) phi[i] += f[i] * dt
        return phi
    }

    /**
     * Discrete process noise for one interval.
     *
     * The position and velocity blocks are the exact discretization of a double integrator driven
     * by white acceleration noise, including the cross term; dropping the cross term is a common
     * simplification that makes the pair of blocks inconsistent with each other.
     */
    private fun processNoise(dt: Double): DoubleArray {
        val q = DoubleArray(DIM * DIM)
        val accel = config.accelNoiseDensityM_S2_RT_HZ * config.accelNoiseDensityM_S2_RT_HZ
        val gyro = config.gyroNoiseDensityRad_S_RT_HZ * config.gyroNoiseDensityRad_S_RT_HZ
        addBlock(q, DIM, IDX_POS, IDX_POS, scale3(identity(3), accel * dt * dt * dt / 3.0))
        addBlock(q, DIM, IDX_POS, IDX_VEL, scale3(identity(3), accel * dt * dt / 2.0))
        addBlock(q, DIM, IDX_VEL, IDX_POS, scale3(identity(3), accel * dt * dt / 2.0))
        addBlock(q, DIM, IDX_VEL, IDX_VEL, scale3(identity(3), accel * dt))
        addBlock(q, DIM, IDX_ATT, IDX_ATT, scale3(identity(3), gyro * dt))
        addBlock(
            q, DIM, IDX_GBIAS, IDX_GBIAS,
            scale3(identity(3), config.gyroBiasRandomWalkRad_S_RT_S * config.gyroBiasRandomWalkRad_S_RT_S * dt),
        )
        addBlock(
            q, DIM, IDX_ABIAS, IDX_ABIAS,
            scale3(identity(3), config.accelBiasRandomWalkM_S2_RT_S * config.accelBiasRandomWalkM_S2_RT_S * dt),
        )
        return q
    }

    // ---------------------------------------------------------------------------------------
    // Measurements
    // ---------------------------------------------------------------------------------------

    /**
     * GNSS position. Two horizontal rows, plus a vertical row only when the provider reported an
     * ellipsoidal altitude and the anchor itself has one: an MSL altitude is a different origin
     * and mixing the two would inject a constant vertical offset as if it were a measurement.
     */
    fun updateGnssPosition(
        tNs: Long,
        latitudeDeg: Double,
        longitudeDeg: Double,
        altitudeM: Double?,
        horizontalVarianceM2: Double,
        /** Null when the provider gave no vertical accuracy, which drops the vertical row. */
        verticalVarianceM2: Double?,
    ): GnssUpdateOutcome = guarded {
        val anchor = anchor!!
        val measured = Geodesy.enuFromGeodetic(
            anchor, latitudeDeg, longitudeDeg, altitudeM ?: anchor.altitudeM,
        )
        val residuals = ArrayList<Double>(3)
        val rows = ArrayList<DoubleArray>(3)
        val variances = ArrayList<Double>(3)
        residuals.add(measured.x - positionEnu.x)
        rows.add(unitRow(IDX_POS + 0))
        variances.add(horizontalVarianceM2)
        residuals.add(measured.y - positionEnu.y)
        rows.add(unitRow(IDX_POS + 1))
        variances.add(horizontalVarianceM2)
        // The vertical row needs a measured ellipsoidal altitude, an anchor that has one, and a
        // stated vertical variance. Without any of the three the row is simply not built, so a
        // fix that reports no vertical accuracy still gets its horizontal update instead of
        // losing the whole measurement.
        if (altitudeM != null && anchor.hasAltitude &&
            verticalVarianceM2 != null && verticalVarianceM2 > 0.0 && verticalVarianceM2.isFinite()
        ) {
            residuals.add(measured.z - positionEnu.z)
            rows.add(unitRow(IDX_POS + 2))
            variances.add(verticalVarianceM2)
        }
        applyRows(
            tNs,
            resetsRejectionSpan = true,
            countAsConstraint = false,
            rows = rows,
            residuals = residuals.toDoubleArray(),
            variances = variances.toDoubleArray(),
        )
    }

    /**
     * GNSS course velocity: both horizontal components, which is only meaningful when the provider
     * reported a course as well as a speed. Course is degrees clockwise from north.
     */
    fun updateGnssCourseVelocity(
        tNs: Long,
        speedM_S: Double,
        courseDeg: Double,
        varianceM2: Double,
    ): GnssUpdateOutcome = guarded {
        val courseRad = Math.toRadians(courseDeg)
        val east = speedM_S * kotlin.math.sin(courseRad)
        val north = speedM_S * kotlin.math.cos(courseRad)
        applyRows(
            tNs,
            resetsRejectionSpan = false,
            rows = arrayListOf(unitRow(IDX_VEL + 0), unitRow(IDX_VEL + 1)),
            residuals = doubleArrayOf(east - velocityEnu.x, north - velocityEnu.y),
            variances = doubleArrayOf(varianceM2, varianceM2),
            countAsConstraint = false,
        )
    }

    /**
     * GNSS speed alone: one row along the filter's own current horizontal heading.
     *
     * A speed with no course carries no direction, so it can only constrain the component along
     * the direction the filter already believes. That is valid while the vehicle is moving and
     * meaningless when it is not, so a near-stationary filter refuses it rather than picking a
     * heading at random.
     */
    fun updateGnssSpeed(tNs: Long, speedM_S: Double, varianceM2: Double): GnssUpdateOutcome = guarded {
        val horizontalSpeed = sqrt(velocityEnu.x * velocityEnu.x + velocityEnu.y * velocityEnu.y)
        if (horizontalSpeed < MIN_SPEED_FOR_DIRECTION_M_S) {
            return@guarded GnssUpdateOutcome.refused(GnssUpdateResult.REJECTED_INVALID, 1)
        }
        val row = unitRow(IDX_VEL + 0)
        row[IDX_VEL + 0] = velocityEnu.x / horizontalSpeed
        row[IDX_VEL + 1] = velocityEnu.y / horizontalSpeed
        applyRows(
            tNs,
            resetsRejectionSpan = false,
            rows = arrayListOf(row),
            residuals = doubleArrayOf(speedM_S - horizontalSpeed),
            variances = doubleArrayOf(varianceM2),
            countAsConstraint = false,
        )
    }

    /**
     * Zero-velocity update: three rows asserting the velocity is zero in ENU.
     *
     * This is a *pseudo-measurement*, not an observation: it is applied only when the engine's
     * stationary gate says the vehicle is at rest (detector quiet, filter speed below the creep
     * bound, and fresh accepted GNSS not contradicting), and its variance is deliberately loose
     * (σ 0.2 m/s by default) so a wrongly-fired constraint degrades rather than freezes the
     * solution. It counts against the constraint bookkeeping, never against the GNSS counters or
     * the persistent-rejection guard — a refused zero-velocity row is a model disagreement, not
     * a lying fix.
     */
    fun updateZupt(tNs: Long, varianceM2: Double): GnssUpdateOutcome = applyRows(
        tNs,
        resetsRejectionSpan = false,
        rows = arrayListOf(unitRow(IDX_VEL + 0), unitRow(IDX_VEL + 1), unitRow(IDX_VEL + 2)),
        residuals = doubleArrayOf(-velocityEnu.x, -velocityEnu.y, -velocityEnu.z),
        variances = doubleArrayOf(varianceM2, varianceM2, varianceM2),
        countAsConstraint = true,
    )

    /**
     * Non-holonomic constraints: the vehicle's lateral (left) and vertical velocity are zero in
     * the vehicle frame.
     *
     * The rows are the vehicle axes expressed in ENU — columns of [qEnuFromVehicle]'s rotation —
     * projected onto the ENU velocity, so the constraint follows the mounting and the current
     * attitude rather than assuming the device frame is the vehicle frame. Only the two
     * constrained components are measured; forward velocity is untouched. The engine stands this
     * down when calibration is invalid, speed is below the parking/reverse bound, or the yaw rate
     * is in the unusual-maneuver range (see `FusionNavigationEngine.applyMotionConstraints`).
     */
    fun updateNhc(
        tNs: Long,
        qEnuFromVehicle: Quaternion,
        lateralVarianceM2: Double,
        verticalVarianceM2: Double,
    ): GnssUpdateOutcome = guarded {
        val c = qEnuFromVehicle.toRotationMatrix()
        // Row-major: element (i, j) is m[i * 3 + j]; column j is the source +j axis in target.
        val lateral = doubleArrayOf(c[1], c[4], c[7])
        val vertical = doubleArrayOf(c[2], c[5], c[8])
        applyRows(
            tNs,
            resetsRejectionSpan = false,
            rows = arrayListOf(velocityRow(lateral), velocityRow(vertical)),
            residuals = doubleArrayOf(
                -projectVelocity(lateral),
                -projectVelocity(vertical),
            ),
            variances = doubleArrayOf(lateralVarianceM2, verticalVarianceM2),
            countAsConstraint = true,
        )
    }

    /** A 1x15 selection row that picks the three velocity components along [axisEnu]. */
    private fun velocityRow(axisEnu: DoubleArray): DoubleArray = DoubleArray(DIM).also {
        it[IDX_VEL] = axisEnu[0]
        it[IDX_VEL + 1] = axisEnu[1]
        it[IDX_VEL + 2] = axisEnu[2]
    }

    private fun projectVelocity(axisEnu: DoubleArray): Double =
        axisEnu[0] * velocityEnu.x + axisEnu[1] * velocityEnu.y + axisEnu[2] * velocityEnu.z

    /** Shared entry checks: nothing is applied to a filter that cannot accept it. */
    private inline fun guarded(block: () -> GnssUpdateOutcome): GnssUpdateOutcome {
        if (!aligned) return GnssUpdateOutcome.refused(GnssUpdateResult.NOT_ALIGNED)
        if (status == FusionStatus.FAILED) return GnssUpdateOutcome.refused(GnssUpdateResult.FAILED)
        return block()
    }

    /**
     * Gate and apply one measurement.
     *
     * Gating is done on the *joint* normalised innovation squared for the whole measurement, so a
     * two-row position update is judged against the 2-degree-of-freedom threshold rather than
     * twice against the 1-degree one, which would be a different and stricter test than intended.
     * Only once the measurement passes is it applied, row by row, each row recomputing its own
     * gain against the covariance the previous row left behind.
     *
     * [countAsConstraint] separates the two books: a rejected GNSS row extends the
     * persistent-rejection span and increments [rejectedUpdates], while a rejected vehicle-
     * constraint row only increments the constraint counter — the guard exists to notice a
     * solution that has stopped describing the same place as the arriving fixes, and a
     * pseudo-measurement disagreeing with the filter is not that.
     */
    private fun applyRows(
        tNs: Long,
        resetsRejectionSpan: Boolean,
        rows: List<DoubleArray>,
        residuals: DoubleArray,
        variances: DoubleArray,
        countAsConstraint: Boolean,
    ): GnssUpdateOutcome {
        val dimension = rows.size
        if (!isUsableCovariance(covariance, DIM)) {
            fail(FusionFailure.NUMERICAL_INVALIDITY)
            return GnssUpdateOutcome.refused(GnssUpdateResult.FAILED, dimension)
        }
        for (variance in variances) {
            if (!variance.isFinite() || variance <= 0.0) {
                return GnssUpdateOutcome.refused(GnssUpdateResult.REJECTED_INVALID, dimension)
            }
        }
        for (residual in residuals) {
            if (!residual.isFinite()) {
                return GnssUpdateOutcome.refused(GnssUpdateResult.REJECTED_INVALID, dimension)
            }
        }

        // Joint innovation covariance S = H P H^T + R.
        val projected = rows.map { multiplyVector(covariance, it, DIM) }
        val innovationCovariance = DoubleArray(dimension * dimension)
        for (i in 0 until dimension) {
            for (j in 0 until dimension) {
                innovationCovariance[i * dimension + j] = dot(rows[i], projected[j])
            }
            innovationCovariance[i * dimension + i] += variances[i]
        }
        val solved = choleskySolve(innovationCovariance, dimension, residuals)
            ?: return GnssUpdateOutcome.refused(GnssUpdateResult.REJECTED_ILL_CONDITIONED, dimension)
        if (solved.any { !it.isFinite() }) {
            return GnssUpdateOutcome.refused(GnssUpdateResult.REJECTED_ILL_CONDITIONED, dimension)
        }
        // Solved is the solution buffer, whose length is the state dimension, not the measurement
        // one: the statistic must sum over exactly the rows this measurement built.
        var nis = 0.0
        for (i in 0 until dimension) nis += residuals[i] * solved[i]
        val threshold = chiSquareThreshold(dimension, config.gateProbability)
        val innovationMagnitude = sqrt(residuals.sumOf { it * it })
        if (!nis.isFinite()) {
            return GnssUpdateOutcome.refused(GnssUpdateResult.REJECTED_ILL_CONDITIONED, dimension)
        }
        if (nis > threshold) {
            if (countAsConstraint) {
                constraintRejected += 1
            } else {
                rejectedUpdates += 1
                lastRejectionNis = nis
                extendRejectionSpan(tNs)
            }
            return GnssUpdateOutcome(
                GnssUpdateResult.REJECTED_GATE, dimension, nis, threshold, innovationMagnitude, 0.0,
            )
        }
        // Only an accepted position update ends the stretch. Velocity rows are part of the same
        // fix, but they constrain how the state moves, not where it is: a solution that has lost
        // its position can accept every velocity row it is offered and still run away. Letting a
        // speed acceptance reset the span would let a fix whose position is refused forever keep
        // the guard from ever firing, which is the one thing it exists to catch. Constraints
        // never touch the span at all (they pass resetsRejectionSpan = false).
        if (!countAsConstraint && resetsRejectionSpan) rejectionSpanS = 0.0

        val correction = DoubleArray(DIM)
        val updateCheckpoint = checkpoint()
        try {
            for (i in 0 until dimension) {
                val row = rows[i]
                val projectedRow = multiplyVector(covariance, row, DIM)
                val rowVariance = dot(row, projectedRow) + variances[i]
                if (!(rowVariance > 0.0) || !rowVariance.isFinite()) {
                    restore(updateCheckpoint)
                    fail(FusionFailure.NUMERICAL_INVALIDITY)
                    return GnssUpdateOutcome.refused(GnssUpdateResult.FAILED, dimension)
                }
                val gain = DoubleArray(DIM) { projectedRow[it] / rowVariance }
                if (gain.any { !it.isFinite() }) {
                    restore(updateCheckpoint)
                    fail(FusionFailure.NUMERICAL_INVALIDITY)
                    return GnssUpdateOutcome.refused(GnssUpdateResult.FAILED, dimension)
                }
                // The error state is relative to the current nominal, so the innovation of this row
                // is what the earlier rows of the same measurement have not already applied.
                val innovation = residuals[i] - dot(row, correction)
                for (k in 0 until DIM) correction[k] += gain[k] * innovation
                covariance = josephUpdate(covariance, row, gain, variances[i])
            }
            covariance = symmetrize(covariance, DIM)
            inject(correction)
            if (!stateIsNumericallyUsable()) {
                restore(updateCheckpoint)
                fail(FusionFailure.NUMERICAL_INVALIDITY)
                return GnssUpdateOutcome.refused(GnssUpdateResult.FAILED, dimension)
            }
        } catch (_: RuntimeException) {
            // A finite input can overflow inside the correction/Joseph update. Roll back the
            // complete measurement so no prefix of its rows changes the published state.
            restore(updateCheckpoint)
            fail(FusionFailure.NUMERICAL_INVALIDITY)
            return GnssUpdateOutcome.refused(GnssUpdateResult.FAILED, dimension)
        }
        if (countAsConstraint) {
            constraintAccepted += 1
        } else {
            acceptedUpdates += 1
            lastGnssUpdateNs = maxOf(lastGnssUpdateNs ?: tNs, tNs)
        }
        val correctionM = sqrt(
            correction[IDX_POS] * correction[IDX_POS] +
                correction[IDX_POS + 1] * correction[IDX_POS + 1] +
                correction[IDX_POS + 2] * correction[IDX_POS + 2],
        )
        return GnssUpdateOutcome(
            GnssUpdateResult.ACCEPTED, dimension, nis, threshold, innovationMagnitude, correctionM,
        )
    }

    /** `P = (I - K h^T) P (I - K h^T)^T + K r K^T`, the form that stays positive-definite. */
    private fun josephUpdate(
        p: DoubleArray,
        row: DoubleArray,
        gain: DoubleArray,
        variance: Double,
    ): DoubleArray {
        val a = identity(DIM)
        for (i in 0 until DIM) {
            val g = gain[i]
            if (g == 0.0) continue
            for (j in 0 until DIM) a[i * DIM + j] -= g * row[j]
        }
        val ap = multiplyMatrix(a, p, DIM)
        val next = multiplyByTranspose(ap, a, DIM)
        for (i in 0 until DIM) {
            for (j in 0 until DIM) next[i * DIM + j] += gain[i] * gain[j] * variance
        }
        return next
    }

    /**
     * Track how long the gate has been continuously closed, and give up when it has been too long.
     *
     * The span counts only the intervals between measurements that actually followed one another:
     * a pause in the stream resets it, because a stream that stopped is an outage, and an outage is
     * coasted through rather than treated as a disagreement. A span that runs past
     * [FusionConfig.maxRejectionSpanS] means the solution and the fixes have stopped describing the
     * same vehicle and the filter has no way to choose between them.
     */
    private fun extendRejectionSpan(tNs: Long) {
        val previous = lastMeasurementAttemptNs
        val gapS = if (previous == null) 0.0 else (tNs - previous) / NANOS_PER_SECOND
        rejectionSpanS = if (gapS <= config.maxRejectionGapS) rejectionSpanS + gapS else 0.0
        lastMeasurementAttemptNs = tNs
        if (rejectionSpanS > config.maxRejectionSpanS) {
            fail(FusionFailure.PERSISTENT_INNOVATION_REJECTION)
        }
    }

    /** Fold an error-state correction into the nominal state, then the error is zero again. */
    private fun inject(correction: DoubleArray) {
        positionEnu = positionEnu + Vector3(correction[IDX_POS], correction[IDX_POS + 1], correction[IDX_POS + 2])
        velocityEnu = velocityEnu + Vector3(correction[IDX_VEL], correction[IDX_VEL + 1], correction[IDX_VEL + 2])
        val attitudeError = Vector3(correction[IDX_ATT], correction[IDX_ATT + 1], correction[IDX_ATT + 2])
        // The error is defined globally, so it composes on the left of the nominal attitude.
        qEnuFromDevice = (quaternionFromRotationVector(attitudeError) * qEnuFromDevice).normalized()
        gyroBias = gyroBias + Vector3(correction[IDX_GBIAS], correction[IDX_GBIAS + 1], correction[IDX_GBIAS + 2])
        accelBias = accelBias + Vector3(correction[IDX_ABIAS], correction[IDX_ABIAS + 1], correction[IDX_ABIAS + 2])
    }

    /**
     * A finite input can still overflow intermediate arithmetic. Do not let a NaN state or a
     * non-positive covariance escape as a confidence radius or as the next update's prior.
     */
    private fun covarianceIsNumericallyUsable(): Boolean {
        return isUsableCovariance(covariance, DIM)
    }

    private fun stateIsNumericallyUsable(): Boolean =
        isFinite(positionEnu) && isFinite(velocityEnu) &&
            isFiniteQuaternion(qEnuFromDevice) && isFinite(gyroBias) && isFinite(accelBias) &&
            covarianceIsNumericallyUsable()

    private fun fail(failure: FusionFailure) {
        status = FusionStatus.FAILED
        this.failure = failure
    }

    // ---------------------------------------------------------------------------------------
    // Readout
    // ---------------------------------------------------------------------------------------

    /** A copy of the filter's state. Nothing here is a view onto a mutable field. */
    val solution: FusionSolution
        get() = FusionSolution(
            status = status,
            tNs = tNs,
            alignedAtNs = alignedAtNs,
            anchor = anchor,
            geodetic = anchor?.let { Geodesy.geodeticFromEnu(it, positionEnu) },
            positionEnuM = positionEnu,
            velocityEnuM_S = velocityEnu,
            qEnuFromDevice = qEnuFromDevice,
            gyroBiasDeviceRad_S = gyroBias,
            accelBiasDeviceM_S2 = accelBias,
            positionSigmaM = Vector3(
                sqrt(covariance[IDX_POS * DIM + IDX_POS]),
                sqrt(covariance[(IDX_POS + 1) * DIM + IDX_POS + 1]),
                sqrt(covariance[(IDX_POS + 2) * DIM + IDX_POS + 2]),
            ),
            velocitySigmaM_S = Vector3(
                sqrt(covariance[IDX_VEL * DIM + IDX_VEL]),
                sqrt(covariance[(IDX_VEL + 1) * DIM + IDX_VEL + 1]),
                sqrt(covariance[(IDX_VEL + 2) * DIM + IDX_VEL + 2]),
            ),
            attitudeSigmaRad = Vector3(
                sqrt(covariance[IDX_ATT * DIM + IDX_ATT]),
                sqrt(covariance[(IDX_ATT + 1) * DIM + IDX_ATT + 1]),
                sqrt(covariance[(IDX_ATT + 2) * DIM + IDX_ATT + 2]),
            ),
            covariance = covariance.copyOf(),
            lastGnssUpdateNs = lastGnssUpdateNs,
            acceptedUpdates = acceptedUpdates,
            rejectedUpdates = rejectedUpdates,
            constraintAccepted = constraintAccepted,
            constraintRejected = constraintRejected,
            duplicateTimestamps = duplicateTimestamps,
            backwardsTimestamps = backwardsTimestamps,
            heldGaps = heldGaps,
            failure = failure,
            lastRejectionNis = lastRejectionNis,
        )

    private companion object {
        const val DIM = 15
        const val IDX_POS = 0
        const val IDX_VEL = 3
        const val IDX_ATT = 6
        const val IDX_GBIAS = 9
        const val IDX_ABIAS = 12
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /** Below this horizontal speed a speed-only measurement has no direction to apply. */
        const val MIN_SPEED_FOR_DIRECTION_M_S = 1.0

        /**
         * Chi-square quantiles at the 0.999 confidence used by the gate, indexed by degrees of
         * freedom. These are the standard tabulated values, quoted rather than recomputed.
         */
        val CHI_SQUARE_999 = doubleArrayOf(0.0, 10.8276, 13.8155, 16.2662)

        fun chiSquareThreshold(dimension: Int, probability: Double): Double {
            if (probability >= 0.999 && dimension in 1..3) return CHI_SQUARE_999[dimension]
            // A different confidence needs its own table; falling back to the tabulated 0.999
            // value is stated here rather than inventing a quantile the code cannot compute.
            return CHI_SQUARE_999.getOrElse(dimension) { Double.POSITIVE_INFINITY }
        }

        /** A 1x15 selection row that picks out one state component. */
        fun unitRow(index: Int): DoubleArray = DoubleArray(DIM).also { it[index] = 1.0 }

        fun setDiagonal(target: DoubleArray, offset: Int, values: Vector3) {
            target[offset * DIM + offset] = values.x
            target[(offset + 1) * DIM + offset + 1] = values.y
            target[(offset + 2) * DIM + offset + 2] = values.z
        }
    }
}
