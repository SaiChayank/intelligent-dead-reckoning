package com.intelligentdeadreckoning.app.map

import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** One observed interval with no current fix. It begins when the previous fix went stale and ends
 * when a fresh one arrived — time only. With no fix there is no distance to report, and nothing
 * anywhere estimates one.
 *
 * Named for what it is rather than for where it came from, because the scripted demo's blackout is
 * the same thing: a stretch of an observed clock with no fix on it. */
data class Outage(val startNs: Long, val endNs: Long) {
    val durationNs get() = (endNs - startNs).coerceAtLeast(0)
}

/** Real GNSS fixes folded into map display state, from a replay or from the live phone stream.
 *
 * The fold is identical for both: every rule below is about what a fix is, not where it came from.
 * What differs is the claim the map makes, so the presented `source` is explicit rather than
 * implied — replayed fixes are `REPLAY_REAL`, live phone fixes are `REAL`, and neither can be
 * labelled as the other.
 *
 * Display conversion only: no propagation, dead reckoning, sensor fusion, road snapping,
 * interpolation across a gap, or inference of a GNSS/DR/fused mode. A fix that is missing
 * is left missing — the drawn trail is split at every gap instead of bridging it, because a
 * straight line between two fixes twenty seconds apart is movement nobody observed.
 *
 * Those same breaks are retained as `Outage` intervals as well as counted, so a caller can draw
 * the loss history and not only its total. One derivation feeds both the drawn trail and any
 * timeline, so the two can never disagree about when the fixes stopped.
 *
 * Records are expected to have been validated by `ReplayReader`, which enforces session,
 * source and contract membership before they reach here.
 */
