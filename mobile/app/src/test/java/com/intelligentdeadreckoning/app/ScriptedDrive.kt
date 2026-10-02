/*
 * The scripted ground-truth drive: one closed-form trajectory, the IMU and GNSS samples generated
 * from it, and the declared sensor error model. Both the confidence-coverage evaluation
 * (`ConfidenceCoverageTest`) and the arm-comparison evaluation (`EvaluationHarnessTest`) replay
 * this drive, so the two evaluations measure the same physics and their numbers are comparable.
 *
 * The drive: 20 s straight north at 12 m/s, an 8 s left turn at 0.2 rad/s (a 92 degree turn at a
 * 60 m radius), then straight for the rest. 70 s total, with the GNSS outage the caller declares.
 * The engine is told the vehicle frame is the device frame and assumes an initial heading of
 * north, so at the first sample the device axes are north / west / up.
 *
 * The declared sensor model (identical to the coverage harness's, which measured it):
 * - IMU at 100 Hz, white noise per sample equal to the engine's own configured density divided by
 *   `sqrt(dt)`, so the filter's process-noise model matches the data it is given;
 * - a constant accelerometer bias of 0.02 m/s^2 and gyroscope bias of 0.001 rad/s, injected and
 *   never disclosed, both inside the filter's own unknown-bias priors;
 * - GNSS at 1 Hz with circular 3 m one-sigma noise reported as a 3 m horizontal accuracy.
 *
 * Nothing here is navigation: it produces the inputs a reference trajectory implies, and the
 * truth itself, so an arm can be scored against a quantity it cannot have fitted to.
 */
package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.contracts.v1.Vector3
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object ScriptedDrive {
    const val SPEED_M_S = 12.0
    const val TURN_START_S = 20.0
    const val TURN_END_S = 28.0
    const val YAW_RATE_RAD_S = 0.2
    const val TOTAL_S = 70.0
    const val OUTAGE_FROM_S = 30.0
    const val OUTAGE_TO_S = 50.0
    const val STEP_S = 0.01

    /** The one-sigma of the generated fixes, reported to the engine as their accuracy. */
    const val FIX_SIGMA_M = 3.0

    /** Declared constant sensor errors the filter is not told about; both inside its priors. */
    const val ACCEL_BIAS_NORTH_M_S2 = 0.02
    const val ACCEL_BIAS_WEST_M_S2 = 0.02
    const val GYRO_BIAS_RAD_S = 0.001

    /** Declared convergence bound: the error a recovering solution must return under. */
    const val RECOVERY_THRESHOLD_M = 5.0

    val ANCHOR = GeodeticAnchor(RoadGraphFixtures.ANCHOR_LAT, RoadGraphFixtures.ANCHOR_LON, 500.0, true)

    private const val PI_2 = Math.PI / 2.0
    private val RADIUS_M = SPEED_M_S / YAW_RATE_RAD_S

    private fun alpha(tS: Double): Double =
        PI_2 + YAW_RATE_RAD_S * (tS - TURN_START_S).coerceIn(0.0, TURN_END_S - TURN_START_S)

    /** The reference position at t seconds, ENU metres from the nominal anchor. */
    fun position(tS: Double): Vector3 {
        if (tS <= TURN_START_S) return Vector3(0.0, SPEED_M_S * tS, 0.0)
        val a = alpha(tS)
        if (tS <= TURN_END_S) {
            // The turn centre is one radius to the left of the entry: due west of (0, 240).
            return Vector3(-RADIUS_M + RADIUS_M * sin(a), SPEED_M_S * TURN_START_S - RADIUS_M * cos(a), 0.0)
        }
        val aEnd = alpha(TURN_END_S)
        val exit = Vector3(
            -RADIUS_M + RADIUS_M * sin(aEnd),
            SPEED_M_S * TURN_START_S - RADIUS_M * cos(aEnd),
            0.0,
        )
        return Vector3(
            exit.x + SPEED_M_S * cos(aEnd) * (tS - TURN_END_S),
            exit.y + SPEED_M_S * sin(aEnd) * (tS - TURN_END_S),
            0.0,
        )
    }

    /** The reference velocity, m/s ENU. */
    fun velocity(tS: Double): Vector3 {
        val a = alpha(tS)
        return Vector3(SPEED_M_S * cos(a), SPEED_M_S * sin(a), 0.0)
    }

    /** The reference speed, m/s. Constant here, but measured the same way any arm's is. */
    fun speed(tS: Double): Double = hypot(velocity(tS).x, velocity(tS).y)

    /** The reference heading, degrees clockwise from north. */
    fun headingDeg(tS: Double): Double {
        val v = velocity(tS)
        return (Math.toDegrees(kotlin.math.atan2(v.x, v.y)) + 360.0) % 360.0
    }

    /** The reference yaw rate about up, rad/s. Non-zero only through the turn. */
    fun yawRate(tS: Double): Double =
        if (tS > TURN_START_S && tS <= TURN_END_S) YAW_RATE_RAD_S else 0.0

    /** The reference acceleration (derivative of [velocity]), m/s^2 ENU. */
    fun acceleration(tS: Double): Vector3 {
        val a = alpha(tS)
        val magnitude = SPEED_M_S * yawRate(tS)
        return Vector3(-magnitude * sin(a), magnitude * cos(a), 0.0)
    }

    /** The truth's geodetic position, which is what a fix is generated around. */
    fun geodetic(tS: Double) = Geodesy.geodeticFromEnu(ANCHOR, position(tS))

    fun gravity(): Double = Geodesy.normalGravity(Math.toRadians(ANCHOR.latitudeDeg), ANCHOR.altitudeM)

    fun accelNoiseSigma(): Double = FusionConfig().accelNoiseDensityM_S2_RT_HZ / Math.sqrt(STEP_S)

    fun gyroNoiseSigma(): Double = FusionConfig().gyroNoiseDensityRad_S_RT_HZ / Math.sqrt(STEP_S)

    /**
     * Device-frame accelerometer and gyroscope samples for one step, with the declared noise and
     * biases drawn in a fixed order from [random]. The device frame at the assumed initial heading
     * is x = north, y = west, z = up.
     */
    fun imu(tS: Double, random: Random): Pair<Vector3, Vector3> {
        val acceleration = acceleration(tS)
        val accelSigma = accelNoiseSigma()
        val gyroSigma = gyroNoiseSigma()
        val accel = Vector3(
            acceleration.y + ACCEL_BIAS_NORTH_M_S2 + random.nextGaussian() * accelSigma,
            -acceleration.x - ACCEL_BIAS_WEST_M_S2 + random.nextGaussian() * accelSigma,
            acceleration.z + gravity() + random.nextGaussian() * accelSigma,
        )
        val gyro = Vector3(
            0.0, 0.0,
            yawRate(tS) + GYRO_BIAS_RAD_S + random.nextGaussian() * gyroSigma,
        )
        return accel to gyro
    }

    /** The two Gaussian draws a generated fix's position noise uses, in a fixed order. */
    fun fixNoise(random: Random): Pair<Double, Double> =
        (random.nextGaussian() * FIX_SIGMA_M) to (random.nextGaussian() * FIX_SIGMA_M)
}
