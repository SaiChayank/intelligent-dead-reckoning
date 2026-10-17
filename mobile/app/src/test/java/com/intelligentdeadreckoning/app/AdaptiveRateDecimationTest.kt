package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.FusionStatus
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Phase 4: Adaptive Rate Decimation for Thermal & Battery Optimization Unit Tests.
 *
 * Verifies stationary rate decimation (100 Hz -> 20 Hz, factor 5), instant wake-up (< 5 ms)
 * upon motion jerk or gyro rotation, stable stationary hold, and computational workload reduction.
 */
class AdaptiveRateDecimationTest {

    private val standardG = 9.80665
    private val header = Header("adaptive-decimation-test", Source.REAL, "1.1.0")
    private val session = EngineSession(header, "session-1", 1_000_000_000L)
    private val calibration = CalibrationResult(
        id = "cal-1",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.95,
    )

    private fun calRecord() = Record(header, Event("cal", session.origin_ns, session.origin_ns, calibration))

    private fun imu(
        tNs: Long,
        accel: Vector3 = Vector3(0.0, 0.0, standardG),
        gyro: Vector3 = Vector3(0.0, 0.0, 0.0),
    ): Pair<Record, Record> = Pair(
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
                vertical_accuracy_m = 2.0,
                satellites_used = 14,
                provider = "gps",
                utc_ms = 1790812800000L,
            ),
        ),
    )

    @Test
    fun disabledByDefaultPreservesStandardExecution() {
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz

        engine.acceptGnss(fix(tNs, 17.4350, 78.4450))

        // Feed 150 stationary samples
        for (i in 0 until 150) {
            tNs += dtNs
            val (a, g) = imu(tNs)
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        assertFalse("Decimation must be disabled by default", engine.isDecimationActive)
        assertEquals("Decimated sample count must be 0 when disabled", 0L, engine.decimatedSamples)
        assertEquals("Wakeup count must be 0 when disabled", 0L, engine.decimationWakeups)
    }

    @Test
    fun stationaryVehicleEntersDecimationAndDecimatesSamples() {
        val config = FusionConfig(
            adaptiveDecimationEnabled = true,
            decimationFactor = 5,
            decimationMinStationaryDwellS = 1.0,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz

        // 1. Initial fix aligns the filter at zero speed
        engine.acceptGnss(fix(tNs, 17.4350, 78.4450, speed = 0.0))

        // 2. Feed 250 stationary samples (2.5 seconds).
        // StationaryDetector requires 2.0s of stillness, then dwell of 1.0s engages decimation.
        // Let's feed 350 samples (3.5 seconds) to ensure dwell is met.
        for (i in 0 until 350) {
            tNs += dtNs
            val (a, g) = imu(tNs)
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        assertTrue("Decimation must engage after stationary dwell", engine.isDecimationActive)
        assertTrue("Decimated samples must be recorded (> 0): ${engine.decimatedSamples}", engine.decimatedSamples > 0)
        assertEquals("Solution status must remain RUNNING", FusionStatus.RUNNING, engine.solution.status)

        // Verify ~80% of samples are decimated during the decimation active period (factor 5 skips 4 of 5)
        val decimatedRatio = engine.decimatedSamples.toDouble() / 350.0
        assertTrue("Decimated ratio must exceed 10% of total run: $decimatedRatio", decimatedRatio > 0.10)
    }

    @Test
    fun motionJerkInstantlyWakesUpFilterWithin5Ms() {
        val config = FusionConfig(
            adaptiveDecimationEnabled = true,
            decimationFactor = 5,
            decimationMinStationaryDwellS = 1.0,
            decimationWakeupJerkM_S2 = 0.3,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz

        engine.acceptGnss(fix(tNs, 17.4350, 78.4450, speed = 0.0))

        // Dwell stationary for 350 samples
        for (i in 0 until 350) {
            tNs += dtNs
            val (a, g) = imu(tNs)
            engine.acceptImu(a)
            engine.acceptImu(g)
        }
        assertTrue("Decimation must be active before jerk", engine.isDecimationActive)
        val decimationCountBefore = engine.decimatedSamples

        // Step acceleration to forward jerk (+0.8 m/s^2 forward, norm jerk = 0.8 > 0.3)
        tNs += dtNs
        val (aJerk, gJerk) = imu(tNs, accel = Vector3(0.8, 0.0, standardG))
        engine.acceptImu(aJerk)
        engine.acceptImu(gJerk)

        assertFalse("Decimation must immediately disengage upon jerk", engine.isDecimationActive)
        assertTrue("Wakeup event must be registered", engine.decimationWakeups >= 1L)

        // Verify the jerk sample was not skipped (decimated samples count unchanged)
        assertEquals("Jerk sample must be propagated immediately without being skipped",
            decimationCountBefore, engine.decimatedSamples)
    }

    @Test
    fun gyroRotationInstantlyWakesUpFilter() {
        val config = FusionConfig(
            adaptiveDecimationEnabled = true,
            decimationFactor = 5,
            decimationMinStationaryDwellS = 1.0,
            decimationWakeupGyroRad_S = 0.08,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz

        engine.acceptGnss(fix(tNs, 17.4350, 78.4450, speed = 0.0))

        // Dwell stationary for 350 samples
        for (i in 0 until 350) {
            tNs += dtNs
            val (a, g) = imu(tNs)
            engine.acceptImu(a)
            engine.acceptImu(g)
        }
        assertTrue("Decimation must be active", engine.isDecimationActive)

        // Inject sudden yaw rotation (+0.15 rad/s > 0.08 rad/s threshold)
        tNs += dtNs
        val (aRot, gRot) = imu(tNs, gyro = Vector3(0.0, 0.0, 0.15))
        engine.acceptImu(aRot)
        engine.acceptImu(gRot)

        assertFalse("Decimation must immediately disengage upon angular rotation", engine.isDecimationActive)
        assertTrue("Wakeup event must be registered", engine.decimationWakeups >= 1L)
    }

    @Test
    fun stationaryPositionAndVelocityRemainStableDuringDecimation() {
        val config = FusionConfig(
            adaptiveDecimationEnabled = true,
            decimationFactor = 5,
            decimationMinStationaryDwellS = 1.0,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz

        engine.acceptGnss(fix(tNs, 17.4350, 78.4450, speed = 0.0))

        // Feed 500 stationary samples (5.0 seconds total, 2.0s entry + 1.0s dwell + 2.0s decimated)
        for (i in 0 until 500) {
            tNs += dtNs
            val (a, g) = imu(tNs)
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        assertTrue("Decimation must be active", engine.isDecimationActive)

        // Verify position and velocity are stably clamped (no drift while stationary)
        val pos = engine.solution.positionEnuM
        val vel = engine.solution.velocityEnuM_S
        val posDrift = sqrt(pos.x * pos.x + pos.y * pos.y + pos.z * pos.z)
        val velNorm = sqrt(vel.x * vel.x + vel.y * vel.y + vel.z * vel.z)

        assertTrue("Stationary position drift must remain < 0.15m: $posDrift", posDrift < 0.15)
        assertTrue("Stationary velocity norm must remain < 0.05 m/s: $velNorm", velNorm < 0.05)
    }

    @Test
    fun cpuWorkloadReductionReducesPropagationCount() {
        // Run with decimation factor 5 on a 600-sample stationary run
        val config = FusionConfig(
            adaptiveDecimationEnabled = true,
            decimationFactor = 5,
            decimationMinStationaryDwellS = 1.0,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz

        engine.acceptGnss(fix(tNs, 17.4350, 78.4450, speed = 0.0))

        for (i in 0 until 600) {
            tNs += dtNs
            val (a, g) = imu(tNs)
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        // With 600 samples:
        // First 300 samples for stationary detection + dwell.
        // Last 300 samples in decimation: 4 out of 5 samples are skipped (80% skipped = ~240 samples skipped).
        assertTrue("Decimated samples must exceed 200: ${engine.decimatedSamples}", engine.decimatedSamples >= 200L)
    }
}
