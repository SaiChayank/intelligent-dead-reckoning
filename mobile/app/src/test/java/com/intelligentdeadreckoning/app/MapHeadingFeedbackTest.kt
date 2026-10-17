package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.conjugate
import com.intelligentdeadreckoning.app.calibration.times
import com.intelligentdeadreckoning.app.calibration.toRotationMatrix
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionInitialState
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.FusionStatus
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.GnssInsEkf
import com.intelligentdeadreckoning.app.fusion.GnssUpdateResult
import com.intelligentdeadreckoning.app.matching.MapMatchConfig
import com.intelligentdeadreckoning.app.matching.MapMatchResult
import com.intelligentdeadreckoning.app.matching.MapMatchStatus
import com.intelligentdeadreckoning.app.matching.MapMatcher
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MapHeadingFeedbackTest {

    private val header = Header("map-heading-test", Source.REAL, "1.1.0")
    private val session = EngineSession(header, "map-heading-boot", 1_000_000_000L)
    private val standardG = 9.80665

    private val nominalMounting = Quaternion(1.0, 0.0, 0.0, 0.0)
    private val nominalCal = CalibrationResult(
        id = "cal-heading-1",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = nominalMounting,
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.98,
    )

    private fun calRecord() = Record(header, Event("cal", session.origin_ns, session.origin_ns, nominalCal))

    private fun imu(tNs: Long, accel: Vector3, gyro: Vector3 = Vector3(0.0, 0.0, 0.0)): Pair<Record, Record> =
        Pair(
            Record(
                header,
                Event(
                    "a$tNs", tNs, tNs,
                    ImuMeasurement(
                        Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                        ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH,
                    ),
                ),
            ),
            Record(
                header,
                Event(
                    "g$tNs", tNs, tNs,
                    ImuMeasurement(
                        Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                        ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH,
                    ),
                ),
            ),
        )

    private fun fix(tNs: Long, lat: Double, lon: Double, speed: Double = 0.0, course: Double = 0.0) = Record(
        header,
        Event(
            "f$tNs", tNs, tNs,
            GnssMeasurement(
                latitude_deg = lat,
                longitude_deg = lon,
                altitude_m = 500.0,
                altitude_reference = AltitudeReference.ELLIPSOID,
                speed_m_s = speed,
                bearing_deg = course,
                horizontal_accuracy_m = 1.5,
                vertical_accuracy_m = 2.5,
                satellites_used = 14,
                provider = "gps",
                utc_ms = 1790812800000L,
            ),
        ),
    )

    private fun createFixtureGraph(vararg roads: RoadGraphFixtures.Road): RoadGraph {
        val dir = Files.createTempDirectory("map-heading-test").toFile()
        dir.deleteOnExit()
        val file = File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(file, RoadGraphFixtures.graphJson(roads.toList()))
        return RoadGraph.load(file)
    }

    @Test
    fun directHeadingFeedbackCorrectsEkfAttitude() {
        val config = FusionConfig()
        val filter = GnssInsEkf(config)

        val anchor = GeodeticAnchor(17.4, 78.45, 500.0, true)
        val initial = FusionInitialState(
            anchor = anchor,
            qEnuFromDevice = Quaternion(1.0, 0.0, 0.0, 0.0), // Facing North
            velocityEnuM_S = Vector3(0.0, 10.0, 0.0),
            gyroBiasDeviceRad_S = Vector3(0.0, 0.0, 0.0),
            accelBiasDeviceM_S2 = Vector3(0.0, 0.0, 0.0),
            positionVarianceM2 = Vector3(1.0, 1.0, 1.0),
            velocityVarianceM2 = Vector3(0.1, 0.1, 0.1),
            attitudeVarianceRad2 = Vector3(0.05, 0.05, 0.05),
            gyroBiasVarianceRad2_S2 = 1e-4,
            accelBiasVarianceM2_S4 = 1e-4,
        )
        assertTrue(filter.align(initial, 1_000_000_000L))

        // Offer a road heading residual of +5 degrees (+0.08726 rad) clockwise
        val roadResidualRad = Math.toRadians(5.0)
        val outcome = filter.updateHeading(1_050_000_000L, roadResidualRad, 0.0076)

        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        assertEquals(1, outcome.dimension)
        assertEquals(1L, filter.solution.constraintAccepted)

        // Attitude should rotate clockwise, turning vector toward East (+X)
        val rot = filter.solution.qEnuFromDevice.toRotationMatrix()
        // Column 0 is forward in ENU (East=rot[0], North=rot[3])
        val forwardHeadingRad = atan2(rot[0], rot[3]).let { if (it < 0.0) it + 2.0 * Math.PI else it }
        assertTrue("Heading must adjust toward road: $forwardHeadingRad", forwardHeadingRad > 0.0)
    }

    @Test
    fun engineAcceptRoadHeadingConstrainsHeadingDuringOutage() {
        val config = FusionConfig(
            mapHeadingFeedbackEnabled = true,
            mapHeadingMinConfidence = 0.80,
            mapHeadingMinGnssStalenessS = 1.0,
            mapHeadingMinSpeedM_S = 2.0,
            mapHeadingUpdateIntervalS = 0.5,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L

        // Align with fix
        engine.acceptGnss(fix(tNs, 17.4, 78.45, speed = 10.0, course = 0.0))

        // Propagate for 1.5 seconds at speed = 10 m/s north
        for (i in 0 until 150) {
            tNs += dtNs
            val (a, g) = imu(tNs, Vector3(0.0, 0.0, standardG))
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        assertEquals(FusionStatus.RUNNING, engine.solution.status)

        // GNSS is now 1.5 s stale (> 1.0 s threshold) and speed is 10 m/s (> 2.0 m/s)
        val accepted = engine.acceptRoadHeading(tNs, roadHeadingRad = Math.toRadians(4.0), confidence = 0.92)
        assertTrue("High confidence road heading during outage must be accepted", accepted)

        // Immediate subsequent call should be rate-limited by updateInterval (0.5 s)
        val rateLimited = engine.acceptRoadHeading(tNs + 10_000_000L, roadHeadingRad = Math.toRadians(4.0), confidence = 0.92)
        assertFalse("Subsequent call within interval must be rate-limited", rateLimited)
    }

    @Test
    fun innovationGatingRejectsContradictoryHeading() {
        val config = FusionConfig(
            mapHeadingFeedbackEnabled = true,
            mapHeadingMaxInnovationDeg = 30.0,
            mapHeadingMinGnssStalenessS = 0.5,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        engine.acceptGnss(fix(tNs, 17.4, 78.45, speed = 10.0, course = 0.0))

        for (i in 0 until 100) {
            tNs += 10_000_000L
            val (a, g) = imu(tNs, Vector3(0.0, 0.0, standardG))
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        // Vehicle is heading North (0 deg). Offer perpendicular road heading East (90 deg).
        // Difference is 90 deg > 30 deg threshold. Must be rejected.
        val rejected = engine.acceptRoadHeading(tNs, roadHeadingRad = Math.PI / 2.0, confidence = 0.95)
        assertFalse("Contradictory road heading (> 30 deg difference) must be gated out", rejected)
    }

    @Test
    fun lowConfidenceAndLowSpeedRejected() {
        val config = FusionConfig(
            mapHeadingFeedbackEnabled = true,
            mapHeadingMinConfidence = 0.80,
            mapHeadingMinSpeedM_S = 2.0,
            mapHeadingMinGnssStalenessS = 1.0,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        engine.acceptGnss(fix(tNs, 17.4, 78.45, speed = 10.0, course = 0.0))

        // While GNSS is fresh (0.2 s stale < 1.0 s), road heading should stand down
        for (i in 0 until 20) {
            tNs += 10_000_000L
            val (a, g) = imu(tNs, Vector3(0.0, 0.0, standardG))
            engine.acceptImu(a)
            engine.acceptImu(g)
        }
        val rejectedFreshGnss = engine.acceptRoadHeading(tNs, roadHeadingRad = 0.0, confidence = 0.95)
        assertFalse("Road heading must stand down while GNSS is fresh", rejectedFreshGnss)

        // Now advance past staleness threshold
        for (i in 0 until 120) {
            tNs += 10_000_000L
            val (a, g) = imu(tNs, Vector3(0.0, 0.0, standardG))
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        // Low confidence (0.65 < 0.80) rejected
        val rejectedLowConf = engine.acceptRoadHeading(tNs, roadHeadingRad = 0.0, confidence = 0.65)
        assertFalse("Low confidence (< 0.80) must be rejected", rejectedLowConf)
    }

    @Test
    fun acceptMapMatchAppliesHeadingFromMatchedResult() {
        val config = FusionConfig(
            mapHeadingFeedbackEnabled = true,
            mapHeadingMinConfidence = 0.80,
            mapHeadingMinGnssStalenessS = 1.0,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        engine.acceptGnss(fix(tNs, 17.4, 78.45, speed = 10.0, course = 0.0))

        for (i in 0 until 150) {
            tNs += 10_000_000L
            val (a, g) = imu(tNs, Vector3(0.0, 0.0, standardG))
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        val matchResult = MapMatchResult(
            timestampNs = tNs,
            rawLatitudeDeg = 17.4,
            rawLongitudeDeg = 78.45,
            matchedLatitudeDeg = 17.4,
            matchedLongitudeDeg = 78.45,
            matchedEdgeId = "osm/123/0",
            confidence = 0.92,
            status = MapMatchStatus.MATCHED,
            distanceToEdgeM = 1.2,
            candidateCount = 1,
            matcherVersion = "idr-map-match/1",
            matchedHeadingRad = Math.toRadians(3.0),
        )

        val applied = engine.acceptMapMatch(matchResult)
        assertTrue("acceptMapMatch must apply heading from matched result", applied)
    }

    @Test
    fun endToEndDeadReckoningWithRoadGraphArrestsHeadingDrift() {
        // Create road running North-South at East offset 0.0
        val roadGraph = createFixtureGraph(RoadGraphFixtures.northSouth(1, 0.0))

        // Helper to run drive arm with or without map heading feedback
        fun runArm(enableMapFeedback: Boolean): Pair<Double, Double> {
            val config = FusionConfig(
                mapHeadingFeedbackEnabled = enableMapFeedback,
                mapHeadingMinConfidence = 0.80,
                mapHeadingMinGnssStalenessS = 1.0,
                mapHeadingUpdateIntervalS = 0.5,
                mapHeadingVarianceRad2 = 0.0076,
            )
            val engine = FusionNavigationEngine(
                initialHeadingRad = 0.0,
                config = config,
                publicationIntervalNs = 0L,
                roadGraph = roadGraph,
            )
            engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

            var tNs = 1_000_000_000L
            val dtNs = 10_000_000L // 100 Hz

            // 1. Align with initial fix driving North at 10 m/s
            engine.acceptGnss(fix(tNs, RoadGraphFixtures.lat(-150.0), RoadGraphFixtures.lon(0.0), speed = 10.0, course = 0.0))

            // 2. Drive for 2 seconds with fixes
            for (i in 0 until 200) {
                tNs += dtNs
                val (a, g) = imu(tNs, Vector3(0.0, 0.0, standardG))
                engine.acceptImu(a)
                engine.acceptImu(g)
            }

            // 3. Enter 20-second GNSS Outage with forward acceleration and Z-gyro drift of +0.008 rad/s (~0.46 deg/s)
            val gyroDrift = Vector3(0.0, 0.0, 0.008)
            val forwardAccel = Vector3(0.5, 0.0, standardG)
            for (i in 0 until 2000) {
                tNs += dtNs
                val (a, g) = imu(tNs, forwardAccel, gyroDrift)
                engine.acceptImu(a)
                engine.acceptImu(g)
            }

            // Return heading error in degrees and lateral (East) position error in metres
            val qVeh = engine.solution.qEnuFromDevice * nominalMounting.conjugate()
            val m = qVeh.toRotationMatrix()
            val headingDeg = Math.toDegrees(atan2(m[0], m[3]).let { if (it < 0.0) it + 2.0 * Math.PI else it })
            val headingError = abs(if (headingDeg > 180.0) headingDeg - 360.0 else headingDeg)
            val lateralDrift = abs(engine.solution.positionEnuM.x)
            return Pair(headingError, lateralDrift)
        }

        val (headingWithout, lateralWithout) = runArm(enableMapFeedback = false)
        val (headingWith, lateralWith) = runArm(enableMapFeedback = true)

        // Without map heading feedback, Z-gyro drift rotates vehicle heading and diverts forward acceleration
        assertTrue("Heading error without map feedback must exceed 7 deg: $headingWithout", headingWithout > 7.0)
        assertTrue("Lateral drift without map feedback must exceed 5m: $lateralWithout", lateralWithout > 5.0)

        // With closed-loop map heading feedback, road heading continuously arrests heading drift
        assertTrue("Heading error with map feedback must stay under 4 deg: $headingWith", headingWith < 4.0)
        assertTrue("Heading error with map feedback must be less than half without feedback: $headingWith vs $headingWithout", headingWith < headingWithout * 0.5)
        assertTrue("Lateral drift with map feedback must stay under 4m: $lateralWith", lateralWith < 4.0)
        assertTrue("Lateral drift with map feedback must be less than without feedback: $lateralWith vs $lateralWithout", lateralWith < lateralWithout)
    }
}
