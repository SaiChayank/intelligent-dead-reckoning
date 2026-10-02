package com.intelligentdeadreckoning.app.map

import com.intelligentdeadreckoning.contracts.v1.*
import kotlin.math.*

data class MapPoint(val latitude: Double, val longitude: Double)
data class MapPresentation(
    val source: Source, val point: MapPoint? = null, val headingDegrees: Double? = null,
    val speedMetresPerSecond: Double? = null, val accuracy95Metres: Double? = null,
    val trail: List<MapPoint> = emptyList(), val status: String = "No navigation position",
    val comparisonPoint: MapPoint? = null, val comparisonTrail: List<MapPoint> = emptyList(),
    val scenarioPath: List<MapPoint> = emptyList(), val outagePath: List<MapPoint> = emptyList(),
    /** Recorded fixes split at every gap. Empty for the synthetic fixture, which has no gaps. */
    val trailSegments: List<List<MapPoint>> = emptyList(),
    /** Platform-reported horizontal accuracy radius (68%). Never a calibrated 95% value. */
    val fixRadiusMetres: Double? = null,
    /**
     * The engine's own covariance restated as a 95% circular radius while its paired
     * `Confidence` state is `UNVALIDATED`.
     *
     * It is a model claim about itself: not a validated error bound, not the calibrated
     * [accuracy95Metres], and not the platform radius in [fixRadiusMetres]. It lives in its own
     * field so no consumer can read it as a calibrated 95% value by accident, and at most one of
     * [accuracy95Metres] and this field is ever non-null.
     */
    val unvalidatedAccuracy95Metres: Double? = null,
    /** The paired `Confidence.state` wire string, when a paired record supplied one. */
    val confidenceState: String? = null,
    /** The engine's horizontal speed sigma in m/s, from the same paired record. */
    val speedStdMetresPerSecond: Double? = null,
    /** Engine-reported localization regime (contract 1.1.0 `localization_mode`), when one exists. */
    val localizationMode: String? = null,
    /**
     * Evaluation-only map-matched output: a parallel claim drawn beside the raw position,
     * never in place of it. `point` and `trail` stay the raw fused navigation truth; these
     * fields exist only while the evaluation toggle is enabled and a match was accepted.
     */
    val matchedPoint: MapPoint? = null,
    val matchedTrail: List<MapPoint> = emptyList(),
    val matchConfidence: Double? = null,
    val matchedEdgeId: String? = null,
    val matcherVersion: String? = null,
    /** Source-local graph-install error; raw navigation remains usable when matching is unavailable. */
    val matchingIssue: String? = null,
)

/** One live GNSS view: what the map draws from the phone's own stream, plus the counts it may state.
 * Nothing here is propagated, fused, corrected or road matched — it is the platform's last fix. */
data class LiveGnssView(val presentation: MapPresentation, val stats: RecordedSessionMap.Stats)

/** Before the live stream has produced a fix: a real source with nothing drawn. */
val NO_LIVE_GNSS = LiveGnssView(MapPresentation(Source.REAL), RecordedSessionMap(source = Source.REAL).stats())

/** Before an engine session has published anything: a real source with nothing drawn. */
val NO_ENGINE_VIEW = MapPresentation(Source.REAL, status = "No engine output yet")

/** Display conversion only: exact WGS84 origin + ENU -> ECEF -> geographic position.
 * No propagation, correction, road snapping or inference of a GNSS/DR/fused mode.
 */
object MapCoordinates {
    fun fromEnu(origin: GeoOrigin, enu: Vector3): MapPoint {
        require(listOf(origin.latitude_deg, origin.longitude_deg, origin.altitude_m, enu.x, enu.y, enu.z).all { it.isFinite() })
        require(origin.latitude_deg in -90.0..90.0 && origin.longitude_deg in -180.0..180.0)
        val lat = Math.toRadians(origin.latitude_deg); val lon = Math.toRadians(origin.longitude_deg)
        val a = 6378137.0; val e2 = 6.6943799901413165e-3
        val n = a / sqrt(1 - e2 * sin(lat).pow(2))
        val x = (n + origin.altitude_m) * cos(lat) * cos(lon) - sin(lon)*enu.x - sin(lat)*cos(lon)*enu.y + cos(lat)*cos(lon)*enu.z
        val y = (n + origin.altitude_m) * cos(lat) * sin(lon) + cos(lon)*enu.x - sin(lat)*sin(lon)*enu.y + cos(lat)*sin(lon)*enu.z
        val z = (n*(1-e2) + origin.altitude_m)*sin(lat) + cos(lat)*enu.y + sin(lat)*enu.z
        val p = hypot(x,y)
        require(hypot(p,z) > a/2) { "Position outside supported terrestrial domain" }
        var phi = atan2(z,p*(1-e2))
        repeat(12) { val radius = a/sqrt(1-e2*sin(phi).pow(2)); phi = atan2(z+e2*radius*sin(phi),p) }
        return MapPoint(Math.toDegrees(phi), Math.toDegrees(atan2(y,x)))
    }
}

/** One explicit session/clock domain. Caller must create a fresh adapter for a new session.
 * Strict codec validation also protects against invalid directly constructed typed models.
 */
