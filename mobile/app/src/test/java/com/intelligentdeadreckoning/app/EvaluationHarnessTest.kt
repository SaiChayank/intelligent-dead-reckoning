/*
 * The arm-comparison evaluation and its reproducibility gate.
 *
 * It replays one scripted ground-truth drive ([ScriptedDrive]) through the production pipeline —
 * `NavigationRuntime` → `FusionNavigationEngine` — once per arm, measures every published position
 * against the trajectory the inputs were generated from, and emits the
 * `contracts/evaluation/v1` report the app's Evaluation tab renders.
 *
 * The arms, and only these, are the ones that can actually run today:
 *
 * - `classical_ins` — the fusion engine aligned on the first fix and then fed no GNSS at all: an
 *   anchored inertial coast. It is a classical INS solution with a known starting point, not
 *   `training/strapdown_ins.py`, which has no GNSS-derived anchor and is not run here.
 * - `classical_fusion` — the shipped engine, GNSS aided, with the vehicle-motion constraints
 *   switched off (`motionConstraintsEnabled = false`), the same A/B switch the constraints stage
 *   evaluated.
 * - `fusion_constraints` — the shipped configuration, constraints on.
 * - `fusion_map_match` — the constraints arm's published positions through the shipped
 *   `MapMatcher` over a synthetic road built *along the truth path*. That construction is why the
 *   matched figures are optimistic by design: they measure the matcher's projection onto a road
 *   that is right by construction, not the road network's agreement with reality.
 * - `fusion_ai` — no model exists in the repository, so the arm is `not_implemented` and carries
 *   a reason instead of numbers.
 *
 * ## Reproducibility
 *
 * The harness is deterministic: one seed, a fixed drive, and the runtime driven on the test
 * dispatcher with an injected clock, so no wall-clock or thread scheduling enters the metrics. It
 * writes the report to `app/build/evaluation-report/` and requires it to match the checked-in
 * `contracts/evaluation/v1/golden_report.json`: every non-numeric field exactly, every metric to
 * 1e-6 relative. Regenerating on another machine may move a value in its last bits; anything
 * larger is a change to the engine, the drive or the metrics.
 *
 * ## What is deliberately absent, and why
 *
 * `memory_peak_mb`, `end_to_end_p50_ms`/`p95_ms` and the inference latencies are `null` in this run.
 * A host replay has no device memory and no sensor-to-display path to measure, and no AI model
 * exists to time. The report says so through its `platform` block and the absent values stay
 * absent: nothing here invents a number to fill a table.
 */
package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.app.matching.MapMatchStatus
import com.intelligentdeadreckoning.app.matching.MapMatcher
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.contracts.evaluation.v1.AccuracyMetrics
import com.intelligentdeadreckoning.contracts.evaluation.v1.Arm
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmId
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmImplementation
import com.intelligentdeadreckoning.contracts.evaluation.v1.ArmStatus
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationCodec
import com.intelligentdeadreckoning.contracts.evaluation.v1.EvaluationReport
import com.intelligentdeadreckoning.contracts.evaluation.v1.PlatformDescriptor
import com.intelligentdeadreckoning.contracts.evaluation.v1.ReferenceDescriptor
import com.intelligentdeadreckoning.contracts.evaluation.v1.ReferenceKind
import com.intelligentdeadreckoning.contracts.evaluation.v1.Segment
import com.intelligentdeadreckoning.contracts.evaluation.v1.SegmentKind
import com.intelligentdeadreckoning.contracts.evaluation.v1.SessionDescriptor
import com.intelligentdeadreckoning.contracts.evaluation.v1.TimingMetrics
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Confidence
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
import com.intelligentdeadreckoning.contracts.v1.AltitudeReference
import com.intelligentdeadreckoning.contracts.v1.Payload
import com.intelligentdeadreckoning.contracts.v1.SensorAccuracy
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class)
class EvaluationHarnessTest {

