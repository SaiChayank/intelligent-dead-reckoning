package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.app.calibration.isProperRotation
import com.intelligentdeadreckoning.contracts.v1.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Single-process benchmark for canonical 1.1.0 calibrated input. It is paced at 1x source-clock
 * time. A 10 Hz recording stays 10 Hz; the harness cannot claim 200 Hz based on fast file reads.
 */
object BenchmarkHarness {
    data class Config(
        val ingressCapacity: Int = 1024,
        val outputCapacity: Int = 4096,
        val maximumPairSkewNs: Long = 30_000_000L,
        val maximumReceiptLagNs: Long = 100_000_000L,
        val publicationIntervalNs: Long = 100_000_000L,
        val profileWindow: Int = 20_000,
        val profile: Boolean = true,
        val paceAtSourceClock: Boolean = true,
    )

    private data class InputInfo(val header: Header, val calibration: Record, val firstNs: Long, val lastNs: Long, val count: Long)
    private data class Truth(val origin: GeoOrigin, val east: Double, val north: Double)
    private class Accuracy {
        var count = 0L
        private var squaredSum = 0.0
        private val errors = ArrayList<Double>()
        fun add(error: Double) { count++; squaredSum += error * error; errors += error }
        fun rmse(): Double? = if (count == 0L) null else sqrt(squaredSum / count)
        fun percentile(q: Double): Double? {
            val sorted = errors.sorted()
            if (sorted.isEmpty()) return null
            val rank = (ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
            return sorted[rank]
        }
    }

    fun run(inputPath: Path, reportPath: Path, referencePath: Path? = null, config: Config = Config()) {
        require(config.ingressCapacity >= 2 && config.outputCapacity >= 2 && config.profileWindow >= 2)
        require(Files.isRegularFile(inputPath)) { "input JSONL not found" }
        require(referencePath == null || Files.isRegularFile(referencePath)) { "reference JSONL not found" }
        val info = inspect(inputPath)
        val reference = referencePath?.let(::readReference).orEmpty()
        val accuracy = Accuracy()
        val runtime = EdgeRuntime(
            ingressCapacity = config.ingressCapacity,
            outputCapacity = config.outputCapacity,
            maximumPairSkewNs = config.maximumPairSkewNs,
            maximumReceiptLagNs = config.maximumReceiptLagNs,
            receiptClockComparable = false,
            publicationIntervalNs = config.publicationIntervalNs,
            latencyWindow = config.profileWindow,
            profile = config.profile,
        )
        check(runtime.start(info.header, info.calibration.event.t_ns, info.calibration)) { "edge runtime refused header/calibration" }
        fun collect() {
            runtime.drain().forEach { record ->
                val state = record.event.data as? NavigationState ?: return@forEach
                val expected = reference[record.event.t_ns] ?: return@forEach
                val actualOrigin = state.origin_wgs84_deg_m ?: return@forEach
                val position = state.position_enu_m ?: return@forEach
                if (actualOrigin == expected.origin) accuracy.add(hypot(position.x - expected.east, position.y - expected.north))
            }
        }
        collect()
        val benchmarkStart = System.nanoTime()
        var wallOriginNs: Long? = null
        EdgeInputAdapter.forEachRecord(inputPath) { record ->
            if (record.event.data is CalibrationResult) {
                require(record == info.calibration) { "only one calibration record is supported" }
                return@forEachRecord
            }
            val origin = wallOriginNs ?: System.nanoTime().also { wallOriginNs = it }
            if (config.paceAtSourceClock) {
                val deadline = origin + (record.event.t_ns - info.firstNs)
                var left = deadline - System.nanoTime()
                while (left > 0L) {
                    LockSupport.parkNanos(left)
                    if (Thread.interrupted()) throw InterruptedException("benchmark interrupted")
                    left = deadline - System.nanoTime()
                }
            }
            runtime.offer(record)
            collect()
        }
        check(runtime.stop(120, java.util.concurrent.TimeUnit.SECONDS)) { "edge worker did not drain" }
        collect()
        val elapsed = System.nanoTime() - benchmarkStart
        val metrics = runtime.metrics()
        Files.createDirectories(reportPath.toAbsolutePath().parent)
        Files.writeString(reportPath, render(info, elapsed, metrics, accuracy, config), StandardCharsets.UTF_8)
    }

