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
    fun json(state: MapPresentation, overlays: DemoOverlays = DemoOverlays()): String {
        val features = mutableListOf<Map<String,Any>>()
        fun add(kind: String,type: String,coordinates: Any) {
            features += mapOf("type" to "Feature","properties" to mapOf("kind" to kind),
                "geometry" to mapOf("type" to type,"coordinates" to coordinates))
        }
        state.point?.let { p ->
            if(state.source == com.intelligentdeadreckoning.contracts.v1.Source.SIMULATION) {
                if(overlays.scenario && state.scenarioPath.size >= 2) add("scenario","LineString",state.scenarioPath.map { listOf(it.longitude,it.latitude) })
                if(overlays.scenario && state.outagePath.size >= 2) add("outage","LineString",state.outagePath.map { listOf(it.longitude,it.latitude) })
                if(overlays.comparison) {
                    if(state.comparisonTrail.size >= 2) add("comparison-trail","LineString",state.comparisonTrail.map { listOf(it.longitude,it.latitude) })
                    state.comparisonPoint?.let { add("comparison","Point",listOf(it.longitude,it.latitude)) }
                }
            }
            if(overlays.trail) {
                // Recorded fixes arrive pre-split at every gap. A segment of one is a position the
                // recording observed, not a path, so it is emitted as a point: dropping it would
                // hide a real fix and joining it to the next one would draw movement nobody saw.
                if(state.trailSegments.isNotEmpty()) for(segment in state.trailSegments) when(segment.size) {
                    0 -> Unit
                    1 -> add("trail-fix","Point",listOf(segment[0].longitude,segment[0].latitude))
                    else -> add("trail","LineString",segment.map { listOf(it.longitude,it.latitude) })
                } else if(state.trail.size >= 2) {
                    add("trail","LineString",state.trail.map { listOf(it.longitude,it.latitude) })
                }
            }
            // Exactly one of these is ever set: a calibrated 95% confidence, or the radius a real
            // fix reported. They are drawn the same way but never averaged or conflated.
            (state.accuracy95Metres ?: state.fixRadiusMetres)?.takeIf { overlays.uncertainty && it > 0 }?.let { radius ->
                // Avoid painting near-global circles; large uncertainty stays available as text.
                if(radius <= 10000) {
                    val ring = (0 until 64).map { offset(p,radius,it*360.0/64) }
                    add("accuracy","Polygon",listOf(ring + listOf(ring.first())))
                }
            }
            // The engine's own covariance while its confidence is UNVALIDATED. It is drawn as its
            // own dashed outline rather than as the filled accuracy area above, because it is a
            // model claim about itself and not a validated error bound. Never merged with the
            // calibrated radius, and never the platform fix radius.
            state.unvalidatedAccuracy95Metres?.takeIf { overlays.uncertainty && it > 0 }?.let { radius ->
                if(radius <= 10000) {
                    val ring = (0 until 64).map { offset(p,radius,it*360.0/64) }
                    add("uncertainty","Polygon",listOf(ring + listOf(ring.first())))
                }
            }
            // The evaluation overlay: the map-matched claim, drawn beside the raw position
            // above and never in place of it. It exists only when the evaluation toggle
            // supplied a road graph and the matcher accepted a fix.
            val matched = state.matchedPoint
            if(matched != null) {
                if(state.matchedTrail.size >= 2) add("matched-trail","LineString",state.matchedTrail.map { listOf(it.longitude,it.latitude) })
                add("matched","Point",listOf(matched.longitude,matched.latitude))
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
