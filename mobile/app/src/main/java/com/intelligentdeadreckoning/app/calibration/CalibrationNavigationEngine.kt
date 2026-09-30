package com.intelligentdeadreckoning.app.calibration

import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.ConfidenceState
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuUnit
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Payload
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.Severity
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlin.math.abs

/**
 * The phone-to-vehicle calibration engine: the first real [NavigationEngine].
 *
 * It runs [MountEstimator] and reports through the frozen contract. It is inserted through
 * [com.intelligentdeadreckoning.app.navigation.NavigationRuntime] and nothing else: the runtime
 * owns the session, routes canonical records in and publishes canonical records out on a single
 * worker thread, so this class never sees a sensor, a file, the map or another thread.
 *
 * ## What it emits
 *
 * - [CalibrationResult] whenever the calibration is new, changed status, or moved materially.
 * - [NavigationState] when the navigation status or the in-force calibration id changes.
 * - [Confidence] when the confidence state changes.
 * - [DiagnosticEvent] for every decision, including every rejection and its reason.
 *
 * ## Two status choices worth stating plainly
 *
 * `CalibrationResult.confidence` is validated by the frozen codec as a probability in `[0, 1]`.
 * The value published here is a **bounded quality score, not a calibrated probability**: it is
 * the weakest measured margin, so `0.8` means "the least-supported part of this estimate had
 * 80% of its tolerance in hand". No calibration probability model exists, so none is claimed,
 * and the `Confidence` payload's numeric fields stay null because there is no accuracy,
 * probability or speed uncertainty this engine can justify.
 *
 * [NavigationStatus] `TRACKING` is never reported. Once a calibration is in force the engine
 * reports `DEGRADED` with `calibration_id` set, because the vehicle frame is known but no
 * position, velocity or heading solution is produced yet: saying `TRACKING` would claim an
 * estimate that does not exist. The reason is emitted as a warning diagnostic next to it.
 *
 * ## Memory
 *
 * Bounded by construction: paired IMU samples are aggregated into running sums, GNSS evidence
 * into per-segment scalars, and the output queue is drained by the runtime after every record.
 * Nothing accumulates with drive length.
 */
