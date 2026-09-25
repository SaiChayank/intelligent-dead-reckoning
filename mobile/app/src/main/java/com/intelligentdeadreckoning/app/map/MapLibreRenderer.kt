package com.intelligentdeadreckoning.app.map

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*

internal class MapLibreRenderer(private val map: MapLibreMap) : MapRenderer {
    override fun focus(point: MapPoint) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude,point.longitude),16.0))
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
}
