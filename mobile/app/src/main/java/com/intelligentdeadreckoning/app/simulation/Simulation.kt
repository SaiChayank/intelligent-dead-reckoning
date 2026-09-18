package com.intelligentdeadreckoning.app.simulation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

data class Vector3(val x: Double, val y: Double, val z: Double)

/** Scripted UI values, NOT a sensor model, calibration or navigation output. */
data class DemoMeasurement(
    val elapsedMillis: Long,
    val speedKmh: Double,
    val headingDegrees: Double,
    val accelerometer: Vector3,
    val gyroscope: Vector3,
    val magnetometer: Vector3,
)

object DemoSignal {
    const val PERIOD_MILLIS = 100L

    fun at(elapsedMillis: Long): DemoMeasurement {
        require(elapsedMillis >= 0) { "Elapsed time must be nonnegative" }
        val seconds = elapsedMillis / 1000.0
        return DemoMeasurement(
            elapsedMillis = elapsedMillis,
            speedKmh = 24.0 * (1.0 - cos(seconds / 8.0)),
            headingDegrees = (45.0 + seconds * 2.0) % 360.0,
            accelerometer = Vector3(0.35 * sin(seconds), 0.65 * cos(seconds / 3), 9.81 + 0.06 * sin(seconds * 2)),
            gyroscope = Vector3(0.012 * sin(seconds), 0.008 * cos(seconds), 0.035 * sin(seconds / 4)),
            magnetometer = Vector3(22.0 + sin(seconds / 2), -6.0 + cos(seconds / 2), -39.0),
        )
    }
}

enum class SessionStatus { READY, RUNNING, STOPPED }
enum class StopReason { USER, BACKGROUND }

data class SimulationState(
    val status: SessionStatus = SessionStatus.READY,
    val measurement: DemoMeasurement? = null,
    val sampleCount: Long = 0,
    val stopReason: StopReason? = null,
) {
    val isRunning: Boolean get() = status == SessionStatus.RUNNING
}

/**
 * Owns a single cancellable job and a single conflated snapshot: no growing history or files.
 * Calls must be serialized on the owning (UI) dispatcher. Clock is monotonic milliseconds.
 * Start after Stop starts a NEW session; Stop preserves the last snapshot for inspection.
 */
class SimulationController(
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long,
) {
    private val mutableState = MutableStateFlow(SimulationState())
    val state: StateFlow<SimulationState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (mutableState.value.isRunning) return
        val start = clockMillis()
        mutableState.value = SimulationState(SessionStatus.RUNNING, DemoSignal.at(0), 1)
        job = scope.launch {
            while (isActive) {
                delay(DemoSignal.PERIOD_MILLIS)
                val elapsed = (clockMillis() - start).coerceAtLeast(
                    mutableState.value.measurement?.elapsedMillis ?: 0,
                )
                mutableState.value = mutableState.value.copy(
                    measurement = DemoSignal.at(elapsed),
                    sampleCount = mutableState.value.sampleCount + 1,
                )
            }
        }
    }

    fun stop(reason: StopReason = StopReason.USER) {
        job?.cancel()
        job = null
        if (mutableState.value.isRunning) {
            mutableState.value = mutableState.value.copy(status = SessionStatus.STOPPED, stopReason = reason)
        }
    }
}
