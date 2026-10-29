package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.matching.RoadGraph
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
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Map matching evaluated on real Hyderabad road geometry (the shipped OSM road graph).
 *
 * A simulated vehicle drives routes built from that graph at 12 m/s: the truth path is the road
 * centreline with its corners rounded (averaged over +-10 m) and shifted 1.5 m to the right as a lane
 * offset, so the map is a good but imperfect description of the truth. Sensors are simulated with the
 * same declared noise and biases as the scripted drive; GNSS is 1 Hz with 3 m noise and is withheld for a 30 s
 * and a 60 s outage. The same noise sequence feeds every arm.
 *
 * This is synthetic motion on real road geometry. It is not a recorded drive, and a real vehicle's
 * deviation from the map will differ. It measures what the matcher and feedback contribute, nothing more.
 * Results are written to build/map_matching_measured.json for tools/sync_map_matching_eval.py.
 */
class HyderabadMapMatchingEvalTest {
    private val header = Header("hyd-map-eval", Source.SIMULATION, "1.1.0")
    private val originNs = 1_000_000_000L
    private val speed = 12.0
    private val dt = 0.01
    private val conditions = listOf("lane1.5m" to 1.5, "lane6m" to 6.0)    // truth offset from the mapped centreline
    private val major = setOf("trunk", "primary", "secondary", "tertiary", "trunk_link", "primary_link", "secondary_link", "tertiary_link")
    private val drivable = setOf("trunk", "primary", "secondary", "tertiary", "trunk_link", "primary_link",
        "secondary_link", "tertiary_link", "residential", "unclassified")

    private data class Outage(val fromS: Double, val toS: Double)
    private val outages = listOf(Outage(60.0, 90.0), Outage(130.0, 190.0))
    private val totalS = 215.0
    private val seeds = listOf(2026L, 7L, 99L)                                          // independent sensor-noise draws

    private data class Start(val name: String, val lat: Double, val lon: Double)
    private val starts = listOf(
        Start("hitec-city", 17.4435, 78.3772), Start("gachibowli", 17.4400, 78.3489),
        Start("banjara-hills", 17.4126, 78.4482), Start("secunderabad", 17.4399, 78.4983),
        Start("old-city", 17.3616, 78.4747), Start("madhapur", 17.4486, 78.3908),
        Start("kukatpally", 17.4948, 78.3996), Start("begumpet", 17.4437, 78.4672),
        Start("tarnaka", 17.4284, 78.5381), Start("mehdipatnam", 17.3950, 78.4380),
    )

    private class Arm(val id: String, val label: String, val config: FusionConfig)

    private val arms = listOf(
        Arm("no_map", "Constraints only (no map)", FusionConfig()),
        Arm("greedy_heading", "Greedy matcher, heading feedback", FusionConfig(mapHeadingFeedbackEnabled = true)),
        Arm("hmm_heading", "HMM matcher, heading feedback",
            FusionConfig(mapHeadingFeedbackEnabled = true, hmmMapMatchingEnabled = true)),
        Arm("hmm_heading_crosstrack", "HMM matcher, heading + cross-track",
            FusionConfig(mapHeadingFeedbackEnabled = true, hmmMapMatchingEnabled = true, mapCrossTrackEnabled = true)),
        Arm("hmm_crosstrack", "HMM matcher, cross-track only",
            FusionConfig(hmmMapMatchingEnabled = true, mapCrossTrackEnabled = true)),
    )

    // ---- route construction from the real graph -------------------------------------------------------