class CalibrationNavigationEngine(
    private val thresholds: MountThresholds = MountThresholds(),
    private val stationaryThresholds: StationaryThresholds = StationaryThresholds(),
) : NavigationEngine {

    private var header: Header? = null
    private var clockNs = 0L
    private var nextEventId = 1L
    private var mode: InitializationMode = InitializationMode.EVALUATION
    private val pending = ArrayDeque<Record>()

    private lateinit var estimator: MountEstimator
    private var accumulator = StationaryAccumulator(stationaryThresholds)
    private var lastAccel: Pair<Long, Vector3>? = null
    private var lastGyro: Pair<Long, Vector3>? = null

    private var publishedNavigationStatus: NavigationStatus? = null
    private var publishedCalibrationId: String? = null
    private var publishedConfidenceState: ConfidenceState? = null
    private var announcedNoNavigator = false

    private var accelerometerSamples = 0L
    private var gyroscopeSamples = 0L
    private var unmatchedSamples = 0L
    private var warnedIncompleteImu = false
    private var warnedMismatchedUnit = false

    override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
        header = session.header
        clockNs = session.origin_ns
        this.mode = mode
        nextEventId = 1L
        pending.clear()
        estimator = MountEstimator(thresholds, mode, session.header.session_id)
        accumulator = StationaryAccumulator(stationaryThresholds)
        lastAccel = null
        lastGyro = null
        publishedNavigationStatus = null
        publishedCalibrationId = null
        publishedConfidenceState = null
        announcedNoNavigator = false
        accelerometerSamples = 0L
        gyroscopeSamples = 0L
        unmatchedSamples = 0L
        warnedIncompleteImu = false
        warnedMismatchedUnit = false

        adoptPriorCalibration(calibration)
        emitDiagnostic(
            Severity.INFO, "CALIBRATION_SESSION_STARTED",
            "Calibration engine started in ${mode.wire} mode from " +
                "${if (mode == InitializationMode.DEPLOYABLE) "a two-segment" else "a single-segment"} " +
                "yaw requirement. Using accelerometer and gyroscope: the magnetometer and the " +
                "vendor gravity sensor are deliberately unused.",
        )
        publishState()
    }

    /**
     * Take over a calibration that was already in force.
     *
     * A prior is only adopted when it is genuinely usable: a `VALID` status and a transform. It
     * counts as one yaw determination, so a deployable session still needs its own additional
     * evidence, and a resting window that contradicts the prior's tilt still expires it.
     */
    private fun adoptPriorCalibration(calibration: Record) {
        val result = calibration.event.data as? CalibrationResult ?: run {
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_INIT_RECORD_INVALID",
                "Initialization record was ${calibration.event.data.type}, not a calibration " +
                    "result; no prior calibration is adopted.",
            )
            return
        }
        if (result.status != CalibrationStatus.VALID) return
        val transform = result.q_vehicle_from_device_wxyz ?: return
        if (!isProperRotation(transform, 1e-6)) {
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_INIT_RECORD_INVALID",
                "Prior calibration ${result.id} is not a proper rotation and is not adopted.",
            )
            return
        }
        if (!estimator.adoptPrior(transform, result.gyro_bias_rad_s,
                result.accelerometer_bias_m_s2)
        ) {
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_INIT_RECORD_INVALID",
                "Prior calibration ${result.id} could not be interpreted and is not adopted.",
            )
            return
        }
        emitDiagnostic(
            Severity.INFO, "CALIBRATION_PRIOR_ADOPTED",
            "Adopted the yaw and sensor biases of calibration ${result.id} from an earlier " +
                "session; its tilt is re-measured here and its transform is not republished " +
                "under that id.",
        )
    }

    override fun acceptImu(measurement: Record) {
        val payload = measurement.event.data as? ImuMeasurement ?: run {
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_RECORD_KIND",
                "Expected an IMU measurement, received ${measurement.event.data.type}; ignored.",
            )
            return
        }
        clockNs = maxOf(clockNs, measurement.event.t_ns)
        // Only the accelerometer and gyroscope carry physical evidence for a mount estimate.
        if (payload.sensor != Sensor.ACCELEROMETER && payload.sensor != Sensor.GYROSCOPE) return
        if (payload.frame != DeviceFrame.ANDROID_DEVICE) {
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_IMU_FRAME",
                "IMU sample arrived in ${payload.frame.wire} rather than the Android device " +
                    "frame; ignored.",
            )
            return
        }
        val expected = if (payload.sensor == Sensor.GYROSCOPE) {
            ImuUnit.RADIANS_PER_SECOND
        } else {
            ImuUnit.METRES_PER_SECOND_SQUARED
        }
        if (payload.unit != expected) {
            if (!warnedMismatchedUnit) {
                warnedMismatchedUnit = true
                emitDiagnostic(
                    Severity.WARNING, "CALIBRATION_IMU_UNIT",
                    "${payload.sensor.wire} samples arrived in ${payload.unit.wire}; ignored " +
                        "rather than converted.",
                )
            }
            return
        }
        val sample = measurement.event.t_ns to payload.xyz
        if (payload.sensor == Sensor.ACCELEROMETER) {
            accelerometerSamples += 1
            lastAccel = sample
        } else {
            gyroscopeSamples += 1
            lastGyro = sample
        }
        pairSamples()
        warnIfImuIncomplete()
    }

    /**
     * Pair the newest accelerometer and gyroscope samples when they are close enough in time.
     *
     * The acquisition streams the two sensors as separate records, so a mount estimate needs
     * pairs. Pairing consumes both samples, which keeps each sample in the statistics exactly
     * once: re-using one gyroscope sample across several accelerometer samples would shrink the
     * measured variance and overstate window stability.
     */
    private fun pairSamples() {
        val accel = lastAccel ?: return
        val gyro = lastGyro ?: return
        if (abs(accel.first - gyro.first) > MAX_PAIR_SKEW_NS) {
            unmatchedSamples += 1
            if (accel.first < gyro.first) lastAccel = null else lastGyro = null
            if (unmatchedSamples == UNMATCHED_WARN) {
                emitDiagnostic(
                    Severity.WARNING, "CALIBRATION_IMU_PAIRING",
                    "Accelerometer and gyroscope samples are more than " +
                        "${MAX_PAIR_SKEW_NS / 1_000_000} ms apart and cannot be paired; " +
                        "no mount estimate can run on unpaired samples.",
                )
            }
            return
        }
        lastAccel = null
        lastGyro = null
        val pair = ImuPair(maxOf(accel.first, gyro.first), accel.second, gyro.second)
        clockNs = maxOf(clockNs, pair.tNs)
        accumulator.offer(pair)?.let { window -> estimator.onStationaryWindow(window) }
        estimator.onImu(pair)
        // Remount detection can fire from an IMU sample alone, so the state is published after
        // both consumers have seen the pair, not only when a window closes.
        publishState()
    }

    private fun warnIfImuIncomplete() {
        if (warnedIncompleteImu || accelerometerSamples + gyroscopeSamples < IMU_WARN_SAMPLES) return
        if (accelerometerSamples == 0L || gyroscopeSamples == 0L) {
            warnedIncompleteImu = true
            val missing = if (accelerometerSamples == 0L) "accelerometer" else "gyroscope"
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_IMU_INCOMPLETE",
                "No $missing samples in this session, so the mount estimate cannot run: tilt " +
                    "needs gravity and yaw needs straight-motion evidence from both sensors.",
            )
        }
    }

    override fun acceptGnss(measurement: Record) {
        val payload = measurement.event.data as? GnssMeasurement ?: run {
            emitDiagnostic(
                Severity.WARNING, "CALIBRATION_RECORD_KIND",
                "Expected a GNSS measurement, received ${measurement.event.data.type}; ignored.",
            )
            return
        }
        clockNs = maxOf(clockNs, measurement.event.t_ns)
        estimator.onGnss(
            GnssFix(
                tNs = measurement.event.t_ns,
                latitudeDeg = payload.latitude_deg,
                longitudeDeg = payload.longitude_deg,
                speedM_S = payload.speed_m_s,
                bearingDeg = payload.bearing_deg,
                horizontalAccuracyM = payload.horizontal_accuracy_m,
            ),
        )
        publishState()
    }

    override fun drain(): Sequence<Record> {
        val out = pending.toList()
        pending.clear()
        return out.asSequence()
    }

    /**
     * Finish the session: deliver a resting period that is still open, close an in-flight
     * segment so its evidence is still judged, then hand the result to the runtime, which
     * publishes what the engine produced while stopping.
     *
     * The accumulator is flushed here rather than only in [reset] because a session that ends
     * while the car is parked has already measured gravity: the phone was placed, the app ran,
     * and the drive never happened. Dropping that window would throw away the only evidence the
     * session collected.
     */
    override fun stop() {
        if (::estimator.isInitialized) {
            accumulator.flush()?.let { window -> estimator.onStationaryWindow(window) }
            estimator.flush()
            publishState()
        }
        accumulator = StationaryAccumulator(stationaryThresholds)
        lastAccel = null
        lastGyro = null
    }

    override fun reset() {
        pending.clear()
        header = null
        clockNs = 0L
        nextEventId = 1L
        lastAccel = null
        lastGyro = null
        accumulator = StationaryAccumulator(stationaryThresholds)
        publishedNavigationStatus = null
        publishedCalibrationId = null
        publishedConfidenceState = null
        announcedNoNavigator = false
        accelerometerSamples = 0L
        gyroscopeSamples = 0L
        warnedIncompleteImu = false
        if (::estimator.isInitialized) estimator.reset()
    }

    // -----------------------------------------------------------------------------------------
    // Publication
    // -----------------------------------------------------------------------------------------

    /**
     * Translate the estimator's current state into canonical records.
     *
     * Called after every accepted record, but it emits only on a real change, so a long drive
     * produces a calibration record per material change rather than per sample.
     */
    private fun publishState() {
        if (!::estimator.isInitialized) return
        for (outcome in estimator.drainOutcomes()) {
            emit(
                CalibrationResult(
                    id = outcome.id,
                    status = outcome.status,
                    q_vehicle_from_device_wxyz = outcome.qVehicleFromDevice,
                    gyro_bias_rad_s = outcome.gyroBiasRad_S,
                    accelerometer_bias_m_s2 = outcome.accelBiasM_S2,
                    confidence = outcome.confidence,
                ),
            )
        }
        for (diagnostic in estimator.drainDiagnostics()) {
            emitDiagnostic(diagnostic.severity, diagnostic.code, diagnostic.message)
        }
        val live = estimator.current
        val status = navigationStatus(live)
        val calibrationId = if (live.status == CalibrationStatus.VALID) live.id else null
        if (status != publishedNavigationStatus || calibrationId != publishedCalibrationId) {
            publishedNavigationStatus = status
            publishedCalibrationId = calibrationId
            emit(
                NavigationState(
                    status = status,
                    initialization_mode = mode,
                    origin_wgs84_deg_m = null,
                    position_enu_m = null,
                    velocity_enu_m_s = null,
                    q_enu_from_vehicle_wxyz = null,
                    heading_deg = null,
                    calibration_id = calibrationId,
                    gnss_used_after_initialization = false,
                ),
            )
            if (status == NavigationStatus.DEGRADED && !announcedNoNavigator) {
                announcedNoNavigator = true
                emitDiagnostic(
                    Severity.WARNING, "NAVIGATION_NOT_IMPLEMENTED",
                    "The vehicle frame is calibrated, but no propagation, fusion or AI runs in " +
                        "this build: no position, velocity or heading is estimated.",
                )
            }
        }
        val confidenceState = when (live.status) {
            CalibrationStatus.VALID -> ConfidenceState.CALIBRATED
            else -> ConfidenceState.UNVALIDATED
        }
        if (confidenceState != publishedConfidenceState) {
            publishedConfidenceState = confidenceState
            emit(Confidence(state = confidenceState, probability = null,
                horizontal_accuracy_95_m = null, speed_std_m_s = null))
        }
    }

    /**
     * `CALIBRATING` until a calibration is in force, then `DEGRADED` rather than `TRACKING`,
     * because the frame is known while no navigation solution exists yet.
     */
    private fun navigationStatus(live: CalibrationOutcome): NavigationStatus =
        if (live.status == CalibrationStatus.VALID) {
            NavigationStatus.DEGRADED
        } else {
            NavigationStatus.CALIBRATING
        }

    private fun emitDiagnostic(severity: Severity, code: String, message: String) {
        emit(DiagnosticEvent(severity, code, message, 0))
    }

    private fun emit(data: Payload) {
        val header = header ?: return
        pending.addLast(
            Record(header, Event((nextEventId++).toString(), clockNs, clockNs, data)),
        )
    }

    private companion object {
        /** Acquisition requests 100 Hz on both sensors, so this tolerates jitter between streams. */
        const val MAX_PAIR_SKEW_NS = 30_000_000L
        const val IMU_WARN_SAMPLES = 200L
        const val UNMATCHED_WARN = 100L
    }
}
