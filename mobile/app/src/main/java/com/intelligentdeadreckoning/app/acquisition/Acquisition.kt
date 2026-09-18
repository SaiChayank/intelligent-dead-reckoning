package com.intelligentdeadreckoning.app.acquisition

import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class InputSource { SIMULATION, REAL }
enum class LocationAccess { NOT_REQUESTED, DENIED, APPROXIMATE, PRECISE, REVOKED }

fun locationAccess(fine: Boolean, coarse: Boolean, requested: Boolean, previouslyGranted: Boolean) = when {
    fine -> LocationAccess.PRECISE
    coarse -> LocationAccess.APPROXIMATE
    previouslyGranted -> LocationAccess.REVOKED
    requested -> LocationAccess.DENIED
    else -> LocationAccess.NOT_REQUESTED
}

interface SourceControl {
    fun start()
    fun stop()
}

/** Called on the UI dispatcher. Starting requires a foreground owner and an explicit action. */
class SourceCoordinator(private val simulation: SourceControl, private val real: SourceControl) {
    private val selection = MutableStateFlow(InputSource.SIMULATION)
    val source = selection.asStateFlow()
    private var foreground = false
    private fun current() = if (selection.value == InputSource.REAL) real else simulation
    fun foreground() { foreground = true }
    fun start() { if (foreground) current().start() }
    fun stop() { current().stop() }
    fun background() { foreground = false; simulation.stop(); real.stop() }
    fun select(source: InputSource) {
        if (selection.value == source) return
        simulation.stop(); real.stop()
        selection.value = source
    }
}

data class SensorInfo(val name: String, val vendor: String, val available: Boolean, val requestedHz: Double)
fun sensorPeriodUs(kind: Sensor) = if (kind == Sensor.MAGNETOMETER || kind == Sensor.GRAVITY) 20_000 else 10_000
fun registerSensor(kind: Sensor, name: String?, vendor: String?, register: () -> Boolean): SensorInfo =
    SensorInfo(name ?: "Unavailable", vendor ?: "Unknown", name != null && register(), 1_000_000.0 / sensorPeriodUs(kind))
data class Sample(val tNs: Long, val receivedNs: Long, val payload: Payload, val locationEpoch: Long = 0)

class BoundedInbox<T>(capacity: Int = 256) {
    private val queue = ArrayBlockingQueue<T>(capacity)
    private val drops = AtomicLong()
    private val peak = AtomicInteger()
    val size get() = queue.size
    val highWater get() = peak.get()
    fun offer(value: T): Boolean {
        if (!queue.offer(value)) { drops.incrementAndGet(); return false }
        peak.accumulateAndGet(queue.size, ::maxOf)
        return true
    }
    fun poll(): T? = queue.poll()
    fun takeDrops(): Long = drops.getAndSet(0)
    fun clear() = queue.clear()
}
data class ChannelReading(val record: Record, val count: Long, val rateHz: Double?, val stale: Boolean)
data class CaptureState(
    val running: Boolean = false,
    val sessionId: String? = null,
    val originNs: Long? = null,
    val permission: LocationAccess = LocationAccess.NOT_REQUESTED,
    val sensors: Map<Sensor, SensorInfo> = emptyMap(),
    val readings: Map<String, ChannelReading> = emptyMap(),
    val diagnostics: List<Record> = emptyList(),
    val accepted: Long = 0,
    val delayed: Long = 0,
    val duplicates: Long = 0,
    val dropped: Long = 0,
    val invalid: Long = 0,
    val queueDepth: Int = 0,
    val queueHighWater: Int = 0,
    val satellitesVisible: Int? = null,
    val satellitesUsed: Long? = null,
    val quality: GnssQualityState = GnssQualityState(GnssState.UNAVAILABLE, null, null, listOf("NOT_STARTED")),
    val message: String = "Ready. Start to read phone sensors.",
)

/** Subtract integers FIRST. Absolute ns timestamps never pass through Double. */
fun elapsedSeconds(timestampNs: Long, originNs: Long): Double {
    require(originNs >= 0 && timestampNs >= originNs)
    return (timestampNs - originNs).toDouble() / 1_000_000_000.0
}

