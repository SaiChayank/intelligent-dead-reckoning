package com.intelligentdeadreckoning.app.map

/** Replaceable presentation boundary; never estimates or corrects position. */
interface MapRenderer {
    fun present(state: MapPresentation, overlays: DemoOverlays = DemoOverlays())
    fun focus(point: MapPoint)
    /** Fits the view to observed positions. A camera move, never a position estimate. */
    fun frame(points: List<MapPoint>)
    fun recenter()
    fun zoomBy(delta: Double)
    fun northUp()
}
