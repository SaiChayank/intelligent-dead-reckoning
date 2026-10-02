package com.intelligentdeadreckoning.app

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intelligentdeadreckoning.app.fusion.Geodesy
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Builds tiny `idr-road-graph/1` packs for matcher tests: roads are defined as
 * polylines in metres around a local anchor, so scenarios can say "the parallel
 * road is 20 m north" and mean exactly that. Output goes through the same
 * gzip path as the packaged asset, so tests exercise the real loader.
 */
object RoadGraphFixtures {
    const val ANCHOR_LAT = 17.4
    const val ANCHOR_LON = 78.45

    private val CLASSES = listOf(
        "motorway", "motorway_link", "trunk", "trunk_link",
        "primary", "primary_link", "secondary", "secondary_link",
        "tertiary", "tertiary_link", "unclassified", "residential",
        "living_street", "service",
    )

    /** Metre offsets to degrees around the anchor, WGS84 via the shared Geodesy. */
    fun lat(offsetM: Double): Double = ANCHOR_LAT + offsetM / metresPerDegree().first

    fun lon(offsetM: Double): Double = ANCHOR_LON + offsetM / metresPerDegree().second

    private fun metresPerDegree(): Pair<Double, Double> {
        val (rE, rN) = Geodesy.radii(Math.toRadians(ANCHOR_LAT))
        val toRad = Math.PI / 180.0
        return (rN * toRad) to (rE * Math.cos(Math.toRadians(ANCHOR_LAT)) * toRad)
    }

    data class Road(
        val wayId: Long,
        val roadClass: String = "residential",
        val name: String? = null,
        val points: List<Pair<Double, Double>>,
    )

    /** Road running due north/south through a given east offset. */
    fun northSouth(wayId: Long, eastM: Double, roadClass: String = "residential"): Road =
        Road(
            wayId, roadClass, null,
            listOf(lat(-200.0) to lon(eastM), lat(200.0) to lon(eastM)),
        )

    /** Road running due east/west through a given north offset. */
    fun eastWest(wayId: Long, northM: Double, roadClass: String = "residential"): Road =
        Road(
            wayId, roadClass, null,
            listOf(lat(northM) to lon(-200.0), lat(northM) to lon(200.0)),
        )

    fun graphJson(roads: List<Road>, extentM: Double = 2000.0): String {
        val nodeList = ArrayList<Pair<Double, Double>>()
        val nodeIndex = HashMap<Pair<Double, Double>, Int>()
        fun nodeAt(point: Pair<Double, Double>): Int = nodeIndex.getOrPut(point) {
            nodeList.add(point)
            nodeList.size - 1
        }
        val names = ArrayList<String>()
        val nameIndex = HashMap<String, Int>()
        val edgeU = JsonArray()
        val edgeV = JsonArray()
        val edgeClass = JsonArray()
        val edgeName = JsonArray()
        val edgeWay = JsonArray()
        val edgePart = JsonArray()
        val edgeGeomOffset = JsonArray()
        val geom = JsonArray()
        val classes = JsonArray()
        for (roadClass in CLASSES) {
            classes.add(roadClass)
        }
        var geomCount = 0
        for (road in roads) {
            require(road.points.size >= 2) { "road needs at least two points" }
            edgeU.add(nodeAt(road.points.first()))
            edgeV.add(nodeAt(road.points.last()))
            edgeClass.add(CLASSES.indexOf(road.roadClass))
            val name = road.name
            if (name != null) {
                nameIndex.getOrPut(name) {
                    names.add(name)
                    names.size - 1
                }
            }
            edgeName.add(if (name == null) -1 else nameIndex[name]!!)
            edgeWay.add(road.wayId)
            edgePart.add(0)
            edgeGeomOffset.add(geomCount)
            var prevLatE6 = 0
            var prevLonE6 = 0
            for ((index, point) in road.points.withIndex()) {
                val latE6 = Math.round(point.first * 1e6).toInt()
                val lonE6 = Math.round(point.second * 1e6).toInt()
                val pair = JsonArray()
                if (index == 0) {
                    pair.add(latE6)
                    pair.add(lonE6)
                } else {
                    pair.add(latE6 - prevLatE6)
                    pair.add(lonE6 - prevLonE6)
                }
                geom.add(pair)
                prevLatE6 = latE6
                prevLonE6 = lonE6
                geomCount++
            }
        }
        edgeGeomOffset.add(geomCount)

        val nodes = JsonArray()
        for ((latDeg, lonDeg) in nodeList) {
            val pair = JsonArray()
            pair.add(Math.round(latDeg * 1e6).toInt())
            pair.add(Math.round(lonDeg * 1e6).toInt())
            nodes.add(pair)
        }
        val counts = JsonObject()
        counts.addProperty("nodes", nodeList.size)
        counts.addProperty("edges", roads.size)
        counts.addProperty("geom_points", geomCount)

        val root = JsonObject()
        root.addProperty("format", "idr-road-graph/1")
        root.addProperty("id", "test-graph")
        root.addProperty("version", 1)
        root.addProperty("crs", "WGS84")
        root.addProperty("coordinate_encoding", "delta-microdegrees")
        root.add("query_bounds", boundsArray(extentM))
        root.add("data_bounds", boundsArray(extentM))
        root.add("counts", counts)
        root.add("classes", classes)
        val nameArray = JsonArray()
        for (name in names) {
            nameArray.add(name)
        }
        root.add("names", nameArray)
        root.add("nodes", nodes)
        root.add("edge_u", edgeU)
        root.add("edge_v", edgeV)
        root.add("edge_class", edgeClass)
        root.add("edge_name", edgeName)
        root.add("edge_way", edgeWay)
        root.add("edge_part", edgePart)
        root.add("edge_geom_offset", edgeGeomOffset)
        root.add("geom", geom)
        return root.toString()
    }

    private fun boundsArray(extentM: Double): JsonArray {
        val array = JsonArray()
        array.add(lon(-extentM))
        array.add(lat(-extentM))
        array.add(lon(extentM))
        array.add(lat(extentM))
        return array
    }

    /** Write a fixture graph as a gzipped pack member the real loader accepts. */
    fun writeGraph(file: File, json: String) {
        file.parentFile?.mkdirs()
        GZIPOutputStream(file.outputStream().buffered()).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
        }
    }
}
