package com.intelligentdeadreckoning.app.map

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*

internal class MapLibreRenderer(private val map: MapLibreMap) : MapRenderer {
    override fun focus(point: MapPoint) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude,point.longitude),FOCUS_ZOOM))
    }
    override fun frame(points: List<MapPoint>) {
        if(points.isEmpty()) return
        val north = points.maxOf { it.latitude }; val south = points.minOf { it.latitude }
        val east = points.maxOf { it.longitude }; val west = points.minOf { it.longitude }
        if(north - south < STATIONARY_SPAN && east - west < STATIONARY_SPAN) {
            // A receiver that never moved reports the same fix again and again. A degenerate box
            // would ask the camera for an unbounded zoom, so one position is centred instead.
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(north,east),FOCUS_ZOOM))
            return
        }
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(
            LatLngBounds.Builder().include(LatLng(north,west)).include(LatLng(south,east)).build(),
            FRAME_PADDING_PX,
        ))
    }
    override fun present(state: MapPresentation, overlays: DemoOverlays) {
        val style = map.style ?: return
        for(id in listOf("road-casing","roads","road-names"))
            style.getLayer(id)?.setProperties(visibility(if(overlays.roads) Property.VISIBLE else Property.NONE))
        var source = style.getSourceAs<GeoJsonSource>("navigation-display")
        if(source == null) {
            source = GeoJsonSource("navigation-display", MapOverlay.json(state,overlays))
            style.addSource(source)
            style.addLayer(LineLayer("display-scenario","navigation-display")
                .withFilter(eq(get("kind"),literal("scenario"))).withProperties(lineColor("#637888"),lineWidth(3f),lineDasharray(arrayOf(2f,2f))))
            style.addLayer(LineLayer("display-outage","navigation-display")
                .withFilter(eq(get("kind"),literal("outage"))).withProperties(lineColor("#d99100"),lineWidth(7f),lineOpacity(0.6f)))
            style.addLayer(FillLayer("display-accuracy","navigation-display")
                .withFilter(eq(get("kind"),literal("accuracy"))).withProperties(fillColor("#3e8cd9"),fillOpacity(0.18f)))
            style.addLayer(LineLayer("display-trail","navigation-display")
                .withFilter(eq(get("kind"),literal("trail"))).withProperties(lineColor("#7856d8"),lineWidth(4f)))
            // A recorded fix with no neighbour is still a fix: drawn as a dot, never joined up.
            style.addLayer(CircleLayer("display-trail-fix","navigation-display")
                .withFilter(eq(get("kind"),literal("trail-fix"))).withProperties(circleColor("#7856d8"),circleRadius(4f),circleStrokeColor("#ffffff"),circleStrokeWidth(1.5f)))
            style.addLayer(LineLayer("display-comparison-trail","navigation-display")
                .withFilter(eq(get("kind"),literal("comparison-trail"))).withProperties(lineColor("#d74545"),lineWidth(3f),lineDasharray(arrayOf(2f,1f))))
            style.addLayer(CircleLayer("display-comparison","navigation-display")
                .withFilter(eq(get("kind"),literal("comparison"))).withProperties(circleColor("#d74545"),circleRadius(7f),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
            style.addLayer(CircleLayer("display-position","navigation-display")
                .withFilter(eq(get("kind"),literal("position"))).withProperties(circleColor("#7856d8"),circleRadius(6f),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
            style.addLayer(FillLayer("display-heading","navigation-display")
                .withFilter(eq(get("kind"),literal("heading"))).withProperties(fillColor("#40228b")))
        } else source.setGeoJson(MapOverlay.json(state,overlays))
    }
    override fun recenter() {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(HyderabadMap.LATITUDE,HyderabadMap.LONGITUDE)).zoom(11.5).bearing(0.0).tilt(0.0).build()))
    }
    override fun zoomBy(delta: Double) { map.animateCamera(CameraUpdateFactory.zoomBy(delta)) }
    override fun northUp() { map.animateCamera(CameraUpdateFactory.bearingTo(0.0)) }

    private companion object {
        const val FOCUS_ZOOM = 16.0
        /** ~1 m: below this the recording is one position, not a track. */
        const val STATIONARY_SPAN = 1e-5
        const val FRAME_PADDING_PX = 96
    }
}