    private fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val east = (b.second - a.second) * cos(Math.toRadians(a.first))
        val north = b.first - a.first
        return atan2(east, north)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % (2 * Math.PI)
        if (d <= -Math.PI) d += 2 * Math.PI
        if (d > Math.PI) d -= 2 * Math.PI
        return d
    }

    private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val (mLat, mLon) = Geodesy.radii(Math.toRadians(a.first)).let { (rE, rN) ->
            (rN * Math.PI / 180.0) to (rE * cos(Math.toRadians(a.first)) * Math.PI / 180.0)
        }
        return hypot((b.second - a.second) * mLon, (b.first - a.first) * mLat)
    }

    private fun geometry(graph: RoadGraph, edge: Int): List<Pair<Double, Double>> {
        val (lat, lon) = graph.decodeGeometry(edge)
        return lat.indices.map { lat[it] to lon[it] }
    }

    private fun walk(graph: RoadGraph, seed: List<Pair<Double, Double>>, firstEdge: Int, targetM: Double): List<Pair<Double, Double>> {
        val points = ArrayList(seed)
        val used = HashSet<Int>().also { it.add(firstEdge) }
        var length = points.zipWithNext { a, b -> metres(a, b) }.sum()
        while (length < targetM) {
            val end = points.last()
            val incoming = bearing(points[points.size - 2], end)
            var bestTurn = Double.MAX_VALUE
            var bestPoints: List<Pair<Double, Double>>? = null
            var bestEdge = -1
            for (e in graph.candidatesNear(end.first, end.second, 5.0)) {
                if (e in used || graph.roadClass(e) !in drivable) continue
                val g = geometry(graph, e)
                val oriented = when {
                    metres(g.first(), end) < 3.0 -> g
                    metres(g.last(), end) < 3.0 -> g.reversed()
                    else -> continue
                }
                if (oriented.size < 2) continue
                val turn = abs(angleDiff(incoming, bearing(oriented[0], oriented[1])))
                if (turn > Math.toRadians(150.0)) continue
                if (turn < bestTurn) { bestTurn = turn; bestPoints = oriented; bestEdge = e }
            }
            val next = bestPoints ?: break
            used.add(bestEdge)
            for (p in next.drop(1)) {
                length += metres(points.last(), p)
                points.add(p)
            }
        }
        return points
    }

    private fun routeLength(r: List<Pair<Double, Double>>) = r.zipWithNext { a, b -> metres(a, b) }.sum()

    /** Follows the least-turning drivable edge from the road nearest the start, in whichever direction runs longer. */
    private fun buildRoute(graph: RoadGraph, start: Start, targetM: Double): List<Pair<Double, Double>> {
        val near = graph.candidatesNear(start.lat, start.lon, 400.0).filter { graph.roadClass(it) in drivable }
        val pool = near.filter { graph.roadClass(it) in major }.ifEmpty { near }
        val first = pool.minByOrNull { graph.project(it, start.lat, start.lon).distanceM } ?: return emptyList()
        val g = geometry(graph, first)
        return listOf(g, g.reversed()).map { walk(graph, it, first, targetM) }.maxByOrNull { routeLength(it) } ?: emptyList()
    }

    private class Path(val east: DoubleArray, val north: DoubleArray, val heading: DoubleArray, val yawPerM: DoubleArray)

    /** Centreline resampled every metre, corners rounded, shifted right by the lane offset. */
    private fun truthPath(route: List<Pair<Double, Double>>, anchor: GeodeticAnchor, laneOffsetM: Double): Path {
        val enu = route.map { Geodesy.enuFromGeodetic(anchor, it.first, it.second, anchor.altitudeM) }
        val e = ArrayList<Double>(); val n = ArrayList<Double>()
        for (i in 0 until enu.size - 1) {
            val len = hypot(enu[i + 1].x - enu[i].x, enu[i + 1].y - enu[i].y)
            val steps = maxOf(1, len.toInt())
            for (k in 0 until steps) {
                val f = k.toDouble() / steps
                e.add(enu[i].x + f * (enu[i + 1].x - enu[i].x)); n.add(enu[i].y + f * (enu[i + 1].y - enu[i].y))
            }
        }
        e.add(enu.last().x); n.add(enu.last().y)
        val size = e.size
        fun smooth(a: List<Double>, half: Int) = DoubleArray(size) { i ->
            val lo = maxOf(0, i - half); val hi = minOf(size - 1, i + half)
            (lo..hi).sumOf { a[it] } / (hi - lo + 1)
        }
        val se = smooth(e, 10); val sn = smooth(n, 10)
        val heading = DoubleArray(size)
        for (i in 0 until size) {
            val a = maxOf(0, i - 3); val b = minOf(size - 1, i + 3)
            heading[i] = atan2(se[b] - se[a], sn[b] - sn[a])
        }
        for (i in 1 until size) heading[i] = heading[i - 1] + angleDiff(heading[i - 1], heading[i])    // unwrap
        val oe = DoubleArray(size) { se[it] + laneOffsetM * cos(heading[it]) }
        val on = DoubleArray(size) { sn[it] - laneOffsetM * sin(heading[it]) }
        val hs = smooth(heading.toList(), 5)
        val yawPerM = DoubleArray(size) { i ->
            val a = maxOf(0, i - 5); val b = minOf(size - 1, i + 5)
            (hs[b] - hs[a]) / maxOf(1, b - a)
        }
        return Path(oe, on, hs, yawPerM)
    }

    private fun at(arr: DoubleArray, s: Double): Double {
        val i = s.toInt().coerceIn(0, arr.size - 2)
        val f = (s - i).coerceIn(0.0, 1.0)
        return arr[i] + f * (arr[i + 1] - arr[i])
    }

    // ---- one simulation -----------------------------------------------------------------------------

    private class Outcome(val endErrorM: DoubleArray, val rmseM: DoubleArray)

    private fun record(tNs: Long, id: String, data: Payload) = Record(header, Event(id, tNs, tNs, data))

    private fun simulate(graph: RoadGraph, path: Path, anchor: GeodeticAnchor, arm: Arm, seed: Long): Outcome {
        val engine = FusionNavigationEngine(
            config = arm.config, initialHeadingRad = at(path.heading, 0.0), roadGraph = graph,
        )
        engine.initialize(
            EngineSession(header, "hyd-session", originNs),
            record(originNs, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)),
            InitializationMode.EVALUATION,
        )
        val random = Random(seed)
        val accelSigma = FusionConfig().accelNoiseDensityM_S2_RT_HZ / sqrt(dt)
        val gyroSigma = FusionConfig().gyroNoiseDensityRad_S_RT_HZ / sqrt(dt)
        val gravity = Geodesy.normalGravity(Math.toRadians(anchor.latitudeDeg), anchor.altitudeM)
        val steps = (totalS / dt).toInt()
        val errs = outages.map { ArrayList<Double>() }
        val endErr = DoubleArray(outages.size) { Double.NaN }
        var origin: Vector3? = null
        for (step in 1..steps) {
            val t = step * dt
            val tNs = originNs + (t * 1e9).toLong()
            val s = speed * t
            val yawRate = -at(path.yawPerM, s) * speed                    // counter-clockwise positive
            engine.acceptImu(record(tNs, "a$tNs", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED,
                Vector3(0.02 + random.nextGaussian() * accelSigma, speed * yawRate + 0.02 + random.nextGaussian() * accelSigma,
                    gravity + random.nextGaussian() * accelSigma), SensorAccuracy.HIGH)))
            engine.acceptImu(record(tNs, "g$tNs", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, Vector3(0.0, 0.0, yawRate + 0.001 + random.nextGaussian() * gyroSigma),
                SensorAccuracy.HIGH)))
            val inOutage = outages.any { t >= it.fromS && t < it.toS }
            if (step % 100 == 0 && !inOutage) {
                val truth = Vector3(at(path.east, s), at(path.north, s), 0.0)
                val noisy = Vector3(truth.x + random.nextGaussian() * 3.0, truth.y + random.nextGaussian() * 3.0, 0.0)
                if (origin == null) origin = noisy
                val g = Geodesy.geodeticFromEnu(anchor, noisy)
                val course = (Math.toDegrees(at(path.heading, s)) % 360.0 + 360.0) % 360.0
                engine.acceptGnss(record(tNs, "n$tNs", GnssMeasurement(g.latitudeDeg, g.longitudeDeg, anchor.altitudeM,
                    AltitudeReference.ELLIPSOID, speed, course, 3.0, 6.0, 20L, "gps", null)))
            } else if (step % 100 == 0 && random.nextGaussian() * 0 == 0.0) {
                random.nextGaussian(); random.nextGaussian()              // keep the noise sequence identical across outages
            }
            for (rec in engine.drain()) {
                val nav = rec.event.data as? NavigationState ?: continue
                val pos = nav.position_enu_m ?: continue
                val o = origin ?: continue
                val tt = (rec.event.t_ns - originNs) / 1e9
                outages.forEachIndexed { k, w ->
                    if (tt >= w.fromS && tt < w.toS) {
                        val ts = speed * tt
                        val e = hypot(o.x + pos.x - at(path.east, ts), o.y + pos.y - at(path.north, ts))
                        errs[k].add(e); endErr[k] = e
                    }
                }
            }
        }
        return Outcome(endErr, DoubleArray(outages.size) { k -> sqrt(errs[k].sumOf { it * it } / errs[k].size.coerceAtLeast(1)) })
    }

    // ---- the evaluation ------------------------------------------------------------------------------

    @Test
    fun evaluatesMapMatchingArmsOnHyderabadRoutes() {
        val graphFile = File("src/main/assets/roadgraph/hyderabad-v1/road-graph.json.gz")
        assertTrue("Hyderabad graph missing at ${graphFile.absolutePath}", graphFile.exists())
        val graph = RoadGraph.load(graphFile)
        val needM = speed * totalS + 100.0
        val results = HashMap<String, MutableList<Triple<String, Double, Double>>>()      // arm|condition -> (outage, end error, drift %)
        val routesUsed = ArrayList<String>()
        for (start in starts) {
            val route = buildRoute(graph, start, needM + 300.0)
            val lengthM = routeLength(route)
            if (lengthM < needM) { println("route ${start.name}: only ${"%.0f".format(lengthM)} m, skipped"); continue }
            val anchor = GeodeticAnchor(route[0].first, route[0].second, 500.0, true)
            routesUsed += "${start.name} (${"%.0f".format(lengthM)} m)"
            for ((condition, offset) in conditions) {
                val path = truthPath(route, anchor, offset)
                for (arm in arms) for (seed in seeds) {
                    val out = simulate(graph, path, anchor, arm, seed)
                    outages.forEachIndexed { k, w ->
                        val distance = speed * (w.toS - w.fromS)
                        results.getOrPut("${arm.id}|$condition") { ArrayList() }.add(
                            Triple("${start.name}#$seed/${w.toS - w.fromS}s", out.endErrorM[k], 100.0 * out.endErrorM[k] / distance))
                    }
                }
            }
        }
        assertTrue("need at least four routes, had ${routesUsed.size}", routesUsed.size >= 4)

        fun median(v: List<Double>) = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        fun f(v: Double) = String.format(Locale.ROOT, "%.4f", v)
        val json = buildString {
            append("{\"description\":\"Simulated vehicle at 12 m/s (constant speed) on routes built from the shipped real Hyderabad OSM road graph; ")
            append("truth is the centreline with rounded corners shifted right by the lane offset of each condition; synthetic sensors with declared noise and biases; ")
            append("1 Hz GNSS with 3 m noise withheld for a 30 s and a 60 s outage; three noise seeds\",")
            append("\"produced_by\":\"HyderabadMapMatchingEvalTest\",\"routes\":[")
            append(routesUsed.joinToString(",") { "\"$it\"" }).append("],\"arms\":[")
            var first = true
            for ((condition, _) in conditions) for (arm in arms) {
                val rows = results["${arm.id}|$condition"]!!
                if (!first) append(',')
                first = false
                append("{\"arm_id\":\"${arm.id}\",\"condition\":\"$condition\",\"label\":\"${arm.label}\",\"outages\":${rows.size},")
                for (len in listOf("30", "60")) {
                    val sel = rows.filter { it.first.endsWith("/$len.0s") }
                    append("\"drift_pct_median_${len}s\":${f(median(sel.map { it.third }))},")
                    append("\"end_error_m_median_${len}s\":${f(median(sel.map { it.second }))},")
                    append("\"share_under_10pct_${len}s\":${f(sel.count { it.third < 10.0 }.toDouble() / sel.size)},")
                }
                append("\"drift_pct_median_all\":${f(median(rows.map { it.third }))},")
                append("\"share_under_10pct_all\":${f(rows.count { it.third < 10.0 }.toDouble() / rows.size)}}")
            }
            append("]}\n")
        }
        val out = File("build/map_matching_measured.json")
        out.parentFile.mkdirs()
        out.writeText(json)
        for ((condition, _) in conditions) arms.forEach { arm ->
            val rows = results["${arm.id}|$condition"]!!
            println(String.format(Locale.ROOT, "%-9s %-24s median drift %.1f%% (30 s %.1f%%, 60 s %.1f%%), under 10%%: %d/%d",
                condition, arm.id, median(rows.map { it.third }), median(rows.filter { it.first.endsWith("/30.0s") }.map { it.third }),
                median(rows.filter { it.first.endsWith("/60.0s") }.map { it.third }), rows.count { it.third < 10.0 }, rows.size))
        }
        for ((condition, _) in conditions) for (arm in arms) {
            assertEquals("${arm.id} $condition scored every outage", routesUsed.size * outages.size * seeds.size, results["${arm.id}|$condition"]!!.size)
        }
    }
}
