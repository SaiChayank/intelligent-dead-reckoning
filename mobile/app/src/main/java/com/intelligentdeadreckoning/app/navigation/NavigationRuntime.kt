package com.intelligentdeadreckoning.app.navigation

import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.GnssQualityState
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Severity
import com.intelligentdeadreckoning.contracts.v1.Source
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NavigationPhase { IDLE, STARTING, RUNNING, STOPPING, FAILED }

/**
 * What consumers may observe. Counters belong to one engine session: they are cleared
 * when a new session starts and by [NavigationRuntime.reset], and they survive [NavigationRuntime.stop]
 * so a finished session can still be read. `engineStatus` is copied verbatim from the
 * last canonical [NavigationState] the engine drained — the runtime never invents one.
 *
 * The counters are disjoint and account for every record [NavigationRuntime.offer] saw:
 * `acceptedImu` + `acceptedGnss` were handed to the engine, `rejected` never owned the
 * session or carried a non-routable payload, `ingressDropped` was accepted by the producer
 * but never reached the engine (bounded queue overflow, or abandoned when a session ended).
 * `accepted*` is written on the engine worker, so it lags [NavigationRuntime.offer] by one
 * dispatch — it counts delivery, not admission.
 */
data class NavigationRuntimeState(
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val sessionId: String? = null,
    val source: Source? = null,
    val engineStatus: NavigationStatus = NavigationStatus.UNINITIALIZED,
    val acceptedImu: Long = 0,
    val acceptedGnss: Long = 0,
    val rejected: Long = 0,
    val ingressDropped: Long = 0,
    val outputDropped: Long = 0,
    val outputRejected: Long = 0,
    val message: String = "No navigation session. Explicit start required.",
)

/**
 * The seam between canonical producers (AndroidAcquisition, Replay) and one
 * [NavigationEngine] session.
 *
 * Responsibilities, and nothing else:
 * - create/reset exactly one engine session with an explicit lifecycle
 *   ([start] / [stop] / [reset]; nothing restarts on its own);
 * - accept canonical typed records only — IMU and GNSS measurements are routed to
 *   `acceptImu`/`acceptGnss`, every other payload is rejected before the engine;
 * - validate source and session ownership: a record is routed only if its header
 *   matches the bound session exactly (this is what keeps replayed rows out of live
 *   sessions and vice versa);
 * - own the engine worker: every engine call is serialized on one dispatcher the
 *   runtime creates (injectable for tests), never on the producer's thread;
 * - drain canonical output (`calibration` / `navigation` / `gnss_quality` /
 *   `confidence` / `diagnostic` records) into a bounded flow, in measurement-time
 *   order. A payload outside that set is counted and dropped rather than forwarded,
 *   so an engine cannot echo its own input back as if it were a result;
 * - stop cleanly on lifecycle or source change and isolate engine failure from
 *   acquisition and recording: an engine exception ends the session, is published
 *   as a diagnostic plus a FAILED state, and never reaches the caller of [offer].
 *
 * Deliberately absent: no SensorManager/LocationManager, no map, no file I/O, and
 * no navigation math of any kind. This class routes and polices records; it never
 * estimates anything.
 */
