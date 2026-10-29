package com.intelligentdeadreckoning.app.navigation

import com.intelligentdeadreckoning.app.calibration.CalibrationNavigationEngine
import com.intelligentdeadreckoning.app.calibration.CalibrationStore
import com.intelligentdeadreckoning.app.calibration.SavedCalibration
import com.intelligentdeadreckoning.app.calibration.SavedCalibrationPolicy
import com.intelligentdeadreckoning.app.calibration.isProperRotation
import com.intelligentdeadreckoning.app.calibration.rotationAngleBetween
import com.intelligentdeadreckoning.app.acquisition.GnssQualityPolicy
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.contracts.v1.*
import kotlin.math.*

/** Session-local causal calibration -> fusion composition, owned by NavigationRuntime's worker.
 * No sample replay, sensor access, or fabricated calibration. A mount saved by an earlier drive of the same
 * device ([store]) is a prior only: it lets fusion start at the first accurate GNSS fix with a course instead
 * of waiting for the live calibrator, and it is dropped if the live calibrator disagrees or the mount expires.
 * Mount refinements within an episode are withheld while the adopted transform is frozen until
 * expiry: silently changing a running filter's body frame would invalidate its state.
 */
class CalibratingFusionEngine(
    private val calibrator: NavigationEngine = CalibrationNavigationEngine(),
    private val roadGraph: com.intelligentdeadreckoning.app.matching.RoadGraph? = null,
    /** Where valid mounts are kept between drives; null disables saving and loading. Used for real sessions only. */
    private val store: CalibrationStore? = null,
    private val deviceKey: String? = null,
    private val clockEpochMs: () -> Long = System::currentTimeMillis,
    private val fusionFactory: (Double) -> NavigationEngine = { heading ->
        FusionNavigationEngine(
            initialHeadingRad = heading,
            publicationIntervalNs = 100_000_000L,
            config = com.intelligentdeadreckoning.app.fusion.FusionConfig(
                aiSpeedEstimationEnabled = true,
                adaptiveNoiseScalingEnabled = true,
                mountSlipDetectionEnabled = true,
                mapHeadingFeedbackEnabled = true,
                adaptiveDecimationEnabled = true,
            ),
            roadGraph = roadGraph,
        )
    },
) : NavigationEngine {
    private var prior: Record? = null
    private var priorActive = false
    private var lastSavedId: String? = null
    private var session: EngineSession? = null
    private var mode = InitializationMode.DEPLOYABLE
    private var fusion: NavigationEngine? = null
    private var adoptedId: String? = null
    private var usable: Record? = null
    private var courseAnchor: Record? = null
    private var nextId = 1L
    private val pending = ArrayDeque<Record>()

    override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
        reset()
        this.session = session
        this.mode = mode
        require(calibration.header == session.header && calibration.event.t_ns <= session.origin_ns)
        calibrator.initialize(session, calibration, mode)
        prior = loadPrior(session)
        collectCalibration(allowActivation = true)
    }

    /** The saved mount as a calibration record, or null when there is none, it is not for this device, or it fails the policy. */
    private fun loadPrior(session: EngineSession): Record? {
        val key = deviceKey
        if (store == null || key == null || session.header.source != Source.REAL) return null
        val saved = store.load(key) ?: return null
        if (SavedCalibrationPolicy.rejection(saved, key, clockEpochMs()) != null) return null
        return Record(session.header, Event("saved-mount", session.origin_ns, session.origin_ns, CalibrationResult(
            "saved-${saved.savedAtEpochMs}", CalibrationStatus.VALID, saved.qVehicleFromDevice,
            saved.gyroBiasRad_S, saved.accelerometerBiasM_S2, saved.confidence,
        )))
    }

    private fun persist(data: CalibrationResult) {
        val key = deviceKey
        val q = data.q_vehicle_from_device_wxyz
        val bias = data.gyro_bias_rad_s
        if (store == null || key == null || session?.header?.source != Source.REAL || q == null || bias == null ||
            data.id == lastSavedId) return
        val candidate = SavedCalibration(key, clockEpochMs(), q, bias, data.accelerometer_bias_m_s2, data.confidence ?: 0.0)
        if (SavedCalibrationPolicy.rejection(candidate, key, candidate.savedAtEpochMs) != null) return
        lastSavedId = data.id
        try { store.save(candidate) } catch (_: Exception) { /* a failed save must never stop navigation */ }
    }

    /** One accurate GNSS fix with a course is enough when the mount is already known: no second fix to compare. */
    private fun headingFromSingleFix(record: Record): Double? {
        val fix = record.event.data as GnssMeasurement
        val speed = fix.speed_m_s
        val bearing = fix.bearing_deg
        val accuracy = fix.horizontal_accuracy_m
        if (fix.provider != "gps" || speed == null || speed < 3.0 || bearing == null || !bearing.isFinite() ||
            accuracy == null || accuracy <= 0.0 || accuracy > 15.0 ||
            (fix.satellites_used ?: 0L) < GnssQualityPolicy().minimumSatellites) return null
        return Math.toRadians(bearing)
    }

    override fun acceptImu(measurement: Record) {
        requireOwnedMeasurement(measurement)
        calibrator.acceptImu(measurement)
        collectCalibration(allowActivation = true)
        fusion?.let { it.acceptImu(measurement); collectFusion(it) }
    }

    override fun acceptGnss(measurement: Record) {
        requireOwnedMeasurement(measurement)
        calibrator.acceptGnss(measurement)
        collectCalibration(allowActivation = true)
        val heading = headingFromMotion(measurement)
        val calibration = usable
        if (fusion == null && calibration != null && heading != null &&
            measurement.event.t_ns > calibration.event.t_ns) {
            val active = fusionFactory(heading)
            // New propagation origin is the handoff time; earlier measurements are never replayed.
            active.initialize(requireNotNull(session).copy(origin_ns = measurement.event.t_ns), calibration, mode)
            fusion = active
            adoptedId = (calibration.event.data as CalibrationResult).id
            collectFusion(active)
        }
        val saved = prior
        if (fusion == null && usable == null && saved != null && measurement.event.t_ns >= saved.event.t_ns) {
            headingFromSingleFix(measurement)?.let { heading ->
                val active = fusionFactory(heading)
                active.initialize(requireNotNull(session).copy(origin_ns = measurement.event.t_ns), saved, mode)
                fusion = active
                adoptedId = (saved.event.data as CalibrationResult).id
                priorActive = true
                collectFusion(active)
            }
        }
        fusion?.let { it.acceptGnss(measurement); collectFusion(it) }
    }

    private fun collectCalibration(allowActivation: Boolean) {
        for (record in calibrator.drain()) {
            val data = record.event.data
            if (data is CalibrationResult) {
                val valid = data.status == CalibrationStatus.VALID &&
                    data.q_vehicle_from_device_wxyz?.let { isProperRotation(it, 1e-6) } == true &&
                    data.gyro_bias_rad_s != null
                if (valid) persist(data)
                if (priorActive) {
                    // The filter is running on the saved mount. Only a live mount that contradicts it, or an
                    // expiry/invalidation of the live estimate, ends that; a pending or agreeing one does not.
                    val saved = (prior?.event?.data as? CalibrationResult)?.q_vehicle_from_device_wxyz
                    val live = data.q_vehicle_from_device_wxyz
                    val agrees = valid && saved != null && live != null &&
                        rotationAngleBetween(saved, live) <= SavedCalibrationPolicy.MAX_DISAGREEMENT_RAD
                    val ends = (valid && !agrees) || data.status == CalibrationStatus.EXPIRED ||
                        data.status == CalibrationStatus.INVALID
                    if (!ends) continue
                    priorActive = false
                    prior = null
                }
                if (!valid || (adoptedId != null && adoptedId != data.id)) {
                    fusion?.reset()
                    fusion = null
                    adoptedId = null
                    usable = null
                    courseAnchor = null
                }
                if (allowActivation && valid && fusion == null) usable = record
                // While navigating, report exactly the calibration adopted by the filter.
                if (fusion == null || data.id != adoptedId) emit(record)
            } else if (data is DiagnosticEvent && data.code == "NAVIGATION_NOT_IMPLEMENTED") {
                emit(record.copy(event = record.event.copy(data = DiagnosticEvent(
                    Severity.INFO, "CALIBRATION_WAITING_FOR_COURSE",
                    "Mount calibrated. Waiting for accurate forward GNSS course agreeing with observed displacement.", 0,
                ))))
            } else if (data is DiagnosticEvent || fusion == null) {
                emit(record)
            }
        }
    }

    /** Course must agree with already observed displacement; no magnetometer or future samples.
     * Engineering gates, not a field-qualified heading-accuracy claim.
     */
    private fun headingFromMotion(record: Record): Double? {
        val fix = record.event.data as GnssMeasurement
        val speed = fix.speed_m_s
        val bearing = fix.bearing_deg
        val accuracy = fix.horizontal_accuracy_m
        if (fix.provider != "gps" || speed == null || speed < 3.0 || bearing == null ||
            accuracy == null || accuracy <= 0.0 || accuracy > 15.0 ||
            (fix.satellites_used ?: 0L) < GnssQualityPolicy().minimumSatellites) {
            courseAnchor = null; return null
        }
        val earlier = courseAnchor
        if (earlier == null) { courseAnchor = record; return null }
        val dt = record.event.t_ns - earlier.event.t_ns
        if (dt <= 0 || dt > 10_000_000_000L) { courseAnchor = record; return null }
        val a = earlier.event.data as GnssMeasurement
        val north = Math.toRadians(fix.latitude_deg - a.latitude_deg) * 6_371_000.0
        val east = Math.toRadians(fix.longitude_deg - a.longitude_deg) * 6_371_000.0 *
            cos(Math.toRadians((fix.latitude_deg + a.latitude_deg) / 2))
        if (hypot(east, north) < 15.0) return null
        courseAnchor = record
        val course = (Math.toDegrees(atan2(east, north)) + 360) % 360
        val difference = abs((bearing - course + 540) % 360 - 180)
        return if (difference <= 15.0) Math.toRadians(bearing) else null
    }

    private fun emit(record: Record) {
        pending.addLast(record.copy(event = record.event.copy(event_id = (nextId++).toString())))
    }
    private fun requireOwnedMeasurement(record: Record) {
        val bound = requireNotNull(session).header
        require(record.header.session_id == bound.session_id && record.header.source == bound.source)
        require(record.header.contract_version in setOf("1.0.0", "1.1.0"))
    }
    private fun collectFusion(engine: NavigationEngine) { engine.drain().forEach(::emit) }
    override fun drain(): Sequence<Record> = pending.toList().also { pending.clear() }.asSequence()
    override fun stop() {
        calibrator.stop()
        collectCalibration(allowActivation = false)
        fusion?.let { it.stop(); collectFusion(it) }
    }
    override fun reset() {
        calibrator.reset(); fusion?.reset(); fusion = null
        session = null; usable = null; adoptedId = null; courseAnchor = null
        prior = null; priorActive = false; lastSavedId = null
        pending.clear(); nextId = 1
    }
}
