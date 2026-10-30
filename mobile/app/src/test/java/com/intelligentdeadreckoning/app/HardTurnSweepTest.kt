package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * SYNTHETIC. A vehicle-frame drive at 14 m/s: 30 s straight north, then a sustained left circle at a swept yaw rate,
 * with GNSS withheld from 35 s to 65 s. Body frame x forward, y left, z up; specific force (0, v*w, g); gyro (0, 0, w).
 */
class HardTurnSweepTest {
    private val header = Header("hard-turn", Source.SIMULATION, "1.1.0")
    private val g = 9.80665
    private fun rec(tNs: Long, id: String, d: Payload) = Record(header, Event(id, tNs, tNs, d))

    class Result(val worstOutageM: Double, val finalM: Double, val headingErrAtOutageEndDeg: Double, val speedAtOutageEnd: Double)

    fun simulate(yaw: Double, config: FusionConfig, fixSigmaM: Double = 2.0, gyroBias: Double = 0.0, suppliedHeading: Boolean = true,
                 noise: Double = 1.0, seed: Long = 5): Result {
        val engine = FusionNavigationEngine(config = config, publicationIntervalNs = 100_000_000L,
            initialHeadingRad = if (suppliedHeading) 0.0 else null)
        engine.initialize(EngineSession(header, "s", 0L),
            rec(0L, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)), InitializationMode.EVALUATION)
        val rnd = Random(seed)
        val anchor = ScriptedDrive.ANCHOR
        val v = 14.0
        var heading = Math.PI / 2.0          // CCW from east; start due north
        var east = 0.0
        var north = 0.0
        var worst = 0.0
        var last = 0.0
        var hErr = 0.0
        var spd = 0.0
        var anchorEn: Vector3? = null
        for (i in 0 until 90 * 200) {
            val t = i / 200.0
            val ramp = ((t - 30.0) / 1.0).coerceIn(0.0, 1.0)
            val w = yaw * ramp
            heading += w / 200.0
            east += v * cos(heading) / 200.0
            north += v * sin(heading) / 200.0
            val tNs = Math.round(t * 1e9)
            engine.acceptImu(rec(tNs, "a$i", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, Vector3(rnd.nextGaussian() * 0.01 * noise, v * w + rnd.nextGaussian() * 0.01 * noise, g + rnd.nextGaussian() * 0.01 * noise), SensorAccuracy.HIGH)))
            engine.acceptImu(rec(tNs, "g$i", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, Vector3(0.0, 0.0, w + gyroBias + rnd.nextGaussian() * 0.0005 * noise), SensorAccuracy.HIGH)))
            if (i % 200 == 0 && !(t >= 35.0 && t < 65.0)) {
                val ne = rnd.nextGaussian() * fixSigmaM
                val nn = rnd.nextGaussian() * fixSigmaM
                if (anchorEn == null) anchorEn = Vector3(east + ne, north + nn, 0.0)
                val geo = Geodesy.geodeticFromEnu(anchor, Vector3(east + ne, north + nn, 0.0))
                val course = ((90.0 - Math.toDegrees(heading)) % 360.0 + 360.0) % 360.0
                engine.acceptGnss(rec(tNs, "n$i", GnssMeasurement(geo.latitudeDeg, geo.longitudeDeg, anchor.altitudeM,
                    AltitudeReference.ELLIPSOID, v, course, 3.0, 6.0, 12L, "gps", null)))
            }
            for (r in engine.drain()) {
                val s = r.event.data as? NavigationState ?: continue
                val p = s.position_enu_m ?: continue
                val o = anchorEn ?: Vector3(0.0, 0.0, 0.0)
                last = hypot(o.x + p.x - east, o.y + p.y - north)
                if (t in 35.0..65.0) {
                    worst = maxOf(worst, last)
                    val course = ((90.0 - Math.toDegrees(heading)) % 360.0 + 360.0) % 360.0
                    hErr = ((s.heading_deg ?: 0.0) - course + 540.0) % 360.0 - 180.0
                    val vel = s.velocity_enu_m_s
                    spd = if (vel == null) -1.0 else hypot(vel.x, vel.y)
                }
            }
        }
        return Result(worst, last, hErr, spd)
    }

    private val rates = listOf(-0.7, -0.3, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 1.0)
    private val configs = listOf(
        "default" to FusionConfig(),
        "velocity_sigma_0.2" to FusionConfig(gnssVelocitySigmaM_S = 0.2),
        "nhc_yaw_gate_2.0" to FusionConfig(nhcMaxYawRateRad_S = 2.0),
        "velocity_sigma_0.2_and_nhc_yaw_gate_2.0" to FusionConfig(gnssVelocitySigmaM_S = 0.2, nhcMaxYawRateRad_S = 2.0),
    )

    /** Worst outage error in metres by configuration and signed yaw rate; also written to build/hard_turn_sweep.json. */
    private val table: Map<String, Map<Double, Result>> by lazy {
        configs.associate { (name, cfg) -> name to rates.associateWith { simulate(it, cfg) } }
    }

    @Test
    fun writeTheSweep() {
        val json = StringBuilder("{\"description\":\"SYNTHETIC vehicle-frame circle sweep: 14 m/s, 30 s straight then a sustained circle at the stated yaw rate (negative = right), GNSS withheld 35-65 s, heading supplied, declared zero accelerometer bias, noisy fixes (2 m).\",\"unit\":\"worst horizontal error during the outage, metres\",\"results\":{")
        json.append(table.entries.joinToString(",") { (name, byRate) ->
            "\"$name\":{" + byRate.entries.joinToString(",") { (w, r) -> "\"$w\":%.1f".format(java.util.Locale.ROOT, r.worstOutageM) } + "}"
        })
        json.append("}}")
        val out = java.io.File("build/hard_turn_sweep.json")
        out.parentFile.mkdirs()
        out.writeText(json.toString())
        table.forEach { (name, byRate) -> println("SWEEP %-42s %s".format(name, byRate.entries.joinToString(" ") { "%+.1f:%.0f".format(it.key, it.value.worstOutageM) })) }
    }

    private fun median(v: List<Double>) = v.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2.0 }
    private val seeds = 1L..12L

    /** Earlier single-seed runs showed 41 m for a right turn and 9 m for a left turn with only the gate raised. */
    @Test
    fun theLeftRightGapWithOnlyTheGateRaisedIsNoiseNotAnAsymmetry() {
        val cfg = FusionConfig(nhcMaxYawRateRad_S = 2.0)
        for (w in listOf(0.7, 1.0)) {
            val left = seeds.map { simulate(w, cfg, seed = it).worstOutageM }
            val right = seeds.map { simulate(-w, cfg, seed = it).worstOutageM }
            println("SEEDS gate_only %.1f left %s | right %s".format(w, left.joinToString(" ") { "%.0f".format(it) }, right.joinToString(" ") { "%.0f".format(it) }))
            val ratio = median(right) / median(left)
            org.junit.Assert.assertTrue("median right/left at $w rad/s = $ratio", ratio in 0.5..2.0)
            org.junit.Assert.assertTrue("a single seed spans a wide range", left.max() > 3.0 * left.min())
        }
    }

    @Test
    fun withTheTightVelocityWeightingBothTurnDirectionsStayUnderFifteenMetresOnEverySeed() {
        val cfg = FusionConfig(gnssVelocitySigmaM_S = 0.2)
        for (w in listOf(0.7, -0.7, 1.0, -1.0)) {
            val worst = seeds.maxOf { simulate(w, cfg, seed = it).worstOutageM }
            println("SEEDS sigma_0.2 %+.1f worst over seeds %.1f".format(w, worst))
            org.junit.Assert.assertTrue("yaw $w worst $worst m", worst < 15.0)
        }
    }

    @Test
    fun theTightVelocityWeightingKeepsEveryYawRateInTheSweepWithinFifteenMetres() {
        val worst = table.getValue("velocity_sigma_0.2").values.maxOf { it.worstOutageM }
        org.junit.Assert.assertTrue("worst over the sweep $worst m", worst < 15.0)
    }

    @Test
    fun theDefaultWeightingFailsAtLowYawRatesWhichIsTheDocumentedFragility() {
        val d = table.getValue("default")
        org.junit.Assert.assertTrue("0.2 rad/s ${d.getValue(0.2).worstOutageM}", d.getValue(0.2).worstOutageM > 100.0)
        org.junit.Assert.assertTrue("0.3 rad/s ${d.getValue(0.3).worstOutageM}", d.getValue(0.3).worstOutageM > 100.0)
    }

    @Test
    fun aboveTheNhcYawGateTheGateIsWhatKeepsTheDefaultAtTensOfMetres() {
        val d = table.getValue("default")
        val g = table.getValue("nhc_yaw_gate_2.0")
        org.junit.Assert.assertTrue("default at 1.0: ${d.getValue(1.0).worstOutageM}", d.getValue(1.0).worstOutageM > 25.0)
        org.junit.Assert.assertTrue("gate 2.0 at 1.0: ${g.getValue(1.0).worstOutageM}", g.getValue(1.0).worstOutageM < d.getValue(1.0).worstOutageM / 2.0)
    }
}
