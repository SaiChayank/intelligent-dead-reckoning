package com.intelligentdeadreckoning.app.replay

import com.intelligentdeadreckoning.app.sessions.SessionFiles
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ReplayPhase { IDLE, LOADING, PLAYING, PAUSED, STOPPING, STOPPED, COMPLETED, FAILED }
data class ReplayState(val phase: ReplayPhase = ReplayPhase.IDLE, val id: String? = null,
    val source: Source? = null, val emitted: Long = 0, val total: Long? = null,
    val elapsedNs: Long = 0, val latest: Record? = null, val incomplete: Boolean = false,
    val message: String = "Select a saved session. Replay is not navigation.") {
    val busy get() = phase in listOf(ReplayPhase.LOADING, ReplayPhase.PLAYING, ReplayPhase.PAUSED, ReplayPhase.STOPPING)
}

/** Commands and state run on owner dispatcher (Main in app). File work runs on IO.
 * Rendezvous/backpressure: emit suspends for active slow subscribers; no drops or unbounded queues.
 * Without subscribers, playback still advances for the bounded diagnostics snapshot. */
class ReplayController(private val files: SessionFiles, private val scope: CoroutineScope,
                       private val nowNs: () -> Long, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutable = MutableStateFlow(ReplayState())
    val state = mutable.asStateFlow()
    private val output = MutableSharedFlow<Record>()
    val events = output.asSharedFlow()
    private var job: Job? = null
    private var wallOrigin = 0L
    private var pauseAt: Long? = null
    private var pausedNs = 0L
    fun start(id: String): Boolean {
        if (mutable.value.busy) return false
        mutable.value = ReplayState(ReplayPhase.LOADING, id, message = "Opening read-only session…")
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Resource lifetime stays inside IO, including cancellation at dispatcher boundaries.
                withContext(io) {
                    val (metadata, input) = files.openReplay(id)
                    input.use {
                        val reader = ReplayReader(input, metadata)
                        withContext(scope.coroutineContext.minusKey(Job)) {
                            wallOrigin = nowNs(); pausedNs = 0; pauseAt = null
                            mutable.value = mutable.value.copy(phase = ReplayPhase.PLAYING,
                                source = replaySource(metadata.source), total = metadata.recordCount,
                                incomplete = metadata.completionState == CompletionState.INCOMPLETE,
                                message = "1× recorded arrival timing · no live sensors or navigation")
                        }
                        var firstReceived: Long? = null
                        var schedule = 0L
                        while (true) {
                            ensureActive()
                            val record = reader.next() ?: break
                            val first = firstReceived ?: record.event.received_ns.also { firstReceived = it }
                            schedule = maxOf(schedule, (record.event.received_ns - first).coerceAtLeast(0))
                            withContext(scope.coroutineContext.minusKey(Job)) {
                                while (true) {
                                    ensureActive()
                                    if (mutable.value.phase == ReplayPhase.PAUSED) {
                                        mutable.first { it.phase != ReplayPhase.PAUSED }
                                        continue
                                    }
                                    val remaining = schedule - (nowNs() - wallOrigin - pausedNs)
                                    if (remaining <= 0) break
                                    delay(minOf(remaining / 1_000_000L + 1, 20L))
                                }
                                output.emit(record)
                                mutable.value = mutable.value.copy(emitted = mutable.value.emitted + 1,
                                    elapsedNs = schedule, latest = record)
                            }
                        }
                    }
                }
                mutable.value = mutable.value.copy(phase = ReplayPhase.COMPLETED, message = "Replay completed and counts verified; not live acquisition.")
            } catch (e: CancellationException) {
                mutable.value = mutable.value.copy(phase = ReplayPhase.STOPPED, message = "Replay stopped; explicit start required.")
            } catch (e: Exception) {
                mutable.value = mutable.value.copy(phase = ReplayPhase.FAILED, message = "${e.message}. Playback aborted; any emitted prefix is not a validated whole session.")
            }
        }
        return true
    }
    fun pause() {
        if (mutable.value.phase == ReplayPhase.PLAYING) {
            pauseAt = nowNs(); mutable.value = mutable.value.copy(phase = ReplayPhase.PAUSED)
        }
    }
    fun resume() {
        if (mutable.value.phase == ReplayPhase.PAUSED) {
            pausedNs += nowNs() - pauseAt!!; pauseAt = null
            mutable.value = mutable.value.copy(phase = ReplayPhase.PLAYING)
        }
    }
    fun stop() {
        if (mutable.value.busy) {
            mutable.value = mutable.value.copy(phase = ReplayPhase.STOPPING)
            job?.cancel()
        }
    }
}
