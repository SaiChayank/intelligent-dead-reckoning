package com.intelligentdeadreckoning.app.map

import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** Real recorded GNSS fixes folded into map display state.
 *
 * Display conversion only: no propagation, dead reckoning, sensor fusion, road snapping,
 * interpolation across a gap, or inference of a GNSS/DR/fused mode. A fix that is missing
 * is left missing — the drawn trail is split at every gap instead of bridging it, because a
 * straight line between two fixes twenty seconds apart is movement nobody observed.
 *
 * Records are expected to have been validated by `ReplayReader`, which enforces session,
 * source and contract membership before they reach here.
 */
class RecordedSessionMap(
    private val maxTrail: Int = DEFAULT_MAX_TRAIL,
    private val gapThresholdNs: Long = DEFAULT_GAP_NS,
) {
    init {
        require(maxTrail in 2..4096)
        require(gapThresholdNs > 0)
    }

    /** What the recording actually contained, so the UI can state it instead of implying more. */
    data class Stats(
        val fixes: Int,
        val drawn: Int,
        val outsideCoverage: Int,
        val malformed: Int,
        val gaps: Int,
        val longestGapNs: Long,
        val spanNs: Long,
        val providers: List<String>,
        val lastFixRadiusMetres: Double?,
    )

    private val open = ArrayList<MapPoint>()
    private val closed = ArrayList<List<MapPoint>>()
    private var drawnInSegments = 0
    private var previousFixNs: Long? = null
    private var previousPoint: MapPoint? = null
    private var point: MapPoint? = null
    private var heading: Double? = null
    private var speed: Double? = null
    private var radius: Double? = null
    private var status = "No recorded fix yet"
    private var fixes = 0
    private var drawn = 0
    private var outside = 0
    private var malformed = 0
    private var gaps = 0
    private var longestGap = 0L
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
        if (firstNs == null) firstNs = time
        lastNs = time
        fixes++
        providers.add(fix.provider)

        val previous = previousFixNs
        previousFixNs = time
        if (previous != null && time > previous && time - previous > gapThresholdNs) {
            gaps++
            longestGap = maxOf(longestGap, time - previous)
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
        drawn++
        status = "Recorded GNSS fix · ${fix.provider}"
        return snapshot()
    }

    fun snapshot(): MapPresentation = MapPresentation(
        source = Source.REPLAY_REAL,
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

    fun stats(): Stats = Stats(
        fixes = fixes,
        drawn = drawn,
        outsideCoverage = outside,
        malformed = malformed,
        gaps = gaps,
        longestGapNs = longestGap,
        spanNs = if (firstNs == null || lastNs == null) 0L else lastNs!! - firstNs!!,
        providers = providers.toList(),
        lastFixRadiusMetres = radius,
    )

    private fun trail(): List<MapPoint> = trailSegments().flatten()

    private fun trailSegments(): List<List<MapPoint>> {
        val segments = closed.toMutableList()
        // A single drawn fix is a position, not a path; it is shown as a point rather than a line.
        if (open.size >= 2) segments.add(open.toList())
        return segments
    }

    private fun closeSegment() {
        if (open.size >= 2) {
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
        /** The same 5 s the acquisition contract uses before a location gap is reported. */
        const val DEFAULT_GAP_NS = 5_000_000_000L
        private const val METRES_PER_DEGREE = 111_320.0
        private const val MIN_COURSE_METRES = 3.0
    }
}
