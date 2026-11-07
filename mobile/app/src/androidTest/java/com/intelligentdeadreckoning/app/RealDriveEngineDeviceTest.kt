package com.intelligentdeadreckoning.app

import androidx.test.platform.app.InstrumentationRegistry
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.navigation.AppFusion
import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Codec
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot

/**
 * REAL driving through the app's own engine on the device. Each folder under `<app files>/real_drives/` holds `measurements.jsonl`: the first
 * minutes of a real phone-in-car trip (Google Smartphone Decimeter Challenge, CC BY-NC-SA 3.0 IGO; made by tools/gsdc_trim_for_device.py and pushed with
 * adb, never committed). The test runs the production calibrator and fusion configuration (AppFusion) on it, withholds GNSS for a 30 s and a 60 s
 * outage, and writes `report_<trip>.json` beside the data: calibration found, first position, publication rate, throughput, drift at the end of each
 * outage against the recording's own reference fixes. The sensors are REPLAYED, not live, and the vehicle is not this phone's, so this checks the
 * engine and calibrator on this hardware with real driving, not the phone's own sensors in a car. With no data pushed the test is skipped.
 */
class RealDriveEngineDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val earthRadius = 6_371_000.0

    private class Fix(val tNs: Long, val lat: Double, val lon: Double, val speed: Double)

    private class Outage(val startNs: Long, val endNs: Long)

    private fun readFixes(file: File): List<Fix> {
        val fixes = ArrayList<Fix>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.contains("\"gnss\"")) continue
                val record = Codec.decodeJson(line.toByteArray(Charsets.UTF_8))
                val g = record.event.data as? GnssMeasurement ?: continue
                fixes += Fix(record.event.t_ns, g.latitude_deg, g.longitude_deg, g.speed_m_s ?: 0.0)
            }
        }
        return fixes
    }

    private fun planOutages(fixes: List<Fix>, originNs: Long): List<Outage> {
        val out = ArrayList<Outage>()
        var earliest = originNs + 300_000_000_000L
        for (duration in listOf(30L, 60L)) {
            val start = fixes.firstOrNull { it.tNs >= earliest && it.speed >= 5.0 } ?: break
            val end = start.tNs + duration * 1_000_000_000L
            if (end > fixes.last().tNs) break
            out += Outage(start.tNs + 1, end)
            earliest = end + 180_000_000_000L
        }
        return out
    }

    private fun horizontalError(lat: Double, lon: Double, refLat: Double, refLon: Double) =
        hypot(Math.toRadians(lat - refLat) * earthRadius, Math.toRadians(lon - refLon) * earthRadius * cos(Math.toRadians(refLat)))

    private fun interpolate(fixes: List<Fix>, tNs: Long): Pair<Double, Double> {
        var lo = 0
        var hi = fixes.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (fixes[mid].tNs <= tNs) lo = mid else hi = mid }
        val a = fixes[lo]; val b = fixes[hi]
        val w = if (b.tNs == a.tNs) 0.0 else ((tNs - a.tNs).toDouble() / (b.tNs - a.tNs)).coerceIn(0.0, 1.0)
        return Pair(a.lat + w * (b.lat - a.lat), a.lon + w * (b.lon - a.lon))
    }

    private fun pathLength(fixes: List<Fix>, fromNs: Long, toNs: Long): Double {
        var d = 0.0
        val inside = fixes.filter { it.tNs in fromNs..toNs }
        for (i in 1 until inside.size) d += horizontalError(inside[i].lat, inside[i].lon, inside[i - 1].lat, inside[i - 1].lon)
        return d
    }

    private class Row(val tNs: Long, val lat: Double, val lon: Double, val status: NavigationStatus)

    private fun replay(tripDir: File): String {
        val measurements = File(tripDir, "measurements.jsonl")
        val fixes = readFixes(measurements)
        assertTrue("the trip needs GNSS reference fixes", fixes.size > 400)
        val sessionId = tripDir.name.take(100)
        val inputHeader = Header(sessionId, Source.REPLAY_REAL, "1.0.0")
        val outputHeader = Header(sessionId, Source.REPLAY_REAL, ENGINE_OUTPUT_CONTRACT_VERSION)
        val outages = ArrayList<Outage>()

        val engine = CalibratingFusionEngine(
            calibrator = AppFusion.calibrator(),
            fusionFactory = AppFusion.factory({ false }, { null }),
        )
        val rows = ArrayList<Row>()
        var calibrationId: String? = null
        var calibrationNs: Long? = null
        var originNs = 0L
        var initialized = false
        var imu = 0L
        var gnssOffered = 0L
        var gnssWithheld = 0L
        var failures = 0L
        val started = System.nanoTime()

        fun drain() {
            for (record in engine.drain()) {
                when (val data = record.event.data) {
                    is CalibrationResult -> if (data.status == CalibrationStatus.VALID && calibrationId == null) {
                        calibrationId = data.id; calibrationNs = record.event.t_ns
                    }
                    is NavigationState -> {
                        if (data.status == NavigationStatus.FAILED) failures++
                        val o = data.origin_wgs84_deg_m
                        val p = data.position_enu_m
                        if (o != null && p != null) {
                            val point = Geodesy.geodeticFromEnu(GeodeticAnchor(o.latitude_deg, o.longitude_deg, o.altitude_m, true), p)
                            rows += Row(record.event.t_ns, point.latitudeDeg, point.longitudeDeg, data.status)
                        }
                    }
                    else -> Unit
                }
            }
        }

        measurements.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val decoded = Codec.decodeJson(line.toByteArray(Charsets.UTF_8))
                val record = Record(inputHeader, Event(decoded.event.event_id, decoded.event.t_ns, decoded.event.received_ns, decoded.event.data))
                if (!initialized) {
                    originNs = record.event.t_ns
                    outages += planOutages(fixes, originNs)
                    val pending = Record(outputHeader, Event("0", originNs, originNs, CalibrationResult(
                        "device-replay-uncalibrated", CalibrationStatus.PENDING, null, null, null, null)))
                    engine.initialize(EngineSession(outputHeader, "device-replay", originNs), pending, InitializationMode.DEPLOYABLE)
                    initialized = true
                    drain()
                }
                when (record.event.data) {
                    is ImuMeasurement -> { engine.acceptImu(record); imu++ }
                    is GnssMeasurement -> {
                        if (outages.any { record.event.t_ns >= it.startNs && record.event.t_ns < it.endNs }) gnssWithheld++
                        else { engine.acceptGnss(record); gnssOffered++ }
                    }
                    else -> Unit
                }
                drain()
            }
        }
        engine.stop()
        drain()
        val wallS = (System.nanoTime() - started) / 1e9
        val driveS = (fixes.last().tNs - originNs) / 1e9

        // publication rate in a steady stretch (after the first outage's recovery would be cleaner; this window has GNSS throughout)
        val firstRow = rows.firstOrNull()
        val rateWindow = firstRow?.let { f -> rows.filter { it.tNs >= f.tNs + 20_000_000_000L && it.tNs < f.tNs + 80_000_000_000L } }.orEmpty()
        val rateHz = if (rateWindow.size > 1) (rateWindow.size - 1) / ((rateWindow.last().tNs - rateWindow.first().tNs) / 1e9) else Double.NaN

        val drift = outages.map { o ->
            val last = rows.lastOrNull { it.tNs > o.startNs && it.tNs < o.endNs }
            if (last == null) null else {
                val ref = interpolate(fixes, last.tNs)
                val err = horizontalError(last.lat, last.lon, ref.first, ref.second)
                val dist = pathLength(fixes, o.startNs, last.tNs)
                Triple((o.endNs - o.startNs) / 1_000_000_000L, err, if (dist > 1.0) 100.0 * err / dist else Double.NaN)
            }
        }
        val driftJson = drift.joinToString(",") { d ->
            if (d == null) "null" else String.format(Locale.ROOT, "{\"outage_s\":%d,\"end_error_m\":%.2f,\"drift_pct\":%.2f}", d.first, d.second, d.third)
        }
        val report = String.format(Locale.ROOT,
            "{\"trip\":\"%s\",\"device\":\"%s %s\",\"drive_s\":%.0f,\"imu_records\":%d,\"gnss_offered\":%d,\"gnss_withheld\":%d," +
                "\"calibration_id\":%s,\"calibration_after_s\":%s,\"first_position_after_s\":%s,\"publication_hz\":%s,\"fused_rows\":%d,\"failed_rows\":%d," +
                "\"wall_s\":%.1f,\"faster_than_real_time\":%.1f,\"outages\":[%s]}",
            tripDir.name, android.os.Build.MANUFACTURER, android.os.Build.MODEL, driveS, imu, gnssOffered, gnssWithheld,
            calibrationId?.let { "\"$it\"" } ?: "null", calibrationNs?.let { String.format(Locale.ROOT, "%.1f", (it - originNs) / 1e9) } ?: "null",
            firstRow?.let { String.format(Locale.ROOT, "%.1f", (it.tNs - originNs) / 1e9) } ?: "null",
            if (rateHz.isNaN()) "null" else String.format(Locale.ROOT, "%.2f", rateHz), rows.size, failures, wallS, driveS / wallS, driftJson)
        File(tripDir.parentFile, "report_${tripDir.name}.json").writeText(report + "\n")
        println("REAL_DRIVE_REPORT $report")

        assertNotNull("the engine must find a mount on its own", calibrationId)
        assertTrue("a batch mount (the in-drive calibrator never locks on real drives): $calibrationId", calibrationId!!.startsWith("batch-"))
        assertTrue("navigation must start", firstRow != null)
        assertEquals("the filter must not fail on this drive", 0L, failures)
        assertTrue("publication rate $rateHz Hz should be the 10 Hz the engine promises", rateHz in 9.0..10.5)
        assertTrue("replay should run faster than real time, was ${driveS / wallS}x", driveS / wallS > 2.0)
        return report
    }

    @Test fun realDrivesRunThroughTheEngineAndCalibratorOnThisDevice() {
        // The app's own files folder first (pushed with `adb run-as`), then its external one (not readable by the app on every Android version).
        val roots = listOfNotNull(File(context.filesDir, "real_drives"), context.getExternalFilesDir(null)?.let { File(it, "real_drives") })
        val root = roots.firstOrNull { dir -> dir.listFiles { f -> f.isDirectory && File(f, "measurements.jsonl").isFile }?.isNotEmpty() == true } ?: roots.first()
        val trips = root.listFiles { f -> f.isDirectory && File(f, "measurements.jsonl").isFile }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no real drives pushed to ${roots.joinToString { it.absolutePath }} (see docs/GSDC_REAL_PHONE_DATA.md)", trips.isNotEmpty())
        for (trip in trips) replay(trip)
    }
}
