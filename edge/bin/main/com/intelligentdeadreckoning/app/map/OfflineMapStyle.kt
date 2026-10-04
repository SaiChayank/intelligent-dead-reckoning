package com.intelligentdeadreckoning.app.map

import com.google.gson.Gson

/** Renderer-only configuration. No sensor, navigation, routing or matching dependencies. */
object HyderabadMap {
    const val WEST = 78.35
    const val SOUTH = 17.30
    const val EAST = 78.60
    const val NORTH = 17.55
    const val LATITUDE = (SOUTH + NORTH) / 2
    const val LONGITUDE = (WEST + EAST) / 2
    fun contains(latitude: Double, longitude: Double) = latitude.isFinite() && longitude.isFinite() &&
        latitude in SOUTH..NORTH && longitude in WEST..EAST
}

object OfflineMapStyle {
    /** Paths supplied exclusively by the trusted bundled-pack installer, never arbitrary user styles. */
    fun json(databasePath: String, glyphTemplate: String): String {
        require(databasePath.startsWith("/") && !databasePath.contains("..")) { "Expected private absolute map path" }
        require(glyphTemplate.startsWith("file:///") && !glyphTemplate.contains("..")) { "Expected local glyph path" }
        val source = "hyderabad"
        fun layer(id: String, type: String, dataLayer: String, paint: Map<String, Any>) =
            mapOf("id" to id, "type" to type, "source" to source, "source-layer" to dataLayer, "paint" to paint)
        val layers = mutableListOf<Map<String, Any>>(
            mapOf("id" to "background", "type" to "background", "paint" to mapOf("background-color" to "#eeeae2")),
            layer("landcover","fill","landcover",mapOf("fill-color" to "#dce6ce", "fill-opacity" to 0.6)),
            layer("landuse","fill","landuse",mapOf("fill-color" to "#e2e4d5")),
            layer("water","fill","water",mapOf("fill-color" to "#a8ccd8")),
            layer("waterway","line","waterway",mapOf("line-color" to "#a8ccd8","line-width" to 1.5)),
            layer("buildings","fill","building",mapOf("fill-color" to "#d0c9bd","fill-outline-color" to "#bdb5a8")),
            layer("road-casing","line","transportation",mapOf("line-color" to "#c8beaa","line-width" to listOf("interpolate",listOf("linear"),listOf("zoom"),10,1.0,16,7.0))),
            layer("roads","line","transportation",mapOf("line-color" to "#fffdf4","line-width" to listOf("interpolate",listOf("linear"),listOf("zoom"),10,0.5,16,4.0))),
        )
        for ((id, dataLayer, placement) in listOf(Triple("road-names","transportation_name","line"), Triple("places","place","point"))) {
            layers += mapOf("id" to id,"type" to "symbol","source" to source,"source-layer" to dataLayer,
                "layout" to mapOf("symbol-placement" to placement, "text-field" to listOf("coalesce",listOf("get","name:en"),listOf("get","name:latin"),""),
                    "text-font" to listOf("Noto Sans Regular"),"text-size" to if (id == "places") 14 else 11),
                "paint" to mapOf("text-color" to "#3b433f","text-halo-color" to "#fffdf4","text-halo-width" to 1.2))
        }
        return Gson().toJson(mapOf("version" to 8,"name" to "Offline Hyderabad prototype",
            "glyphs" to glyphTemplate,"sources" to mapOf(source to mapOf("type" to "vector","url" to "mbtiles://$databasePath",
                "minzoom" to 10,"maxzoom" to 14, "attribution" to "OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors")),"layers" to layers))
    }
}
