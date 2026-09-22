package com.intelligentdeadreckoning.app.recording

import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class RecorderPhase { IDLE, STARTING, RECORDING, FINALIZING, COMPLETED, FAILED, RECOVERED }
data class RecorderState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val recordingId: String? = null,
    val written: Long = 0,
    val dropped: Long = 0,
    val writeErrors: Long = 0,
    val eventIdGaps: Long = 0,
    val message: String = "Local recording is optional.",
) {
    val busy get() = phase in listOf(RecorderPhase.STARTING, RecorderPhase.RECORDING, RecorderPhase.FINALIZING)
}

interface RecordingOutput : AutoCloseable {
    fun append(bytes: ByteArray)
    fun sync()
}
interface RecordingStorage {
    fun create(metadata: RecordingMetadata): RecordingOutput
    fun finalize(metadata: RecordingMetadata)
    fun recover(): RecoverySummary
}
data class RecoverySummary(val recovered: Int = 0, val failed: Int = 0, val message: String = "No interrupted recordings.")

/** Downstream subscriber only. No acquisition ownership, Android APIs or UI dependencies.
 * The collector never waits for disk. On queue overflow admission stops, the accepted prefix
 * drains, and metadata is FAILED. The acquisition producer remains independent.
 * An independent scope permits bounded-queue finalization after the ViewModel is cleared.
 */