    private fun inspect(path: Path): InputInfo {
        var header: Header? = null
        var calibration: Record? = null
        var first: Long? = null
        var last: Long? = null
        var count = 0L
        EdgeInputAdapter.forEachRecord(path) { record ->
            if (header == null) header = record.header else require(record.header == header) { "multiple input sessions" }
            when (val payload = record.event.data) {
                is CalibrationResult -> {
                    require(calibration == null) { "multiple calibration records" }
                    require(payload.status == CalibrationStatus.VALID &&
                        isProperRotation(payload.q_vehicle_from_device_wxyz ?: error("missing rotation"), 1e-6) &&
                        payload.gyro_bias_rad_s != null
                    ) { "valid mounting calibration with gyro bias required" }
                    calibration = record
                }
                is ImuMeasurement, is GnssMeasurement -> {
                    first = minOf(first ?: record.event.t_ns, record.event.t_ns)
                    last = maxOf(last ?: record.event.t_ns, record.event.t_ns)
                    count++
                }
                else -> error("only calibration, IMU, GNSS records are accepted")
            }
        }
        val sessionHeader = requireNotNull(header) { "input JSONL empty" }
        require(sessionHeader.source == Source.REAL && sessionHeader.contract_version == "1.1.0") {
            "input must be real-source contract 1.1.0"
        }
        val calibrationRecord = requireNotNull(calibration) { "valid calibration required" }
        val start = requireNotNull(first) { "no IMU or GNSS records" }
        val end = requireNotNull(last)
        require(calibrationRecord.event.t_ns <= start) { "calibration must precede measurements" }
        return InputInfo(sessionHeader, calibrationRecord, start, end, count)
    }

    private fun readReference(path: Path): Map<Long, Truth> {
        val result = HashMap<Long, Truth>()
        EdgeInputAdapter.forEachRecord(path) { record ->
            val state = record.event.data as? NavigationState ?: return@forEachRecord
            val origin = state.origin_wgs84_deg_m ?: return@forEachRecord
            val point = state.position_enu_m ?: return@forEachRecord
            require(result.put(record.event.t_ns, Truth(origin, point.x, point.y)) == null) { "duplicate reference timestamp" }
        }
        return result
    }

