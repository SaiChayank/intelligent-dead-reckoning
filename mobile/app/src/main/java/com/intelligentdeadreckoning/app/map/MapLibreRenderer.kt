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
    override fun present(state: MapPresentation) {
        val style = map.style ?: return
        var source = style.getSourceAs<GeoJsonSource>("navigation-display")
        if(source == null) {
            source = GeoJsonSource("navigation-display", MapOverlay.json(state))
            style.addSource(source)
            style.addLayer(FillLayer("display-accuracy","navigation-display")
                .withFilter(eq(get("kind"),literal("accuracy"))).withProperties(fillColor("#3e8cd9"),fillOpacity(0.18f)))
            style.addLayer(LineLayer("display-trail","navigation-display")
                .withFilter(eq(get("kind"),literal("trail"))).withProperties(lineColor("#7856d8"),lineWidth(4f)))
            style.addLayer(CircleLayer("display-position","navigation-display")
                .withFilter(eq(get("kind"),literal("position"))).withProperties(circleColor("#7856d8"),circleRadius(6f),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
            style.addLayer(FillLayer("display-heading","navigation-display")
                .withFilter(eq(get("kind"),literal("heading"))).withProperties(fillColor("#40228b")))
        } else source.setGeoJson(MapOverlay.json(state))
    }
    override fun recenter() {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(HyderabadMap.LATITUDE,HyderabadMap.LONGITUDE)).zoom(11.5).bearing(0.0).tilt(0.0).build()))
    }
    override fun zoomBy(delta: Double) { map.animateCamera(CameraUpdateFactory.zoomBy(delta)) }
    override fun northUp() { map.animateCamera(CameraUpdateFactory.bearingTo(0.0)) }
}