class LocalRecorder(
    private val storage: RecordingStorage,
    private val nanoTime: () -> Long,
    private val utcMillis: () -> Long,
    private val capacity: Int = 512,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val collectorDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    init { require(capacity > 0) }
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val lock = Any()
    private val mutable = MutableStateFlow(RecorderState())
    val state = mutable.asStateFlow()
    private var run: Run? = null
    private var loading = false
    private var closed = false
    private class Run(val metadata: RecordingMetadata, capacity: Int) {
        val queue = Channel<Record>(capacity)
        var collector: Job? = null
        var accepting = true
        var failure: String? = null
        var stopNs: Long? = null
        var stopUtc: Long? = null
    }

    fun loadInterrupted() = synchronized(lock) {
        check(run == null && !loading && !closed)
        loading = true
        mutable.value = RecorderState(RecorderPhase.STARTING, message = "Checking interrupted recordings…")
        scope.launch {
            val result = try { storage.recover() } catch (e: Exception) {
                RecoverySummary(failed = 1, message = "Recovery failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            synchronized(lock) {
                loading = false
                mutable.value = RecorderState(when {
                    result.failed > 0 -> RecorderPhase.FAILED
                    result.recovered > 0 -> RecorderPhase.RECOVERED
                    else -> RecorderPhase.IDLE
                }, message = result.message)
                if (closed) scope.cancel()
            }
        }
        Unit
    }

    /** Returns false for repeated start, startup recovery, or disposed owner. */
    fun start(metadata: RecordingMetadata, stream: Flow<Record>): Boolean = synchronized(lock) {
        if (closed || loading || run != null) return false
        require(metadata.completionState == CompletionState.OPEN)
        val active = Run(metadata, capacity)
        run = active
        mutable.value = RecorderState(RecorderPhase.STARTING, metadata.recordingId, message = "Opening private session…")
        // UNDISTPATCHED registers with SharedFlow before start returns; no file access here.
        active.collector = scope.launch(collectorDispatcher, start = CoroutineStart.UNDISPATCHED) {
            try {
                stream.collect { record ->
                    synchronized(lock) {
                        if (active.accepting) {
                            if (record.header.session_id != metadata.acquisitionSessionId || record.header.source != metadata.source) {
                                failAdmission(active, "SOURCE_SESSION_CHANGED", 1)
                            } else if (!active.queue.trySend(record).isSuccess) {
                                failAdmission(active, "RECORDING_QUEUE_OVERFLOW", 1)
                            }
                        }
                    }
                }
                synchronized(lock) { if (active.accepting) endAdmission(active, "Acquisition stream ended.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { synchronized(lock) { failAdmission(active, "STREAM_FAILURE: ${e.message}", 0) } }
        }
        scope.launch { write(active) }
        true
    }

    private fun endAdmission(active: Run, reason: String) {
        if (!active.accepting) return
        active.accepting = false
        active.stopNs = nanoTime().coerceAtLeast(active.metadata.clock.startedNs)
        active.stopUtc = utcMillis()
        active.collector?.cancel()
        active.queue.close()
        mutable.value = mutable.value.copy(phase = RecorderPhase.FINALIZING, message = reason)
    }
    private fun failAdmission(active: Run, reason: String, dropped: Long) {
        active.failure = active.failure ?: reason
        mutable.value = mutable.value.copy(dropped = mutable.value.dropped + dropped)
        endAdmission(active, reason)
    }
    fun stop(reason: String = "Explicit stop.") = synchronized(lock) {
        run?.let { endAdmission(it, reason) }
    }
    fun close() = synchronized(lock) {
        closed = true
        run?.let { endAdmission(it, "Owner stopped.") }
        if (run == null && !loading) scope.cancel()
    }

    private suspend fun write(active: Run) {
        var output: RecordingOutput? = null
        var count = 0L
        var pending = false
        val counts = linkedMapOf<String, Long>()
        val guard = AcquisitionRecordGuard(active.metadata)
        try {
            output = storage.create(active.metadata)
            synchronized(lock) {
                if (active.accepting) mutable.value = mutable.value.copy(phase = RecorderPhase.RECORDING, message = "Recording locally.")
            }
            for (record in active.queue) {
                pending = true
                val bytes = RecordingCodec.encodeRecord(record, active.metadata)
                val gaps = guard.accept(record)
                output.append(bytes)
                count++
                counts.merge(record.event.data.type, 1L, Long::plus)
                pending = false
                synchronized(lock) {
                    mutable.value = mutable.value.copy(written = count, eventIdGaps = mutable.value.eventIdGaps + gaps)
                }
            }
            output.sync()
        } catch (e: Exception) {
            synchronized(lock) {
                failAdmission(active, "WRITE_FAILURE: ${e.javaClass.simpleName}: ${e.message}", if (pending) 1 else 0)
                var remaining = 0L
                while (active.queue.tryReceive().isSuccess) remaining++
                mutable.value = mutable.value.copy(dropped = mutable.value.dropped + remaining, writeErrors = mutable.value.writeErrors + 1)
            }
        } finally {
            try { output?.close() } catch (e: Exception) {
                synchronized(lock) {
                    active.failure = active.failure ?: "CLOSE_FAILURE: ${e.message}"
                    mutable.value = mutable.value.copy(writeErrors = mutable.value.writeErrors + 1)
                }
            }
        }
        val failed = synchronized(lock) { active.failure != null }
        val finalized = active.metadata.copy(
            completionState = if (failed) CompletionState.FAILED else CompletionState.COMPLETED,
            endState = if (failed) RecordingEndState.FAILED else RecordingEndState.STOPPED,
            recoveryState = if (failed) RecoveryState.REQUIRED else RecoveryState.NONE,
            endedUtcMs = active.stopUtc ?: utcMillis(),
            clock = active.metadata.clock.copy(endedNs = maxOf(active.stopNs ?: nanoTime(), guard.lastReceived, active.metadata.clock.startedNs)),
            recordCount = count, channelCounts = counts.map { ChannelCount(it.key, it.value) },
        )
        // create() may have failed because the ID already exists. Never overwrite that session.
        try { if (output != null) storage.finalize(finalized) } catch (e: Exception) {
            synchronized(lock) {
                active.failure = "FINALIZATION_FAILURE: ${e.javaClass.simpleName}: ${e.message}; previous metadata preserved for recovery."
                mutable.value = mutable.value.copy(writeErrors = mutable.value.writeErrors + 1)
            }
        }
        synchronized(lock) {
            mutable.value = mutable.value.copy(
                phase = if (active.failure == null) RecorderPhase.COMPLETED else RecorderPhase.FAILED,
                message = active.failure ?: "Completed locally: $count records. Acquisition may continue.",
            )
            run = null
            if (closed) scope.cancel()
        }
    }
}

/** Acquisition allocates a measurement ID before emitting up to two diagnostics (late/gap).
 * Those diagnostics can arrive before their triggering measurement. Track a three-ID window
 * without sorting/renumbering. Older IDs cannot occur in this single-worker producer, so
 * rejecting them also detects duplicates outside the bounded window. Gap deltas may be
 * negative when the triggering measurement fills a diagnostic's temporary hole.
 */
internal class AcquisitionRecordGuard(private val metadata: RecordingMetadata) {
    private var previous: Long? = null
    private var first: Long? = null
    private val seen = hashSetOf<Long>()
    var lastReceived: Long = metadata.clock.startedNs; private set
    fun accept(record: Record): Long {
        require(record.header.session_id == metadata.acquisitionSessionId && record.header.source == metadata.source) { "SESSION_MISMATCH" }
        val id = record.event.event_id.toLongOrNull()
        require(id != null && id >= 0 && id.toString() == record.event.event_id) { "NON_ACQUISITION_EVENT_ID" }
        require(id !in seen && (previous == null || id >= previous!! - 2)) { "DUPLICATE_OR_OUT_OF_WINDOW_EVENT_ID" }
        require(record.event.t_ns >= metadata.clock.originNs) { "PRE_SESSION_RECORD" }
        val gaps = when {
            previous == null -> 0L
            id > previous!! -> id - previous!! - 1
            id < first!! -> first!! - id - 1
            else -> -1L
        }
        first = minOf(first ?: id, id)
        previous = maxOf(previous ?: id, id)
        seen.add(id)
        seen.removeAll { it < previous!! - 2 }
        lastReceived = maxOf(lastReceived, record.event.received_ns)
        return gaps
    }
}
