package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.AltitudeReference
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuUnit
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.Payload
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.SensorAccuracy
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** A vertical shock during an outage must hold back speed pseudo-measurements, and a gentle bump must not. */
class BumpSuppressionTest {
    private val header = Header("bump-test", Source.SIMULATION, "1.1.0")
    private val originNs = 1_000_000_000L

    private fun record(tNs: Long, id: String, data: Payload) = Record(header, Event(id, tNs, tNs, data))

    private fun suppressedUpdates(spikeM_S2: Double, enabled: Boolean): Long {
        val engine = FusionNavigationEngine(
            config = FusionConfig(aiSpeedEstimationEnabled = true, bumpSuppressionEnabled = enabled),
        )
        engine.initialize(
            EngineSession(header, "bump-session", originNs),
            record(originNs, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)),
            InitializationMode.EVALUATION,
        )
        val random = Random(11)
        val steps = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()
        for (step in 1..steps) {
            val tS = step * ScriptedDrive.STEP_S
            val tNs = originNs + (tS * 1e9).toLong()
            val (accel, gyro) = ScriptedDrive.imu(tS, random)
            val shock = tS >= 35.0 && tS < 35.05
            val z = if (shock) accel.z + spikeM_S2 else accel.z
            engine.acceptImu(record(tNs, "a$tNs", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, Vector3(accel.x, accel.y, z), SensorAccuracy.HIGH)))
            engine.acceptImu(record(tNs, "g$tNs", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH)))
            val inOutage = tS >= ScriptedDrive.OUTAGE_FROM_S && tS < ScriptedDrive.OUTAGE_TO_S
            if (step % 100 == 0 && !inOutage) {
                val (ne, nn) = ScriptedDrive.fixNoise(random)
                val truth = ScriptedDrive.position(tS)
                val g = Geodesy.geodeticFromEnu(ScriptedDrive.ANCHOR, Vector3(truth.x + ne, truth.y + nn, 0.0))
                engine.acceptGnss(record(tNs, "n$tNs", GnssMeasurement(g.latitudeDeg, g.longitudeDeg,
                    ScriptedDrive.ANCHOR.altitudeM, AltitudeReference.ELLIPSOID, ScriptedDrive.speed(tS),
                    ScriptedDrive.headingDeg(tS), ScriptedDrive.FIX_SIGMA_M, 2.0 * ScriptedDrive.FIX_SIGMA_M, 20L,
                    "gps", null)))
            }
            engine.drain()
        }
        return engine.bumpSuppressedUpdates
    }

    @Test
    fun aVerticalShockWithholdsSpeedUpdatesForTheHoldPeriod() {
        val withheld = suppressedUpdates(spikeM_S2 = 6.0, enabled = true)
        println("Updates withheld after one 6 m/s^2 shock: $withheld")
        assertTrue("a shock during the outage must withhold at least one speed update (was $withheld)", withheld >= 1)
        // Attempts are counted per 100 Hz sample, so a 0.5 s hold is about 50 of them; a long suppression would be far more.
        assertTrue("suppression must stay near the 0.5 s hold (was $withheld)", withheld <= 60)
    }

    @Test
    fun aGentleBumpBelowTheThresholdWithholdsNothing() {
        assertEquals(0L, suppressedUpdates(spikeM_S2 = 1.5, enabled = true))
    }

    @Test
    fun disabledSuppressionNeverWithholds() {
        assertEquals(0L, suppressedUpdates(spikeM_S2 = 6.0, enabled = false))
    }
}
