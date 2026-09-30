package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.AltitudeReference
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuUnit
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.SensorAccuracy
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import java.util.Locale
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level vehicle-motion constraints: the gate matrix through the production engine, and
 * the A/B held-out outage evaluation (EKF only vs EKF + ZUPT/NHC) on identical synthetic drives.
 *
 * The drive is *physically consistent*: the IMU carries the specific force of every speed
 * change (accelerate at 1 m/s², brake at 1 m/s², level cruise otherwise), and the truth is
 * integrated from the same profile, so the mechanization sees exactly the motion the truth
 * performs. The scenario shape is the declared held-out one: parked, a drive, a GNSS outage
 * with a full stop inside it, recovery, and a final stationary period. Both arms consume the
 * same seeded stream; only [FusionConfig.motionConstraintsEnabled] differs.
 */
class VehicleConstraintsEngineTest {

    private val header = Header("constraints-ab", Source.REAL)
    private val session = EngineSession(header, "boot", 1_000_000_000L)
    private val calibration = CalibrationResult(
        id = "cal-1",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = quaternionAboutZ(Math.PI / 2.0),
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.9,
    )
    private val history = mutableListOf<Record>()

    private fun engine(cfg: FusionConfig) =
        FusionNavigationEngine(config = cfg, publicationIntervalNs = 0L)

    private fun calRecord(result: CalibrationResult) =
        Record(header, Event("cal", session.origin_ns, session.origin_ns, result))

    private fun imuRecords(tNs: Long, accel: Vector3, gyro: Vector3): List<Record> = listOf(
        Record(
            header, Event("a$tNs", tNs, tNs, ImuMeasurement(
                Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH)),
        ),
        Record(
            header, Event("g$tNs", tNs, tNs, ImuMeasurement(
                Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH)),
        ),
    )

    private fun fixRecord(
        tNs: Long,
        lat: Double,
        lon: Double,
        speed: Double? = null,
        bearing: Double? = null,
        accuracy: Double = 3.0,
    ): Record = Record(
        header, Event("n$tNs", tNs, tNs, GnssMeasurement(
            latitude_deg = lat, longitude_deg = lon, altitude_m = 500.0,
            altitude_reference = AltitudeReference.ELLIPSOID,
            speed_m_s = speed, bearing_deg = bearing,
            horizontal_accuracy_m = accuracy, vertical_accuracy_m = 5.0,
            satellites_used = 20L, provider = "gps", utc_ms = null)),
    )

    private fun drain(engine: FusionNavigationEngine): List<Record> {
        val fresh = engine.drain().toList()
        history.addAll(fresh)
        return fresh
    }

    private fun codes() = history.mapNotNull { (it.event.data as? DiagnosticEvent)?.code }

