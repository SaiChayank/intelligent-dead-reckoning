package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.norm
import com.intelligentdeadreckoning.app.calibration.quaternionAboutZ
import com.intelligentdeadreckoning.app.calibration.rotate
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.FusionStatus
import com.intelligentdeadreckoning.app.fusion.MountSlipDetector
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class MountSlipDetectorTest {

    private val header = Header("mount-slip-test", Source.REAL, "1.1.0")
    private val session = EngineSession(header, "mount-slip-boot", 1_000_000_000L)
    private val standardG = 9.80665

    private val nominalMounting = Quaternion(1.0, 0.0, 0.0, 0.0)
    private val nominalCal = CalibrationResult(
        id = "cal-mount-1",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = nominalMounting,
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.98,
    )

    private fun calRecord(cal: CalibrationResult = nominalCal) =
        Record(header, Event("cal", session.origin_ns, session.origin_ns, cal))

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

    private fun fix(tNs: Long, lat: Double, lon: Double, speed: Double = 0.0) = Record(
        header,
        Event(
            "f$tNs", tNs, tNs,
            GnssMeasurement(
                latitude_deg = lat,
                longitude_deg = lon,
                altitude_m = 500.0,
                altitude_reference = AltitudeReference.ELLIPSOID,
                speed_m_s = speed,
                bearing_deg = 0.0,
                horizontal_accuracy_m = 1.5,
                vertical_accuracy_m = 2.5,
                satellites_used = 14,
                provider = "gps",
                utc_ms = 1790812800000L,
            ),
        ),
    )

    @Test
    fun pitchSlipDetectedAndReleveled() {
        val config = FusionConfig(
            mountSlipDetectionEnabled = true,
            mountSlipAngleThresholdDeg = 4.0,
            mountSlipDwellS = 0.35,
            gravityFilterTimeConstantS = 0.1,
        )
        val detector = MountSlipDetector(config)

        val pitchAngleRad = Math.toRadians(10.0)
        // Device pitched: gravity vector in device frame tilts along X
        val slippedAccel = Vector3(
            -standardG * sin(pitchAngleRad),
            0.0,
            standardG * cos(pitchAngleRad),
        )

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz
        var detectedEvent: com.intelligentdeadreckoning.app.fusion.MountSlipEvent? = null

        // Feed 50 samples (0.5 s total > 0.35 s dwell)
        for (i in 0 until 50) {
            tNs += dtNs
            val ev = detector.offer(tNs, slippedAccel, Vector3(0.0, 0.0, 0.0), nominalMounting)
            if (ev != null) {
                detectedEvent = ev
                break
            }
        }

        assertNotNull("Mount slip event must be triggered after dwell", detectedEvent)
        assertEquals(1, detectedEvent!!.relevelCount)
        assertTrue("Slip angle should be near 10 deg: ${detectedEvent.slipAngleDeg}", detectedEvent.slipAngleDeg in 9.0..11.0)
        assertTrue("Dwell duration satisfied: ${detectedEvent.dwellDurationS}", detectedEvent.dwellDurationS >= 0.35)

        // Verify that updated mounting re-levels gravity to VEHICLE_UP
        val newUp = detectedEvent.updatedMounting.rotate(detectedEvent.apparentGravityDevice)
        assertTrue("Re-leveled X should be ~0: ${newUp.x}", abs(newUp.x) < 0.05)
        assertTrue("Re-leveled Y should be ~0: ${newUp.y}", abs(newUp.y) < 0.05)
        assertTrue("Re-leveled Z should be ~1: ${newUp.z}", abs(newUp.z - 1.0) < 0.05)
    }

    @Test
    fun rollSlipDetectedAndReleveled() {
        val config = FusionConfig(
            mountSlipDetectionEnabled = true,
            mountSlipAngleThresholdDeg = 4.0,
            mountSlipDwellS = 0.35,
            gravityFilterTimeConstantS = 0.1,
        )
        val detector = MountSlipDetector(config)

        val rollAngleRad = Math.toRadians(15.0)
        val slippedAccel = Vector3(
            0.0,
            standardG * sin(rollAngleRad),
            standardG * cos(rollAngleRad),
        )

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L
        var detectedEvent: com.intelligentdeadreckoning.app.fusion.MountSlipEvent? = null

        for (i in 0 until 60) {
            tNs += dtNs
            val ev = detector.offer(tNs, slippedAccel, Vector3(0.0, 0.0, 0.0), nominalMounting)
            if (ev != null) {
                detectedEvent = ev
                break
            }
        }

        assertNotNull("Roll slip must be detected", detectedEvent)
        assertTrue("Slip angle should be near 15 deg: ${detectedEvent!!.slipAngleDeg}", detectedEvent.slipAngleDeg in 14.0..16.0)

        val newUp = detectedEvent.updatedMounting.rotate(detectedEvent.apparentGravityDevice)
        assertTrue("Re-leveled X ~ 0", abs(newUp.x) < 0.05)
        assertTrue("Re-leveled Y ~ 0", abs(newUp.y) < 0.05)
        assertTrue("Re-leveled Z ~ 1", abs(newUp.z - 1.0) < 0.05)
    }

    @Test
    fun compoundSlipPreservesVehicleYaw() {
        // Nominal mounting has 45 deg yaw about vehicle Z
        val initialMounting = quaternionAboutZ(Math.PI / 4.0)

        // Device tilted in compound angle
        val tiltDirection = Vector3(1.5, -1.2, 9.6)
        val releveled = MountSlipDetector.computeReleveledMounting(initialMounting, tiltDirection)

        val rotatedUp = releveled.rotate(tiltDirection.let {
            val n = it.norm()
            Vector3(it.x / n, it.y / n, it.z / n)
        })
        assertTrue("Compound tilt re-leveled to vehicle UP: ${rotatedUp.z}", abs(rotatedUp.z - 1.0) < 1e-4)
        assertTrue("X near 0: ${rotatedUp.x}", abs(rotatedUp.x) < 1e-4)
        assertTrue("Y near 0: ${rotatedUp.y}", abs(rotatedUp.y) < 1e-4)
    }

    @Test
    fun transientBumpRejectedByDwellTime() {
        val config = FusionConfig(
            mountSlipDetectionEnabled = true,
            mountSlipAngleThresholdDeg = 4.0,
            mountSlipDwellS = 0.35,
            gravityFilterTimeConstantS = 0.1,
        )
        val detector = MountSlipDetector(config)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L

        // Feed transient bump lasting only 100 ms (10 samples)
        for (i in 0 until 10) {
            tNs += dtNs
            val ev = detector.offer(tNs, Vector3(2.5, 0.0, 9.5), Vector3(0.0, 0.0, 0.0), nominalMounting)
            assertNull("Transient bump (< 0.35 s) must not trigger slip event", ev)
        }

        // Return to nominal for 40 samples
        for (i in 0 until 40) {
            tNs += dtNs
            val ev = detector.offer(tNs, Vector3(0.0, 0.0, standardG), Vector3(0.0, 0.0, 0.0), nominalMounting)
            assertNull("Nominal rest must not trigger slip", ev)
        }
        assertEquals(0, detector.currentRelevelCount)
    }

    @Test
    fun highYawTurnRejectedByGyroGate() {
        val config = FusionConfig(
            mountSlipDetectionEnabled = true,
            mountSlipAngleThresholdDeg = 4.0,
            mountSlipDwellS = 0.35,
            nhcMaxYawRateRad_S = 0.5,
        )
        val detector = MountSlipDetector(config)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L
        val slippedAccel = Vector3(2.0, 0.0, 9.6)
        val highTurnGyro = Vector3(0.0, 0.0, 0.8) // > 0.5 rad/s

        for (i in 0 until 60) {
            tNs += dtNs
            val ev = detector.offer(tNs, slippedAccel, highTurnGyro, nominalMounting)
            assertNull("High yaw rate turn must suppress slip re-leveling", ev)
        }
        assertEquals(0, detector.currentRelevelCount)
    }

    @Test
    fun extremeForceMagnitudeRejected() {
        val config = FusionConfig(mountSlipDetectionEnabled = true)
        val detector = MountSlipDetector(config)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L
        val shockAccel = Vector3(5.0, 10.0, 25.0) // Norm ~27 > 12.5 m/s^2

        for (i in 0 until 50) {
            tNs += dtNs
            val ev = detector.offer(tNs, shockAccel, Vector3(0.0, 0.0, 0.0), nominalMounting)
            assertNull("Extreme shock force must be rejected", ev)
        }
        assertEquals(0, detector.currentRelevelCount)
    }

    @Test
    fun engineInMotionRelevelArrestsDeadReckoningDrift() {
        val pitchSlipDeg = 8.0
        val pitchSlipRad = Math.toRadians(pitchSlipDeg)
        val slippedAccel = Vector3(
            -standardG * sin(pitchSlipRad),
            0.0,
            standardG * cos(pitchSlipRad),
        )

        // Helper to run simulation arm
        fun runArm(enableRelevel: Boolean): Double {
            val config = FusionConfig(
                mountSlipDetectionEnabled = enableRelevel,
                mountSlipAngleThresholdDeg = 4.0,
                mountSlipDwellS = 0.35,
                gravityFilterTimeConstantS = 0.1,
            )
            val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
            engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

            var tNs = 1_000_000_000L
            val dtNs = 10_000_000L // 100 Hz

            // 1. Initial GNSS fix to align filter
            engine.acceptGnss(fix(tNs, 17.4350, 78.4450))

            // 2. Feed 20 clean resting samples
            for (i in 0 until 20) {
                tNs += dtNs
                val (a, g) = imu(tNs, Vector3(0.0, 0.0, standardG))
                engine.acceptImu(a)
                engine.acceptImu(g)
            }
            assertEquals(FusionStatus.RUNNING, engine.solution.status)

            // 3. GNSS Outage begins: phone slips in cradle by 8 deg pitch for 15 seconds
            for (i in 0 until 1500) {
                tNs += dtNs
                val (a, g) = imu(tNs, slippedAccel)
                engine.acceptImu(a)
                engine.acceptImu(g)
            }

            val finalPos = engine.solution.positionEnuM
            return sqrt(finalPos.x * finalPos.x + finalPos.y * finalPos.y)
        }

        val driftWithoutRelevel = runArm(enableRelevel = false)
        val driftWithRelevel = runArm(enableRelevel = true)

        // Without re-leveling, 8 deg tilt leaks g*sin(8 deg) = 1.36 m/s^2 into horizontal acceleration.
        // In 15 seconds, drift = 0.5 * 1.36 * 15^2 ~ 150+ meters!
        assertTrue("Without re-level, drift must exceed 30m: $driftWithoutRelevel", driftWithoutRelevel > 30.0)

        // With in-motion re-leveling, detector catches slip within 0.4s and re-levels attitude,
        // arresting horizontal drift to under 5 meters!
        assertTrue("With re-level, drift must be arrested under 5m: $driftWithRelevel", driftWithRelevel < 5.0)
    }
}