class RecordedSessionMap(
    private val maxTrail: Int = DEFAULT_MAX_TRAIL,
    private val gapThresholdNs: Long = DEFAULT_GAP_NS,
    /** Provider-specific freshness from the acquisition policy; defaults to the legacy 5 s view. */
    private val staleAfterForProvider: (String) -> Long = { gapThresholdNs },
    /** The claim the presentation makes about where these fixes came from. */
    private val source: Source = Source.REPLAY_REAL,
    private val maxOutages: Int = DEFAULT_MAX_OUTAGES,
) {
    init {
        require(maxTrail in 2..4096)
        require(gapThresholdNs > 0)
        require(maxOutages >= 1)
    }

    /** What the recording actually contained, so the UI can state it instead of implying more. */
    data class Stats(
        val fixes: Int,
        /** Multi-point segments actually plotted: movement observed between two fixes. */
        val lines: Int,
        /** Isolated fixes actually plotted. One fix is a position, not a path, so it is a dot. */
        val points: Int,
        val outsideCoverage: Int,
        val malformed: Int,
        val gaps: Int,
        val longestGapNs: Long,
        /** The retained outages, oldest first, bounded to the newest `maxOutages`. `gaps` above is
         * still the total over the whole stream, so a long run loses detail rather than the count. */
        val outages: List<Outage>,
        /** The observed window: first and last fix, which any timeline is drawn over. */
        val observedStartNs: Long?,
        val observedEndNs: Long?,
        val spanNs: Long,
        val providers: List<String>,
        val lastFixRadiusMetres: Double?,
    )

    private val open = ArrayList<MapPoint>()
    private val closed = ArrayList<List<MapPoint>>()
    private var drawnInSegments = 0
    private var previousFixNs: Long? = null
    private var previousProvider: String? = null
    private var previousPoint: MapPoint? = null
    private var point: MapPoint? = null
    private var heading: Double? = null
    private var speed: Double? = null
    private var radius: Double? = null
    private var status = "No recorded fix yet"
    private var fixes = 0
    private var outside = 0
    private var malformed = 0
    private var gaps = 0
    private var longestGap = 0L
    private val outages = ArrayList<Outage>()
    private var firstNs: Long? = null
    private var lastNs: Long? = null
    private val providers = LinkedHashSet<String>()

    /** Non-GNSS records are ignored: this display has no navigation record to show. */
    fun accept(record: Record): MapPresentation {
        val fix = record.event.data as? GnssMeasurement ?: return snapshot()
        val latitude = fix.latitude_deg
        val longitude = fix.longitude_deg
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            malformed++
            status = "Invalid recorded fix"
            return snapshot()
        }
        val time = record.event.t_ns
        val previousTime = previousFixNs
        if (previousTime != null && time <= previousTime) {
            // A malformed or out-of-order stream cannot move the marker backwards or reopen a
            // path across already-presented time. The caller normally validates replay order;
            // this defensive display gate protects the live fold and directly constructed data.
            malformed++
            status = "Out-of-order recorded fix — ignored"
            return snapshot()
        }
        if (firstNs == null) firstNs = time
        lastNs = time
        fixes++
        providers.add(fix.provider)

        val previous = previousFixNs
        val previousStaleAfter = previousProvider?.let(staleAfterForProvider) ?: gapThresholdNs
        previousFixNs = time
        previousProvider = fix.provider
        if (previous != null && time > previous && time - previous > previousStaleAfter) {
            gaps++
            longestGap = maxOf(longestGap, time - previous)
            // The loss began when the previous provider's validated cadence made its fix stale.
            outages.add(Outage(previous + previousStaleAfter, time))
            while (outages.size > maxOutages) outages.removeAt(0)
            closeSegment()
        }
        if (!HyderabadMap.contains(latitude, longitude)) {
            // The bundled pack only covers central Hyderabad; an outside fix is counted and
            // never drawn, and it also ends the current segment rather than bridging to it.
            outside++
            previousPoint = null
            heading = null
            closeSegment()
            status = "Recorded fix outside bundled coverage"
            return snapshot()
        }

        val current = MapPoint(latitude, longitude)
        val prior = previousPoint
        heading = fix.bearing_deg?.takeIf { it.isFinite() }
            ?: courseOverGround(prior, current)
        speed = fix.speed_m_s?.takeIf { it.isFinite() && it >= 0.0 }
        radius = fix.horizontal_accuracy_m?.takeIf { it.isFinite() && it > 0.0 }
        point = current
        previousPoint = current
        if (open.lastOrNull() != current) open.add(current)
        while (open.size > maxTrail) open.removeAt(0)
        status = "Recorded GNSS fix · ${fix.provider}"
        return snapshot()
    }

    fun snapshot(nowNs: Long? = null): MapPresentation {
        val staleAfter = previousProvider?.let(staleAfterForProvider) ?: gapThresholdNs
        if (nowNs != null && previousFixNs?.let { nowNs < it || nowNs - it > staleAfter } == true) {
            // A trail is history; its final fix is not current position after the channel's
            // freshness bound. Keep the recorded segments, but hide point, heading and live speed.
            return MapPresentation(
                source = source,
                trail = trail(),
                trailSegments = trailSegments(),
                status = "Stale fix — position hidden",
            )
        }
        return MapPresentation(
            source = source,
            point = point,
            headingDegrees = heading,
            speedMetresPerSecond = speed,
            // A recorded fix carries no calibrated 95% confidence, so that field stays empty and
            // the reported platform radius is carried separately rather than relabelled.
            accuracy95Metres = null,
            fixRadiusMetres = radius,
            trail = trail(),
            trailSegments = trailSegments(),
            status = status,
        )
    }

    /** Clear session-owned state at a capture boundary or a permission loss. */
    fun reset() {
        open.clear()
        closed.clear()
        drawnInSegments = 0
        previousFixNs = null
        previousProvider = null
        previousPoint = null
        point = null
        heading = null
        speed = null
        radius = null
        status = "No recorded fix yet"
        fixes = 0
        outside = 0
        malformed = 0
        gaps = 0
        longestGap = 0L
        outages.clear()
        firstNs = null
        lastNs = null
        providers.clear()
    }

    fun stats(): Stats {
        // Counted from the retained geometry, not from the fixes accepted, so this states what the
        // map is actually drawing rather than what the recording contained.
        val segments = trailSegments()
        return Stats(
            fixes = fixes,
            lines = segments.count { it.size >= 2 },
            points = segments.count { it.size == 1 },
            outsideCoverage = outside,
            malformed = malformed,
            gaps = gaps,
            longestGapNs = longestGap,
            outages = outages.toList(),
            observedStartNs = firstNs,
            observedEndNs = lastNs,
            // Derived from the same fields it reports, so the window and the span cannot disagree.
            spanNs = if (firstNs == null || lastNs == null) 0L else lastNs!! - firstNs!!,
            providers = providers.toList(),
            lastFixRadiusMetres = radius,
        )
    }

    private fun trail(): List<MapPoint> = trailSegments().flatten()

    /** The trail split at every gap and at every fix outside coverage. A one-point segment is a
     * position the recording really did observe, so it is kept and drawn as a dot; discarding it
     * would lose an observation, and joining it to the next fix would invent movement. */
    private fun trailSegments(): List<List<MapPoint>> {
        val segments = closed.toMutableList()
        if (open.isNotEmpty()) segments.add(open.toList())
        return segments
    }

    private fun closeSegment() {
        if (open.isNotEmpty()) {
            closed.add(open.toList())
            drawnInSegments += open.size
            while (drawnInSegments > maxTrail && closed.size > 1) {
                drawnInSegments -= closed.removeAt(0).size
            }
        }
        open.clear()
    }

    /** Course over ground from two observed fixes, or null when the step is too short to define one. */
    private fun courseOverGround(from: MapPoint?, to: MapPoint): Double? {
        if (from == null) return null
        val north = (to.latitude - from.latitude) * METRES_PER_DEGREE
        val east = (to.longitude - from.longitude) * METRES_PER_DEGREE * cos(Math.toRadians(to.latitude))
        if (hypot(east, north) < MIN_COURSE_METRES) return null
        return (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
    }

    companion object {
        const val DEFAULT_MAX_TRAIL = 512
        /** Outages retained for a timeline. Far more than a plausible drive, and bounded memory. */
        const val DEFAULT_MAX_OUTAGES = 256
        /** The same 5 s the acquisition contract uses before a location gap is reported. */
        const val DEFAULT_GAP_NS = 5_000_000_000L
        private const val METRES_PER_DEGREE = 111_320.0
        private const val MIN_COURSE_METRES = 3.0
    }
}
