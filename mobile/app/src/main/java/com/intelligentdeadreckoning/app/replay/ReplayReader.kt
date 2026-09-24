package com.intelligentdeadreckoning.app.replay

import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import java.io.*

/** Exact duplicate detection with fixed 16 MiB storage, at most 1M events, no payload history.
 * Open addressing; -1 is safe because canonical event IDs are nonnegative Int64. */
private class EventIds {
    private val slots = LongArray(1 shl 21) { -1L }
    fun add(id: Long) {
        var index = ((id xor (id ushr 32)) * -7046029254386353131L).ushr(43).toInt()
        while (slots[index] != -1L) {
            require(slots[index] != id) { "DUPLICATE_EVENT" }
            index = (index + 1) and (slots.size - 1)
        }
        slots[index] = id
    }
}

/** Strict, read-only, arrival-order reader. Caller owns input lifetime. */
class ReplayReader(private val input: InputStream, private val metadata: RecordingMetadata) {
    private val ids = EventIds()
    private var count = 0L
    private val channels = mutableMapOf<String, Long>()
    private var mode: InitializationMode? = null
    init { require(metadata.recordCount != null && metadata.recordCount <= 1_000_000L) { "REPLAY_RECORD_LIMIT: maximum 1000000 records" } }
    fun next(): Record? {
        try {
            val bytes = ByteArrayOutputStream()
            var ended = false
            while (true) {
                val b = input.read()
                if (b < 0) { ended = true; break }
                if (b == 10) break
                require(bytes.size() < Limits().maxRecordBytes + 1) { "RECORD_TOO_LARGE" }
                bytes.write(b)
            }
            var raw = bytes.toByteArray()
            if (ended && raw.isEmpty()) {
                require(count == metadata.recordCount) { "RECORD_COUNT_MISMATCH" }
                metadata.channelCounts?.let { expected ->
                    require(expected.filter { it.count != 0L }.associate { it.channel to it.count } == channels) { "CHANNEL_COUNT_MISMATCH" }
                }
                return null
            }
            if (!ended && raw.lastOrNull() == 13.toByte()) raw = raw.copyOf(raw.size - 1)
            require(count < 1_000_000L) { "REPLAY_RECORD_LIMIT" }
            val record = RecordingCodec.decodeRecord(raw, metadata)
            ids.add(record.event.event_id.toLong())
            require(record.event.t_ns >= metadata.clock.originNs) { "PRE_SESSION_RECORD" }
            (record.event.data as? NavigationState)?.let {
                require(mode == null || mode == it.initialization_mode) { "INITIALIZATION_MODE_CHANGED" }
                mode = it.initialization_mode
            }
            count++
            require(count <= metadata.recordCount!!) { "RECORD_COUNT_MISMATCH" }
            channels.merge(record.event.data.type, 1L, Long::plus)
            return record.copy(header = record.header.copy(source = replaySource(metadata.source)))
        } catch (e: Exception) {
            throw IOException("Replay line ${count + 1}: ${e.message}", e)
        }
    }
}
