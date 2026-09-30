package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.conjugate
import com.intelligentdeadreckoning.app.calibration.cross
import com.intelligentdeadreckoning.app.calibration.minus
import com.intelligentdeadreckoning.app.calibration.plus
import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.calibration.rotate
import com.intelligentdeadreckoning.app.calibration.times
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionFailure
import com.intelligentdeadreckoning.app.fusion.FusionInitialState
import com.intelligentdeadreckoning.app.fusion.FusionStatus
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.GnssInsEkf
import com.intelligentdeadreckoning.app.fusion.GnssUpdateOutcome
import com.intelligentdeadreckoning.app.fusion.GnssUpdateResult
import com.intelligentdeadreckoning.app.fusion.PropagationOutcome
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classical filter against synthetic truth.
 *
 * Every case is closed form: a trajectory is chosen, the inertial measurements *physics* would
 * produce are synthesized from the equations of motion, and the filter output is compared with the
 * analytic answer. The generator uses the same gravity and frame-rate model the filter does, so it
 * is not an independent witness of those — that is what `training/strapdown_ins.py`'s tests and
 * `GeodesyTest` are for. What it does witness is the filtering: prediction, gating, covariance
 * growth and recovery.
 */
internal data class TruthState(
    val positionEnu: Vector3,
    val velocityEnu: Vector3,
    val accelerationEnu: Vector3,
    val headingRad: Double,
    val bodyRateEnu: Vector3,
)

internal class Script {
    private class Entry(val startS: Double, val durationS: Double, val evaluate: (Double, TruthState) -> TruthState)

    private val entries = ArrayList<Entry>()

    private fun add(durationS: Double, evaluate: (Double, TruthState) -> TruthState): Script {
        val start = if (entries.isEmpty()) 0.0 else entries.last().let { it.startS + it.durationS }
        entries.add(Entry(start, durationS, evaluate))
        return this
    }

    fun stationary(seconds: Double): Script = add(seconds) { _, start ->
        start.copy(
            velocityEnu = Vector3(0.0, 0.0, 0.0),
            accelerationEnu = Vector3(0.0, 0.0, 0.0),
            bodyRateEnu = Vector3(0.0, 0.0, 0.0),
        )
    }

    fun straight(seconds: Double, speed: Double, accel: Double = 0.0): Script = add(seconds) { s, start ->
        val heading = start.headingRad
        val direction = Vector3(sin(heading), cos(heading), 0.0)
        val distance = speed * s + 0.5 * accel * s * s
        start.copy(
            positionEnu = start.positionEnu + direction * distance,
            velocityEnu = direction * (speed + accel * s),
            accelerationEnu = direction * accel,
            bodyRateEnu = Vector3(0.0, 0.0, 0.0),
        )
    }

    /**
     * A constant-rate turn at constant speed. A positive rate increases the compass heading, i.e.
     * turns right — which is a *negative* rotation about ENU up, so the body rate carried in the
     * gyroscope has the opposite sign to the heading rate. Getting this wrong feeds the filter a
     * yaw rate that disagrees with the trajectory the fixes describe, and the estimator dutifully
     * absorbs the disagreement into the yaw gyro bias.
     */
    fun turn(seconds: Double, speed: Double, yawRateRadPerS: Double): Script = add(seconds) { s, start ->
        val psi0 = start.headingRad
        val psi = psi0 + yawRateRadPerS * s
        val arc = if (abs(yawRateRadPerS) < 1e-12) {
            Vector3(speed * s * sin(psi0), speed * s * cos(psi0), 0.0)
        } else {
            Vector3(
                speed / yawRateRadPerS * (cos(psi0) - cos(psi)),
                speed / yawRateRadPerS * (sin(psi) - sin(psi0)),
                0.0,
            )
        }
        start.copy(
            positionEnu = start.positionEnu + arc,
            velocityEnu = Vector3(speed * sin(psi), speed * cos(psi), 0.0),
            accelerationEnu = Vector3(
                speed * yawRateRadPerS * cos(psi),
                -speed * yawRateRadPerS * sin(psi),
                0.0,
            ),
            headingRad = psi,
            bodyRateEnu = Vector3(0.0, 0.0, -yawRateRadPerS),
        )
    }

    /** Truth at an absolute time, evaluated piecewise from the start of the script. */
    fun stateAt(tS: Double): TruthState {
        var state = TruthState(
            Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.0,
            Vector3(0.0, 0.0, 0.0),
        )
        for (entry in entries) {
            if (tS <= entry.startS + entry.durationS + 1e-12) {
                return entry.evaluate(tS - entry.startS, state)
            }
            state = entry.evaluate(entry.durationS, state)
        }
        return state
    }
}

/**
 * Truth plus IMU synthesis plus a filter, driven step by step.
 *
 * The synthesized accelerometer reads the specific force physics would produce — at rest it points
 * up with magnitude g — and the gyroscope reads the Earth rate, the transport rate at the current
 * speed and the vehicle's own body rate, all expressed in the device frame through the mounting.
 */