    /** [count] samples of parked IMU at 100 Hz; returns the new timestamp. */
    private fun parkedSamples(engine: FusionNavigationEngine, fromNs: Long, count: Int): Long {
        var tNs = fromNs
        for (i in 1..count) {
            tNs += 10_000_000L
            imuRecords(tNs, Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0))
                .forEach { engine.acceptImu(it) }
        }
        return tNs
    }

    // ------------------------------------------------------------------ gate matrix

    @Test
    fun zuptFiresWhenParkedAndIsBookedAsAConstraint() {
        val fusion = engine(FusionConfig())
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        var tNs = parkedSamples(fusion, session.origin_ns, 600)
        fusion.acceptGnss(fixRecord(tNs, ANCHOR_LAT, ANCHOR_LON, speed = 0.0))
        drain(fusion)
        assertTrue(fusion.solution.aligned)
        val before = fusion.solution.constraintAccepted
        // One more parked second: ZUPT fires at its cadence and is booked as a constraint.
        tNs = parkedSamples(fusion, tNs, 100)
        drain(fusion)
        assertTrue(
            "constraintAccepted ${fusion.solution.constraintAccepted} must exceed $before",
            fusion.solution.constraintAccepted > before,
        )
        assertTrue(codes().contains("FUSION_CONSTRAINT_APPLIED"))
        // The constraint is not a GNSS update: the GNSS book must not move.
        assertEquals(0L, fusion.solution.acceptedUpdates)
    }

    @Test
    fun nhcFiresWhileDrivingAndForwardVelocitySurvives() {
        val fusion = engine(FusionConfig())
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        var tNs = parkedSamples(fusion, session.origin_ns, 250)
        fusion.acceptGnss(fixRecord(tNs, ANCHOR_LAT, ANCHOR_LON, speed = 0.0))
        drain(fusion)
        // Accelerate north at 1 m/s² for 12 s, then settle at cruise for 30 s — the same
        // phase structure the A/B outage drives use, where the constraint loop is verified
        // healthy. With the test mount (device y points backward along the vehicle), forward
        // acceleration is NEGATIVE y in the device frame: (0, -a, g).
        var v = 0.0
        var x = 0.0
        val rN = Geodesy.radii(Math.toRadians(ANCHOR_LAT)).second
        for (i in 1..4200) {
            tNs += 10_000_000L
            val a = if (i <= 1200) 1.0 else 0.0
            v = minOf(12.0, v + a * 0.01)
            x += v * 0.01
            imuRecords(tNs, Vector3(0.0, -a, 9.80665), Vector3(0.0, 0.0, 0.0))
                .forEach { fusion.acceptImu(it) }
            if (i % 100 == 0) {
                fusion.acceptGnss(
                    fixRecord(
                        tNs,
                        ANCHOR_LAT + Math.toDegrees(x / rN),
                        ANCHOR_LON,
                        speed = v,
                        bearing = 0.0,
                    ),
                )
                drain(fusion)
            }
        }
        val last = fusion.solution
        assertTrue("NHC never fired (${last.constraintAccepted})", last.constraintAccepted > 0)
        // The forward velocity survives the lateral/vertical constraints: the reported speed
        // stays near the truth instead of being dragged down.
        val speed = sqrt(
            last.velocityEnuM_S.x * last.velocityEnuM_S.x +
                last.velocityEnuM_S.y * last.velocityEnuM_S.y,
        )
        assertTrue("speed $speed must stay near 12", speed in 10.0..14.0)
        val posError = sqrt(
            (last.positionEnuM.y - x) * (last.positionEnuM.y - x) +
                last.positionEnuM.x * last.positionEnuM.x,
        )
        assertTrue("position error $posError m after settled cruise", posError < 10.0)
    }

    @Test
    fun nhcStandsDownWhenTheYawRateIsBeyondTheBound() {
        val fusion = engine(FusionConfig())
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        var tNs = parkedSamples(fusion, session.origin_ns, 250)
        fusion.acceptGnss(fixRecord(tNs, ANCHOR_LAT, ANCHOR_LON, speed = 0.0))
        drain(fusion)
        // Build filter speed honestly (accelerate at 1 m/s² for 12 s with the matching
        // specific force, then settle at cruise), then turn hard at 1.0 rad/s about device z —
        // above the 0.5 rad/s bound — while the fixes keep claiming 12 m/s so the speed is far
        // above the NHC floor. NHC must stand down; the detector also reads rotation, so ZUPT
        // stands down too.
        var v = 0.0
        val rN = Geodesy.radii(Math.toRadians(ANCHOR_LAT)).second
        for (i in 1..1600) {
            tNs += 10_000_000L
            val a = if (i <= 1200) 1.0 else 0.0
            v = minOf(12.0, v + a * 0.01)
            imuRecords(tNs, Vector3(0.0, -a, 9.80665), Vector3(0.0, 0.0, 0.0))
                .forEach { fusion.acceptImu(it) }
            if (i % 100 == 0) {
                fusion.acceptGnss(
                    fixRecord(tNs, ANCHOR_LAT + Math.toDegrees(v * i * 0.01 / rN), ANCHOR_LON,
                        speed = v, bearing = 0.0),
                )
                drain(fusion)
            }
        }
        val before = fusion.solution.constraintAccepted
        for (i in 1..1000) {
            tNs += 10_000_000L
            imuRecords(tNs, Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 1.0))
                .forEach { fusion.acceptImu(it) }
            if (i % 100 == 0) {
                fusion.acceptGnss(fixRecord(tNs, ANCHOR_LAT, ANCHOR_LON, speed = 12.0, bearing = 0.0))
                drain(fusion)
            }
        }
        val after = fusion.solution.constraintAccepted
        assertTrue("a constraint fired through the yaw-rate gate: $before -> $after", after == before)
    }

    @Test
    fun zuptStandsDownWhileTheVehicleIsMoving() {
        val fusion = engine(FusionConfig())
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        var tNs = parkedSamples(fusion, session.origin_ns, 250)
        fusion.acceptGnss(fixRecord(tNs, ANCHOR_LAT, ANCHOR_LON, speed = 0.0))
        drain(fusion)
        // Accelerate honestly to 15 m/s, then cruise with quiet IMU and fixes reporting the
        // real speed. The filter knows it is moving, so the speed gate keeps ZUPT from
        // freezing a moving vehicle — the declared "slow creep interpreted as stopped"
        // protection, exercised at the far end of its range.
        var v = 0.0
        var x = 0.0
        val rN = Geodesy.radii(Math.toRadians(ANCHOR_LAT)).second
        for (i in 1..1500) {
            tNs += 10_000_000L
            val a = if (v < 15.0) 1.0 else 0.0
            v = minOf(15.0, v + a * 0.01)
            x += v * 0.01
            imuRecords(tNs, Vector3(0.0, -a, 9.80665), Vector3(0.0, 0.0, 0.0))
                .forEach { fusion.acceptImu(it) }
            if (i % 100 == 0) {
                fusion.acceptGnss(
                    fixRecord(tNs, ANCHOR_LAT + Math.toDegrees(x / rN), ANCHOR_LON,
                        speed = v, bearing = 0.0),
                )
                drain(fusion)
            }
        }
        val gnssAcceptedBefore = fusion.solution.acceptedUpdates
        // Ten seconds of quiet-IMU cruise with fixes carrying the true speed: the ZUPT must
        // stand down (its gate is the filter's own speed, far above the threshold), and the
        // NHC keeps the constrained components honest. The observable that matters: the
        // vehicle is not frozen and the fixes keep being accepted. (The combined constraint
        // counter still rises here: NHC legitimately fires during a cruise; isolating ZUPT is
        // the filter-level wrong-claim test above.)
        for (i in 1..1000) {
            tNs += 10_000_000L
            x += v * 0.01
            imuRecords(tNs, Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0))
                .forEach { fusion.acceptImu(it) }
            if (i % 100 == 0) {
                fusion.acceptGnss(
                    fixRecord(tNs, ANCHOR_LAT + Math.toDegrees(x / rN), ANCHOR_LON,
                        speed = 15.0, bearing = 0.0),
                )
                drain(fusion)
            }
        }
        val s = fusion.solution
        val speed = sqrt(s.velocityEnuM_S.x * s.velocityEnuM_S.x + s.velocityEnuM_S.y * s.velocityEnuM_S.y)
        assertTrue("the vehicle was frozen at $speed m/s", speed in 13.0..17.0)
        assertTrue(
            "fixes stopped being accepted (${s.acceptedUpdates} vs $gnssAcceptedBefore)",
            s.acceptedUpdates > gnssAcceptedBefore,
        )
        val posError = sqrt(
            (s.positionEnuM.y - x) * (s.positionEnuM.y - x) + s.positionEnuM.x * s.positionEnuM.x,
        )
        assertTrue("position error $posError m after settled cruise", posError < 30.0)
    }

    @Test
    fun theMasterSwitchProducesThePlainFilter() {
        val fusion = engine(FusionConfig().copy(motionConstraintsEnabled = false))
        fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
        var tNs = parkedSamples(fusion, session.origin_ns, 600)
        fusion.acceptGnss(fixRecord(tNs, ANCHOR_LAT, ANCHOR_LON, speed = 0.0))
        drain(fusion)
        tNs = parkedSamples(fusion, tNs, 100)
        drain(fusion)
        assertEquals(0L, fusion.solution.constraintAccepted)
        assertEquals(0L, fusion.solution.constraintRejected)
        assertFalse(codes().contains("FUSION_CONSTRAINT_APPLIED"))
    }

    // ------------------------------------------------------------------ A/B held-out outage

    /**
     * One drive: 30 s parked, accelerate to 12 m/s, cruise, then a GNSS outage containing a
     * full stop (brake at 1 m/s², 30 s parked, accelerate again), 66 s of outage cruise,
     * recovery fixes, and a final parked period. Truth is integrated from the same profile
     * the IMU reports, so the mechanization and the truth never disagree about the motion.
     */
    private inner class OutageDrive(val seed: Long) {

        /** name to (samples, acceleration m/s²). 100 Hz samples. */
        val plan = listOf(
            "park1" to (3000 to 0.0),
            "accel1" to (1200 to 1.0),
            "cruise1" to (7800 to 0.0),
            "outageBrake" to (1200 to -1.0),
            "outageStop" to (3000 to 0.0),
            "outageAccel" to (1200 to 1.0),
            "outageCruise" to (6600 to 0.0),
            "cruise2" to (3000 to 0.0),
            "park2" to (3000 to 0.0),
        )

        /** Per-sample truth speed and distance-from-start, integrated at 100 Hz. */
        fun truth(): List<Pair<Double, Double>> {
            val out = mutableListOf<Pair<Double, Double>>()
            var v = 0.0
            var x = 0.0
            for ((_, spec) in plan) {
                val (count, a) = spec
                for (i in 1..count) {
                    v = (v + a * 0.01).coerceIn(0.0, 12.0)
                    x += v * 0.01
                    out.add(v to x)
                }
            }
            return out
        }

        /**
         * Runs the drive; returns [outageEndErrorM, outageRmseM, stopDriftM, finalErrorM,
         * constraintAccepted].
         */
        fun run(constraints: Boolean): List<Double> {
            val truth = truth()
            val cfg = FusionConfig().copy(motionConstraintsEnabled = constraints)
            val fusion = engine(cfg)
            fusion.initialize(session, calRecord(calibration), InitializationMode.EVALUATION)
            var tNs = session.origin_ns
            val r = java.util.Random(seed)
            val rN = Geodesy.radii(Math.toRadians(ANCHOR_LAT)).second
            var sample = 0
            var outageStart = -1
            var outageEnd = -1
            var sumSq = 0.0
            var outageSamples = 0
            var maxStopDrift = 0.0
            var pathLength = 0.0
            var outageEndError = 0.0
            var inStop = false
            for ((name, spec) in plan) {
                val (count, a) = spec
                val outage = name.startsWith("outage")
                inStop = name == "outageStop" || name == "park2"
                for (i in 1..count) {
                    tNs += 10_000_000L
                    sample += 1
                    val (tv, tx) = truth[sample - 1]
                    // Device frame: forward acceleration is NEGATIVE y with this mount.
                    imuRecords(tNs, Vector3(0.0, -a, 9.80665), Vector3(0.0, 0.0, 0.0))
                        .forEach { fusion.acceptImu(it) }
                    if (!outage && sample % 100 == 0) {
                        fusion.acceptGnss(
                            fixRecord(
                                tNs,
                                ANCHOR_LAT + Math.toDegrees((tx + r.nextGaussian() * 3.0) / rN),
                                ANCHOR_LON,
                                speed = tv,
                                bearing = if (tv > 0.5) 0.0 else null,
                            ),
                        )
                    }
                    if (outage) {
                        if (outageStart < 0) outageStart = sample
                        outageEnd = sample
                        val s = fusion.solution
                        val err = sqrt(
                            (s.positionEnuM.y - tx) * (s.positionEnuM.y - tx) +
                                s.positionEnuM.x * s.positionEnuM.x,
                        )
                        sumSq += err * err
                        outageSamples += 1
                        outageEndError = err
                        if (inStop) maxStopDrift = maxOf(maxStopDrift, err)
                    }
                    if (tv > 0.0 && sample % 10 == 0) pathLength += tv * 0.1
                }
            }
            val s = fusion.solution
            val (fv, fx) = truth[truth.size - 1]
            val finalError = sqrt(
                (s.positionEnuM.y - fx) * (s.positionEnuM.y - fx) +
                    s.positionEnuM.x * s.positionEnuM.x,
            )
            val rmse = sqrt(sumSq / outageSamples.coerceAtLeast(1))
            println(
                String.format(
                    Locale.US,
                    "  arm=%-11s seed=%3d outageEndErr=%8.2f m  outageRmse=%8.2f m  " +
                        "stopDrift=%8.2f m  finalErr=%8.2f m  constraints=(acc=%d rej=%d)",
                    if (constraints) "ekf+zuptnhc" else "ekf-only",
                    seed, outageEndError, rmse, maxStopDrift, finalError,
                    s.constraintAccepted, s.constraintRejected,
                ),
            )
            return listOf(
                outageEndError, rmse, maxStopDrift, finalError, s.constraintAccepted.toDouble(),
            )
        }
    }

    @Test
    fun theHeldOutOutageEvaluationRunsBothArmsOnTheSameDrives() {
        history.clear()
        val seeds = listOf(11L, 23L, 47L, 83L, 101L)
        val ekfOnly = seeds.map { OutageDrive(it).run(constraints = false) }
        val ekfPlus = seeds.map { OutageDrive(it).run(constraints = true) }
        val names = listOf(
            "outageEndErrorM", "outageRmseM", "stopDriftM", "finalErrorM", "constraintsUsed",
        )
        println("=== A/B held-out outage evaluation (5 drives, identical streams) ===")
        for (i in names.indices) {
            val a = ekfOnly.map { it[i] }.average()
            val b = ekfPlus.map { it[i] }.average()
            val change = if (a > 0.0) 100.0 * (b - a) / a else 0.0
            println(
                String.format(
                    Locale.US, "  %-16s ekf-only=%10.3f   ekf+zupt/nhc=%10.3f   change=%+8.2f%%",
                    names[i], a, b, change,
                ),
            )
        }
        // The constrained arm must have actually used its constraints; the plain arm must not.
        assertTrue("the constrained arm never applied a constraint", ekfPlus.sumOf { it[4] } > 0.0)
        assertEquals(0.0, ekfOnly.sumOf { it[4] }, 0.0)
        // And it must not regress the held-out evaluation: a mean regression beyond 10% on
        // any error metric fails, which keeps the constraints honest against this drive set.
        for (i in 0..3) {
            val a = ekfOnly.map { it[i] }.average()
            val b = ekfPlus.map { it[i] }.average()
            assertTrue(
                "${names[i]} regressed: $a -> $b",
                b <= a * 1.10 + 1e-9,
            )
        }
    }

    companion object {
        private const val ANCHOR_LAT = 17.5
        private const val ANCHOR_LON = 78.4
    }
}