    private fun render(info: InputInfo, elapsed: Long, m: EdgeRuntimeMetrics, accuracy: Accuracy, config: Config): String {
        fun n(value: Any?) = value?.toString() ?: "null"
        fun r(value: RateSummary) = "{\"count\":${value.count},\"hz\":${n(value.hz)}," +
            "\"first_ns\":${n(value.firstTimestampNs)},\"last_ns\":${n(value.lastTimestampNs)}," +
            "\"min_interval_ns\":${n(value.minimumIntervalNs)},\"max_interval_ns\":${n(value.maximumIntervalNs)}}"
        fun l(value: LatencySummary) = "{\"count\":${value.count},\"p50_ns\":${n(value.p50Ns)}," +
            "\"p95_ns\":${n(value.p95Ns)},\"p99_ns\":${n(value.p99Ns)},\"max_ns\":${n(value.maxNs)}}"
        val stageJson = m.stageLatency.toSortedMap().entries.joinToString(",") { "\"${escape(it.key)}\":${l(it.value)}" }
        val sourceHz = listOfNotNull(m.accelerometerInput.hz, m.gyroscopeInput.hz).minOrNull()
        val processHz = m.pairedImuProcessing.hz
        val meets200 = sourceHz != null && processHz != null && sourceHz >= 200.0 && processHz >= 200.0 &&
            m.dropped == 0L && m.ingressHighWater < m.ingressCapacity
        val cpuPercent = if (m.workerCpuTimeNs != null && m.workerWallTimeNs != null && m.workerWallTimeNs > 0L) {
            m.workerCpuTimeNs * 100.0 / m.workerWallTimeNs
        } else null
        return """
        {
          "report_version":"edge-profile/1",
          "session":{"session_id":"${escape(info.header.session_id)}","contract_version":"${info.header.contract_version}","calibration_event_id":"${escape(info.calibration.event.event_id)}","measurement_count":${info.count},"measurement_duration_ns":"${info.lastNs - info.firstNs}"},
          "provenance":{"runtime":"single-process Kotlin/JVM; exact mobile FusionNavigationEngine","pace_at_source_clock":${config.paceAtSourceClock},"host_os":"${escape(System.getProperty("os.name"))}","input":"no timestamp scaling; no interpolation or upsampling"},
          "input":{"accelerometer_source":${r(m.accelerometerInput)},"accelerometer_wall_admission":${r(m.accelerometerInputWall)},"gyroscope_source":${r(m.gyroscopeInput)},"gyroscope_wall_admission":${r(m.gyroscopeInputWall)},"gnss_source":${r(m.gnssInput)},"gnss_wall_admission":${r(m.gnssInputWall)},"paired_imu_source":${r(m.pairedImuSource)},"imu_processing_wall":${r(m.imuProcessing)},"gnss_processing_wall":${r(m.gnssProcessing)},"paired_imu_processing_wall":${r(m.pairedImuProcessing)}},
          "pipeline":{"navigation_state_source":${r(m.navigationOutput)},"navigation_state_processing_wall":${r(m.navigationOutputProcessing)},"navigation_state_count":${m.navigationStateCount},"required_imu_hz":200.0,"sustained_200_hz_met":$meets200},
          "stages":{"feature_generation":"not implemented; no feature schema","onnx_inference":{"available":${m.aiAvailable},"unavailable_reason":"${escape(m.inferenceUnavailableReason ?: "unavailable")}","latency_p50_ns":null,"latency_p95_ns":null,"latency_p99_ns":null},"timings":{$stageJson}},
          "latency":{"total_queue_to_completion":${l(m.totalCycleLatency)}},
          "resources":{"worker_cpu_ns":${n(m.workerCpuTimeNs)},"worker_wall_ns":${n(m.workerWallTimeNs)},"cpu_percent_of_one_core":${n(cpuPercent)},"benchmark_elapsed_ns":$elapsed,"sampled_peak_jvm_heap_bytes":${n(m.peakHeapBytesSampled)},"sampled_peak_rss_bytes":null,"rss_reason":"portable JVM RSS metrics unavailable"},
          "queues":{"ingress":{"capacity":${m.ingressCapacity},"high_water":${m.ingressHighWater},"depth_after_stop":${m.ingressDepth},"dropped":${m.dropped}},"output":{"capacity":${m.outputCapacity},"high_water":${m.outputHighWater},"depth_after_stop":${m.outputDepth},"dropped":${m.outputDropped}},"rejected":${m.rejected},"late":${m.late},"invalid":${m.invalid},"duplicate":${m.duplicate}},
          "accuracy":{"join":"exact source timestamp and identical ENU origin","matches":${accuracy.count},"horizontal_rmse_m":${n(accuracy.rmse())},"p50_error_m":${n(accuracy.percentile(.5))},"p95_error_m":${n(accuracy.percentile(.95))}},
          "limits":["No 200 Hz claim without genuinely 200 Hz source data and measured processing cadence.","AI and feature-generation stages are absent; latency is N/A, not zero.","JVM heap is not process RSS; edge-board memory, CPU, thermal and power still require target qualification.","NavigationState cadence remains the mobile engine's 5 Hz publication semantics, not 200 Hz."]
        }
        """.trimIndent() + "\n"
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

fun main(args: Array<String>) {
    require(args.isNotEmpty() && args[0].isNotBlank()) {
        "Usage: gradle -p edge runEdgeBenchmark -Pinput=path/to/input.jsonl -Poutput=path/to/report.json [-Preference=path/to/reference.jsonl]"
    }
    val input = Path.of(args[0])
    val output = Path.of(args.getOrNull(1)?.takeIf(String::isNotBlank) ?: "edge-profile.json")
    val reference = args.getOrNull(2)?.takeIf(String::isNotBlank)?.let(Path::of)
    BenchmarkHarness.run(input, output, reference)
    println("Edge benchmark report written: $output")
}