internal class FusionSim(
    private val script: Script,
    val simConfig: FusionConfig = FusionConfig(),
    private val gyroBiasDevice: Vector3 = Vector3(0.0, 0.0, 0.0),
    private val accelBiasDevice: Vector3 = Vector3(0.0, 0.0, 0.0),
    private val imuRateHz: Double = 100.0,
    private val gyroBiasVariance: Double = 1.0e-4,
    private val accelBiasVariance: Double = 1.0e-2,
) {
    val anchor = GeodeticAnchor(ANCHOR_LATITUDE_DEG, ANCHOR_LONGITUDE_DEG, 0.0, true)
    val filter = GnssInsEkf(simConfig)
    private val latitudeRad = Math.toRadians(ANCHOR_LATITUDE_DEG)
    private var stepNs = 0L

    val dtNs: Long = (1_000_000_000.0 / imuRateHz).toLong()

    /** True attitude in ENU-from-device, from the truth heading and the fixed mounting. */
    fun attitudeAt(tS: Double): Quaternion {
        val truth = script.stateAt(tS)
        return enuFromVehicle(truth.headingRad) * MOUNTING_VEHICLE_FROM_DEVICE
    }

    fun enuFromVehicle(headingRad: Double): Quaternion = quaternionAboutZ(Math.PI / 2.0 - headingRad)

    /** Align to the truth at t = 0. Alignment is an input, not something the filter guesses. */
    fun align(
        positionVariance: Double = 100.0,
        velocityVariance: Double = 25.0,
        attitudeVarianceRad2: Double = 1e-4,
    ): Boolean {
        val truth = script.stateAt(0.0)
        return filter.align(
            FusionInitialState(
                anchor = anchor,
                qEnuFromDevice = attitudeAt(0.0),
                velocityEnuM_S = truth.velocityEnu,
                gyroBiasDeviceRad_S = Vector3(0.0, 0.0, 0.0),
                accelBiasDeviceM_S2 = Vector3(0.0, 0.0, 0.0),
                positionVarianceM2 = Vector3(positionVariance, positionVariance, positionVariance),
                velocityVarianceM2 = Vector3(velocityVariance, velocityVariance, velocityVariance),
                attitudeVarianceRad2 = Vector3(
                    attitudeVarianceRad2, attitudeVarianceRad2, attitudeVarianceRad2,
                ),
                gyroBiasVarianceRad2_S2 = gyroBiasVariance,
                accelBiasVarianceM2_S4 = accelBiasVariance,
            ),
            0L,
        )
    }

    fun acceptImuDt(t0Ns: Long, t1Ns: Long): PropagationOutcome {
        val accel = accelDevice(t0Ns)
        val gyro = gyroDevice(t0Ns)
        return filter.propagate(accel, gyro, t1Ns)
    }

    /** Advance one nominal step, feeding the inertial sample that the interval produced. */
    fun step(): PropagationOutcome {
        val outcome = acceptImuDt(stepNs, stepNs + dtNs)
        stepNs += dtNs
        return outcome
    }

    fun step(seconds: Double) {
        val count = Math.round(seconds * imuRateHz).toInt()
        repeat(count) { step() }
    }

    val nowS: Double get() = stepNs / 1e9

    fun accelDevice(tNs: Long): Vector3 {
        val tS = tNs / 1e9
        val truth = script.stateAt(tS)
        val frameRate = Geodesy.earthRateEnu(latitudeRad) * 2.0 +
            Geodesy.transportRateEnu(latitudeRad, truth.positionEnu.z, truth.velocityEnu)
        val specificEnu = truth.accelerationEnu + (frameRate cross truth.velocityEnu) -
            Geodesy.gravityEnu(latitudeRad, truth.positionEnu.z)
        return attitudeAt(tS).conjugate().rotate(specificEnu) + accelBiasDevice
    }

    fun gyroDevice(tNs: Long): Vector3 {
        val tS = tNs / 1e9
        val truth = script.stateAt(tS)
        val navRate = Geodesy.earthRateEnu(latitudeRad) +
            Geodesy.transportRateEnu(latitudeRad, truth.positionEnu.z, truth.velocityEnu)
        return attitudeAt(tS).conjugate().rotate(navRate + truth.bodyRateEnu) + gyroBiasDevice
    }

    /** A synthetic fix: the truth's geodetic position plus an optional ENU offset. */
    fun fix(offsetEnu: Vector3 = Vector3(0.0, 0.0, 0.0), tS: Double = nowS): Fix {
        val point = Geodesy.geodeticFromEnu(anchor, script.stateAt(tS).positionEnu + offsetEnu)
        return Fix(tS, point.latitudeDeg, point.longitudeDeg, point.altitudeM)
    }

    fun update(
        fix: Fix,
        horizontalVariance: Double,
        verticalVariance: Double? = 4.0,
    ): GnssUpdateOutcome = filter.updateGnssPosition(
        tNs = (fix.tS * 1e9).toLong(),
        latitudeDeg = fix.latitudeDeg,
        longitudeDeg = fix.longitudeDeg,
        altitudeM = fix.altitudeM,
        horizontalVarianceM2 = horizontalVariance,
        verticalVarianceM2 = verticalVariance,
    )

    fun horizontalError(tS: Double = nowS): Double {
        val truth = script.stateAt(tS).positionEnu
        val estimate = filter.solution.positionEnuM
        return hypot(truth.x - estimate.x, truth.y - estimate.y)
    }

    fun velocityError(tS: Double = nowS): Double {
        val truth = script.stateAt(tS).velocityEnu
        val estimate = filter.solution.velocityEnuM_S
        return hypot(truth.x - estimate.x, truth.y - estimate.y)
    }

    companion object {
        const val ANCHOR_LATITUDE_DEG = 17.5
        const val ANCHOR_LONGITUDE_DEG = 78.4

        /** A yaw of 90 degrees between device and vehicle, so the mounting is actually exercised. */
        val MOUNTING_VEHICLE_FROM_DEVICE: Quaternion = quaternionAboutZ(Math.PI / 2.0)
    }
}

