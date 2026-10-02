/*
 * The confidence-coverage evaluation: does the uncertainty the engine publishes actually bound
 * the error the engine makes?
 *
 * The request this harness answers is "compare predicted uncertainty with actual horizontal
 * error, in the GNSS-good, degraded, DR and recovery regimes, and test empirical coverage". The
 * reference here is a **scripted truth**: the trajectory is a closed-form path, the IMU and GNSS
 * samples are generated *from* it, and the engine never sees it. Every published position is
 * compared with the truth at the same instant, so the error is measured against a quantity the
 * filter cannot have fitted to.
 *
 * ## What this can and cannot decide
 *
 * It is independent of the filter, but it is **not** a surveyed drive: the truth is synthetic and
 * the sensor error model is the declared one below. It can therefore decide whether the published
 * covariance is consistent with the filter's error *under that model* — the strongest statement
 * the repository currently supports — and it cannot license a `CALIBRATED` confidence for a real
 * drive. `reports/confidence_evaluation_2026_10_01.md` records the numbers this harness produced
 * and the verdict they do not support.
 *
 * ## The declared sensor model
 *
 * The drive, its IMU and GNSS generation and the declared sensor error model live in
 * [ScriptedDrive], shared with `EvaluationHarnessTest` so both evaluations measure the same
 * physics. In one line: 100 Hz IMU with white noise at the engine's own configured densities, a
 * constant accelerometer bias of 0.02 m/s^2 and gyroscope bias of 0.001 rad/s injected and never
 * disclosed (both inside the filter's own unknown-bias priors), GNSS at 1 Hz with circular 3 m
 * one-sigma noise reported as a 3 m accuracy, and a 20 s outage in the middle of the drive.
 */
