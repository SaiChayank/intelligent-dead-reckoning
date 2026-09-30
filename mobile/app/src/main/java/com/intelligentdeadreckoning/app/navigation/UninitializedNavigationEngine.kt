package com.intelligentdeadreckoning.app.navigation

import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.ConfidenceState
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Payload
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Severity

/**
 * The engine that exists while the algorithms do not.
 *
 * This is a deliberate, honest placeholder so the runtime seam can be built and tested
 * before any INS/EKF/AI work starts. It accepts the session, counts the IMU and GNSS
 * records routed to it, and drains exactly three things: a [NavigationState] that stays
 * [NavigationStatus.UNINITIALIZED] with every estimate null, a [Confidence] that says
 * UNAVAILABLE, and a [DiagnosticEvent] naming the absence. It never computes, smooths,
 * interpolates or fabricates a position, velocity, heading or confidence value.
 *
 * The real engine will live in `core/` and satisfy the same frozen [NavigationEngine]
 * interface; swapping it in is a constructor argument on [NavigationRuntime].
 */
class UninitializedNavigationEngine : NavigationEngine {

    private var header: com.intelligentdeadreckoning.contracts.v1.Header? = null
    private var clockNs = 0L
    private var nextEventId = 1L
    private var calibrationId: String? = null
    private var mode: InitializationMode = InitializationMode.EVALUATION
    private val pending = ArrayDeque<Record>()

    override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
        header = session.header
        clockNs = session.origin_ns
        this.mode = mode
        calibrationId = (calibration.event.data as? CalibrationResult)?.id
        pending.clear()
        emit(
            NavigationState(
                NavigationStatus.UNINITIALIZED, mode, null, null, null, null, null,
                calibrationId, false,
            ),
        )
        emit(Confidence(ConfidenceState.UNAVAILABLE, null, null, null))
        emit(
            DiagnosticEvent(
                Severity.WARNING, "NAVIGATION_NOT_IMPLEMENTED",
                "No INS, EKF, AI or fusion runs in this build; no position, velocity or heading is estimated.",
                0,
            ),
        )
    }

    override fun acceptImu(measurement: Record) {
        // Counted by the runtime; deliberately no propagation here.
        clockNs = maxOf(clockNs, measurement.event.t_ns)
    }

    override fun acceptGnss(measurement: Record) {
        // The fix is recorded in time only. It is not used to fabricate a position.
        clockNs = maxOf(clockNs, measurement.event.t_ns)
    }

    /** Everything queued, in the order it was produced (measurement-time order). */
    override fun drain(): Sequence<Record> {
        val out = pending.toList()
        pending.clear()
        return out.asSequence()
    }

    override fun stop() {
        pending.clear()
    }

    override fun reset() {
        header = null
        pending.clear()
        calibrationId = null
        clockNs = 0L
        nextEventId = 1L
    }

    private fun emit(data: Payload) {
        val header = header ?: return
        pending.addLast(Record(header, Event((nextEventId++).toString(), clockNs, clockNs, data)))
    }
}