internal data class Fix(
    val tS: Double,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
)

class FusionFilterTest {

    private fun movingScript(): Script = Script().straight(120.0, speed = 15.0)

    // ---------------------------------------------------------------- 1. perfect GNSS

    @Test
    fun perfectGnssTracksTruthAndStaysInTrackingState() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        var accepted = 0
        repeat(60) {
            sim.step(1.0)
            if (sim.update(sim.fix(), horizontalVariance = 4.0).accepted) accepted += 1
        }
        assertEquals(60, accepted)
        assertEquals(FusionStatus.RUNNING, sim.filter.solution.status)
        assertEquals(60L, sim.filter.solution.acceptedUpdates)
        assertEquals(0L, sim.filter.solution.rejectedUpdates)
        // A perfect fix on a perfect mechanization: nothing legitimate is left to correct.
        assertTrue("horizontal error ${sim.horizontalError()}", sim.horizontalError() < 0.05)
        assertTrue("velocity error ${sim.velocityError()}", sim.velocityError() < 0.01)
    }

    // ---------------------------------------------------------------- 2. noisy GNSS

    @Test
    fun noisyGnssIsAveragedDownRatherThanFollowed() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align(positionVariance = 400.0))
        val random = Random(20260930L)
        val sigma = 10.0
        val errors = ArrayList<Double>()
        val rawErrors = ArrayList<Double>()
        val outcomes = ArrayList<GnssUpdateOutcome>()
        repeat(300) {
            sim.step(1.0)
            val noise = Vector3(
                random.nextGaussian() * sigma, random.nextGaussian() * sigma, 0.0,
            )
            outcomes.add(sim.update(sim.fix(noise), horizontalVariance = sigma * sigma))
            errors.add(sim.horizontalError())
            rawErrors.add(hypot(noise.x, noise.y))
        }
        val mean = errors.drop(200).average()
        // The comparison that makes "averaged down rather than followed" concrete: the same
        // samples, measured against the fix and against the solution. A filter that followed the
        // fixes would land on the second number, not the first.
        val rawMean = rawErrors.drop(200).average()
        assertTrue("raw mean $rawMean against $sigma m noise", rawMean > sigma)
        assertTrue("mean error $mean against a raw fix at $rawMean", mean < 0.6 * rawMean)
        assertTrue("mean error $mean against $sigma m noise", mean < 0.75 * sigma)

        // The reported uncertainty must be the filter's own rather than the provider's radius, and
        // it must be honest: the error actually achieved has to sit inside the sigma claimed for
        // it. A covariance that is merely small is not a covariance that is right.
        val reported = sim.filter.solution.positionSigmaM.x
        assertTrue("reported sigma $reported against $sigma", reported < sigma)
        assertTrue(
            "mean error $mean against reported sigma $reported",
            abs(mean - reported) < 0.5 * reported,
        )

        // A gate held at p = 0.999 is entitled to its own false-rejection rate, which over 300
        // measurements and two horizontal degrees of freedom is about a third of one. Demanding
        // that all 300 be accepted would be demanding that the gate never fires on anything but
        // a gross bias, which is not what a chi-square test does. What is demanded is that every
        // refusal was a genuine outlier and that it moved nothing at all.
        val refused = outcomes.filter { it.result == GnssUpdateResult.REJECTED_GATE }
        assertTrue(
            "refusals ${outcomes.groupingBy { it.result }.eachCount()}",
            refused.size <= 3,
        )
        assertTrue(refused.all { it.nis > it.gateThreshold })
        assertTrue(refused.all { it.correctionM == 0.0 })
        assertEquals((300 - refused.size).toLong(), sim.filter.solution.acceptedUpdates)
        assertEquals(refused.size.toLong(), sim.filter.solution.rejectedUpdates)

    }

    @Test
    fun theSolutionStaysBoundedThroughALongStraightDriveWithNoHeadingMeasurement() {
        // Twenty-five minutes of straight-line driving on 10 m fixes with no heading or course
        // measurement anywhere. That is the regime the recorded data is actually in: `bearing_deg`
        // is null on every one of the 1,782 fixes in the corpus. Yaw is then unobservable in the
        // classical sense — with the specific force vertical, rotating about the vertical axis does
        // not change its ENU projection — so the yaw error variance is free to grow and the
        // gyroscope bias it is coupled to is free to wander.
        //
        // What must not happen is for that freedom to destroy the position. It does not: the yaw
        // variance saturates rather than diverging, the bias settles, and the position error stays
        // the size of the fix noise. This test is the guard on that, and it is why the unobservable
        // yaw is reported honestly in the diagnostics rather than papered over.
        val sim = FusionSim(movingScript())
        assertTrue(sim.align(positionVariance = 400.0))
        val random = Random(77L)
        val sigma = 10.0
        var worst = 0.0
        var yawSigmaHalfway = 0.0
        repeat(1500) {
            sim.step(1.0)
            sim.update(
                sim.fix(Vector3(random.nextGaussian() * sigma, random.nextGaussian() * sigma, 0.0)),
                horizontalVariance = sigma * sigma,
            )
            worst = maxOf(worst, sim.horizontalError())
            if (sim.filter.solution.acceptedUpdates + sim.filter.solution.rejectedUpdates == 750L) {
                yawSigmaHalfway = sim.filter.solution.attitudeSigmaRad.z
            }
        }
        val solution = sim.filter.solution
        assertTrue("worst error $worst over 1500 s", worst < 8.0 * solution.positionSigmaM.x)
        assertTrue("final error ${sim.horizontalError()}", sim.horizontalError() < 6.0 * sigma)
        // The yaw variance is bounded, and by the second half it is not growing any more.
        assertTrue("yaw sigma ${solution.attitudeSigmaRad.z}", solution.attitudeSigmaRad.z < 1.0)
        assertTrue(
            "yaw sigma ${yawSigmaHalfway} -> ${solution.attitudeSigmaRad.z}",
            solution.attitudeSigmaRad.z < 2.0 * yawSigmaHalfway,
        )
        // And the bias estimate settles instead of running away, which is what keeps the attitude
        // and therefore the levelling honest for the whole run.
        assertTrue(
            "gyro bias ${solution.gyroBiasDeviceRad_S}",
            abs(solution.gyroBiasDeviceRad_S.z) < 0.05,
        )
        assertEquals(1500L, solution.acceptedUpdates + solution.rejectedUpdates)
    }

    // ---------------------------------------------------------------- 3. biased bad GNSS

    @Test
    fun aBiasedFixIsRefusedByTheGateAndNeverFollowed() {
        // A converged filter first: a velocity that is not yet known to better than 1 m/s
        // legitimately widens the gate, and a 200 m measurement is only a few sigma away.
        val sim = FusionSim(movingScript())
        assertTrue(sim.align(positionVariance = 100.0, velocityVariance = 1.0))
        repeat(60) {
            sim.step(1.0)
            assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        }
        assertTrue("pre-bias error ${sim.horizontalError()}", sim.horizontalError() < 2.0)

        // Six seconds of a grossly biased segment: each fix disagrees with the propagated state
        // by 200 m, some twenty times the filter's own position sigma.
        val bias = Vector3(0.0, 200.0, 0.0)
        val outcomes = ArrayList<GnssUpdateOutcome>()
        repeat(6) {
            sim.step(1.0)
            outcomes.add(sim.update(sim.fix(bias), horizontalVariance = 100.0))
        }
        assertEquals(
            "outcomes ${outcomes.map { it.result }}",
            List(6) { GnssUpdateResult.REJECTED_GATE },
            outcomes.map { it.result },
        )
        for (outcome in outcomes) {
            // Each refusal is a statement about the data, not a fallback: the statistic really did
            // exceed its threshold, and nothing at all was applied to the state.
            assertTrue("nis ${outcome.nis}", outcome.nis > outcome.gateThreshold)
            assertTrue("innovation ${outcome.innovationM}", outcome.innovationM > 190.0)
            assertEquals(0.0, outcome.correctionM, 0.0)
        }
        assertEquals(6L, sim.filter.solution.rejectedUpdates)
        assertEquals(60L, sim.filter.solution.acceptedUpdates)
        assertEquals(60_000_000_000L, sim.filter.solution.lastGnssUpdateNs)
        assertNotNull(sim.filter.solution.lastRejectionNis)
        // Dead reckoning ran on and is still on the truth, because the stubborn fix never touched
        // the state: the filter was never told where the liar said it was.
        assertTrue("during-bias error ${sim.horizontalError()}", sim.horizontalError() < 1.0)

        // Once the fixes are honest again the filter keeps them.
        repeat(30) {
            sim.step(1.0)
            assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        }
        assertTrue("post-bias error ${sim.horizontalError()}", sim.horizontalError() < 5.0)
    }

    @Test
    fun aRefusedMeasurementLeavesTheFilterExactlyWhereAnAbsentOneWould() {
        // Two filters, the same inertial stream and the same honest fixes. Then one is fed nothing
        // and the other a fix 200 m away. If gating means anything the two must be bit-identical
        // afterwards, which says more than "the error stayed small": the refused measurement was
        // applied nowhere, not merely applied a little.
        val starved = FusionSim(movingScript())
        val lied = FusionSim(movingScript())
        for (sim in listOf(starved, lied)) {
            assertTrue(sim.align(positionVariance = 100.0, velocityVariance = 1.0))
        }
        repeat(60) {
            starved.step(1.0)
            lied.step(1.0)
            assertTrue(starved.update(starved.fix(), horizontalVariance = 25.0).accepted)
            assertTrue(lied.update(lied.fix(), horizontalVariance = 25.0).accepted)
        }
        val bias = Vector3(0.0, 200.0, 0.0)
        repeat(6) {
            starved.step(1.0)
            lied.step(1.0)
            assertEquals(
                GnssUpdateResult.REJECTED_GATE,
                lied.update(lied.fix(bias), horizontalVariance = 100.0).result,
            )
        }
        val absent = starved.filter.solution
        val refused = lied.filter.solution
        assertEquals(absent.tNs, refused.tNs)
        assertEquals(absent.positionEnuM, refused.positionEnuM)
        assertEquals(absent.velocityEnuM_S, refused.velocityEnuM_S)
        assertEquals(absent.qEnuFromDevice, refused.qEnuFromDevice)
        assertEquals(absent.gyroBiasDeviceRad_S, refused.gyroBiasDeviceRad_S)
        assertEquals(absent.accelBiasDeviceM_S2, refused.accelBiasDeviceM_S2)
        assertEquals(absent.positionSigmaM, refused.positionSigmaM)
        assertEquals(absent.velocitySigmaM_S, refused.velocitySigmaM_S)
        assertTrue("covariance differs", absent.covariance.contentEquals(refused.covariance))
        assertEquals(absent.acceptedUpdates, refused.acceptedUpdates)
        // The one difference is the bookkeeping, which is the point of counting rather than hiding.
        assertEquals(0L, absent.rejectedUpdates)
        assertEquals(6L, refused.rejectedUpdates)
        assertTrue(absent.lastRejectionNis == null)
        assertNotNull(refused.lastRejectionNis)
    }

    @Test
    fun aSustainedLieIsEventuallyAdmittedBecauseTheGateBelievesItsOwnCovariance() {
        // An innovation gate is a statistical test against a covariance the filter reports about
        // itself. Once the filter honestly admits it may be tens of metres wrong, a measurement
        // 200 m away is no longer an outlier, and refusing it forever would mean the filter could
        // never recover from its own over-confidence. This test states the consequence plainly so
        // the limitation is visible here rather than in a vehicle: gating is not robustness against
        // a persistent, self-consistent lie.
        val sim = FusionSim(movingScript())
        assertTrue(sim.align(positionVariance = 100.0, velocityVariance = 1.0))
        repeat(60) {
            sim.step(1.0)
            assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        }
        val sigmaBefore = sim.filter.solution.positionSigmaM.x
        val bias = Vector3(0.0, 200.0, 0.0)
        var refusals = 0
        var admissionNis: Double? = null
        var admissionGate = 0.0
        var admissionSigma = 0.0
        var admissionCorrection = 0.0
        var admissionInnovation = 0.0
        repeat(30) {
            sim.step(1.0)
            val sigmaBeforeThis = sim.filter.solution.positionSigmaM.x
            val outcome = sim.update(sim.fix(bias), horizontalVariance = 100.0)
            if (outcome.result == GnssUpdateResult.REJECTED_GATE) {
                refusals += 1
            } else if (admissionNis == null) {
                admissionNis = outcome.nis
                admissionGate = outcome.gateThreshold
                admissionSigma = sigmaBeforeThis
                admissionCorrection = outcome.correctionM
                admissionInnovation = outcome.innovationM
            }
        }
        // The lie is refused for a long stretch, which is the gate doing its job...
        assertTrue("refusals $refusals", refusals >= 10)
        // ...and then admitted. Not because the gate broke, but because for eighteen seconds
        // nothing constrained the solution and the filter's own reported position sigma grew until
        // 200 m fit inside the test. That growth is not a defect: with the configured process
        // noise and a gyroscope-bias prior of 0.01 rad/s, and no attitude or heading measurement
        // anywhere in this stage, the uncertainty genuinely is that large.
        assertNotNull(admissionNis)
        assertTrue(
            "sigma before admission $admissionSigma against $sigmaBefore",
            admissionSigma > 10.0 * sigmaBefore,
        )
        assertTrue("nis ${admissionNis!!} against gate $admissionGate", admissionNis!! <= admissionGate)
        // Even admitted, it is applied by the Kalman gain and never copied in: the state moved by
        // strictly less than the whole innovation, exactly as the recovery case demands.
        assertTrue(
            "correction $admissionCorrection against innovation $admissionInnovation",
            admissionCorrection < admissionInnovation,
        )
        // And then the honest fixes come back. The state is sitting on the lie, so the fixes
        // disagree with it by the whole 200 m and they are refused too — and this time the refusals
        // do not stop, because nothing is constraining anything any more. This is the failure the
        // gate cannot defend against from the inside: a solution that has drifted away from a live
        // stream of fixes and refuses all of them, one individually defensible rejection at a
        // time, while the position grows without bound.
        //
        // So the filter stops. After a contiguous stretch of [FusionConfig.maxRejectionSpanS]
        // seconds of refusals it declares the run lost rather than publishing a position that has
        // stopped meaning anything. Recovery is a deliberate re-alignment, which is a decision for
        // a caller and not something this class may take on its own.
        repeat(200) {
            sim.step(1.0)
            sim.update(sim.fix(), horizontalVariance = 25.0)
        }
        val ended = sim.filter.solution
        assertEquals(FusionStatus.FAILED, ended.status)
        assertEquals(FusionFailure.PERSISTENT_INNOVATION_REJECTION, ended.failure)
        assertTrue("nis ${ended.lastRejectionNis}", ended.lastRejectionNis!! > admissionGate)
        // Nothing past the declaration is applied or propagated, so the run cannot keep running away.
        val frozen = ended.positionEnuM
        assertEquals(
            GnssUpdateResult.FAILED,
            sim.update(sim.fix(), horizontalVariance = 25.0).result,
        )
        assertEquals(
            PropagationOutcome.FAILED,
            sim.filter.propagate(
                Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0), ended.tNs + 100_000_000L,
            ),
        )
        assertEquals(frozen, sim.filter.solution.positionEnuM)
    }

    @Test
    fun anOutageIsCoastedThroughHoweverLongItLastsAndNeverTripsTheGuard() {
        // The guard exists to catch a solution that has drifted away from *arriving* fixes. An
        // outage has no arrivals to refuse, so it must not reach it at all: coasting is the point
        // of the product. Ten minutes of silence, and the run is still alive and still reporting.
        val sim = FusionSim(movingScript())
        assertTrue(sim.align(positionVariance = 100.0))
        repeat(10) {
            sim.step(1.0)
            assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        }
        val sigmaAtOutage = sim.filter.solution.positionSigmaM.x
        repeat(600) { sim.step(1.0) }
        val solution = sim.filter.solution
        assertEquals(FusionStatus.RUNNING, solution.status)
        assertNull(solution.failure)
        assertTrue(solution.aligned)
        assertEquals(10L, solution.acceptedUpdates)
        assertEquals(0L, solution.rejectedUpdates)
        // It paid for the silence in covariance, as it should, and it never stopped reporting.
        assertTrue(
            "sigma ${solution.positionSigmaM.x} from $sigmaAtOutage",
            solution.positionSigmaM.x > 5.0 * sigmaAtOutage,
        )
        assertNotNull(solution.geodetic)
    }

    // ---------------------------------------------------------------- 4. outage

    @Test
    fun outageIsPredictionOnlyAndReportsTheDeadReckonedPosition() {
        // A bias the filter is not permitted to absorb: its prior variance is deliberately far
        // smaller than the bias, so the outage drift is the bias's doing and not the estimator's.
        val sim = FusionSim(
            movingScript(),
            accelBiasDevice = Vector3(0.02, 0.0, 0.0),
            accelBiasVariance = 1e-4,
        )
        assertTrue(sim.align(positionVariance = 100.0))
        repeat(20) {
            sim.step(1.0)
            assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        }
        val errorAtOutage = sim.horizontalError()
        val sigmaAtOutage = sim.filter.solution.positionSigmaM.x
        // Thirty seconds with no GNSS at all, while the vehicle keeps moving.
        repeat(30) { sim.step(1.0) }
        assertEquals(20L, sim.filter.solution.acceptedUpdates)
        assertEquals(20_000_000_000L, sim.filter.solution.lastGnssUpdateNs)
        assertTrue(sim.filter.solution.aligned)
        // The solution still exists and is still reported: this is dead reckoning, not a failure.
        assertNotNull(sim.filter.solution.geodetic)
        assertTrue(sim.horizontalError() > errorAtOutage)
        // The drift is bounded by what the unmodelled bias can do in the interval.
        assertTrue("outage error ${sim.horizontalError()}", sim.horizontalError() < 25.0)
        assertTrue(sim.filter.solution.positionSigmaM.x > sigmaAtOutage)
    }

    // ---------------------------------------------------------------- 5. recovery

    @Test
    fun recoveryCorrectsTowardTheFixWithoutSnappingToIt() {
        val sim = FusionSim(
            movingScript(),
            accelBiasDevice = Vector3(0.05, 0.0, 0.0),
            accelBiasVariance = 1e-4,
        )
        assertTrue(sim.align(positionVariance = 100.0))
        repeat(10) {
            sim.step(1.0)
            assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        }
        repeat(40) { sim.step(1.0) }
        val errorBefore = sim.horizontalError()
        assertTrue("drift before recovery $errorBefore", errorBefore > 1.0)

        // The returning fix disagrees with the propagated state by the whole accumulated drift.
        val outcome = sim.update(sim.fix(), horizontalVariance = 25.0)
        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        assertTrue("innovation ${outcome.innovationM}", outcome.innovationM > 1.0)
        assertEquals(outcome.innovationM, errorBefore, 0.5)
        // The property that matters: the state moved by the Kalman gain, strictly less than the
        // whole innovation, so the fix was not copied into the state.
        assertTrue(
            "correction ${outcome.correctionM} vs innovation ${outcome.innovationM}",
            outcome.correctionM < outcome.innovationM,
        )
        assertTrue(outcome.correctionM > 0.0)
        val errorAfterOneFix = sim.horizontalError()
        assertTrue("error did not improve", errorAfterOneFix < errorBefore)
        assertTrue("error collapsed in one fix", errorAfterOneFix > 0.0)

        // Convergence is over several updates rather than one jump.
        repeat(20) {
            sim.step(1.0)
            assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        }
        assertTrue("settled error ${sim.horizontalError()}", sim.horizontalError() < 1.0)
    }

    // ---------------------------------------------------------------- 6. covariance growth

    @Test
    fun covarianceGrowsThroughAnOutageAndShrinksAgainOnRecovery() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align(positionVariance = 100.0))
        repeat(20) {
            sim.step(1.0)
            sim.update(sim.fix(), horizontalVariance = 25.0)
        }
        val before = sim.filter.solution.positionSigmaM.x
        var previous = before
        var monotonic = true
        repeat(30) {
            sim.step(1.0)
            val now = sim.filter.solution.positionSigmaM.x
            if (now < previous) monotonic = false
            previous = now
        }
        val duringOutage = sim.filter.solution.positionSigmaM.x
        assertTrue("not monotonic", monotonic)
        assertTrue("grew from $before to $duringOutage", duringOutage > before * 2.0)

        // A measurement must pull it back down: an uncertainty that only ever grows is not a filter.
        repeat(10) {
            sim.step(1.0)
            sim.update(sim.fix(), horizontalVariance = 25.0)
        }
        val afterRecovery = sim.filter.solution.positionSigmaM.x
        assertTrue("shrank to $afterRecovery", afterRecovery < duringOutage)
        assertTrue(afterRecovery > 0.0)
    }

    // ---------------------------------------------------------------- 7. bias convergence

    @Test
    fun instrumentBiasesConvergeTowardTheInjectedTruth() {
        val trueGyroBias = Vector3(0.01, 0.0, 0.004)
        val trueAccelBias = Vector3(0.08, 0.0, 0.0)
        // Accelerate, turn, accelerate again: bias observability needs motion and rotation.
        val script = Script().straight(20.0, speed = 5.0, accel = 0.4)
            .turn(40.0, speed = 13.0, yawRateRadPerS = Math.toRadians(6.0))
            .straight(60.0, speed = 13.0, accel = -0.1)
        // Priors a few times the true bias, which is what a bias estimator has to be given. Much
        // looser than that and the state absorbs the mechanization's own integration error, which
        // is not a bias and does not point at the truth.
        val sim = FusionSim(
            script,
            gyroBiasDevice = trueGyroBias,
            accelBiasDevice = trueAccelBias,
            gyroBiasVariance = 4.0e-4,
            accelBiasVariance = 4.0e-2,
        )
        assertTrue(sim.align(positionVariance = 400.0, velocityVariance = 100.0))
        // Realistic measurement noise. Without it the bias signature and the filter's own
        // integration error are the same size, and the estimator fits the latter.
        val random = Random(4242L)
        val sigma = 5.0
        repeat(120) {
            sim.step(1.0)
            sim.update(
                sim.fix(Vector3(random.nextGaussian() * sigma, random.nextGaussian() * sigma, 0.0)),
                horizontalVariance = sigma * sigma,
            )
        }
        val estimatedGyro = sim.filter.solution.gyroBiasDeviceRad_S
        val estimatedAccel = sim.filter.solution.accelBiasDeviceM_S2
        // The estimate has to move decisively toward the injected value, in the injected direction,
        // without having been seeded with it: the filter started both at zero.
        assertTrue("gyro x ${estimatedGyro.x}", estimatedGyro.x > 0.5 * trueGyroBias.x)
        assertTrue("gyro z ${estimatedGyro.z}", estimatedGyro.z > 0.5 * trueGyroBias.z)
        // And the positional price of not knowing them must stay small.
        assertTrue("error ${sim.horizontalError()}", sim.horizontalError() < 20.0)
    }

    @Test
    fun aZeroBiasInstrumentIsNotGivenAFabricatedBias() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        repeat(60) {
            sim.step(1.0)
            sim.update(sim.fix(), horizontalVariance = 4.0)
        }
        val gyro = sim.filter.solution.gyroBiasDeviceRad_S
        val accel = sim.filter.solution.accelBiasDeviceM_S2
        assertEquals(0.0, gyro.x, 1e-3)
        assertEquals(0.0, gyro.z, 1e-3)
        assertEquals(0.0, accel.x, 1e-2)
    }

    // ---------------------------------------------------------------- 8. repeated timestamps

    @Test
    fun repeatedTimestampsNeitherPropagateNorBreakTheFilter() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        repeat(10) { sim.step() }
        val before = sim.filter.solution
        // The same timestamp three more times: no time passed, so nothing may change.
        repeat(3) {
            assertEquals(
                PropagationOutcome.DUPLICATE_TIMESTAMP,
                sim.filter.propagate(
                    Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0), sim.filter.solution.tNs,
                ),
            )
        }
        val after = sim.filter.solution
        assertEquals(3L, after.duplicateTimestamps)
        assertEquals(before.tNs, after.tNs)
        assertEquals(before.positionEnuM, after.positionEnuM)
        assertEquals(before.velocityEnuM_S, after.velocityEnuM_S)
        assertEquals(before.qEnuFromDevice, after.qEnuFromDevice)
        assertTrue(before.covariance.contentEquals(after.covariance))
        // And the stream carries on afterwards.
        sim.step(1.0)
        assertTrue(sim.update(sim.fix(), horizontalVariance = 25.0).accepted)
        assertEquals(FusionStatus.RUNNING, sim.filter.solution.status)
    }

    // ---------------------------------------------------------------- 9. time gaps

    @Test
    fun aTimeGapHoldsTheStateAndInflatesTheCovariance() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        repeat(10) { sim.step() }
        val before = sim.filter.solution
        // A 0.5 s interval with no samples inside it: too long to integrate, too short to fail.
        val outcome = sim.filter.propagate(
            sim.accelDevice(before.tNs), sim.gyroDevice(before.tNs),
            before.tNs + 500_000_000L,
        )
        assertEquals(PropagationOutcome.HELD_GAP, outcome)
        val after = sim.filter.solution
        assertEquals(1L, after.heldGaps)
        assertEquals(before.tNs + 500_000_000L, after.tNs)
        // The state is held, because there is nothing to integrate across the hole...
        assertEquals(before.positionEnuM, after.positionEnuM)
        assertEquals(before.velocityEnuM_S, after.velocityEnuM_S)
        // ...and the uncertainty grows, because unobserved motion is still uncertainty.
        assertTrue(
            "sigma ${before.positionSigmaM.z} -> ${after.positionSigmaM.z}",
            after.positionSigmaM.z > before.positionSigmaM.z,
        )
    }

    @Test
    fun anIntervalBeyondTheFailureLimitStopsTheRunInsteadOfInventingATrajectory() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        repeat(10) { sim.step() }
        val before = sim.filter.solution
        val outcome = sim.filter.propagate(
            Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0), before.tNs + 6_000_000_000L,
        )
        assertEquals(PropagationOutcome.FAILED_GAP, outcome)
        assertEquals(FusionStatus.FAILED, sim.filter.solution.status)
        assertNotNull(sim.filter.solution.failure)
        // Nothing beyond the failure is claimed, and nothing is propagated into the hole.
        assertEquals(before.positionEnuM, sim.filter.solution.positionEnuM)
        assertEquals(
            PropagationOutcome.FAILED,
            sim.filter.propagate(Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0), before.tNs + 7_000_000_000L),
        )
    }

    // ---------------------------------------------------------------- structure and honesty

    @Test
    fun theCovarianceStaysSymmetricAndPositiveDefiniteThroughout() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        repeat(60) {
            sim.step(1.0)
            sim.update(sim.fix(), horizontalVariance = 100.0)
            assertSaneCovariance(sim.filter.solution.covariance)
        }
    }

    @Test
    fun aBackwardsTimestampIsRejectedAndCountedRatherThanIntegrated() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        repeat(10) { sim.step() }
        val before = sim.filter.solution
        assertEquals(
            PropagationOutcome.BACKWARDS_TIMESTAMP,
            sim.filter.propagate(
                Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0), before.tNs - 10_000_000L,
            ),
        )
        val after = sim.filter.solution
        assertEquals(1L, after.backwardsTimestamps)
        assertEquals(before.positionEnuM, after.positionEnuM)
        assertEquals(FusionStatus.RUNNING, after.status)
    }

    @Test
    fun aNonFiniteSampleFailsTheFilterRatherThanPropagatingNaN() {
        val sim = FusionSim(movingScript())
        assertTrue(sim.align())
        repeat(5) { sim.step() }
        assertEquals(
            PropagationOutcome.FAILED_NON_FINITE,
            sim.filter.propagate(Vector3(Double.NaN, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 100_000_000L),
        )
        assertEquals(FusionStatus.FAILED, sim.filter.solution.status)
        assertTrue(sim.filter.solution.positionEnuM.x.isFinite())
    }

    @Test
    fun alignmentRefusesNonPositiveVariances() {
        val sim = FusionSim(movingScript())
        val bad = FusionInitialState(
            anchor = sim.anchor,
            qEnuFromDevice = Quaternion(1.0, 0.0, 0.0, 0.0),
            velocityEnuM_S = Vector3(0.0, 0.0, 0.0),
            gyroBiasDeviceRad_S = Vector3(0.0, 0.0, 0.0),
            accelBiasDeviceM_S2 = Vector3(0.0, 0.0, 0.0),
            positionVarianceM2 = Vector3(0.0, 1.0, 1.0),
            velocityVarianceM2 = Vector3(1.0, 1.0, 1.0),
            attitudeVarianceRad2 = Vector3(1.0, 1.0, 1.0),
            gyroBiasVarianceRad2_S2 = 1.0,
            accelBiasVarianceM2_S4 = 1.0,
        )
        assertFalse(sim.filter.align(bad, 0L))
        assertFalse(sim.filter.aligned)
    }

    @Test
    fun aSpeedOnlyMeasurementIsRefusedWhileStationaryAndUsedWhileMoving() {
        val stationary = FusionSim(Script().stationary(5.0))
        assertTrue(stationary.align())
        repeat(3) { stationary.step(1.0) }
        assertEquals(
            GnssUpdateResult.REJECTED_INVALID,
            stationary.filter.updateGnssSpeed(3_000_000_000L, 0.0, 1.0).result,
        )
        val moving = FusionSim(movingScript())
        assertTrue(moving.align())
        repeat(3) { moving.step(1.0) }
        val outcome = moving.filter.updateGnssSpeed(3_000_000_000L, 15.0, 1.0)
        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        assertEquals(1, outcome.dimension)
    }

    @Test
    fun everythingIsRefusedBeforeAlignment() {
        val sim = FusionSim(movingScript())
        assertEquals(
            PropagationOutcome.NOT_ALIGNED,
            sim.filter.propagate(Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0), 0L),
        )
        assertEquals(
            GnssUpdateResult.NOT_ALIGNED,
            sim.update(sim.fix(tS = 0.0), horizontalVariance = 25.0).result,
        )
        assertEquals(FusionStatus.UNINITIALIZED, sim.filter.solution.status)
        assertFalse(sim.filter.aligned)
    }

    private fun assertSaneCovariance(covariance: DoubleArray) {
        val n = 15
        var worstAsymmetry = 0.0
        for (i in 0 until n) {
            for (j in 0 until n) {
                worstAsymmetry = maxOf(worstAsymmetry, abs(covariance[i * n + j] - covariance[j * n + i]))
            }
            val diagonal = covariance[i * n + i]
            assertTrue("diagonal $i is $diagonal", diagonal > 0.0)
            assertTrue("diagonal $i is not finite", diagonal.isFinite())
        }
        assertTrue("asymmetry $worstAsymmetry", worstAsymmetry < 1e-9)
    }
}