/** Android has* flags determine nulls before these range checks. No missing field becomes zero. */
fun gnssPayload(latitude: Double, longitude: Double, altitude: Double?, speed: Double?, bearing: Double?,
                horizontalAccuracy: Double?, verticalAccuracy: Double?, satellites: Long?,
                provider: String, utcMs: Long?): GnssMeasurement {
    fun nonnegative(x: Double?) = x?.takeIf { it.isFinite() && it >= 0 }
    val height = altitude?.takeIf { it.isFinite() }
    return GnssMeasurement(latitude, longitude, height, height?.let { AltitudeReference.ELLIPSOID },
        nonnegative(speed), bearing?.takeIf { it.isFinite() && it >= 0 && it < 360 },
        nonnegative(horizontalAccuracy), nonnegative(verticalAccuracy), satellites?.takeIf { it >= 0 },
        provider, utcMs?.takeIf { it >= 0 })
}

/** Single worker ownership. Bounded per-channel history; preserves accepted arrival order.
 * No calibration, integration, resampling, or wall-clock timestamp conversion.
 */
class AcquisitionProcessor(val header: Header, val originNs: Long, private val emit: (Record) -> Unit = {}) {
    companion object {
        const val LATE_NS = 100_000_000L
        const val FIX_STALE_NS = 5_000_000_000L
        const val HISTORY = 256
    }
    private data class Track(var latest: Record, var count: Long = 0,
                             val times: ArrayDeque<Long> = ArrayDeque(),
                             val seen: LinkedHashSet<Long> = linkedSetOf(), var floor: Long = -1)
    private val tracks = linkedMapOf<String, Track>()
    private val notices = ArrayDeque<Record>()
    private var nextId = 0L
    var accepted = 0L; private set
    var delayed = 0L; private set
    var duplicates = 0L; private set
    var dropped = 0L; private set
    var invalid = 0L; private set

    fun clearLocation() { tracks.keys.removeAll { it.startsWith("gnss_") } }
    fun discardForPermission(now: Long) {
        dropped++
        diagnostic("PERMISSION_CHANGED", "Queued location from previous permission state discarded.", now, 1)
    }
    fun stopped(count: Long, now: Long) {
        dropped += count
        diagnostic("SESSION_STOPPED", "Foreground session stopped; queued samples discarded.", now, count)
    }

    private fun record(t: Long, received: Long, payload: Payload) =
        Record(header, Event((nextId++).toString(), t, received, payload))

    fun diagnostic(code: String, message: String, now: Long, count: Long = 0) {
        val r = record(now, now, DiagnosticEvent(Severity.WARNING, code, message, count))
        if (notices.size == 16) notices.removeFirst()
        notices.addLast(r)
        emit(r)
    }

    fun overflow(count: Long, now: Long) {
        if (count <= 0) return
        dropped += count
        diagnostic("QUEUE_OVERFLOW", "Bounded acquisition or subscriber queue dropped samples.", now, count)
    }

