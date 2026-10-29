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
import com.intelligentdeadreckoning.contracts.v1.NavigationState
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
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Measures four arms on the scripted synthetic drive and writes what it measured, so the shipped
 * evaluation report is generated from a run and never typed in (tools/generate_ai_evaluation_report.py
 * reads the file this test writes: mobile/app/build/synthetic_outage_measured.json).
 *
 * Arms: classical INS (one alignment fix, then no GNSS at all), classical fusion (GNSS, no motion
 * constraints), fusion with constraints, and constraints plus Model A. The scripted drive is a straight
 * cruise at 12 m/s with one turn, so these numbers describe that scenario only.
 */
class SyntheticOutageMeasurementTest {
    private val header = Header("synthetic-outage-measured", Source.SIMULATION, "1.1.0")
    private val originNs = 1_000_000_000L

    private val calibration = CalibrationResult(
        id = "cal-evaluation", status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0), accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.9,
    )

    private data class Arm(val id: String, val label: String, val config: FusionConfig, val gnssAfterAlignment: Boolean)

    private data class Metrics(
        val endErrorM: Double, val maxErrorM: Double, val positionRmseM: Double, val speedMaeM_S: Double,
        val speedRmseM_S: Double, val headingErrorDeg: Double, val recoveryConvergenceS: Double?,
        val outageSamples: Int, val outputHz: Double, val allSamples: Int,
    )

    private fun record(tNs: Long, id: String, data: com.intelligentdeadreckoning.contracts.v1.Payload) =
        Record(header, Event(id, tNs, tNs, data))

    private fun imu(tNs: Long, id: String, sensor: Sensor, unit: ImuUnit, xyz: Vector3) =
        record(tNs, id, ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE, unit, xyz, SensorAccuracy.HIGH))

    private fun run(arm: Arm): Metrics {
        val engine = FusionNavigationEngine(config = arm.config, publicationIntervalNs = 100_000_000L)
        engine.initialize(EngineSession(header, "session-measured", originNs), record(originNs, "cal", calibration),
            InitializationMode.EVALUATION)
        val random = Random(42)
        val steps = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()
        var anchor: Vector3? = null
        var firstFix = true
        val outageErrors = ArrayList<Double>()
        val speedErrors = ArrayList<Double>()
        var endError = 0.0
        var maxError = 0.0
        var headingError = 0.0
        var recovered: Double? = null
        var all = 0
        var firstPublishS: Double? = null
        var lastPublishS = 0.0
        for (step in 1..steps) {
            val tS = step * ScriptedDrive.STEP_S
            val tNs = originNs + (tS * 1e9).toLong()
            val (accel, gyro) = ScriptedDrive.imu(tS, random)
            engine.acceptImu(imu(tNs, "a$tNs", Sensor.ACCELEROMETER, ImuUnit.METRES_PER_SECOND_SQUARED, accel))
            engine.acceptImu(imu(tNs, "g$tNs", Sensor.GYROSCOPE, ImuUnit.RADIANS_PER_SECOND, gyro))
            val inOutage = tS >= ScriptedDrive.OUTAGE_FROM_S && tS < ScriptedDrive.OUTAGE_TO_S
            if (step % 100 == 0 && !inOutage && (arm.gnssAfterAlignment || firstFix)) {
                firstFix = false
                val (ne, nn) = ScriptedDrive.fixNoise(random)
                val truth = ScriptedDrive.position(tS)
                if (anchor == null) anchor = Vector3(truth.x + ne, truth.y + nn, 0.0)
                val g = Geodesy.geodeticFromEnu(ScriptedDrive.ANCHOR, Vector3(truth.x + ne, truth.y + nn, 0.0))
                engine.acceptGnss(record(tNs, "n$tNs", GnssMeasurement(
                    g.latitudeDeg, g.longitudeDeg, ScriptedDrive.ANCHOR.altitudeM, AltitudeReference.ELLIPSOID,
                    ScriptedDrive.speed(tS), ScriptedDrive.headingDeg(tS), ScriptedDrive.FIX_SIGMA_M,
                    2.0 * ScriptedDrive.FIX_SIGMA_M, 20L, "gps", null)))
            }
            for (rec in engine.drain()) {
                val nav = rec.event.data as? NavigationState ?: continue
                val pos = nav.position_enu_m ?: continue
                val t = (rec.event.t_ns - originNs) / 1e9
                all++
                if (firstPublishS == null) firstPublishS = t
                lastPublishS = t
                val origin = anchor ?: Vector3(0.0, 0.0, 0.0)
                val truth = ScriptedDrive.position(t)
                val error = hypot(origin.x + pos.x - truth.x, origin.y + pos.y - truth.y)
                // Strictly before the first returning fix (delivered at exactly OUTAGE_TO_S): a state at
                // or after it already contains that fix and is not dead reckoning any more.
                if (t >= ScriptedDrive.OUTAGE_FROM_S && t < ScriptedDrive.OUTAGE_TO_S) {
                    outageErrors += error
                    endError = error
                    maxError = maxOf(maxError, error)
                    nav.velocity_enu_m_s?.let { speedErrors += hypot(it.x, it.y) - ScriptedDrive.speed(t) }
                    nav.heading_deg?.let {
                        headingError = abs((it - ScriptedDrive.headingDeg(t) + 540.0) % 360.0 - 180.0)
                    }
                }
                if (t > ScriptedDrive.OUTAGE_TO_S && recovered == null && error < ScriptedDrive.RECOVERY_THRESHOLD_M) {
                    recovered = t - ScriptedDrive.OUTAGE_TO_S
                }
            }
        }
        val span = (lastPublishS - (firstPublishS ?: 0.0)).coerceAtLeast(1e-9)
        return Metrics(
            endError, maxError, sqrt(outageErrors.sumOf { it * it } / outageErrors.size.coerceAtLeast(1)),
            speedErrors.sumOf { abs(it) } / speedErrors.size.coerceAtLeast(1),
            sqrt(speedErrors.sumOf { it * it } / speedErrors.size.coerceAtLeast(1)),
            headingError, recovered, outageErrors.size, (all - 1) / span, all,
        )
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    @Test
    fun measuresEachArmOnTheScriptedDriveAndWritesTheResult() {
        val arms = listOf(
            Arm("classical_ins", "Classical INS (one alignment fix, then no GNSS)",
                FusionConfig(motionConstraintsEnabled = false), gnssAfterAlignment = false),
            Arm("classical_fusion", "Classical fusion (GNSS+INS filter, no motion constraints)",
                FusionConfig(motionConstraintsEnabled = false), gnssAfterAlignment = true),
            Arm("fusion_constraints", "Fusion + vehicle-motion constraints",
                FusionConfig(motionConstraintsEnabled = true), gnssAfterAlignment = true),
            Arm("fusion_ai", "Fusion + constraints + Model A v1 forward speed",
                FusionConfig(motionConstraintsEnabled = true, aiSpeedEstimationEnabled = true), gnssAfterAlignment = true),
        )
        val distance = (ScriptedDrive.OUTAGE_TO_S - ScriptedDrive.OUTAGE_FROM_S) * ScriptedDrive.SPEED_M_S
        val results = arms.map { it to run(it) }
        val json = buildString {
            append("{\"description\":\"70 s scripted drive: straight cruise at 12 m/s with one turn, 20 s GNSS outage, ")
            append("seeded noise and fixed sensor biases\",\"produced_by\":\"SyntheticOutageMeasurementTest\",")
            append("\"outage_duration_s\":20.0,\"outage_distance_m\":${f(distance)},\"session_duration_s\":70.0,")
            append("\"recovery_threshold_m\":${f(ScriptedDrive.RECOVERY_THRESHOLD_M)},\"arms\":[")
            results.forEachIndexed { i, (arm, m) ->
                if (i > 0) append(',')
                append("{\"arm_id\":\"${arm.id}\",\"label\":\"${arm.label}\",\"end_error_m\":${f(m.endErrorM)},")
                append("\"max_error_m\":${f(m.maxErrorM)},\"drift_pct\":${f(100.0 * m.endErrorM / distance)},")
                append("\"position_rmse_m\":${f(m.positionRmseM)},\"speed_mae_m_s\":${f(m.speedMaeM_S)},")
                append("\"speed_rmse_m_s\":${f(m.speedRmseM_S)},\"heading_error_deg\":${f(m.headingErrorDeg)},")
                append("\"recovery_convergence_s\":${m.recoveryConvergenceS?.let { f(it) } ?: "null"},")
                append("\"outage_samples\":${m.outageSamples},\"output_hz\":${f(m.outputHz)},\"samples\":${m.allSamples}}")
            }
            append("]}\n")
        }
        val out = File("build/synthetic_outage_measured.json")
        out.parentFile.mkdirs()
        out.writeText(json)
        println("Measured arms: " + results.joinToString { (a, m) -> "${a.id}=${f(m.endErrorM)} m" })

        // Sanity only: every arm produced states through the outage, and rates are near the 10 Hz setting.
        for ((arm, m) in results) {
            assertTrue("${arm.id} must publish during the outage", m.outageSamples > 100)
            assertEquals("${arm.id} output rate", 10.0, m.outputHz, 1.0)
        }
    }
}
