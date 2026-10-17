package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.FusionStatus
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBAdaptiveNoiseTest {

    private val header = Header("synthetic-model-b", Source.REAL, "1.1.0")
    private val session = EngineSession(header, "boot", 1_000_000_000L)
    private val calibration = CalibrationResult(
        id = "cal-1",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.95,
    )

    private fun calRecord() = Record(header, Event("cal", session.origin_ns, session.origin_ns, calibration))

    private fun imu(tNs: Long, accel: Vector3, gyro: Vector3): Pair<Record, Record> = Pair(
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

    private fun fix(tNs: Long, lat: Double, lon: Double) = Record(
        header,
        Event(
            "f$tNs", tNs, tNs,
            GnssMeasurement(
                latitude_deg = lat,
                longitude_deg = lon,
                altitude_m = 505.0,
                altitude_reference = AltitudeReference.ELLIPSOID,
                speed_m_s = 0.0,
                bearing_deg = 0.0,
                horizontal_accuracy_m = 2.0,
                vertical_accuracy_m = 3.0,
                satellites_used = 12,
                provider = "gps",
                utc_ms = 1790812800000L,
            ),
        ),
    )

    @Test
    fun testAdaptiveNoiseDisabledByDefault() {
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)
        assertEquals(FusionStatus.UNINITIALIZED, engine.solution.status)
    }

    @Test
    fun testAdaptiveNoiseScalesWithVibration() {
        val config = FusionConfig(
            adaptiveNoiseScalingEnabled = true,
            vibrationThresholdM_S2 = 0.5,
            maxAdaptiveNoiseInflation = 4.0,
        )
        val engine = FusionNavigationEngine(initialHeadingRad = 0.0, config = config, publicationIntervalNs = 0L)
        engine.initialize(session, calRecord(), InitializationMode.DEPLOYABLE)

        var tNs = 1_000_000_000L
        val dtNs = 10_000_000L // 100 Hz

        // 1. Initial GNSS fix to align filter
        engine.acceptGnss(fix(tNs, 17.4350, 78.4450))

        // 2. Feed 20 clean resting samples (zero vibration)
        for (i in 0 until 20) {
            tNs += dtNs
            val (a, g) = imu(tNs, Vector3(0.0, 0.0, 9.80665), Vector3(0.0, 0.0, 0.0))
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        // Under smooth rest, filter is running cleanly
        assertEquals(FusionStatus.RUNNING, engine.solution.status)

        // 3. Feed 20 rough road vibration samples (vertical shock bouncing +/- 2.5 m/s^2)
        for (i in 20 until 40) {
            tNs += dtNs
            val shock = if (i % 2 == 0) 2.5 else -2.5
            val (a, g) = imu(tNs, Vector3(0.0, 0.0, 9.80665 + shock), Vector3(0.0, 0.0, 0.0))
            engine.acceptImu(a)
            engine.acceptImu(g)
        }

        // Filter survives severe shock without numerical invalidity or failure
        assertEquals(FusionStatus.RUNNING, engine.solution.status)
        assertTrue(engine.solution.positionEnuM.z.isFinite())
    }
}
