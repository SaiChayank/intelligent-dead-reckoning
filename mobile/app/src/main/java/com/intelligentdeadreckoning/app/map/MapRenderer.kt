package com.intelligentdeadreckoning.app.map

/** Replaceable presentation boundary; never estimates or corrects position. */
interface MapRenderer {
    fun present(state: MapPresentation)
    fun focus(point: MapPoint)
    fun recenter()
    fun zoomBy(delta: Double)
    fun northUp()
}