    fun accept(sample: Sample) {
        val key = when (val p = sample.payload) {
            is ImuMeasurement -> p.sensor.wire
            is GnssMeasurement -> if (p.provider in listOf("gps", "network")) "gnss_${p.provider}" else {
                invalid++; dropped++; diagnostic("INVALID_MEASUREMENT", "Unknown acquisition provider.", sample.receivedNs.coerceAtLeast(0), 1); return
            }
            is DiagnosticEvent -> { diagnostic(p.code, p.message, sample.receivedNs.coerceAtLeast(0), p.dropped_count); return }
            else -> {
                invalid++; dropped++
                diagnostic("INVALID_MEASUREMENT", "Unsupported acquisition payload rejected.", sample.receivedNs.coerceAtLeast(0), 1)
                return
            }
        }
        val r = record(sample.tNs, sample.receivedNs, sample.payload)
        try {
            // Validates finite values, units, frames and timestamp relationships on this worker.
            Codec.encodeJson(r)
            require(sample.tNs >= originNs) // No cached/pre-session fixes in this foreground capture.
        } catch (_: IllegalArgumentException) {
            invalid++; dropped++
            diagnostic("INVALID_MEASUREMENT", "Invalid value or pre-session/future timestamp rejected.", sample.receivedNs.coerceAtLeast(0), 1)
            return
        }
        val track = tracks[key]
        if (track != null && sample.tNs in track.seen) {
            duplicates++; dropped++
            diagnostic("DUPLICATE_EVENT", "Duplicate sensor/provider timestamp rejected.", sample.receivedNs, 1)
            return
        }
        val tooOld = track != null && (sample.tNs <= track.floor ||
            (sample.tNs < track.latest.event.t_ns && track.latest.event.t_ns - sample.tNs > LATE_NS))
        if (sample.receivedNs - sample.tNs > LATE_NS || tooOld) {
            delayed++
            diagnostic("LATE_MEASUREMENT", if (tooOld) "Outside channel reorder window; rejected." else "Delayed receipt; original timestamps retained.",
                sample.receivedNs, if (tooOld) 1 else 0)
        }
        if (tooOld) { dropped++; return }
        val t = track ?: Track(r).also { tracks[key] = it }
        val newer = sample.tNs > t.latest.event.t_ns || t.count == 0L
        if (newer) {
            if (t.count > 0 && sample.tNs - t.latest.event.t_ns > (if (sample.payload is ImuMeasurement) LATE_NS else FIX_STALE_NS))
                diagnostic("TIME_GAP", "Gap between channel measurements.", sample.receivedNs)
            t.latest = r
            t.times.addLast(sample.tNs)
            while (t.times.size > HISTORY) t.times.removeFirst()
        }
        t.count++
        t.seen.add(sample.tNs)
        if (t.seen.size > HISTORY) {
            val oldest = t.seen.min(); t.seen.remove(oldest); t.floor = maxOf(t.floor, oldest)
        }
        accepted++
        emit(r)
    }

    fun snapshot(now: Long, access: LocationAccess, providerEnabled: Boolean, satellites: Long?): CaptureState {
        val readings = tracks.mapValues { (_, t) ->
            val staleAfter = if (t.latest.event.data is ImuMeasurement) 1_000_000_000L else FIX_STALE_NS
            val stale = now - t.latest.event.t_ns > staleAfter
            val hz = when {
                stale -> 0.0
                t.times.size < 2 || t.times.last <= t.times.first -> null
                else -> (t.times.size - 1) / elapsedSeconds(t.times.last, t.times.first)
            }
            ChannelReading(t.latest, t.count, hz, stale)
        }
        val fix = tracks.values.map { it.latest }.filter { it.event.data is GnssMeasurement }.maxByOrNull { it.event.t_ns }
        val age = fix?.let { elapsedSeconds(maxOf(now, it.event.t_ns), it.event.t_ns) }
        val quality = when {
            access !in listOf(LocationAccess.PRECISE, LocationAccess.APPROXIMATE) -> GnssQualityState(GnssState.DENIED, null, null, listOf("PERMISSION_DENIED"))
            !providerEnabled -> GnssQualityState(GnssState.UNAVAILABLE, age, null, listOf("PROVIDER_DISABLED"))
            age == null -> GnssQualityState(GnssState.ACQUIRING, null, satellites, listOf("NO_FIX"))
            age > 5 -> GnssQualityState(GnssState.STALE, age, satellites, listOf("STALE_FIX"))
            else -> GnssQualityState(GnssState.DEGRADED, age, satellites,
                listOf(if (access == LocationAccess.APPROXIMATE) "APPROXIMATE_LOCATION" else "QUALITY_NOT_VALIDATED"))
        }
        return CaptureState(true, header.session_id, originNs, access, readings = readings,
            diagnostics = notices.toList(), accepted = accepted, delayed = delayed, duplicates = duplicates,
            dropped = dropped, invalid = invalid, quality = quality)
    }
}
