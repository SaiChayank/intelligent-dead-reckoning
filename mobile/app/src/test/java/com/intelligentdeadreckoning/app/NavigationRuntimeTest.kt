package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.navigation.ENGINE_OUTPUT_CONTRACT_VERSION
import com.intelligentdeadreckoning.app.navigation.NavigationPhase
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.app.navigation.UninitializedNavigationEngine
import com.intelligentdeadreckoning.contracts.v1.CalibrationResult
import com.intelligentdeadreckoning.contracts.v1.CalibrationStatus
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.ConfidenceState
import com.intelligentdeadreckoning.contracts.v1.DeviceFrame
import com.intelligentdeadreckoning.contracts.v1.DiagnosticEvent
import com.intelligentdeadreckoning.contracts.v1.EngineSession
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.GnssQualityState
import com.intelligentdeadreckoning.contracts.v1.GnssState
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.ImuMeasurement
import com.intelligentdeadreckoning.contracts.v1.ImuUnit
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationEngine
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.SensorAccuracy
import com.intelligentdeadreckoning.contracts.v1.Severity
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NavigationRuntime contract tests: the seam must keep an explicit engine-session
 * lifecycle (start/stop/reset, duplicate start rejected, no automatic restart), police
 * record ownership (source/session mismatch, replay isolation), stay bounded under
 * flood, and contain engine failure so producers never see it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationRuntimeTest {

    private class FakeEngine : NavigationEngine {
        val sessions = mutableListOf<EngineSession>()
        val modes = mutableListOf<InitializationMode>()
        val imu = mutableListOf<Record>()
        val gnss = mutableListOf<Record>()
        var stops = 0
        var resets = 0
        var throwOnAccept = false
        var throwOnInitialize = false
        var failReset = false
        private val queued = ArrayDeque<Record>()

        fun queue(vararg records: Record) = queued.addAll(records)

        override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) {
            if (throwOnInitialize) throw IllegalStateException("init exploded")
            sessions += session
            modes += mode
        }

        override fun acceptImu(measurement: Record) {
            if (throwOnAccept) throw IllegalStateException("engine exploded")
            imu += measurement
        }

        override fun acceptGnss(measurement: Record) {
            if (throwOnAccept) throw IllegalStateException("engine exploded")
            gnss += measurement
        }

        override fun drain(): Sequence<Record> {
            val out = queued.toList()
            queued.clear()
            return out.asSequence()
        }

        override fun stop() { stops++ }
        override fun reset() { resets++; if (failReset) throw IllegalStateException("reset exploded") }
    }

    // The scope is the TestScope itself (same pattern as ReplayTest): backgroundScope work
    // is not advanced by advanceUntilIdle with a StandardTestDispatcher in this coroutines-test
    // version. close() at each test's end releases the worker so runTest can finish.
    private fun TestScope.runtime(engine: FakeEngine, ingress: Int = 256) = NavigationRuntime(
        engine, this, { 0L }, StandardTestDispatcher(testScheduler), ingressCapacity = ingress,
    )

    private fun imu(header: Header, id: Long) = Record(
        header,
        Event(id.toString(), 1_000L + id, 1_000L + id, ImuMeasurement(
            Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE, ImuUnit.METRES_PER_SECOND_SQUARED,
            Vector3(0.0, 0.0, 9.81), SensorAccuracy.HIGH,
        )),
    )

    private fun gnss(header: Header, id: Long) = Record(
        header,
        Event(id.toString(), 1_000L + id, 1_000L + id, GnssMeasurement(
            17.4250, 78.4750, null, null, null, null, 8.0, null, 7, "gps", null,
        )),
    )

    @Test
    fun startStopAndResetOwnExactlyOneEngineSession() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        val live = Header("session-a", Source.REAL, "1.1.0")

        assertTrue(runtime.start(live, 42L))
        advanceUntilIdle()
        assertEquals(NavigationPhase.RUNNING, runtime.state.value.phase)
        assertEquals("session-a", engine.sessions.single().header.session_id)
        assertEquals(42L, engine.sessions.single().origin_ns)

        assertTrue(runtime.offer(imu(live, 1)))
        assertTrue(runtime.offer(gnss(live, 2)))
        advanceUntilIdle()
        assertEquals(1, engine.imu.size)
        assertEquals(1, engine.gnss.size)

        assertTrue(runtime.stop())
        advanceUntilIdle()
        assertEquals(NavigationPhase.IDLE, runtime.state.value.phase)
        assertEquals(1, engine.stops)
        assertNull(runtime.state.value.sessionId)
        assertNull(runtime.state.value.source)
        // A finished session stays readable: stop() keeps the tallies, reset() clears them.
        assertEquals(1, runtime.state.value.acceptedImu)
        assertEquals(1, runtime.state.value.acceptedGnss)
        assertFalse(runtime.offer(imu(live, 3)))

        assertTrue(runtime.reset())
        assertEquals(1, engine.resets)
        assertEquals(0, runtime.state.value.acceptedImu)

        assertTrue(runtime.start(live, 42L))
        advanceUntilIdle()
        assertEquals(2, engine.sessions.size)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun stopRequestedBeforeInitializationCompletesNeverReportsRunning() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        val live = Header("session-a", Source.REAL, "1.1.0")

        val phases = mutableListOf<NavigationPhase>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            runtime.state.collect { phases += it.phase }
        }

        // The worker is queued on a StandardTestDispatcher, so stop() lands while
        // initialize() has still not run.
        assertTrue(runtime.start(live, 0L))
        assertTrue(runtime.stop())
        assertEquals(NavigationPhase.STOPPING, runtime.state.value.phase)
        advanceUntilIdle()

        assertEquals(NavigationPhase.IDLE, runtime.state.value.phase)
        assertEquals(1, engine.stops)
        assertFalse(
            "an observer must never see RUNNING after requesting a stop",
            phases.contains(NavigationPhase.RUNNING),
        )
        collector.cancel()
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun duplicateStartIsRejectedWithoutTouchingTheSession() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        assertTrue(runtime.start(Header("session-a", Source.REAL, "1.1.0"), 0L))
        advanceUntilIdle()

        assertFalse(runtime.start(Header("session-b", Source.REAL, "1.1.0"), 99L))
        advanceUntilIdle()
        assertEquals(1, engine.sessions.size)
        assertEquals("session-a", runtime.state.value.sessionId)
        assertEquals(NavigationPhase.RUNNING, runtime.state.value.phase)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun engineSessionRefusesAContractVersionThatCannotCarryEngineOutput() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        // The engine output stream speaks the navigation exchange contract; a session bound to
        // 1.0.0 could not encode a localization_mode and is refused without side effects.
        assertFalse(runtime.start(Header("session-a", Source.REAL), 0L))
        advanceUntilIdle()
        assertTrue(engine.sessions.isEmpty())
        assertEquals(NavigationPhase.IDLE, runtime.state.value.phase)
        assertEquals(Header("session-a", Source.REAL, "1.1.0"), Header("session-a", Source.REAL, ENGINE_OUTPUT_CONTRACT_VERSION))
        assertTrue(runtime.start(Header("session-a", Source.REAL, ENGINE_OUTPUT_CONTRACT_VERSION), 0L))
        advanceUntilIdle()
        assertEquals(1, engine.sessions.size)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun recordsOfAnotherSourceOrSessionNeverReachTheEngine() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        advanceUntilIdle()

        // Right session, wrong source.
        assertFalse(runtime.offer(imu(Header("session-a", Source.SIMULATION, "1.1.0"), 1)))
        // Right source, wrong session.
        assertFalse(runtime.offer(imu(Header("session-b", Source.REAL, "1.1.0"), 2)))
        // Right header, wrong payload type for the engine.
        assertFalse(runtime.offer(Record(live, Event("3", 1_003L, 1_003L, DiagnosticEvent(
            Severity.INFO, "NOTE", "not routable", 0,
        )))))
        advanceUntilIdle()
        assertTrue(engine.imu.isEmpty())
        assertEquals(3, runtime.state.value.rejected)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun replayRowsAreIsolatedFromLiveSessionsAndViceVersa() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        advanceUntilIdle()

        // A replayed row of the same recorded session id must not enter the live session.
        val replayed = Header("session-a", Source.REPLAY_REAL, "1.1.0")
        assertFalse(runtime.offer(imu(replayed, 1)))
        assertEquals(1, runtime.state.value.rejected)

        // The mirror case: a replay session rejects live rows and routes replayed ones.
        assertTrue(runtime.stop())
        advanceUntilIdle()
        val replaySession = Header("recording-1", Source.REPLAY_SIMULATION, "1.1.0")
        assertTrue(runtime.start(replaySession, 0L))
        advanceUntilIdle()
        assertFalse(runtime.offer(imu(live, 2)))
        assertTrue(runtime.offer(imu(replaySession, 3)))
        advanceUntilIdle()
        assertEquals(1, engine.imu.size)
        // Counters belong to the new engine session; the earlier violation is not carried over.
        assertEquals(1, runtime.state.value.rejected)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun backgroundStopRoutesAcceptedRowsThenStopsOnceWithoutAutoRestart() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        advanceUntilIdle()

        repeat(3) { i -> assertTrue(runtime.offer(imu(live, i.toLong()))) }
        // What onBackground() calls: everything accepted is still routed, then one stop.
        assertTrue(runtime.stop())
        advanceUntilIdle()
        assertEquals(3, engine.imu.size)
        assertEquals(1, engine.stops)
        assertEquals(NavigationPhase.IDLE, runtime.state.value.phase)

        // Nothing may come back without an explicit start.
        assertFalse(runtime.offer(imu(live, 9)))
        repeat(3) { advanceUntilIdle() }
        assertEquals(1, engine.sessions.size)
        assertEquals(1, engine.stops)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun switchingSourceStopsTheOldSessionBeforeTheNewOneBinds() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        advanceUntilIdle()

        // A source change must stop first; a second session while one runs is refused.
        assertFalse(runtime.start(Header("session-b", Source.SIMULATION, "1.1.0"), 7L))
        assertTrue(runtime.stop())
        advanceUntilIdle()
        val simulation = Header("session-b", Source.SIMULATION, "1.1.0")
        assertTrue(runtime.start(simulation, 7L))
        advanceUntilIdle()

        assertFalse(runtime.offer(imu(live, 1)))
        assertTrue(runtime.offer(imu(simulation, 2)))
        advanceUntilIdle()
        assertEquals(1, engine.imu.size)
        assertEquals(1, engine.stops)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun ingressIsBoundedAndDropsAreCounted() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine, ingress = 8)
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        // Deliberately no advanceUntilIdle before the flood: while the worker has not started
        // consuming, the bound is exact — nothing can be hand-delivered to a waiting receiver.
        var accepted = 0
        var refused = 0
        repeat(20) { i -> if (runtime.offer(imu(live, i.toLong()))) accepted++ else refused++ }
        assertEquals(8, accepted)
        assertEquals(12, refused)
        assertEquals(12, runtime.state.value.ingressDropped)

        advanceUntilIdle()
        assertEquals(8, engine.imu.size)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun engineFailureIsContainedAndPropagatedAsStateAndDiagnostic() = runTest {
        val engine = FakeEngine()
        engine.throwOnAccept = true
        val runtime = runtime(engine)
        val outputs = mutableListOf<Record>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runtime.output.collect { outputs += it } }
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        advanceUntilIdle()

        // The producer-side call must never throw, even when the engine does.
        assertTrue(runtime.offer(imu(live, 1)))
        advanceUntilIdle()
        val state = runtime.state.value
        assertEquals(NavigationPhase.FAILED, state.phase)
        assertTrue(state.message.contains("acquisition and recording are unaffected"))
        val failure = outputs.map { it.event.data }.filterIsInstance<DiagnosticEvent>().single { it.code == "ENGINE_FAILURE" }
        assertEquals(Severity.ERROR, failure.severity)

        // Contained: later offers are refused quietly, and the engine was torn down once.
        assertFalse(runtime.offer(imu(live, 2)))
        advanceUntilIdle()
        assertEquals(1, engine.stops)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun initializationFailureIsContainedAndNoSpatialOutputEscapes() = runTest {
        val engine = FakeEngine().apply { throwOnInitialize = true }
        val runtime = runtime(engine)
        val outputs = mutableListOf<Record>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runtime.output.collect { outputs += it } }
        assertTrue(runtime.start(Header("session-init-failure", Source.REAL, "1.1.0"), 0L))
        advanceUntilIdle()
        assertEquals(NavigationPhase.FAILED, runtime.state.value.phase)
        assertTrue(runtime.state.value.message.contains("acquisition and recording are unaffected"))
        assertEquals(1, outputs.map { it.event.data }.filterIsInstance<DiagnosticEvent>().count { it.code == "ENGINE_FAILURE" })
        assertTrue(outputs.none { it.event.data is NavigationState })
        assertFalse(runtime.offer(imu(Header("session-init-failure", Source.REAL, "1.1.0"), 1)))
        runtime.close(); advanceUntilIdle()
    }

    @Test
    fun resetFailureRemainsContainedAndKeepsTheRuntimeFailed() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        engine.failReset = true
        assertFalse(runtime.reset())
        assertEquals(NavigationPhase.FAILED, runtime.state.value.phase)
        assertTrue(runtime.state.value.message.contains("failed to reset"))
        engine.failReset = false
        assertTrue(runtime.reset())
        assertEquals(NavigationPhase.IDLE, runtime.state.value.phase)
        runtime.close(); advanceUntilIdle()
    }

    @Test
    fun failureIsTerminalUntilAnExplicitReset() = runTest {
        val engine = FakeEngine()
        engine.throwOnAccept = true
        val runtime = runtime(engine)
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        advanceUntilIdle()
        assertTrue(runtime.offer(imu(live, 1)))
        advanceUntilIdle()
        assertEquals(NavigationPhase.FAILED, runtime.state.value.phase)

        // No automatic restart, and a new session needs the failure cleared first.
        repeat(3) { advanceUntilIdle() }
        assertEquals(1, engine.sessions.size)
        assertFalse(runtime.start(Header("session-b", Source.REAL, "1.1.0"), 0L))
        assertTrue(runtime.reset())
        assertEquals(1, engine.resets)
        assertTrue(runtime.start(Header("session-b", Source.REAL, "1.1.0"), 0L))
        advanceUntilIdle()
        assertEquals(2, engine.sessions.size)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun resetIsRejectedWhileARunningSessionExists() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        assertTrue(runtime.start(Header("session-a", Source.REAL, "1.1.0"), 0L))
        advanceUntilIdle()
        assertFalse(runtime.reset())
        advanceUntilIdle()
        assertEquals(0, engine.resets)
        assertEquals(NavigationPhase.RUNNING, runtime.state.value.phase)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun onlyCanonicalNavigationOutputIsPublished() = runTest {
        val engine = FakeEngine()
        val runtime = runtime(engine)
        val outputs = mutableListOf<Record>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { runtime.output.collect { outputs += it } }
        val live = Header("session-a", Source.REAL, "1.1.0")
        assertTrue(runtime.start(live, 0L))
        advanceUntilIdle()

        engine.queue(
            Record(live, Event("1", 1L, 1L, NavigationState(
                NavigationStatus.CALIBRATING, InitializationMode.EVALUATION,
                null, null, null, null, null, null, false, null,
            ))),
            Record(live, Event("2", 2L, 2L, GnssQualityState(GnssState.ACQUIRING, null, null, listOf("NO_FIX")))),
            Record(live, Event("3", 3L, 3L, Confidence(ConfidenceState.UNAVAILABLE, null, null, null))),
            Record(live, Event("4", 4L, 4L, DiagnosticEvent(Severity.INFO, "X", "y", 0))),
            // An engine may not echo measurements as output; this one must be dropped.
            Record(live, Event("5", 5L, 5L, ImuMeasurement(
                Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE, ImuUnit.METRES_PER_SECOND_SQUARED,
                Vector3(0.0, 0.0, 9.81), SensorAccuracy.HIGH,
            ))),
        )
        assertTrue(runtime.offer(imu(live, 6)))
        advanceUntilIdle()

        assertEquals(4, outputs.size)
        assertEquals(1, runtime.state.value.outputRejected)
        assertEquals(NavigationStatus.CALIBRATING, runtime.state.value.engineStatus)
        runtime.close()
        advanceUntilIdle()
    }

    @Test
    fun placeholderEngineEstimatesNothingAndSaysSo() {
        val engine = UninitializedNavigationEngine()
        val header = Header("session-a", Source.REAL, "1.1.0")
        engine.initialize(
            EngineSession(header, "boot", 0L),
            Record(header, Event("0", 0L, 0L, CalibrationResult("none", CalibrationStatus.PENDING, null, null, null, null))),
            InitializationMode.EVALUATION,
        )
        engine.acceptImu(imu(header, 1))
        engine.acceptGnss(gnss(header, 2))

        val out = engine.drain().toList()
        val navigation = out.map { it.event.data }.filterIsInstance<NavigationState>().single()
        assertEquals(NavigationStatus.UNINITIALIZED, navigation.status)
        assertNull(navigation.position_enu_m)
        assertNull(navigation.velocity_enu_m_s)
        assertNull(navigation.heading_deg)
        assertFalse(navigation.gnss_used_after_initialization)
        val confidence = out.map { it.event.data }.filterIsInstance<Confidence>().single()
        assertEquals(ConfidenceState.UNAVAILABLE, confidence.state)
        val notice = out.map { it.event.data }.filterIsInstance<DiagnosticEvent>().single()
        assertEquals("NAVIGATION_NOT_IMPLEMENTED", notice.code)

        // Accepting measurements fabricates nothing further.
        assertEquals(0, engine.drain().count())
    }
}
