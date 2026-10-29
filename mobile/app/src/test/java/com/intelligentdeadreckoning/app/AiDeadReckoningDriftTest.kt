package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/**
 * Regression guard for the scripted 20-second GNSS outage (240 m driven). It measures the drift the
 * engine really has at the end of the outage, and it does NOT claim the PS #26168 gate (under 10 %).
 *
 * History: an earlier version of this test reported 1.44 % because its outage window included the
 * state published at t = 50.0 s, which already contains the first returning GNSS fix (about 3 m of
 * GPS noise). Measured strictly before that fix, the engine drifts about 30 % on this scenario with or
 * without the AI model (see SyntheticOutageMeasurementTest and reports/synthetic_outage_measured.json).
 */
class AiDeadReckoningDriftTest {

    private val header = Header("ai-outage-eval", Source.SIMULATION, "1.1.0")
    private val originNs = 1_000_000_000L

    private val calibration = CalibrationResult(
        id = "cal-evaluation",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.9,
    )

    private fun record(tNs: Long, id: String, data: Payload) =
        Record(header, Event(id, tNs, tNs, data))

    private fun imuRecord(tNs: Long, id: String, sensor: Sensor, unit: ImuUnit, xyz: Vector3) =
        record(tNs, id, ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE, unit, xyz, SensorAccuracy.HIGH))

    private fun fixRecord(tNs: Long, id: String, latitudeDeg: Double, longitudeDeg: Double, speed: Double, bearing: Double) =
        record(
            tNs, id,
            GnssMeasurement(
                latitude_deg = latitudeDeg,
                longitude_deg = longitudeDeg,
                altitude_m = ScriptedDrive.ANCHOR.altitudeM,
                altitude_reference = AltitudeReference.ELLIPSOID,
                speed_m_s = speed,
                bearing_deg = bearing,
                horizontal_accuracy_m = ScriptedDrive.FIX_SIGMA_M,
                vertical_accuracy_m = 2.0 * ScriptedDrive.FIX_SIGMA_M,
                satellites_used = 20L,
                provider = "gps",
                utc_ms = null,
            ),
        )

    private fun runOutageSimulation(aiEnabled: Boolean): OutageResults {
        val config = FusionConfig(
            motionConstraintsEnabled = true,
            aiSpeedEstimationEnabled = aiEnabled,
            aiSpeedUpdateIntervalS = 0.1,
            aiSpeedMinGnssStalenessS = 1.0,
        )
        val engine = FusionNavigationEngine(
            config = config,
            publicationIntervalNs = 100_000_000L,
        )
        engine.initialize(
            EngineSession(header, "session-ai", originNs),
            record(originNs, "cal", calibration),
            InitializationMode.EVALUATION,
        )

        val random = Random(42)
        val steps = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()

        var maxOutageErrorM = 0.0
        var endOutageErrorM = 0.0
        val outageDistanceM = (ScriptedDrive.OUTAGE_TO_S - ScriptedDrive.OUTAGE_FROM_S) * ScriptedDrive.SPEED_M_S // 240.0 m

        var publishedCount = 0
        var anchorEnu: Vector3? = null

        for (step in 1..steps) {
            val tS = step * ScriptedDrive.STEP_S
            val tNs = originNs + (tS * 1e9).toLong()

            val (accel, gyro) = ScriptedDrive.imu(tS, random)
            engine.acceptImu(imuRecord(tNs, "a$tNs", Sensor.ACCELEROMETER, ImuUnit.METRES_PER_SECOND_SQUARED, accel))
            engine.acceptImu(imuRecord(tNs, "g$tNs", Sensor.GYROSCOPE, ImuUnit.RADIANS_PER_SECOND, gyro))

            // Outage between 30s and 50s
            val inOutage = tS >= ScriptedDrive.OUTAGE_FROM_S && tS < ScriptedDrive.OUTAGE_TO_S
            if (step % 100 == 0 && !inOutage) {
                val (noiseEast, noiseNorth) = ScriptedDrive.fixNoise(random)
                val truthPos = ScriptedDrive.position(tS)
                if (anchorEnu == null) anchorEnu = Vector3(truthPos.x + noiseEast, truthPos.y + noiseNorth, 0.0)

                val geodetic = Geodesy.geodeticFromEnu(
                    ScriptedDrive.ANCHOR,
                    Vector3(truthPos.x + noiseEast, truthPos.y + noiseNorth, 0.0),
                )
                engine.acceptGnss(
                    fixRecord(
                        tNs, "n$tNs",
                        geodetic.latitudeDeg, geodetic.longitudeDeg,
                        ScriptedDrive.speed(tS), ScriptedDrive.headingDeg(tS),
                    ),
                )
            }

            // Drain published outputs
            val published = engine.drain().toList()
            for (rec in published) {
                val nav = rec.event.data as? NavigationState ?: continue
                val pos = nav.position_enu_m ?: continue
                publishedCount++
                val curTimeS = (rec.event.t_ns - originNs) / 1e9
                if (curTimeS >= ScriptedDrive.OUTAGE_FROM_S && curTimeS < ScriptedDrive.OUTAGE_TO_S) {
                    val truthPos = ScriptedDrive.position(curTimeS)
                    val origin = anchorEnu ?: Vector3(0.0, 0.0, 0.0)
                    val filterX = origin.x + pos.x
                    val filterY = origin.y + pos.y
                    val errorM = hypot(filterX - truthPos.x, filterY - truthPos.y)
                    if (errorM > maxOutageErrorM) maxOutageErrorM = errorM
                    endOutageErrorM = errorM
                }
            }
        }

        val driftPercentage = (endOutageErrorM / outageDistanceM) * 100.0
        return OutageResults(maxOutageErrorM, endOutageErrorM, outageDistanceM, driftPercentage, publishedCount)
    }

    data class OutageResults(
        val maxErrorM: Double,
        val endErrorM: Double,
        val outageDistanceM: Double,
        val driftPercentage: Double,
        val publishedCount: Int,
    )

    @Test
    fun outageDriftIsMeasuredBeforeTheFirstReturningFixAndStaysBounded() {
        val aiRun = runOutageSimulation(aiEnabled = true)

        println(
            String.format(
                "AI-Assisted Fusion: Outage Distance = %.1f m | End Error = %.2f m | Max Error = %.2f m | Drift = %.2f%%",
                aiRun.outageDistanceM,
                aiRun.endErrorM,
                aiRun.maxErrorM,
                aiRun.driftPercentage,
            ),
        )

        assertTrue("Evaluation must publish navigation states", aiRun.publishedCount > 500)
        // Regression bound, not the PS gate: the measured value is about 31 %.
        assertTrue(
            "Outage drift regressed past 40 % (was ${aiRun.driftPercentage}%)",
            aiRun.driftPercentage < 40.0,
        )
        assertTrue(
            "A measured drift this low means the window includes the returning fix again (was ${aiRun.driftPercentage}%)",
            aiRun.driftPercentage > 10.0,
        )
    }

    @Test
    fun aiModelDoesNotWorsenOutageAccuracyComparedWithTheConstraintsBaseline() {
        val baseline = runOutageSimulation(aiEnabled = false)
        val aiRun = runOutageSimulation(aiEnabled = true)

        println(
            String.format(
                "Comparison: Baseline Outage End Error = %.2f m (%.2f%%) vs AI Outage End Error = %.2f m (%.2f%%)",
                baseline.endErrorM,
                baseline.driftPercentage,
                aiRun.endErrorM,
                aiRun.driftPercentage,
            ),
        )

        // The model is not expected to help on this constant-speed scenario; it must not hurt.
        assertTrue(
            "AI drift (${aiRun.driftPercentage}%) is worse than the baseline (${baseline.driftPercentage}%)",
            aiRun.driftPercentage <= baseline.driftPercentage + 1.0,
        )
    }
}
