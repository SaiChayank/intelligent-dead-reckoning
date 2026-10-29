package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.DEFAULT_PUBLICATION_INTERVAL_NS
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
import com.intelligentdeadreckoning.contracts.v1.LocalizationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.Payload
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.SensorAccuracy
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The PS asks for a 10 Hz position update on the phone. This pins the engine's default cadence, checks that
 * publication does not stall through a GNSS outage, and measures how long the engine takes to declare dead
 * reckoning after the last accepted fix (that delay is the engine's stale bound, not a processing latency).
 */
class PublicationRateTest {
    private val header = Header("rate-test", Source.SIMULATION, "1.1.0")
    private val originNs = 1_000_000_000L

    private fun record(tNs: Long, id: String, data: Payload) = Record(header, Event(id, tNs, tNs, data))

    @Test
    fun defaultCadenceIsTenHertz() {
        assertEquals(100_000_000L, DEFAULT_PUBLICATION_INTERVAL_NS)
    }

    @Test
    fun publishesAtTenHertzThroughAnOutageAndReportsTheSwitchDelay() {
        val engine = FusionNavigationEngine()              // default constructor: the cadence under test
        engine.initialize(
            EngineSession(header, "rate-session", originNs),
            record(originNs, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)),
            InitializationMode.EVALUATION,
        )
        val random = Random(7)
        val steps = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()
        val publishTimes = ArrayList<Double>()
        var lastFixS = 0.0
        var firstDrS: Double? = null
        for (step in 1..steps) {
            val tS = step * ScriptedDrive.STEP_S
            val tNs = originNs + (tS * 1e9).toLong()
            val (accel, gyro) = ScriptedDrive.imu(tS, random)
            engine.acceptImu(record(tNs, "a$tNs", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH)))
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
                if (tS < ScriptedDrive.OUTAGE_FROM_S) lastFixS = tS   // last fix BEFORE the outage
            }
            for (rec in engine.drain()) {
                val nav = rec.event.data as? NavigationState ?: continue
                if (nav.position_enu_m == null) continue
                val t = (rec.event.t_ns - originNs) / 1e9
                publishTimes += t
                if (firstDrS == null && t > ScriptedDrive.OUTAGE_FROM_S && nav.localization_mode == LocalizationMode.DR) {
                    firstDrS = t
                }
            }
        }
        val gaps = publishTimes.zipWithNext { a, b -> b - a }
        val rate = (publishTimes.size - 1) / (publishTimes.last() - publishTimes.first())
        val drDelay = firstDrS!! - lastFixS
        println(String.format("Publication: %.2f Hz, longest gap %.3f s; dead reckoning declared %.2f s after the last fix",
            rate, gaps.max(), drDelay))
        assertEquals(10.0, rate, 0.5)
        assertTrue("publication stalled: longest gap ${gaps.max()} s", gaps.max() <= 0.25)
        assertNotNull(firstDrS)
        assertTrue("switch delay must be measured from the last fix before the outage (was $drDelay)", drDelay > 0.0)
        assertTrue("dead reckoning not declared within the outage", firstDrS!! < ScriptedDrive.OUTAGE_TO_S)
    }
}