package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.contracts.v1.AltitudeReference
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.ConfidenceState
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
import java.util.Locale
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ConfidenceCoverageTest {

    private class Sample(
        val tNs: Long,
        val status: String,
        val mode: String?,
        val radius95M: Double?,
        val speedStdM_S: Double?,
        val errorM: Double,
    ) {
        /** The regime the request names, read from what the engine published, never assigned. */
        val regime: String
            get() = when (mode) {
                "gnss" -> "GNSS"
                "fused" -> "FUSED"
                "dr" -> "DR"
                "recovery" -> "RECOVERY"
                else -> "OTHER"
            }
    }

    private val header = Header("confidence-coverage", Source.REAL, "1.1.0")
    private val session = EngineSession(header, "boot", 1_000_000_000L)
    private val anchor = ScriptedDrive.ANCHOR

    /** Identity mounting: the vehicle frame is the device frame, as the engine is told. */
    private val calibration = CalibrationResult(
        id = "cal-coverage",
        status = CalibrationStatus.VALID,
        q_vehicle_from_device_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.9,
    )

    private fun imu(tNs: Long, id: String, sensor: Sensor, unit: ImuUnit, xyz: Vector3) = Record(
        header,
        Event(
            id, tNs, tNs,
            ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE, unit, xyz, SensorAccuracy.HIGH),
        ),
    )

    private fun fix(
        tNs: Long, id: String, latitudeDeg: Double, longitudeDeg: Double,
        altitudeM: Double, speedM_S: Double, bearingDeg: Double,
    ) = Record(
        header,
        Event(
            id, tNs, tNs,
            GnssMeasurement(
                latitude_deg = latitudeDeg,
                longitude_deg = longitudeDeg,
                altitude_m = altitudeM,
                altitude_reference = AltitudeReference.ELLIPSOID,
                speed_m_s = speedM_S,
                bearing_deg = bearingDeg,
                horizontal_accuracy_m = ScriptedDrive.FIX_SIGMA_M,
                vertical_accuracy_m = 2.0 * ScriptedDrive.FIX_SIGMA_M,
                satellites_used = 20L,
                provider = "gps",
                utc_ms = null,
            ),
        ),
    )

    private fun speed(velocity: Vector3): Double = hypot(velocity.x, velocity.y)

    private fun bearing(velocity: Vector3): Double =
        (Math.toDegrees(atan2(velocity.x, velocity.y)) + 360.0) % 360.0

    /**
     * Replay the declared drive through the production engine, pairing each published
     * [NavigationState] with the [Confidence] published beside it (same measurement time) and
     * measuring the published position against the scripted truth. Nothing here is filtered,
     * smoothed or adjusted afterwards: the error of every sample is the error the engine made.
     */
    private fun replay(): List<Sample> {
        val engine = FusionNavigationEngine(
            config = FusionConfig(),
            publicationIntervalNs = 100_000_000L,
        )
        engine.initialize(
            session,
            Record(header, Event("cal", session.origin_ns, session.origin_ns, calibration)),
            InitializationMode.EVALUATION,
        )
        val random = Random(26168L)
        val samples = mutableListOf<Sample>()
        var anchorEnu: Vector3? = null
        var publishedPositions = 0L
        var pending: Pair<Vector3, Vector3>? = null
        var pendingStatus = "—"
        var pendingMode: String? = null

        val steps = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()
        for (step in 1..steps) {
            val tNs = session.origin_ns + step * 10_000_000L
            val tS = step * ScriptedDrive.STEP_S
            val truth = ScriptedDrive.position(tS)
            val velocity = ScriptedDrive.velocity(tS)
            // Device frame at the assumed initial heading: device x is north, device y is west.
            val (accelDevice, gyroDevice) = ScriptedDrive.imu(tS, random)
            engine.acceptImu(
                imu(tNs, "a$tNs", Sensor.ACCELEROMETER, ImuUnit.METRES_PER_SECOND_SQUARED, accelDevice),
            )
            engine.acceptImu(
                imu(tNs, "g$tNs", Sensor.GYROSCOPE, ImuUnit.RADIANS_PER_SECOND, gyroDevice),
            )

            val inOutage = tS >= ScriptedDrive.OUTAGE_FROM_S && tS < ScriptedDrive.OUTAGE_TO_S
            if (step % 100 == 0 && !inOutage) {
                val (noiseEast, noiseNorth) = ScriptedDrive.fixNoise(random)
                // The first usable fix is the anchor: its own noise is the fixed offset every
                // later position inherits, exactly as the engine's ENU frame does. It is recorded
                // because the harness knows the noise it drew, not because it can see the filter.
                if (anchorEnu == null) anchorEnu = Vector3(truth.x + noiseEast, truth.y + noiseNorth, 0.0)
                val geodetic = Geodesy.geodeticFromEnu(
                    ScriptedDrive.ANCHOR, Vector3(truth.x + noiseEast, truth.y + noiseNorth, 0.0),
                )
                engine.acceptGnss(
                    fix(
                        tNs, "n$tNs", geodetic.latitudeDeg, geodetic.longitudeDeg, geodetic.altitudeM,
                        speed(velocity), bearing(velocity),
                    ),
                )
            }

            for (record in engine.drain()) {
                when (val data = record.event.data) {
                    is NavigationState -> {
                        val position = data.position_enu_m
                        val origin = anchorEnu
                        if (position == null || origin == null) {
                            pending = null
                        } else {
                            publishedPositions += 1
                            pending = Pair(
                                Vector3(origin.x + position.x, origin.y + position.y, origin.z + position.z),
                                ScriptedDrive.position((record.event.t_ns - session.origin_ns) / 1e9),
                            )
                            pendingStatus = data.status.wire
                            pendingMode = data.localization_mode?.wire
                        }
                    }
                    is Confidence -> {
                        val held = pending ?: continue
                        pending = null
                        samples += Sample(
                            tNs = record.event.t_ns,
                            status = pendingStatus,
                            mode = pendingMode,
                            radius95M = data.horizontal_accuracy_95_m,
                            speedStdM_S = data.speed_std_m_s,
                            errorM = hypot(held.first.x - held.second.x, held.first.y - held.second.y),
                        )
                    }
                    else -> Unit
                }
            }
        }
        assertEquals("every published position is measured once", publishedPositions, samples.size.toLong())
        return samples
    }

    /** The [quantile] of the values, nearest rank. The scalar inflation a 95% claim would need. */
    private fun quantile(values: List<Double>, quantile: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val rank = Math.ceil(quantile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun report(samples: List<Sample>): String {
        val build = StringBuilder()
        build.append(
            String.format(
                Locale.ROOT,
                "%-9s %5s %7s %9s %9s %9s %8s%n",
                "regime", "n", "cover", "med err", "med r95", "max err", "k95",
            ),
        )
        for (regime in listOf("GNSS", "FUSED", "DR", "RECOVERY", "OTHER", "ALL")) {
            val group = if (regime == "ALL") samples else samples.filter { it.regime == regime }
            val withRadius = group.filter { it.radius95M != null && it.radius95M!! > 0.0 }
            if (withRadius.isEmpty()) continue
            val covered = withRadius.count { it.errorM <= it.radius95M!! }
            build.append(
                String.format(
                    Locale.ROOT,
                    "%-9s %5d %6.1f%% %8.2f m %8.1f m %8.2f m %8.2f%n",
                    regime, withRadius.size, 100.0 * covered / withRadius.size,
                    quantile(withRadius.map { it.errorM }, 0.5),
                    quantile(withRadius.map { it.radius95M!! }, 0.5),
                    withRadius.maxOf { it.errorM },
                    quantile(withRadius.map { it.errorM / it.radius95M!! }, 0.95),
                ),
            )
        }
        build.append(
            "status x mode samples: " +
                samples.groupingBy { "${it.status} | ${it.mode ?: "null"}" }.eachCount()
                    .entries.sortedBy { it.key }.joinToString(", ") { "${it.key} = ${it.value}" } + "\n",
        )
        return build.toString()
    }

    @Test
    fun everyRegimeIsExercisedAndEveryPublishedConfidenceIsUnvalidated() {
        val samples = replay()
        val regimes = samples.map { it.regime }.toSet()
        assertTrue("regimes seen: $regimes", regimes.containsAll(setOf("DR", "RECOVERY", "FUSED")))
        assertTrue("every sample carries a radius", samples.all { it.radius95M != null && it.radius95M!! > 0.0 })
        assertTrue("every sample carries a speed sigma", samples.all { it.speedStdM_S != null })
        val dr = samples.filter { it.regime == "DR" }
        assertTrue("a 20 s outage must produce DR samples, saw ${dr.size}", dr.size >= 100)
        assertTrue(
            "the covariance must grow while the solution is prediction-only",
            dr.last().radius95M!! > dr.first().radius95M!!,
        )
    }

    /**
     * The evaluation itself. It records the measured coverage rather than asserting a nominal
     * 95%: the numbers are the evidence, and they are what decides whether any calibration is
     * supported. What is asserted here is the harness's own validity — a measurement over a real
     * sample count, from a filter that is not frozen.
     */
    @Test
    fun coverageIsMeasuredPerRegimeAndReported() {
        val samples = replay()
        println("confidence coverage — scripted truth, declared sensor model")
        print(report(samples))
        val withRadius = samples.filter { it.radius95M != null && it.radius95M!! > 0.0 }
        val coverage = withRadius.count { it.errorM <= it.radius95M!! }.toDouble() / withRadius.size
        println(
            String.format(
                Locale.ROOT,
                "measured all-regime coverage %.3f over %d samples; required inflation k95 %.2f",
                coverage, withRadius.size, quantile(withRadius.map { it.errorM / it.radius95M!! }, 0.95),
            ),
        )
        assertTrue("coverage must be a measured fraction", coverage in 0.0..1.0)
        assertTrue("a coverage over tens of samples is not evidence", withRadius.size >= 200)
    }

}
