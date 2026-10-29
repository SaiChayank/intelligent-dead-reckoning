package com.intelligentdeadreckoning.edge

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.calibration.CalibrationNavigationEngine
import com.intelligentdeadreckoning.app.calibration.MountThresholds
import com.intelligentdeadreckoning.app.calibration.StationaryThresholds
import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.SpeedCorrectionModel
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Codec
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/**
 * Offline evaluation of converted IO-VNBD recordings (tools/iovnbd_to_contract.py) through the
 * phone's own calibrating engine. Nothing here is a second navigation implementation: each job
 * replays one recording, in arrival order, into [CalibratingFusionEngine] with one fusion
 * configuration ("arm"), withholding GNSS records whose measurement time falls inside the job's
 * outage intervals. Masking is half-open `[start, end)` on `t_ns`, matching
 * contracts/experiment/v1/masking.py: a withheld fix is dropped, never altered.
 *
 * Every published navigation state is written as one CSV row with the position converted back
 * to WGS-84 by [Geodesy.geodeticFromEnu], the exact inverse of the engine's own projection.
 * Scoring against truth happens in tools/iovnbd_outage_eval.py, which never feeds the engine.
 */
object IovnbdEval {
    enum class Arm(val id: String) {
        /** GNSS/INS filter without vehicle constraints: unaided inertial coasting in an outage. */
        INS("ins"),
        /** Default filter: zero-velocity and non-holonomic constraints. */
        EKF_NHC("ekf_nhc"),
        /** Constraints plus the shipped Model A forward-speed pseudo-measurement. */
        EKF_NHC_AI("ekf_nhc_ai"),
        /** Constraints plus the v3 learned outage speed correction (training/speed_v3.py). */
        EKF_NHC_V3("ekf_nhc_v3"),

        /** The v3 correction with weights trained WITHOUT the drive being run (the job names the file). */
        EKF_NHC_V3_HELDOUT("ekf_nhc_v3_heldout"),

        /** Exactly the configuration the app runs (see CalibratingFusionEngine). */
        APP("app");

        fun config(): FusionConfig = when (this) {
            INS -> FusionConfig(motionConstraintsEnabled = false)
            EKF_NHC -> FusionConfig()
            EKF_NHC_AI -> FusionConfig(aiSpeedEstimationEnabled = true)
            EKF_NHC_V3 -> FusionConfig(speedCorrectionEnabled = true)
            EKF_NHC_V3_HELDOUT -> FusionConfig(speedCorrectionEnabled = true)
            APP -> FusionConfig(
                aiSpeedEstimationEnabled = true,
                adaptiveNoiseScalingEnabled = true,
                mountSlipDetectionEnabled = true,
                mapHeadingFeedbackEnabled = true,
                adaptiveDecimationEnabled = true,
            )
        }

        companion object {
            fun of(id: String): Arm = entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown arm '$id' (expected ${entries.joinToString { it.id }})")
        }
    }

    /** GNSS-denied interval, half-open: `startNs <= t_ns < endNs`. */
    data class Outage(val startNs: Long, val endNs: Long) {
        init { require(endNs > startNs) { "empty outage [$startNs, $endNs)" } }
        operator fun contains(tNs: Long) = tNs >= startNs && tNs < endNs
    }

    /** A calibration estimated outside the engine, released at [availableNs] and not before. */
    data class BatchCalibration(
        val id: String,
        val qVehicleFromDevice: Quaternion,
        val gyroBiasRad_S: Vector3,
        val availableNs: Long,
    )

    data class Job(
        val session: Path,
        val output: Path,
        val arm: Arm,
        val outagesNs: List<Outage>,
        /** Null runs the app's own in-motion calibrator (with the IO-VNBD rest profile). */
        val calibration: BatchCalibration?,
        /** Weights file for the v3 correction; null uses the packaged weights (in-sample for these drives). */
        val speedModel: Path? = null,
    )

    /**
     * Stands in for [CalibrationNavigationEngine] when the calibration comes from a batch
     * estimate: publishes one VALID result once the clock reaches its release time, and nothing
     * else. [CalibratingFusionEngine]'s handoff (heading from GNSS motion, fusion start, origin
     * at the handoff fix) runs exactly as in the app.
     */
    internal class ReleasedCalibration(private val calibration: BatchCalibration) : NavigationEngine {
        private var header: Header? = null
        private var released = false
        private val pending = ArrayDeque<Record>()