class NavigationRuntime(
    private val engine: NavigationEngine,
    private val scope: CoroutineScope,
    private val nowNs: () -> Long,
    dispatcher: CoroutineDispatcher? = null,
    private val ingressCapacity: Int = 256,
    outputCapacity: Int = 256,
) : AutoCloseable {

    private val worker: CoroutineDispatcher
    private val ownsWorker: Boolean

    init {
        if (dispatcher != null) {
            worker = dispatcher
            ownsWorker = false
        } else {
            worker = Executors.newSingleThreadExecutor { Thread(it, "navigation-engine") }.asCoroutineDispatcher()
            ownsWorker = true
        }
    }

    /** One lock guards session state, counters and ingress; engine calls never run under it. */
    private val lock = Any()
    private val mutable = MutableStateFlow(NavigationRuntimeState())
    val state = mutable.asStateFlow()

    /** Bounded output: overflow drops the oldest unseen record and counts it. */
    private val outputFlow = MutableSharedFlow<Record>(
        extraBufferCapacity = outputCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output = outputFlow.asSharedFlow()

    private val bootId: String = UUID.randomUUID().toString()
    private var session: EngineSession? = null
    private var ingress: Channel<Record>? = null
    private var nextEventId = 0L

    /**
     * Creates one engine session bound to [header]. Every record offered later must carry
     * this exact header (session id AND source). Returns false without side effects when a
     * session already exists, when one is still stopping, or after a failure that has not
     * been reset — there is never more than one session and never an automatic restart.
     */
    fun start(
        header: Header,
        originNs: Long,
        mode: InitializationMode = InitializationMode.EVALUATION,
        calibration: Record? = null,
    ): Boolean {
        val session: EngineSession
        val channel: Channel<Record>
        val calibrationRecord: Record
        synchronized(lock) {
            if (mutable.value.phase != NavigationPhase.IDLE) return false
            session = EngineSession(header, bootId, originNs)
            // Honest default: no calibration exists yet, and PENDING says exactly that.
            calibrationRecord = calibration ?: Record(
                header,
                Event("0", originNs, originNs, CalibrationResult("none", CalibrationStatus.PENDING, null, null, null, null)),
            )
            channel = Channel(ingressCapacity)
            this.session = session
            this.ingress = channel
            mutable.value = NavigationRuntimeState(
                phase = NavigationPhase.STARTING,
                sessionId = header.session_id,
                source = header.source,
                message = "Creating engine session…",
            )
        }
        scope.launch(worker) { runSession(session, calibrationRecord, mode, channel) }
        return true
    }

    /**
     * Routes one canonical record to the engine session. Returns false — never throws —
     * when there is no active session, when the record's header does not own the session,
     * when the payload is not routable (only IMU and GNSS measurements are), or when the
     * bounded ingress queue is full (counted as a drop).
     */
    fun offer(record: Record): Boolean {
        synchronized(lock) {
            val current = mutable.value
            if (current.phase != NavigationPhase.STARTING && current.phase != NavigationPhase.RUNNING) return false
            val session = session ?: return false
            val ownsRecord = record.header.session_id == session.header.session_id &&
                record.header.source == session.header.source
            if (!ownsRecord) {
                mutable.value = current.copy(rejected = current.rejected + 1)
                return false
            }
            when (record.event.data) {
                is ImuMeasurement, is GnssMeasurement -> Unit
                else -> {
                    mutable.value = current.copy(rejected = current.rejected + 1)
                    return false
                }
            }
            val channel = ingress ?: return false
            if (!channel.trySend(record).isSuccess) {
                mutable.value = current.copy(ingressDropped = current.ingressDropped + 1)
                return false
            }
            return true
        }
    }

    /**
     * Clean stop for lifecycle and source changes: already-accepted records are still
     * routed, the engine drains once more, then `engine.stop()` runs exactly once on the
     * worker. Returns false when no session is active or one is already stopping.
     */
    fun stop(): Boolean {
        synchronized(lock) {
            val current = mutable.value
            if (current.phase != NavigationPhase.STARTING && current.phase != NavigationPhase.RUNNING) return false
            mutable.value = current.copy(phase = NavigationPhase.STOPPING, message = "Stopping engine session…")
            ingress?.close()
            return true
        }
    }

    /**
     * Clears a finished (IDLE) or failed (FAILED) session: `engine.reset()` on a quiescent
     * engine, counters and state cleared. Rejected while a session is active or stopping,
     * so a reset can never race the worker. A failed engine that throws again here stays
     * FAILED; the exception is contained.
     */
    fun reset(): Boolean {
        val quiescent = synchronized(lock) {
            mutable.value.phase == NavigationPhase.IDLE || mutable.value.phase == NavigationPhase.FAILED
        }
        if (!quiescent) return false
        val result = runCatching { engine.reset() }
        synchronized(lock) {
            mutable.value = if (result.isSuccess) {
                NavigationRuntimeState(message = "Engine session reset. Explicit start required.")
            } else {
                NavigationRuntimeState(
                    phase = NavigationPhase.FAILED,
                    message = "Navigation engine failed to reset (${result.exceptionOrNull()?.message}). Reset required.",
                )
            }
        }
        return result.isSuccess
    }

    /** Stops the session and releases the worker thread when the runtime owns it. */
    override fun close() {
        stop()
        if (ownsWorker) (worker as? ExecutorCoroutineDispatcher)?.close()
    }

    // -----------------------------------------------------------------------------------------
    // Worker side. Every engine call happens here, in order, on the owned dispatcher.
    // -----------------------------------------------------------------------------------------

    private suspend fun runSession(
        session: EngineSession,
        calibration: Record,
        mode: InitializationMode,
        channel: Channel<Record>,
    ) {
        var engineStopped = false
        try {
            engine.initialize(session, calibration, mode)
            // A stop() that arrived while initialize() was still running already closed ingress
            // and moved the phase to STOPPING. Promote to RUNNING only if nobody asked to stop
            // in the meantime, so an observer never sees RUNNING after requesting a stop.
            synchronized(lock) {
                if (mutable.value.phase == NavigationPhase.STARTING) {
                    mutable.value = mutable.value.copy(
                        phase = NavigationPhase.RUNNING,
                        message = "Engine session active. Routing IMU and GNSS records.",
                    )
                }
            }
            for (record in channel) {
                // Counted as accepted only here, when the record is actually handed to the
                // engine. A record admitted by offer() but abandoned before this point is
                // counted as an ingress drop instead, so the counters never overlap.
                when (record.event.data) {
                    is ImuMeasurement -> {
                        countAccepted(imu = true)
                        engine.acceptImu(record)
                    }
                    is GnssMeasurement -> {
                        countAccepted(imu = false)
                        engine.acceptGnss(record)
                    }
                    // Unreachable: offer() admits only IMU and GNSS payloads.
                    else -> Unit
                }
                drainEngine()
            }
            // Closed with everything routed: one final drain, then one clean stop.
            drainEngine()
            engine.stop()
            engineStopped = true
            // Counters survive a finished session, as the state contract promises, so the tallies
            // of a completed run stay readable for diagnostics; session identity does not.
            // Only start() and reset() clear them.
            synchronized(lock) {
                mutable.value = mutable.value.copy(
                    phase = NavigationPhase.IDLE,
                    sessionId = null,
                    source = null,
                    message = "Session stopped cleanly. Explicit start required.",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { engine.stop() }
            throw e
        } catch (t: Throwable) {
            fail(t, engineStopped)
        }
    }

    private fun countAccepted(imu: Boolean) {
        synchronized(lock) {
            val current = mutable.value
            mutable.value = if (imu) {
                current.copy(acceptedImu = current.acceptedImu + 1)
            } else {
                current.copy(acceptedGnss = current.acceptedGnss + 1)
            }
        }
    }

    private fun drainEngine() {
        for (record in engine.drain()) {
            // CalibrationResult belongs here wherever it comes from: an engine that establishes
            // the vehicle frame must be able to publish it, because it is the only record a
            // consumer can use to know which transform is in force.
            val canonical = when (record.event.data) {
                is NavigationState, is GnssQualityState, is Confidence, is DiagnosticEvent,
                is CalibrationResult -> true

                else -> false
            }
            synchronized(lock) {
                val current = mutable.value
                mutable.value = when {
                    !canonical -> current.copy(outputRejected = current.outputRejected + 1)
                    record.event.data is NavigationState ->
                        current.copy(engineStatus = (record.event.data as NavigationState).status)
                    else -> current
                }
            }
            if (canonical && !outputFlow.tryEmit(record)) {
                synchronized(lock) { mutable.value = mutable.value.copy(outputDropped = mutable.value.outputDropped + 1) }
            }
        }
    }

    private fun fail(t: Throwable, engineStopped: Boolean) {
        // Teardown the broken engine BEFORE the failure is visible, so a concurrent
        // reset() can only ever meet a quiescent engine. Any teardown exception is
        // swallowed: the failure must stay contained in this worker.
        if (!engineStopped) runCatching { engine.stop() }
        val header: Header
        synchronized(lock) {
            var abandoned = 0L
            val channel = ingress
            if (channel != null) {
                while (channel.tryReceive().isSuccess) abandoned++
                channel.close()
            }
            header = session?.header ?: Header("none", Source.REAL)
            mutable.value = mutable.value.copy(
                phase = NavigationPhase.FAILED,
                ingressDropped = mutable.value.ingressDropped + abandoned,
                message = "Navigation engine failed (${t.message}); acquisition and recording are unaffected. Reset required.",
            )
        }
        publishDiagnostic(header, Severity.ERROR, "ENGINE_FAILURE", "Engine worker stopped: ${t.message}", 0)
    }

    private fun publishDiagnostic(header: Header, severity: Severity, code: String, message: String, count: Long) {
        val id: String
        synchronized(lock) { id = (nextEventId++).toString() }
        val t = nowNs()
        val record = Record(header, Event(id, t, t, DiagnosticEvent(severity, code, message, count)))
        if (!outputFlow.tryEmit(record)) {
            synchronized(lock) { mutable.value = mutable.value.copy(outputDropped = mutable.value.outputDropped + 1) }
        }
    }
}
