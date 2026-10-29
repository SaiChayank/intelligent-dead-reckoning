package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.CalibrationStore
import com.intelligentdeadreckoning.app.calibration.SavedCalibration
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Replays the scripted drive through the real calibrate-then-fuse engine with no live calibration available
 * (the calibrator never leaves PENDING in 70 s of this input), once without and once with a saved mount.
 */
class ColdStartReplayTest {
    private val header = Header("cold-replay", Source.REAL, "1.1.0")
    private val originNs = 1_000_000_000L
    private fun record(tNs: Long, id: String, data: Payload) = Record(header, Event(id, tNs, tNs, data))

    private class Store(val value: SavedCalibration?) : CalibrationStore {
        override fun load(deviceKey: String) = value
        override fun save(calibration: SavedCalibration) {}
    }

    /** Seconds from the start until the first navigation state that carries a position, or null. */
    private fun firstPositionS(store: CalibrationStore?): Double? {
        val engine = CalibratingFusionEngine(store = store, deviceKey = "Pixel_7", clockEpochMs = { 1_800_000_000_000L })
        engine.initialize(
            EngineSession(header, "cold-session", originNs),
            record(originNs, "cal", CalibrationResult("cal", CalibrationStatus.PENDING, null, null, null, null)),
            InitializationMode.DEPLOYABLE,
        )
        val random = Random(5)
        for (step in 1..(30.0 / ScriptedDrive.STEP_S).toInt()) {
            val t = step * ScriptedDrive.STEP_S
            val tNs = originNs + (t * 1e9).toLong()
            val (accel, gyro) = ScriptedDrive.imu(t, random)
            engine.acceptImu(record(tNs, "a$tNs", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH)))
            engine.acceptImu(record(tNs, "g$tNs", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH)))
            if (step % 100 == 0) {
                val (ne, nn) = ScriptedDrive.fixNoise(random)
                val truth = ScriptedDrive.position(t)
                val g = Geodesy.geodeticFromEnu(ScriptedDrive.ANCHOR, Vector3(truth.x + ne, truth.y + nn, 0.0))
                engine.acceptGnss(record(tNs, "n$tNs", GnssMeasurement(g.latitudeDeg, g.longitudeDeg,
                    ScriptedDrive.ANCHOR.altitudeM, AltitudeReference.ELLIPSOID, ScriptedDrive.speed(t),
                    ScriptedDrive.headingDeg(t), ScriptedDrive.FIX_SIGMA_M, 2.0 * ScriptedDrive.FIX_SIGMA_M, 20L,
                    "gps", null)))
            }
            for (rec in engine.drain()) {
                val nav = rec.event.data as? NavigationState ?: continue
                if (nav.position_enu_m != null) return t
            }
        }
        return null
    }

    @Test fun savedMountGivesPositionsAtTheFirstFixWhereNoMountGivesNone() {
        val saved = SavedCalibration("Pixel_7", 1_800_000_000_000L - 3_600_000L, Quaternion(1.0, 0.0, 0.0, 0.0),
            Vector3(0.0, 0.0, 0.0), null, 0.9)
        val without = firstPositionS(null)
        val with = firstPositionS(Store(saved))
        println("first position: without saved mount $without s, with saved mount $with s")
        assertNull("the live calibrator should not have finished in 30 s of this input", without)
        assertNotNull(with)
        assertTrue("first position at $with s", with!! <= 2.0)
    }
}
