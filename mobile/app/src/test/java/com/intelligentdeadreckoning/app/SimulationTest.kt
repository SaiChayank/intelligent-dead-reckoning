package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.simulation.*
import com.intelligentdeadreckoning.app.ui.duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SimulationTest {
    @Test fun startsReadyWithoutInventingMeasurements() = runTest {
        val controller = SimulationController(backgroundScope) { testScheduler.currentTime }
        assertEquals(SessionStatus.READY, controller.state.value.status)
        assertNull(controller.state.value.measurement)
        assertEquals(0, controller.state.value.sampleCount)
    }

    @Test fun startsAtZeroThenUsesElapsedMonotonicTime() = runTest {
        val controller = SimulationController(backgroundScope) { testScheduler.currentTime }
        controller.start()
        runCurrent()
        assertEquals(0, controller.state.value.measurement!!.elapsedMillis)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1000, controller.state.value.measurement!!.elapsedMillis)
        assertEquals(11, controller.state.value.sampleCount)
    }

    @Test fun repeatedStartCannotCreateDuplicateTickers() = runTest {
        val controller = SimulationController(backgroundScope) { testScheduler.currentTime }
        repeat(8) { controller.start() }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(11, controller.state.value.sampleCount)
    }

    @Test fun stopFreezesSnapshotAndCancelsPendingUpdates() = runTest {
        val controller = SimulationController(backgroundScope) { testScheduler.currentTime }
        controller.start()
        runCurrent()
        advanceTimeBy(400)
        runCurrent()
        controller.stop()
        val stopped = controller.state.value
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(stopped, controller.state.value)
        assertEquals(SessionStatus.STOPPED, stopped.status)
        assertEquals(StopReason.USER, stopped.stopReason)
    }

    @Test fun backgroundStopDoesNotResumeWithoutUserAction() = runTest {
        val controller = SimulationController(backgroundScope) { testScheduler.currentTime }
        controller.start()
        runCurrent()
        controller.stop(StopReason.BACKGROUND)
        advanceTimeBy(10_000)
        runCurrent()
        assertFalse(controller.state.value.isRunning)
        assertEquals(StopReason.BACKGROUND, controller.state.value.stopReason)
        assertEquals(1, controller.state.value.sampleCount)
    }

    @Test fun restartingCreatesFreshDeterministicSession() = runTest {
        val controller = SimulationController(backgroundScope) { testScheduler.currentTime }
        controller.start()
        val initial = controller.state.value
        runCurrent()
        advanceTimeBy(1500)
        runCurrent()
        controller.stop()
        controller.start()
        assertEquals(initial, controller.state.value)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(2, controller.state.value.sampleCount)
    }

    @Test fun stoppingBeforeStartOrTwiceIsHarmless() = runTest {
        val controller = SimulationController(backgroundScope) { testScheduler.currentTime }
        controller.stop()
        assertEquals(SimulationState(), controller.state.value)
        controller.start()
        controller.stop()
        val stopped = controller.state.value
        controller.stop(StopReason.BACKGROUND)
        assertEquals(stopped, controller.state.value)
    }

    @Test fun schedulerDelayDoesNotPretendItDeliveredMissingSamples() = runTest {
        var clock = 1000L
        val controller = SimulationController(backgroundScope) { clock }
        controller.start()
        runCurrent()
        clock += 5100
        advanceTimeBy(100)
        runCurrent()
        assertEquals(5100, controller.state.value.measurement!!.elapsedMillis)
        assertEquals(2, controller.state.value.sampleCount)
    }

    @Test fun elapsedClockNeverMovesBackwards() = runTest {
        var clock = 1000L
        val controller = SimulationController(backgroundScope) { clock }
        controller.start()
        runCurrent()
        clock = 1200
        advanceTimeBy(100)
        runCurrent()
        clock = 900
        advanceTimeBy(100)
        runCurrent()
        assertEquals(200, controller.state.value.measurement!!.elapsedMillis)
    }

    @Test fun signalsAreDeterministicFiniteAndWithinDisplayRange() {
        for (millis in 0L..600_000L step 100) {
            val sample = DemoSignal.at(millis)
            assertEquals(sample, DemoSignal.at(millis))
            assertTrue(sample.speedKmh in 0.0..48.0)
            assertTrue(sample.headingDegrees >= 0 && sample.headingDegrees < 360)
            listOf(sample.accelerometer, sample.gyroscope, sample.magnetometer).forEach {
                assertTrue(it.x.isFinite() && it.y.isFinite() && it.z.isFinite())
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSampleTimeIsRejected() { DemoSignal.at(-1) }

    @Test fun sessionDurationIncludesHoursWithoutWrappingAtOneHour() {
        assertEquals("00:00:00", duration(0))
        assertEquals("01:01:01", duration(3_661_000))
        assertEquals("100:00:00", duration(360_000_000))
    }
}