class NavigationPresentation(private val header: Header, private val mode: InitializationMode,
                             private val capacity: Int = 512) {
    init { require(capacity in 2..4096) }
    private val trail = ArrayDeque<MapPoint>()
    private var lastTime: Long? = null
    private var state = MapPresentation(header.source)
    fun accept(record: Record, nowNs: Long, confidence: Record? = null): MapPresentation {
        fun reject(reason: String): MapPresentation {
            trail.clear()
            state = MapPresentation(header.source, status = reason)
            return state
        }
        try { Codec.encodeJson(record) } catch (_: IllegalArgumentException) { return reject("Invalid navigation record") }
        if (record.header != header) return reject("Session/source mismatch")
        val nav = record.event.data as? NavigationState ?: return reject("Not a navigation event")
        if (nav.initialization_mode != mode) return reject("Initialization mode changed")
        val time = record.event.t_ns
        if (nowNs < time || nowNs < record.event.received_ns) return reject("Future timestamp")
        if (lastTime != null && time <= lastTime!!) return reject("Duplicate or out-of-order position")
        if (lastTime != null && time-lastTime!! > STALE_NS) trail.clear()
        lastTime = time
        if (nowNs-time > STALE_NS) return reject("Stale position — hidden")
        if (nav.status == NavigationStatus.FAILED) {
            // A finite last state is still no longer a trustworthy position once the producer
            // declares itself failed. Do not leave the previous marker/trail looking current.
            return reject("Navigation failed — position hidden")
        }
        val origin = nav.origin_wgs84_deg_m; val position = nav.position_enu_m
        if (origin == null || position == null) return reject("${nav.status.wire} — no position")
        val point = try { MapCoordinates.fromEnu(origin,position) } catch (_: IllegalArgumentException) { return reject("Invalid geographic position") }
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0
        ) return reject("Invalid geographic position")
        if (!HyderabadMap.contains(point.latitude,point.longitude)) return reject("Outside offline coverage")
        if (trail.lastOrNull() != point) { trail.addLast(point); if(trail.size > capacity) trail.removeFirst() }
        // Confidence has no navigation event reference in v1: require same session and exact time.
        // The state decides which field the radius lands in — a calibrated 95% accuracy, or the
        // engine's own covariance while it is honest that nothing has validated it. The two are
        // never merged, so no reader can mistake one for the other.
        val paired = confidence?.let {
            try {
                Codec.encodeJson(it)
                val value = it.event.data as? Confidence
                if (it.header == header && it.event.t_ns == time && it.event.received_ns <= nowNs) value else null
            } catch (_: IllegalArgumentException) { null }
        }
        val calibrated = if (paired?.state == ConfidenceState.CALIBRATED) paired.horizontal_accuracy_95_m else null
        val unvalidated = if (paired?.state == ConfidenceState.UNVALIDATED) paired.horizontal_accuracy_95_m else null
        // A speed sigma travels only with a state that actually claims an uncertainty: a record
        // that published neither a radius nor a probability has nothing to carry alongside it.
        val speedStd = if (calibrated != null || unvalidated != null) paired?.speed_std_m_s else null
        state = MapPresentation(header.source,point,nav.heading_deg,
            nav.velocity_enu_m_s?.let { hypot(it.x,it.y) },calibrated,trail.toList(),nav.status.wire,
            unvalidatedAccuracy95Metres = unvalidated,
            confidenceState = paired?.state?.wire,
            speedStdMetresPerSecond = speedStd)
        return state
    }
    fun snapshot(nowNs: Long): MapPresentation {
        val last = lastTime
        if (last != null && (nowNs < last || nowNs-last > STALE_NS)) {
            trail.clear(); state = MapPresentation(header.source,status = "Stale position — hidden")
        }
        return state
    }
    companion object { const val STALE_NS = 3_000_000_000L }
}

/** Synthetic UI fixture, not a sensor simulation, route, engine or training sample. */
object SyntheticMapDemo {
    const val DURATION_MS = 30_000L
    val header = Header("map-ui-fixture",Source.SIMULATION)
    fun scenario(elapsedMs: Long): String = when {
        elapsedMs < 10_000 -> "SYNTHETIC GNSS scenario"
        elapsedMs < 20_000 -> "SYNTHETIC DR scenario (no INS running)"
        else -> "SYNTHETIC GNSS recovery scenario (no fusion running)"
    }
    fun record(elapsedMs: Long, startNs: Long, scenario: DemoScenario = DemoScenario.CURVE): Record {
        require(elapsedMs in 0..DURATION_MS && startNs >= 0)
        val t = elapsedMs/1000.0
        val direction = if(scenario == DemoScenario.RIGHT_CURVE) -1 else 1
        val angle = if(scenario == DemoScenario.STRAIGHT) 0.0 else direction*t/15.0
        val heading = (90.0-Math.toDegrees(angle)+360)%360
        val yaw = angle
        val nav = NavigationState(if(t in 10.0..<20.0) NavigationStatus.DEGRADED else NavigationStatus.TRACKING,
            InitializationMode.DEPLOYABLE,GeoOrigin(17.435,78.445,500.0),
            if(scenario == DemoScenario.STRAIGHT) Vector3(10*t,0.0,0.0)
            else Vector3(direction*150*sin(angle),direction*150*(1-cos(angle)),0.0),
            Vector3(10*cos(angle),10*sin(angle),0.0),
            Quaternion(cos(yaw/2),0.0,0.0,sin(yaw/2)),heading,"synthetic-only",false,null)
        val stamp = Math.addExact(startNs,Math.multiplyExact(elapsedMs,1_000_000L))
        return Record(header,Event(elapsedMs.toString(),stamp,stamp,nav))
    }
}
