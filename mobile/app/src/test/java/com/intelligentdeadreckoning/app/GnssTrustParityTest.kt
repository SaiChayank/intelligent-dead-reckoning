package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.fusion.GnssTrustModel
import com.intelligentdeadreckoning.app.fusion.GnssTrustTracker
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.hypot

/** Kotlin must reproduce the Python trust features and prediction (tools/make_gnss_trust_golden.py). */
class GnssTrustParityTest {
    private val weights = File("src/main/assets/models/gnss_trust_weights.json").readText()
    private val golden = JsonParser.parseString(File("src/test/resources/gnss_trust_golden.json").readText())
        .asJsonObject.getAsJsonArray("fixes")

    private fun fix(o: com.google.gson.JsonObject): GnssMeasurement {
        fun d(k: String) = if (o.get(k).isJsonNull) null else o.get(k).asDouble
        return GnssMeasurement(o.get("lat").asDouble, o.get("lon").asDouble, null, null, d("speed_m_s"), d("course_deg"),
            d("accuracy_m"), null, if (o.get("satellites").isJsonNull) null else o.get("satellites").asLong, "gps", null)
    }

    @Test
    fun featuresAndPredictionsMatchThePythonImplementationFixByFix() {
        val tracker = GnssTrustTracker(GnssTrustModel.fromJson(weights))
        val model = GnssTrustModel.fromJson(weights)
        var formed = 0
        for (entry in golden) {
            val o = entry.asJsonObject
            val f = fix(o)
            val t = o.get("t_ns").asLong
            val expectedFeatures = o.get("features")
            val features = tracker.features(t, f)
            if (expectedFeatures.isJsonNull) {
                assertNull("fix at $t should have no features", features)
            } else {
                val want = expectedFeatures.asJsonArray.map { it.asDouble }
                assertTrue("fix at $t has no features", features != null)
                for (i in want.indices) assertEquals("feature $i at $t", want[i], features!![i], 1e-9 + 1e-9 * Math.abs(want[i]))
                assertEquals("prediction at $t", o.get("predicted_error_m").asDouble, model.predictErrorM(features!!), 1e-6)
                formed++
            }
            tracker.observe(t, f)           // advances the previous-fix state exactly as the engine does
        }
        assertTrue("only $formed fixes formed features", formed > 50)
    }

    @Test
    fun observeReturnsTheSamePredictionAsTheDirectCallAndNothingForTheFirstFix() {
        val tracker = GnssTrustTracker(GnssTrustModel.fromJson(weights))
        val first = golden[0].asJsonObject
        assertNull(tracker.observe(first.get("t_ns").asLong, fix(first)))
        val second = golden[1].asJsonObject
        assertEquals(second.get("predicted_error_m").asDouble, tracker.observe(second.get("t_ns").asLong, fix(second))!!, 1e-6)
    }

    // ---- engine plumbing ---------------------------------------------------------------------------------

    private val header = Header("trust", Source.SIMULATION, "1.1.0")
    private fun rec(tNs: Long, id: String, d: Payload) = Record(header, Event(id, tNs, tNs, d))

    private class Outcome(val scaled: Long, val endErrorM: Double)

    private fun run(config: FusionConfig, model: GnssTrustModel?): Outcome {
        val originNs = 1_000_000_000L
        val engine = FusionNavigationEngine(config = config, gnssTrustModel = model)
        engine.initialize(EngineSession(header, "trust-s", originNs),
            rec(originNs, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)), InitializationMode.EVALUATION)
        val random = Random(3)
        var anchor: Vector3? = null
        var last = 0.0
        for (step in 1..(ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()) {
            val t = step * ScriptedDrive.STEP_S
            val tNs = originNs + (t * 1e9).toLong()
            val (accel, gyro) = ScriptedDrive.imu(t, random)
            engine.acceptImu(rec(tNs, "a$tNs", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH)))
            engine.acceptImu(rec(tNs, "g$tNs", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH)))
            val inOutage = t >= ScriptedDrive.OUTAGE_FROM_S && t < ScriptedDrive.OUTAGE_TO_S
            if (step % 100 == 0 && !inOutage) {
                val (ne, nn) = ScriptedDrive.fixNoise(random)
                val truth = ScriptedDrive.position(t)
                if (anchor == null) anchor = Vector3(truth.x + ne, truth.y + nn, 0.0)
                val g = Geodesy.geodeticFromEnu(ScriptedDrive.ANCHOR, Vector3(truth.x + ne, truth.y + nn, 0.0))
                engine.acceptGnss(rec(tNs, "n$tNs", GnssMeasurement(g.latitudeDeg, g.longitudeDeg,
                    ScriptedDrive.ANCHOR.altitudeM, AltitudeReference.ELLIPSOID, ScriptedDrive.speed(t),
                    ScriptedDrive.headingDeg(t), ScriptedDrive.FIX_SIGMA_M, 2.0 * ScriptedDrive.FIX_SIGMA_M, 20L, "gps", null)))
            }
            for (r in engine.drain()) {
                val nav = r.event.data as? NavigationState ?: continue
                val pos = nav.position_enu_m ?: continue
                val tt = (r.event.t_ns - originNs) / 1e9
                if (tt >= ScriptedDrive.OUTAGE_FROM_S && tt < ScriptedDrive.OUTAGE_TO_S) {
                    val o = anchor ?: Vector3(0.0, 0.0, 0.0)
                    val truth = ScriptedDrive.position(tt)
                    last = hypot(o.x + pos.x - truth.x, o.y + pos.y - truth.y)
                }
            }
        }
        return Outcome(engine.gnssTrustScaledFixes, last)
    }

    @Test
    fun theFlagIsOffByDefaultAndWhenOnTheModelScalesTheFixVariances() {
        assertEquals(false, FusionConfig().gnssTrustEnabled)
        val model = GnssTrustModel.fromJson(weights)
        val off = run(FusionConfig(), model)
        val on = run(FusionConfig(gnssTrustEnabled = true), model)
        println("trust plumbing: flag off scaled ${off.scaled} fixes, end error %.2f m; flag on scaled ${on.scaled} fixes, end error %.2f m".format(off.endErrorM, on.endErrorM))
        assertEquals("flag off must not touch any fix", 0L, off.scaled)
        assertTrue("flag on scaled ${on.scaled} fixes", on.scaled > 10)
        assertTrue("the setting changed nothing (${off.endErrorM} vs ${on.endErrorM})", Math.abs(off.endErrorM - on.endErrorM) > 0.001)
    }
}
