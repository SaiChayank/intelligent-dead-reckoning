package com.intelligentdeadreckoning.app.map

import com.intelligentdeadreckoning.contracts.v1.*
import kotlin.math.*

data class MapPoint(val latitude: Double, val longitude: Double)
data class MapPresentation(
    val source: Source, val point: MapPoint? = null, val headingDegrees: Double? = null,
    val speedMetresPerSecond: Double? = null, val accuracy95Metres: Double? = null,
    val trail: List<MapPoint> = emptyList(), val status: String = "No navigation position",
)

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
        val origin = nav.origin_wgs84_deg_m; val position = nav.position_enu_m
        if (origin == null || position == null) return reject("${nav.status.wire} — no position")
        val point = try { MapCoordinates.fromEnu(origin,position) } catch (_: IllegalArgumentException) { return reject("Invalid geographic position") }
        if (!HyderabadMap.contains(point.latitude,point.longitude)) return reject("Outside offline coverage")
        if (trail.lastOrNull() != point) { trail.addLast(point); if(trail.size > capacity) trail.removeFirst() }
        // Confidence has no navigation event reference in v1: require same session and exact time.
        val accuracy = confidence?.let {
            try {
                Codec.encodeJson(it)
                val value = it.event.data as? Confidence
                if (it.header == header && it.event.t_ns == time && it.event.received_ns <= nowNs &&
                    value?.state == ConfidenceState.CALIBRATED) value.horizontal_accuracy_95_m else null
            } catch (_: IllegalArgumentException) { null }
        }
        state = MapPresentation(header.source,point,nav.heading_deg,
            nav.velocity_enu_m_s?.let { hypot(it.x,it.y) },accuracy,trail.toList(),nav.status.wire)
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
    fun record(elapsedMs: Long, startNs: Long): Record {
        require(elapsedMs in 0..DURATION_MS && startNs >= 0)
        val t = elapsedMs/1000.0
        val angle = t/15.0
        val heading = (90.0-Math.toDegrees(angle)+360)%360
        val yaw = angle
        val nav = NavigationState(if(t in 10.0..<20.0) NavigationStatus.DEGRADED else NavigationStatus.TRACKING,
            InitializationMode.DEPLOYABLE,GeoOrigin(17.425,78.475,500.0),
            Vector3(150*sin(angle),150*(1-cos(angle)),0.0),Vector3(10*cos(angle),10*sin(angle),0.0),
            Quaternion(cos(yaw/2),0.0,0.0,sin(yaw/2)),heading,"synthetic-only",false)
        val stamp = Math.addExact(startNs,Math.multiplyExact(elapsedMs,1_000_000L))
        return Record(header,Event(elapsedMs.toString(),stamp,stamp,nav))
    }
}
