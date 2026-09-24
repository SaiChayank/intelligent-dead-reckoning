package com.intelligentdeadreckoning.app.map

import com.google.gson.Gson
import kotlin.math.*

/** GeoJSON in longitude/latitude order. Metres are converted to spherical display geometry.
 * No imagery, network resources, map matching or navigation-state mutation.
 */
object MapOverlay {
    private fun offset(p: MapPoint, metres: Double, degrees: Double): List<Double> {
        val d = metres/6371008.8; val b = Math.toRadians(degrees)
        val lat = Math.toRadians(p.latitude); val lon = Math.toRadians(p.longitude)
        val next = asin(sin(lat)*cos(d)+cos(lat)*sin(d)*cos(b))
        return listOf(Math.toDegrees(lon+atan2(sin(b)*sin(d)*cos(lat),cos(d)-sin(lat)*sin(next))),Math.toDegrees(next))
    }
    fun json(state: MapPresentation): String {
        val features = mutableListOf<Map<String,Any>>()
        fun add(kind: String,type: String,coordinates: Any) {
            features += mapOf("type" to "Feature","properties" to mapOf("kind" to kind),
                "geometry" to mapOf("type" to type,"coordinates" to coordinates))
        }
        state.point?.let { p ->
            if(state.trail.size >= 2) add("trail","LineString",state.trail.map { listOf(it.longitude,it.latitude) })
            state.accuracy95Metres?.takeIf { it > 0 }?.let { radius ->
                // Avoid painting near-global circles; large uncertainty stays available as text.
                if(radius <= 10000) {
                    val ring = (0 until 64).map { offset(p,radius,it*360.0/64) }
                    add("accuracy","Polygon",listOf(ring + listOf(ring.first())))
                }
            }
            add("position","Point",listOf(p.longitude,p.latitude))
            state.headingDegrees?.let { heading ->
                val tip = offset(p,18.0,heading)
                add("heading","Polygon",listOf(listOf(tip,offset(p,9.0,heading+140),offset(p,9.0,heading-140),tip)))
            }
        }
        return Gson().toJson(mapOf("type" to "FeatureCollection","features" to features))
    }
}
