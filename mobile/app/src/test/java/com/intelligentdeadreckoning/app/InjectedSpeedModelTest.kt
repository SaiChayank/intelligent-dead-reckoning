package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.fusion.SpeedCorrectionModel
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
import com.intelligentdeadreckoning.contracts.v1.NavigationState
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
import java.io.File
import java.util.Random
import kotlin.math.hypot

/**
 * The held-out evaluation hands each run its own weights. This checks the injection point is used: the unit-test
 * classpath carries no packaged weights, so an engine without an injected model applies no correction at all.
 * A model injected with every residual forced to zero still applies the integrated-acceleration speed as a
 * pseudo-measurement, so it must change the outcome. (The real weights are not asserted to change this scripted
 * drive: its acceleration is in a fixed frame, so the learned correction does not fit and the gate refuses it.)
 */
class InjectedSpeedModelTest {
    private val header = Header("inject-test", Source.SIMULATION, "1.1.0")
    private val originNs = 1_000_000_000L

    private fun record(tNs: Long, id: String, data: Payload) = Record(header, Event(id, tNs, tNs, data))

    private fun weightsJson() = File("src/main/assets/models/speed_estimator_v3_weights.json").readText()

    private fun zeroed(): SpeedCorrectionModel {
        val j = com.google.gson.JsonParser.parseString(weightsJson()).asJsonObject
        j.addProperty("y_scale", 0.0)                                  // every residual becomes exactly 0
        return SpeedCorrectionModel.fromJson(j.toString())
    }

    /** End-of-outage error of one scripted run; the same noise sequence every time. */
    private fun endError(model: SpeedCorrectionModel?): Double {
        val engine = FusionNavigationEngine(config = FusionConfig(speedCorrectionEnabled = true), speedCorrectionModel = model)
        engine.initialize(
            EngineSession(header, "inject-session", originNs),
            record(originNs, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)),
            InitializationMode.EVALUATION,
        )
        val random = Random(3)
        var anchor: Vector3? = null
        var last = 0.0
        for (step in 1..(ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()) {
            val t = step * ScriptedDrive.STEP_S
            val tNs = originNs + (t * 1e9).toLong()
            val (accel, gyro) = ScriptedDrive.imu(t, random)
            engine.acceptImu(record(tNs, "a$tNs", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH)))
            engine.acceptImu(record(tNs, "g$tNs", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH)))
            val inOutage = t >= ScriptedDrive.OUTAGE_FROM_S && t < ScriptedDrive.OUTAGE_TO_S
            if (step % 100 == 0 && !inOutage) {
                val (ne, nn) = ScriptedDrive.fixNoise(random)
                val truth = ScriptedDrive.position(t)
                if (anchor == null) anchor = Vector3(truth.x + ne, truth.y + nn, 0.0)
                val g = Geodesy.geodeticFromEnu(ScriptedDrive.ANCHOR, Vector3(truth.x + ne, truth.y + nn, 0.0))
                engine.acceptGnss(record(tNs, "n$tNs", GnssMeasurement(g.latitudeDeg, g.longitudeDeg,
                    ScriptedDrive.ANCHOR.altitudeM, AltitudeReference.ELLIPSOID, ScriptedDrive.speed(t),
                    ScriptedDrive.headingDeg(t), ScriptedDrive.FIX_SIGMA_M, 2.0 * ScriptedDrive.FIX_SIGMA_M, 20L,
                    "gps", null)))
            }
            for (rec in engine.drain()) {
                val nav = rec.event.data as? NavigationState ?: continue
                val pos = nav.position_enu_m ?: continue
                val tt = (rec.event.t_ns - originNs) / 1e9
                if (tt >= ScriptedDrive.OUTAGE_FROM_S && tt < ScriptedDrive.OUTAGE_TO_S) {
                    val o = anchor ?: Vector3(0.0, 0.0, 0.0)
                    val truth = ScriptedDrive.position(tt)
                    last = hypot(o.x + pos.x - truth.x, o.y + pos.y - truth.y)
                }
            }
        }
        return last
    }

    @Test
    fun anInjectedModelIsUsedInPlaceOfThePackagedResource() {
        val uncorrected = endError(null)
        val withZero = endError(zeroed())
        val withModel = endError(SpeedCorrectionModel.fromJson(weightsJson()))      // must at least run end to end
        println("outage end error: uncorrected %.3f m, zero model %.3f m, real weights %.3f m".format(uncorrected, withZero, withModel))
        assertTrue("injection had no effect (uncorrected $uncorrected, zero model $withZero)", kotlin.math.abs(withZero - uncorrected) > 0.01)
        assertTrue(withModel.isFinite())
    }
}