        override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
            header = session.header
            released = false
            pending.clear()
        }

        private fun advance(tNs: Long) {
            val h = header ?: return
            if (released || tNs < calibration.availableNs) return
            released = true
            pending.addLast(Record(h, Event("cal-${calibration.id}", tNs, tNs, CalibrationResult(
                id = calibration.id,
                status = CalibrationStatus.VALID,
                q_vehicle_from_device_wxyz = calibration.qVehicleFromDevice,
                gyro_bias_rad_s = calibration.gyroBiasRad_S,
                accelerometer_bias_m_s2 = null,
                confidence = null,
            ))))
        }

        override fun acceptImu(measurement: Record) = advance(measurement.event.t_ns)
        override fun acceptGnss(measurement: Record) = advance(measurement.event.t_ns)
        override fun drain(): Sequence<Record> = pending.toList().also { pending.clear() }.asSequence()
        override fun stop() = Unit
        override fun reset() { header = null; released = false; pending.clear() }
    }

    data class Result(
        val imu: Long,
        val gnssOffered: Long,
        val gnssWithheld: Long,
        val navigationRows: Long,
        val calibrations: Long,
        val firstPositionNs: Long?,
        val elapsedMs: Long,
        val diagnostics: Map<String, Long>,
        val firstMessages: Map<String, String>,
        val restarts: Long,
    )

    /** Same publication cadence the app uses (CalibratingFusionEngine): 10 Hz. */
    private const val PUBLICATION_INTERVAL_NS = 100_000_000L

    /**
     * Rest detection matched to IO-VNBD: 10 Hz samples from a phone in an idling car.
     *
     * The app's defaults assume a 100 Hz phone that is nearly silent at rest (per-axis accel
     * std < 0.05 m/s², 60 samples). Measured on the converted sequences during VBOX-confirmed
     * standstill (2 s windows): accelerometer std-norm median 0.10–0.25 m/s², gyro std median
     * 0.005–0.015 rad/s. With app defaults no rest is ever accepted and the calibrator never
     * starts. Only the stillness gates move: sample counts keep the same durations at 10 Hz and
     * noise limits sit near the measured standstill p90. The app itself is unchanged.
     */
    val IOVNBD_STATIONARY = StationaryThresholds(
        maxAccelStdM_S2 = 0.20,
        maxGyroStdRad_S = 0.02,
        minSamples = 15,
        subWindowS = 1.0,
    )
    /**
     * Mount (yaw) stage matched to the same data. On VBOX-confirmed straight road (sequence m,
     * 18,716 samples) the phone's yaw rate is under the app's 0.03 rad/s straightness limit only
     * 62 % of the time (p90 0.069 rad/s, holder vibration), so no segment reaches the 90 %
     * straight fraction. The limit moves just above that p90; course-deviation and
     * course-versus-displacement checks still guard straightness independently.
     */
    val IOVNBD_MOUNT = MountThresholds(maxGravitySpreadM_S2 = 0.10, maxYawRateRad_S = 0.08)

    private fun FusionConfig.forIovnbd(): FusionConfig = copy(
        stationaryMinSamples = 15,
        stationaryAccelStdNormM_S2 = 0.25,
    )

    fun run(job: Job): Result {
        val started = System.nanoTime()
        val sessionId = job.session.fileName.toString()
        val measurements = job.session.resolve("measurements.jsonl")
        require(Files.isRegularFile(measurements)) { "missing $measurements" }

        // Replayed real data, same rule the app's ReplayReader applies to a `real` recording.
        val inputHeader = Header(sessionId, Source.REPLAY_REAL, "1.0.0")
        val outputHeader = Header(sessionId, Source.REPLAY_REAL, ENGINE_OUTPUT_CONTRACT_VERSION)
        val config = job.arm.config().forIovnbd()
        require(job.arm != Arm.EKF_NHC_V3_HELDOUT || job.speedModel != null) { "held-out arm needs a speed_model file" }
        val speedModel = job.speedModel?.let { Files.newInputStream(it).use(SpeedCorrectionModel::fromStream) }
        val engine = CalibratingFusionEngine(
            calibrator = job.calibration?.let { ReleasedCalibration(it) }
                ?: CalibrationNavigationEngine(IOVNBD_MOUNT, IOVNBD_STATIONARY),
            fusionFactory = { heading ->
            FusionNavigationEngine(
                initialHeadingRad = heading,
                publicationIntervalNs = PUBLICATION_INTERVAL_NS,
                config = config,
                speedCorrectionModel = speedModel,
            )
        },
        )

        var imu = 0L
        var gnssOffered = 0L
        var gnssWithheld = 0L
        var rows = 0L
        var calibrations = 0L
        var firstPositionNs: Long? = null
        var lastHorizontal95: Double? = null
        var initialized = false
        var failed = false
        var restarts = 0L
        val diagnostics = sortedMapOf<String, Long>()
        val firstMessages = sortedMapOf<String, String>()
        val outages = job.outagesNs.sortedBy { it.startNs }

        Files.createDirectories(job.output.toAbsolutePath().parent)
        Files.newBufferedWriter(job.output, StandardCharsets.UTF_8).use { out ->
            out.write("t_ns,status,localization_mode,latitude_deg,longitude_deg,east_m,north_m," +
                "speed_m_s,heading_deg,horizontal_95_m,calibration_id\n")

            fun drain() {
                for (record in engine.drain()) {
                    when (val data = record.event.data) {
                        is Confidence -> lastHorizontal95 = data.horizontal_accuracy_95_m
                        is CalibrationResult -> calibrations++
                        is DiagnosticEvent -> {
                            diagnostics.merge(data.code, 1L, Long::plus)
                            firstMessages.putIfAbsent(data.code, "t_ns=${record.event.t_ns}: ${data.message}")
                        }
                        is NavigationState -> {
                            writeRow(out, record.event.t_ns, data, lastHorizontal95)
                            rows++
                            if (data.status == NavigationStatus.FAILED) failed = true
                            if (firstPositionNs == null && data.position_enu_m != null) {
                                firstPositionNs = record.event.t_ns
                            }
                        }
                        else -> Unit
                    }
                }
            }

            Files.newBufferedReader(measurements, StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val decoded = Codec.decodeJson(line.toByteArray(StandardCharsets.UTF_8))
                    val record = Record(inputHeader, Event(
                        decoded.event.event_id, decoded.event.t_ns, decoded.event.received_ns,
                        decoded.event.data,
                    ))
                    if (failed) {
                        // A failed filter claims nothing until re-aligned. Restart it the way a
                        // user would restart navigation, and count it: the report scores every
                        // outage that overlaps a failure as failed, so this hides nothing.
                        engine.stop()
                        drain()
                        engine.reset()
                        initialized = false
                        failed = false
                        restarts++
                    }
                    if (!initialized) {
                        val origin = record.event.t_ns
                        val calibration = Record(outputHeader, Event("0", origin, origin, CalibrationResult(
                            id = "iovnbd-uncalibrated", status = CalibrationStatus.PENDING,
                            q_vehicle_from_device_wxyz = null, gyro_bias_rad_s = null,
                            accelerometer_bias_m_s2 = null, confidence = null,
                        )))
                        engine.initialize(EngineSession(outputHeader, "iovnbd-replay", origin),
                            calibration, InitializationMode.DEPLOYABLE)
                        initialized = true
                        drain()
                    }
                    when (record.event.data) {
                        is ImuMeasurement -> { engine.acceptImu(record); imu++ }
                        is GnssMeasurement -> {
                            if (withheld(record.event.t_ns, outages)) gnssWithheld++
                            else { engine.acceptGnss(record); gnssOffered++ }
                        }
                        else -> Unit
                    }
                    drain()
                }
            }
            engine.stop()
            drain()
        }
        return Result(imu, gnssOffered, gnssWithheld, rows, calibrations, firstPositionNs,
            (System.nanoTime() - started) / 1_000_000, diagnostics, firstMessages, restarts)
    }

    internal fun withheld(tNs: Long, outages: List<Outage>): Boolean = outages.any { tNs in it }

    private fun f(pattern: String, vararg values: Double) = String.format(Locale.ROOT, pattern, *values.toTypedArray())

    private fun writeRow(out: BufferedWriter, tNs: Long, s: NavigationState, h95: Double?) {
        val origin = s.origin_wgs84_deg_m
        val p = s.position_enu_m
        val v = s.velocity_enu_m_s
        val sb = StringBuilder()
        sb.append(tNs).append(',').append(s.status.name.lowercase()).append(',')
            .append(s.localization_mode?.name?.lowercase() ?: "").append(',')
        if (origin != null && p != null) {
            val point = Geodesy.geodeticFromEnu(
                GeodeticAnchor(origin.latitude_deg, origin.longitude_deg, origin.altitude_m, true), p,
            )
            sb.append(f("%.8f,%.8f,%.3f,%.3f", point.latitudeDeg, point.longitudeDeg, p.x, p.y))
        } else {
            sb.append(",,,")
        }
        sb.append(',')
        if (v != null) sb.append(f("%.3f", kotlin.math.hypot(v.x, v.y)))
        sb.append(',').append(s.heading_deg?.let { f("%.2f", it) } ?: "")
            .append(',').append(h95?.let { f("%.2f", it) } ?: "")
            .append(',').append(s.calibration_id ?: "").append('\n')
        out.write(sb.toString())
    }

    /** Jobs file: {"jobs":[{"session":dir,"output":csv,"arm":id,"outages_ns":[[start,end],...]}]} */
    fun readJobs(path: Path): List<Job> {
        val root = JsonParser.parseString(Files.readString(path)).asJsonObject
        return root.getAsJsonArray("jobs").map { element ->
            val o = element.asJsonObject
            Job(
                session = Paths.get(o.get("session").asString),
                output = Paths.get(o.get("output").asString),
                arm = Arm.of(o.get("arm").asString),
                outagesNs = o.getAsJsonArray("outages_ns").map { pair ->
                    val a = pair.asJsonArray
                    Outage(a[0].asString.toLong(), a[1].asString.toLong())
                },
                speedModel = o.get("speed_model")?.takeUnless { it.isJsonNull }?.asString?.let { Paths.get(it) },
                calibration = o.get("calibration")?.takeUnless { it.isJsonNull }?.asJsonObject?.let { c ->
                    val q = c.getAsJsonArray("q_vehicle_from_device_wxyz").map { it.asDouble }
                    val b = c.getAsJsonArray("gyro_bias_rad_s").map { it.asDouble }
                    BatchCalibration(
                        id = c.get("id").asString,
                        qVehicleFromDevice = Quaternion(q[0], q[1], q[2], q[3]),
                        gyroBiasRad_S = Vector3(b[0], b[1], b[2]),
                        availableNs = c.get("available_ns").asString.toLong(),
                    )
                },
            )
        }
    }
}