    private val header = Header("scripted-drive-70s", Source.SIMULATION, "1.1.0")
    private val calibration = CalibrationResult(
        id = "cal-evaluation",
        status = CalibrationStatus.VALID,
        // Identity mounting: the vehicle frame is the device frame, as the drive's samples assume.
        q_vehicle_from_device_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
        gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
        accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
        confidence = 0.9,
    )

    /** How an arm is fed GNSS. The reference is never fed to any arm. */
    private enum class GnssPlan { FIRST_FIX_ONLY, AIDED_WITH_OUTAGE }

    /** One published position, already translated into the truth's own ENU frame. */
    private class Fix(
        val tS: Double,
        val eastM: Double,
        val northM: Double,
        val headingDeg: Double?,
        val speedMS: Double?,
        val radius95M: Double?,
    )

    private class ArmRun(
        val fixes: List<Fix>,
        val outputHz: Double,
        val queueHighWater: Long,
        val drops: Long,
        /** Records the runtime refused to route, plus the refusals and rejections the engine counted. */
        val errors: Long,
        val offered: Long,
        val accepted: Long,
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

    /**
     * Replay the drive once, through `NavigationRuntime` → engine, pacing the feed one second at a
     * time on the test dispatcher so the queue measurements describe a real-time producer rather
     * than a flood (the flood behaviour is `NavigationRuntimeTest`'s subject).
     */
    private fun replayArm(config: FusionConfig, gnss: GnssPlan): ArmRun {
        lateinit var result: ArmRun
        runTest {
        var clockNs = ORIGIN_NS
        val runtime = NavigationRuntime(
            engine = FusionNavigationEngine(config = config, publicationIntervalNs = 100_000_000L),
            scope = this,
            nowNs = { clockNs },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val collected = mutableListOf<Record>()
        val collector = launch { runtime.output.collect { collected += it } }
        assertTrue(
            runtime.start(
                header, ORIGIN_NS, InitializationMode.EVALUATION,
                record(ORIGIN_NS, "cal", calibration),
            ),
        )
        val random = Random(SEED)
        var anchorEnu: Vector3? = null
        var offered = 0L
        val steps = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()
        for (step in 1..steps) {
            val tNs = ORIGIN_NS + step * 10_000_000L
            clockNs = tNs
            val tS = step * ScriptedDrive.STEP_S
            val truth = ScriptedDrive.position(tS)
            val (accel, gyro) = ScriptedDrive.imu(tS, random)
            runtime.offer(imuRecord(tNs, "a$tNs", Sensor.ACCELEROMETER, ImuUnit.METRES_PER_SECOND_SQUARED, accel))
            runtime.offer(imuRecord(tNs, "g$tNs", Sensor.GYROSCOPE, ImuUnit.RADIANS_PER_SECOND, gyro))
            offered += 2
            val inOutage = tS >= ScriptedDrive.OUTAGE_FROM_S && tS < ScriptedDrive.OUTAGE_TO_S
            val feedFix = when (gnss) {
                GnssPlan.FIRST_FIX_ONLY -> step == 100
                GnssPlan.AIDED_WITH_OUTAGE -> !inOutage
            }
            if (step % 100 == 0 && feedFix) {
                val (noiseEast, noiseNorth) = ScriptedDrive.fixNoise(random)
                // The first fix is the anchor; its drawn noise is the fixed offset every later
                // position inherits, exactly as the engine's ENU frame does.
                if (anchorEnu == null) anchorEnu = Vector3(truth.x + noiseEast, truth.y + noiseNorth, 0.0)
                val geodetic = Geodesy.geodeticFromEnu(
                    ScriptedDrive.ANCHOR, Vector3(truth.x + noiseEast, truth.y + noiseNorth, 0.0),
                )
                runtime.offer(
                    fixRecord(tNs, "n$tNs", geodetic.latitudeDeg, geodetic.longitudeDeg,
                        ScriptedDrive.speed(tS), ScriptedDrive.headingDeg(tS)),
                )
                offered += 1
            }
            // Drain every step: a real-time producer at 100 Hz would not queue a whole second of
            // records ahead of the engine, and a queue depth measured across a batch would measure
            // the harness's batching rather than the seam.
            advanceUntilIdle()
        }
        advanceUntilIdle()
        val state = runtime.state.value
        runtime.stop()
        advanceUntilIdle()
        collector.cancel()
        runtime.close()

        // Pair each published state with the confidence of the same measurement time, exactly as
        // the map fold does: a state whose confidence never arrived is kept, with no radius.
        val radiusByTime = HashMap<Long, Double>()
        for (record in collected) {
            val data = record.event.data
            if (data is Confidence) radiusByTime[record.event.t_ns] = data.horizontal_accuracy_95_m ?: -1.0
        }
        val fixes = mutableListOf<Fix>()
        val origin = anchorEnu
        if (origin != null) {
            for (record in collected) {
                val nav = record.event.data as? NavigationState ?: continue
                val position = nav.position_enu_m ?: continue
                val tS = (record.event.t_ns - ORIGIN_NS) / 1e9
                fixes += Fix(
                    tS = tS,
                    eastM = origin.x + position.x,
                    northM = origin.y + position.y,
                    headingDeg = nav.heading_deg,
                    speedMS = nav.velocity_enu_m_s?.let { hypot(it.x, it.y) },
                    radius95M = radiusByTime[record.event.t_ns]?.takeIf { it > 0.0 },
                )
            }
        }
        // The engine's own error counts are published as diagnostics carrying the count they
        // summarise; summing them is the measurement, not a guess about what it refused.
        val engineErrors = collected.mapNotNull { it.event.data as? com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent }
            .filter { it.code == "GNSS_MEASUREMENT_REFUSED" || it.code == "GNSS_UPDATE_REJECTED" }
            .sumOf { it.dropped_count }
        result = ArmRun(
            fixes = fixes,
            outputHz = fixes.size / ScriptedDrive.TOTAL_S,
            queueHighWater = state.ingressHighWater,
            drops = state.ingressDropped + state.outputDropped,
            errors = state.rejected + engineErrors,
            offered = offered,
            accepted = state.acceptedImu + state.acceptedGnss,
        )
        }
        return result
    }

    /** The synthetic road the map-matching arm is evaluated against: the truth path itself. */
    private fun truthRoad(): RoadGraph {
        val points = ArrayList<Pair<Double, Double>>()
        var tS = 0.0
        while (tS <= ScriptedDrive.TOTAL_S) {
            val geodetic = ScriptedDrive.geodetic(tS)
            points += geodetic.latitudeDeg to geodetic.longitudeDeg
            tS += 1.0
        }
        val directory = java.nio.file.Files.createTempDirectory("evaluation-road").toFile()
        directory.deleteOnExit()
        val file = File(directory, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(
            file,
            RoadGraphFixtures.graphJson(listOf(RoadGraphFixtures.Road(1L, "residential", null, points))),
        )
        return RoadGraph.load(file)
    }

    /** The map-matched output: the snapped position where the matcher decided, the raw one otherwise. */
    private fun matchedFixes(run: ArmRun, graph: RoadGraph): List<Fix> {
        val matcher = MapMatcher(graph)
        return run.fixes.map { fix ->
            val geodetic = Geodesy.geodeticFromEnu(
                ScriptedDrive.ANCHOR, Vector3(fix.eastM, fix.northM, 0.0),
            )
            val result = matcher.match(
                timestampNs = (fix.tS * 1e9).toLong(),
                latitudeDeg = geodetic.latitudeDeg,
                longitudeDeg = geodetic.longitudeDeg,
                headingRad = fix.headingDeg?.let { Math.toRadians(it) },
                // The published uncertainty is what gates the matcher, exactly as in the app; a
                // position with no published radius is left unmatched rather than given one.
                accuracy95M = fix.radius95M ?: return@map fix,
                speedM_S = fix.speedMS,
            )
            val matched = result.status == MapMatchStatus.MATCHED &&
                result.matchedLatitudeDeg != null && result.matchedLongitudeDeg != null
            if (!matched) return@map fix
            val snapped = Geodesy.enuFromGeodetic(
                ScriptedDrive.ANCHOR,
                result.matchedLatitudeDeg!!,
                result.matchedLongitudeDeg!!,
                ScriptedDrive.ANCHOR.altitudeM,
            )
            Fix(fix.tS, snapped.x, snapped.y, fix.headingDeg, fix.speedMS, fix.radius95M)
        }
    }

    private fun truthDistanceM(fromS: Double, toS: Double): Double {
        var distance = 0.0
        var t = fromS
        while (t < toS) {
            val step = minOf(0.1, toS - t)
            distance += ScriptedDrive.speed(t) * step
            t += step
        }
        return distance
    }

    private fun wrapDegrees(value: Double): Double {
        var wrapped = value % 360.0
        if (wrapped > 180.0) wrapped -= 360.0
        if (wrapped < -180.0) wrapped += 360.0
        return wrapped
    }

    /**
     * Every error figure here is measured against [ScriptedDrive], which no arm ever receives. A
     * metric that could not be measured stays `null`; nothing is filled in.
     */
    private fun accuracy(fixes: List<Fix>): AccuracyMetrics {
        val errors = fixes.map { fix ->
            val truth = ScriptedDrive.position(fix.tS)
            hypot(fix.eastM - truth.x, fix.northM - truth.y)
        }
        val speedErrors = fixes.mapNotNull { fix ->
            fix.speedMS?.let { it - ScriptedDrive.speed(fix.tS) }
        }
        val headingErrors = fixes.mapNotNull { fix ->
            fix.headingDeg?.let { abs(wrapDegrees(it - ScriptedDrive.headingDeg(fix.tS))) }
        }
        val outageDistance = truthDistanceM(ScriptedDrive.OUTAGE_FROM_S, ScriptedDrive.OUTAGE_TO_S)
        val recovery = fixes.filter { it.tS >= ScriptedDrive.OUTAGE_TO_S }
            .firstOrNull { fix ->
                val truth = ScriptedDrive.position(fix.tS)
                hypot(fix.eastM - truth.x, fix.northM - truth.y) <= ScriptedDrive.RECOVERY_THRESHOLD_M
            }
        val mean = { values: List<Double> -> if (values.isEmpty()) null else values.average() }
        // Drift is the error the outage ended with, over the distance driven blind: the run-final
        // error is a recovery figure and would read as a small drift for a solution that recovered.
        val outageEndError = fixes.filter { it.tS < ScriptedDrive.OUTAGE_TO_S }.lastOrNull()?.let { fix ->
            val truth = ScriptedDrive.position(fix.tS)
            hypot(fix.eastM - truth.x, fix.northM - truth.y)
        }
        return AccuracyMetrics(
            referenceConsumed = false,
            outageDurationS = ScriptedDrive.OUTAGE_TO_S - ScriptedDrive.OUTAGE_FROM_S,
            outageDistanceM = outageDistance,
            finalPositionErrorM = errors.lastOrNull(),
            driftPercent = outageEndError?.let { 100.0 * it / outageDistance },
            positionRmseM = if (errors.isEmpty()) null else sqrt(errors.sumOf { it * it } / errors.size),
            speedMaeMS = mean(speedErrors.map { abs(it) }),
            speedRmseMS = if (speedErrors.isEmpty()) null else sqrt(speedErrors.sumOf { it * it } / speedErrors.size),
            headingErrorDeg = mean(headingErrors),
            recoveryConvergenceS = recovery?.let { it.tS - ScriptedDrive.OUTAGE_TO_S },
            recoveryThresholdM = ScriptedDrive.RECOVERY_THRESHOLD_M,
            samples = fixes.size.toLong(),
        )
    }

    /** An arm's runtime measurements. Device memory and the sensor-to-display path are absent. */
    private fun timing(run: ArmRun): TimingMetrics = TimingMetrics(
        outputHz = run.outputHz,
        inferenceLatencyP50Ms = null,
        inferenceLatencyP95Ms = null,
        endToEndP50Ms = null,
        endToEndP95Ms = null,
        queueHighWater = run.queueHighWater,
        drops = run.drops,
        errors = run.errors,
        memoryPeakMb = null,
        samples = run.fixes.size.toLong(),
    )

    private fun evaluated(
        armId: ArmId,
        label: String,
        implementation: ArmImplementation,
        fixes: List<Fix>,
        timing: TimingMetrics?,
    ) = Arm(armId, label, implementation, ArmStatus.EVALUATED, null, accuracy(fixes), timing)

    /** The one report this harness produces. */
    private fun buildReport(createdUtcMs: Long): EvaluationReport {
        val ins = replayArm(FusionConfig(), GnssPlan.FIRST_FIX_ONLY)
        val fusionOff = replayArm(
            FusionConfig(motionConstraintsEnabled = false), GnssPlan.AIDED_WITH_OUTAGE,
        )
        val fusionOn = replayArm(
            FusionConfig(motionConstraintsEnabled = true), GnssPlan.AIDED_WITH_OUTAGE,
        )
        val matched = matchedFixes(fusionOn, truthRoad())
        val second = ScriptedDrive.OUTAGE_FROM_S * 1e9
        val third = ScriptedDrive.OUTAGE_TO_S * 1e9
        return EvaluationReport(
            evaluationContractVersion = EvaluationReport.VERSION,
            evaluationId = "scripted-outage-70s",
            createdUtcMs = createdUtcMs,
            session = SessionDescriptor(
                sessionId = "scripted-drive-70s",
                source = "simulation",
                contractVersion = "1.1.0",
                durationS = ScriptedDrive.TOTAL_S,
                // The drive itself: two IMU records per 10 ms step, one fix per second. The
                // outage is part of the scenario, declared in `segments`, not a missing
                // measurement in the recording.
                records = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toLong() * 2L +
                    ScriptedDrive.TOTAL_S.toLong(),
                description = "Scripted 70 s drive (straight, 92 degree turn, straight) with a 20 s " +
                    "GNSS outage; IMU at 100 Hz and GNSS at 1 Hz generated from the trajectory.",
            ),
            platform = PlatformDescriptor(
                host = true,
                deviceModel = null,
                androidRelease = null,
                note = "Host JVM replay through NavigationRuntime on the test dispatcher. Device memory " +
                    "and the sensor-to-display latency path were not measured, and are null rather " +
                    "than estimated; there is no AI model to time.",
            ),
            reference = ReferenceDescriptor(
                kind = ReferenceKind.SCRIPTED_TRUTH,
                independent = true,
                description = "The closed-form trajectory the IMU and GNSS samples were generated " +
                    "from (ScriptedDrive). No arm receives it; it is not field data.",
            ),
            segments = listOf(
                Segment(SegmentKind.GNSS_GOOD, 0L, second.toLong()),
                Segment(SegmentKind.DENIED, second.toLong(), third.toLong()),
                Segment(SegmentKind.RECOVERY, third.toLong(), (ScriptedDrive.TOTAL_S * 1e9).toLong()),
            ),
            arms = listOf(
                evaluated(
                    ArmId.CLASSICAL_INS,
                    "Classical INS (anchored inertial coast, no GNSS aiding)",
                    ArmImplementation("FusionNavigationEngine, GNSS disabled after the alignment fix", "1.1.0"),
                    ins.fixes,
                    timing(ins),
                ),
                evaluated(
                    ArmId.CLASSICAL_FUSION,
                    "Classical fusion (GNSS+INS filter, no motion constraints)",
                    ArmImplementation("FusionNavigationEngine, motionConstraintsEnabled = false", "1.1.0"),
                    fusionOff.fixes,
                    timing(fusionOff),
                ),
                evaluated(
                    ArmId.FUSION_CONSTRAINTS,
                    "Fusion + vehicle-motion constraints",
                    ArmImplementation("FusionNavigationEngine, motionConstraintsEnabled = true", "1.1.0"),
                    fusionOn.fixes,
                    timing(fusionOn),
                ),
                Arm(
                    ArmId.FUSION_AI,
                    "Fusion + AI correction",
                    ArmImplementation("none", "not-implemented"),
                    ArmStatus.NOT_IMPLEMENTED,
                    "No trained error-correction model exists in this repository, so there is no " +
                        "inference to time and no arm to evaluate. Nothing is reported for it.",
                    null,
                    null,
                ),
                evaluated(
                    ArmId.FUSION_MAP_MATCH,
                    "Fusion + constraints + map matching",
                    ArmImplementation("MapMatcher over the synthetic road built along the truth path", MapMatcher.MATCHER_VERSION),
                    matched,
                    null,
                ),
            ),
        )
    }

    private fun readGolden(): String? {
        val classLoader = javaClass.classLoader ?: return null
        val stream = classLoader.getResourceAsStream("golden_report.json") ?: return null
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun sameMetrics(left: Double?, right: Double?, path: String) {
        if (left == null || right == null) {
            assertEquals("absent on one side only at $path", left == null, right == null)
            return
        }
        val tolerance = 1e-6 * maxOf(1.0, abs(right))
        assertTrue(
            String.format(Locale.ROOT, "%s: %.9f vs %.9f", path, left, right),
            abs(left - right) <= tolerance,
        )
    }

    @Test
    fun everyEvaluatedArmCarriesRealNumbersAndEveryAbsentOneSaysWhy() {
        val report = buildReport(GOLDEN_CREATED_UTC_MS)
        val byId = report.arms.associateBy { it.armId }
        assertEquals(ArmId.entries.toSet(), byId.keys)
        val ai = byId.getValue(ArmId.FUSION_AI)
        assertEquals(ArmStatus.NOT_IMPLEMENTED, ai.status)
        assertNull(ai.accuracy)
        assertNull(ai.timing)
        assertTrue(ai.reason!!.contains("No trained"))
        for (arm in report.arms.filter { it.status == ArmStatus.EVALUATED }) {
            val accuracy = arm.accuracy!!
            assertEquals("no arm consumes the reference", false, accuracy.referenceConsumed)
            assertTrue("${arm.armId} measured something", accuracy.positionRmseM!! > 0.0)
            assertTrue("${arm.armId} published samples", accuracy.samples!! > 100L)
        }
        // Host runs cannot claim device memory or a latency path, and the report says so.
        assertEquals(true, report.platform.host)
        val timing = byId.getValue(ArmId.FUSION_CONSTRAINTS).timing!!
        assertNull(timing.memoryPeakMb)
        assertNull(timing.endToEndP50Ms)
        assertTrue("the run published at a real rate", timing.outputHz!! > 1.0)
        assertEquals(0L, timing.drops)
        // The scripted road runs along the truth, so matching must not make a measured arm worse.
        val raw = byId.getValue(ArmId.FUSION_CONSTRAINTS).accuracy!!
        val matched = byId.getValue(ArmId.FUSION_MAP_MATCH).accuracy!!
        assertTrue(
            "matched RMSE ${matched.positionRmseM} vs raw ${raw.positionRmseM}",
            matched.positionRmseM!! <= raw.positionRmseM!!,
        )
    }

    /**
     * Why the `classical_fusion` and `fusion_constraints` arms measure the same on this drive,
     * pinned as evidence rather than left as a shrug: the non-holonomic rows never apply, because
     * the benign-dynamics gate reads the **instantaneous** forward specific force per sample and
     * requires 5 s contiguous below 0.35 m/s^2, while the accelerometer noise at the engine's own
     * configured density is 0.5 m/s^2 per 100 Hz sample. The dwell therefore never accumulates and
     * each cadence evaluates the newest sample, so no constraint is ever offered: 0 accepted and 0
     * refused, in both arms, for the same reason.
     *
     * The zero-velocity update is a different matter — it has no accelerometer gate — but this
     * drive never stops, so it has nothing to do here either. If the gate is ever fixed (a filtered
     * specific force, or a measured noise density), this test must fail and the report's reading of
     * the A/B must be re-measured rather than quietly kept.
     */
    @Test
    fun theConstraintsAbIsDegenerateOnThisDriveAndTheReasonIsMeasured() {
        for (enabled in listOf(true, false)) {
            val engine = FusionNavigationEngine(
                config = FusionConfig(motionConstraintsEnabled = enabled),
                publicationIntervalNs = 100_000_000L,
            )
            engine.initialize(
                EngineSession(header, "boot", ORIGIN_NS),
                record(ORIGIN_NS, "cal", calibration),
                InitializationMode.EVALUATION,
            )
            val random = Random(SEED)
            val steps = (ScriptedDrive.TOTAL_S / ScriptedDrive.STEP_S).toInt()
            for (step in 1..steps) {
                val tNs = ORIGIN_NS + step * 10_000_000L
                val tS = step * ScriptedDrive.STEP_S
                val (accel, gyro) = ScriptedDrive.imu(tS, random)
                engine.acceptImu(imuRecord(tNs, "a$tNs", Sensor.ACCELEROMETER, ImuUnit.METRES_PER_SECOND_SQUARED, accel))
                engine.acceptImu(imuRecord(tNs, "g$tNs", Sensor.GYROSCOPE, ImuUnit.RADIANS_PER_SECOND, gyro))
                val inOutage = tS >= ScriptedDrive.OUTAGE_FROM_S && tS < ScriptedDrive.OUTAGE_TO_S
                if (step % 100 == 0 && !inOutage) {
                    val (noiseEast, noiseNorth) = ScriptedDrive.fixNoise(random)
                    val geodetic = Geodesy.geodeticFromEnu(
                        ScriptedDrive.ANCHOR,
                        Vector3(ScriptedDrive.position(tS).x + noiseEast, ScriptedDrive.position(tS).y + noiseNorth, 0.0),
                    )
                    engine.acceptGnss(
                        fixRecord(tNs, "n$tNs", geodetic.latitudeDeg, geodetic.longitudeDeg,
                            ScriptedDrive.speed(tS), ScriptedDrive.headingDeg(tS)),
                    )
                }
            }
            engine.drain()
            val solution = engine.solution
            println(
                "motionConstraintsEnabled=$enabled: accepted=${solution.constraintAccepted} " +
                    "refused=${solution.constraintRejected}",
            )
            assertEquals("no constraint is offered in either arm", 0L, solution.constraintAccepted)
            assertEquals("and none is refused", 0L, solution.constraintRejected)
        }
    }

    @Test
    fun theReportIsReproducibleAgainstTheCheckedInGoldenDocument() {
        val report = buildReport(GOLDEN_CREATED_UTC_MS)
        val text = EvaluationCodec.encode(report)
        val output = File("build/evaluation-report/${report.evaluationId}.json")
        output.parentFile?.mkdirs()
        output.writeText(text, Charsets.UTF_8)
        val golden = readGolden()
        if (golden == null) {
            throw AssertionError(
                "golden_report.json is not on the test classpath; copy the generated document from " +
                    "$output into contracts/evaluation/v1/golden_report.json and re-run.",
            )
        }
        val expected = EvaluationCodec.decode(golden)
        // The golden must be a valid report in its own right before it is used as a reference.
        val actual = EvaluationCodec.decode(text)
        assertEquals(expected.evaluationId, actual.evaluationId)
        assertEquals(expected.createdUtcMs, actual.createdUtcMs)
        assertEquals(expected.session, actual.session)
        assertEquals(expected.platform, actual.platform)
        assertEquals(expected.reference, actual.reference)
        assertEquals(expected.segments, actual.segments)
        assertEquals(expected.arms.map { it.armId }, actual.arms.map { it.armId })
        expected.arms.zip(actual.arms).forEach { (want, got) ->
            val path = "arms[${want.armId.wire}]"
            assertEquals(path, want.label, got.label)
            assertEquals(path, want.implementation, got.implementation)
            assertEquals(path, want.status, got.status)
            assertEquals(path, want.reason, got.reason)
            assertEquals("$path.accuracy == null", want.accuracy == null, got.accuracy == null)
            want.accuracy?.let { reference ->
                val measured = got.accuracy!!
                assertEquals("$path.referenceConsumed", reference.referenceConsumed, measured.referenceConsumed)
                sameMetrics(reference.outageDurationS, measured.outageDurationS, "$path.outageDurationS")
                sameMetrics(reference.outageDistanceM, measured.outageDistanceM, "$path.outageDistanceM")
                sameMetrics(reference.finalPositionErrorM, measured.finalPositionErrorM, "$path.finalPositionErrorM")
                sameMetrics(reference.driftPercent, measured.driftPercent, "$path.driftPercent")
                sameMetrics(reference.positionRmseM, measured.positionRmseM, "$path.positionRmseM")
                sameMetrics(reference.speedMaeMS, measured.speedMaeMS, "$path.speedMaeMS")
                sameMetrics(reference.speedRmseMS, measured.speedRmseMS, "$path.speedRmseMS")
                sameMetrics(reference.headingErrorDeg, measured.headingErrorDeg, "$path.headingErrorDeg")
                sameMetrics(reference.recoveryConvergenceS, measured.recoveryConvergenceS, "$path.recoveryConvergenceS")
                sameMetrics(reference.recoveryThresholdM, measured.recoveryThresholdM, "$path.recoveryThresholdM")
                assertEquals("$path.samples", reference.samples, measured.samples)
            }
            assertEquals("$path.timing == null", want.timing == null, got.timing == null)
            want.timing?.let { reference ->
                val measured = got.timing!!
                sameMetrics(reference.outputHz, measured.outputHz, "$path.outputHz")
                assertEquals("$path.queueHighWater", reference.queueHighWater, measured.queueHighWater)
                assertEquals("$path.drops", reference.drops, measured.drops)
                assertEquals("$path.errors", reference.errors, measured.errors)
                assertEquals("$path.samples", reference.samples, measured.samples)
                sameMetrics(reference.memoryPeakMb, measured.memoryPeakMb, "$path.memoryPeakMb")
                sameMetrics(reference.endToEndP50Ms, measured.endToEndP50Ms, "$path.endToEndP50Ms")
            }
        }
        println("arm comparison — ${report.evaluationId} (host replay, scripted truth)")
        println(
            String.format(
                Locale.ROOT, "%-34s %9s %9s %9s %9s %8s %8s",
                "arm", "rmse m", "final m", "drift %", "speed ma", "conv s", "out Hz",
            ),
        )
        for (arm in report.arms) {
            val accuracy = arm.accuracy
            println(
                String.format(
                    Locale.ROOT, "%-34s %9s %9s %9s %9s %8s %8s",
                    arm.armId.wire,
                    accuracy?.positionRmseM?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—",
                    accuracy?.finalPositionErrorM?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—",
                    accuracy?.driftPercent?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—",
                    accuracy?.speedMaeMS?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—",
                    accuracy?.recoveryConvergenceS?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—",
                    arm.timing?.outputHz?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—",
                ),
            )
        }
        println("report written to $output")
    }

    private companion object {
        const val ORIGIN_NS = 1_700_000_000_000_000_000L
        const val SEED = 26168L

        /** The creation time of the evaluated run, so the checked-in document is reproducible. */
        const val GOLDEN_CREATED_UTC_MS = 1_790_812_800_000L
    }
}