private fun jsonEscape(value: String): String = buildString {
    for (ch in value) when {
        ch == '"' -> append("\\\"")
        ch == '\\' -> append("\\\\")
        ch < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", ch.code))
        else -> append(ch)
    }
}

/** Usage: IovnbdEvalKt <jobs.json> <results.json> */
fun main(args: Array<String>) {
    require(args.size == 2 && args.none { it.isBlank() }) { "usage: <jobs.json> <results.json>" }
    val jobs = IovnbdEval.readJobs(Paths.get(args[0]))
    val results = StringBuilder("{\"results\":[")
    jobs.forEachIndexed { i, job ->
        val r = IovnbdEval.run(job)
        println("[${i + 1}/${jobs.size}] ${job.session.fileName} ${job.arm.id}: ${r.navigationRows} states, " +
            "${r.gnssWithheld} fixes withheld, ${r.elapsedMs} ms")
        if (i > 0) results.append(',')
        results.append("{\"output\":\"").append(job.output.toString().replace("\\", "/")).append("\",")
            .append("\"arm\":\"").append(job.arm.id).append("\",")
            .append("\"imu\":").append(r.imu).append(',')
            .append("\"gnss_offered\":").append(r.gnssOffered).append(',')
            .append("\"gnss_withheld\":").append(r.gnssWithheld).append(',')
            .append("\"navigation_rows\":").append(r.navigationRows).append(',')
            .append("\"calibrations\":").append(r.calibrations).append(',')
            .append("\"first_position_ns\":").append(r.firstPositionNs?.let { "\"$it\"" } ?: "null").append(',')
            .append("\"elapsed_ms\":").append(r.elapsedMs).append(',')
            .append("\"restarts_after_failure\":").append(r.restarts).append(',')
            .append("\"diagnostics\":{")
            .append(r.diagnostics.entries.joinToString(",") { "\"${it.key}\":${it.value}" })
            .append("},\"first_messages\":{")
            .append(r.firstMessages.entries.joinToString(",") { "\"${it.key}\":\"${jsonEscape(it.value)}\"" })
            .append("}}")
    }
    results.append("]}\n")
    Files.writeString(Paths.get(args[1]), results.toString())
}
