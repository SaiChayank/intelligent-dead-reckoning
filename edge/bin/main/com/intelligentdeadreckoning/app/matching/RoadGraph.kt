package com.intelligentdeadreckoning.app.matching

import com.google.gson.stream.JsonReader
import com.intelligentdeadreckoning.app.fusion.Geodesy
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The offline matchable road graph (`idr-road-graph/1`). Deliberately separate from
 * the rendering pack: MBTiles hold display tiles and are not a routable/matchable
 * graph. This is a matching graph only — undirected topology, geometry, road class
 * and name. It carries no turn restrictions, speeds or elevations and is not used
 * for routing.
 *
 * Layout is columnar; `edge_geom_offset` has `edges + 1` entries and `geom` is a
 * flattened polyline: each edge's first point is absolute microdegrees, following
 * points are delta microdegrees. Edge ids are `osm/{way}/{part}`.
 */
class RoadGraph private constructor(
    val id: String,
    val version: Int,
    val queryBounds: DoubleArray,
    val dataBounds: DoubleArray,
    val classes: Array<String>,
    val names: Array<String>,
    private val nodeLatDeg: DoubleArray,
    private val nodeLonDeg: DoubleArray,
    private val edgeU: IntArray,
    private val edgeV: IntArray,
    private val edgeClass: IntArray,
    private val edgeName: IntArray,
    private val edgeWay: LongArray,
    private val edgePart: IntArray,
    private val edgeGeomOffset: IntArray,
    private val geomLatRaw: IntArray,
    private val geomLonRaw: IntArray,
) {
    val nodeCount: Int get() = nodeLatDeg.size
    val edgeCount: Int get() = edgeU.size

    private val cellSizeDeg = CELL_DEG
    private val grid: Map<Long, IntArray> = buildGrid()

    fun edgeId(edge: Int): String = "osm/${edgeWay[edge]}/${edgePart[edge]}"
    fun wayId(edge: Int): Long = edgeWay[edge]
    fun roadClass(edge: Int): String = classes[edgeClass[edge]]
    fun edgeName(edge: Int): String? =
        if (edgeName[edge] < 0) null else names[edgeName[edge]]

    fun sharesNode(a: Int, b: Int): Boolean =
        edgeU[a] == edgeU[b] || edgeU[a] == edgeV[b] ||
            edgeV[a] == edgeU[b] || edgeV[a] == edgeV[b]

    fun sameWay(a: Int, b: Int): Boolean = edgeWay[a] == edgeWay[b]

    /** True when the point lies inside the graph's actual geometry bounds. */
    fun covers(latitudeDeg: Double, longitudeDeg: Double, marginDeg: Double): Boolean {
        val (west, south, east, north) = dataBounds
        return longitudeDeg >= west - marginDeg && longitudeDeg <= east + marginDeg &&
            latitudeDeg >= south - marginDeg && latitudeDeg <= north + marginDeg
    }

    /**
     * Edge indices whose bounding box may lie within `radiusM` of the point.
     * Grid-precise filtering is the caller's job; this over-approximates.
     */
    fun candidatesNear(latitudeDeg: Double, longitudeDeg: Double, radiusM: Double): IntArray {
        val (mPerDegLat, mPerDegLon) = metresPerDegree(latitudeDeg)
        val dLat = radiusM / mPerDegLat
        val dLon = radiusM / mPerDegLon
        val minLat = cellIndex(latitudeDeg - dLat)
        val maxLat = cellIndex(latitudeDeg + dLat)
        val minLon = cellIndex(longitudeDeg - dLon)
        val maxLon = cellIndex(longitudeDeg + dLon)
        val seen = BooleanArray(edgeCount)
        var count = 0
        var out = IntArray(64)
        for (latIdx in minLat..maxLat) {
            for (lonIdx in minLon..maxLon) {
                val bucket = grid[cellKey(latIdx, lonIdx)] ?: continue
                for (edge in bucket) {
                    if (!seen[edge]) {
                        seen[edge] = true
                        if (count == out.size) out = out.copyOf(count * 2)
                        out[count++] = edge
                    }
                }
            }
        }
        return out.copyOf(count)
    }

    /**
     * Closest point on the edge polyline to the given position, in a local
     * tangent plane at the query point (WGS84 radii via the shared Geodesy
     * math; the planar approximation is sub-millimetre at these distances).
     */
    fun project(edge: Int, latitudeDeg: Double, longitudeDeg: Double): Projection {
        val (lat, lon) = decodeGeometry(edge)
        val (mPerDegLat, mPerDegLon) = metresPerDegree(latitudeDeg)
        if (lat.size == 1) {
            val x = (lon[0] - longitudeDeg) * mPerDegLon
            val y = (lat[0] - latitudeDeg) * mPerDegLat
            return Projection(hypot(x, y), lat[0], lon[0], 0.0)
        }
        var bestD2 = Double.POSITIVE_INFINITY
        var bestX = 0.0
        var bestY = 0.0
        var bestHeading = 0.0
        var x1 = (lon[0] - longitudeDeg) * mPerDegLon
        var y1 = (lat[0] - latitudeDeg) * mPerDegLat
        for (i in 1 until lat.size) {
            val x2 = (lon[i] - longitudeDeg) * mPerDegLon
            val y2 = (lat[i] - latitudeDeg) * mPerDegLat
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else clamp01((0.0 - x1) * dx / len2 + (0.0 - y1) * dy / len2)
            val px = x1 + t * dx
            val py = y1 + t * dy
            val d2 = px * px + py * py
            if (d2 < bestD2) {
                bestD2 = d2
                bestX = px
                bestY = py
                bestHeading = bearingRad(dx, dy)
            }
            x1 = x2
            y1 = y2
        }
        val bestLat = latitudeDeg + bestY / mPerDegLat
        val bestLon = longitudeDeg + bestX / mPerDegLon
        return Projection(hypot(bestX, bestY), bestLat, bestLon, bestHeading)
    }

    /** Decoded polyline of one edge: latitude degrees, longitude degrees. */
    fun decodeGeometry(edge: Int): Pair<DoubleArray, DoubleArray> {
        val start = edgeGeomOffset[edge]
        val end = edgeGeomOffset[edge + 1]
        val lat = DoubleArray(end - start)
        val lon = DoubleArray(end - start)
        var latE6 = 0
        var lonE6 = 0
        for (i in start until end) {
            if (i == start) {
                latE6 = geomLatRaw[i]
                lonE6 = geomLonRaw[i]
            } else {
                latE6 += geomLatRaw[i]
                lonE6 += geomLonRaw[i]
            }
            lat[i - start] = latE6 / 1e6
            lon[i - start] = lonE6 / 1e6
        }
        return lat to lon
    }

    private fun buildGrid(): Map<Long, IntArray> {
        val buckets = HashMap<Long, MutableList<Int>>()
        for (edge in 0 until edgeCount) {
            val (lat, lon) = decodeGeometry(edge)
            var minLat = lat[0]
            var maxLat = lat[0]
            var minLon = lon[0]
            var maxLon = lon[0]
            for (i in 1 until lat.size) {
                if (lat[i] < minLat) minLat = lat[i]
                if (lat[i] > maxLat) maxLat = lat[i]
                if (lon[i] < minLon) minLon = lon[i]
                if (lon[i] > maxLon) maxLon = lon[i]
            }
            val (mPerDegLat, mPerDegLon) = metresPerDegree((minLat + maxLat) / 2.0)
            val latLo = cellIndex(minLat - CELL_SLACK_M / mPerDegLat)
            val latHi = cellIndex(maxLat + CELL_SLACK_M / mPerDegLat)
            val lonLo = cellIndex(minLon - CELL_SLACK_M / mPerDegLon)
            val lonHi = cellIndex(maxLon + CELL_SLACK_M / mPerDegLon)
            for (latIdx in latLo..latHi) {
                for (lonIdx in lonLo..lonHi) {
                    buckets.getOrPut(cellKey(latIdx, lonIdx)) { ArrayList() }.add(edge)
                }
            }
        }
        return buckets.mapValues { (_, list) -> list.toIntArray() }
    }

    private fun metresPerDegree(latitudeDeg: Double): Pair<Double, Double> {
        val (rE, rN) = Geodesy.radii(Math.toRadians(latitudeDeg))
        val toRad = Math.PI / 180.0
        return (rN * toRad) to (rE * cos(Math.toRadians(latitudeDeg)) * toRad)
    }

    private fun cellIndex(deg: Double): Int = floor(deg / cellSizeDeg).toInt()

    private fun cellKey(latIdx: Int, lonIdx: Int): Long =
        (latIdx.toLong() shl 32) xor (lonIdx.toLong() and 0xffffffffL)

    private fun clamp01(value: Double): Double =
        if (value < 0.0) 0.0 else if (value > 1.0) 1.0 else value

    private fun bearingRad(east: Double, north: Double): Double {
        val raw = Math.atan2(east, north)
        return if (raw < 0.0) raw + 2.0 * Math.PI else raw
    }

    class Projection(
        val distanceM: Double,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val headingRad: Double,
    )

    companion object {
        const val FORMAT = "idr-road-graph/1"
        const val GRAPH_FILE = "road-graph.json.gz"
        const val CELL_DEG = 0.001
        private const val CELL_SLACK_M = 5.0

        /** Load the packaged gzipped graph. Streams; no whole-file DOM. */
        fun load(graphFile: File): RoadGraph {
            require(graphFile.name == GRAPH_FILE) { "unexpected graph file ${graphFile.name}" }
            GZIPInputStream(graphFile.inputStream().buffered()).use { gzip ->
                return parse(JsonReader(gzip.reader(Charsets.UTF_8)))
            }
        }

        /**
         * Verify the packaged graph against its manifest (byte size and SHA-256).
         * Throws IllegalStateException on any mismatch; one check, one home.
         */
        fun verifyManifest(graphFile: File, manifestFile: File) {
            val manifest = com.google.gson.JsonParser
                .parseString(manifestFile.readText(Charsets.UTF_8)).asJsonObject
            val format = manifest["format"].asString
            check(format == FORMAT) { "unsupported road-graph format $format" }
            val entries = manifest["files"].asJsonArray.map { it.asJsonObject }
            val entry = entries.singleOrNull { it["path"].asString == graphFile.name }
                ?: throw IllegalStateException("manifest has no entry for ${graphFile.name}")
            val bytes = entry["bytes"].asLong
            val sha256 = entry["sha256"].asString
            check(graphFile.length() == bytes) {
                "road-graph size mismatch: ${graphFile.length()} != $bytes"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            graphFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "road-graph checksum mismatch" }
        }

        private fun parse(json: JsonReader): RoadGraph {
            var format = ""
            var id = ""
            var version = 0
            var queryBounds = DoubleArray(0)
            var dataBounds = DoubleArray(0)
            var classes = emptyArray<String>()
            var names = emptyArray<String>()
            var nodeLatE6 = IntArray(0)
            var nodeLonE6 = IntArray(0)
            var edgeU = IntArray(0)
            var edgeV = IntArray(0)
            var edgeClass = IntArray(0)
            var edgeName = IntArray(0)
            var edgeWay = LongArray(0)
            var edgePart = IntArray(0)
            var edgeGeomOffset = IntArray(0)
            var geomLatRaw = IntArray(0)
            var geomLonRaw = IntArray(0)

            json.beginObject()
            while (json.hasNext()) {
                when (json.nextName()) {
                    "format" -> format = json.nextString()
                    "id" -> id = json.nextString()
                    "version" -> version = json.nextInt()
                    "query_bounds" -> queryBounds = readDoubles(json)
                    "data_bounds" -> dataBounds = readDoubles(json)
                    "classes" -> classes = readStrings(json)
                    "names" -> names = readStrings(json)
                    "nodes" -> {
                        val lats = IntBuffer()
                        val lons = IntBuffer()
                        json.beginArray()
                        while (json.hasNext()) {
                            json.beginArray()
                            lats.add(json.nextInt())
                            lons.add(json.nextInt())
                            json.endArray()
                        }
                        json.endArray()
                        nodeLatE6 = lats.toArray()
                        nodeLonE6 = lons.toArray()
                    }
                    "edge_u" -> edgeU = readInts(json)
                    "edge_v" -> edgeV = readInts(json)
                    "edge_class" -> edgeClass = readInts(json)
                    "edge_name" -> edgeName = readInts(json)
                    "edge_part" -> edgePart = readInts(json)
                    "edge_geom_offset" -> edgeGeomOffset = readInts(json)
                    "edge_way" -> {
                        val buffer = LongBuffer()
                        json.beginArray()
                        while (json.hasNext()) buffer.add(json.nextLong())
                        json.endArray()
                        edgeWay = buffer.toArray()
                    }
                    "geom" -> {
                        val lats = IntBuffer()
                        val lons = IntBuffer()
                        json.beginArray()
                        while (json.hasNext()) {
                            json.beginArray()
                            lats.add(json.nextInt())
                            lons.add(json.nextInt())
                            json.endArray()
                        }
                        json.endArray()
                        geomLatRaw = lats.toArray()
                        geomLonRaw = lons.toArray()
                    }
                    else -> json.skipValue()
                }
            }
            json.endObject()
            check(format == FORMAT) { "unsupported road-graph format $format" }
            check(edgeU.size == edgeV.size && edgeU.size == edgeClass.size &&
                edgeU.size == edgeName.size && edgeU.size == edgeWay.size &&
                edgeU.size == edgePart.size && edgeGeomOffset.size == edgeU.size + 1
            ) { "inconsistent edge arrays" }
            check(nodeLatE6.size == nodeLonE6.size) { "inconsistent node arrays" }
            check(edgeU.isNotEmpty() && nodeLatE6.isNotEmpty()) { "road graph is empty" }
            check(queryBounds.size == 4 && dataBounds.size == 4 &&
                queryBounds.all { it.isFinite() } && dataBounds.all { it.isFinite() }
            ) { "invalid road-graph bounds" }
            check(classes.isNotEmpty()) { "road graph has no classes" }
            check(geomLatRaw.size == geomLonRaw.size && edgeGeomOffset.first() == 0 &&
                edgeGeomOffset.last() == geomLatRaw.size
            ) { "inconsistent geometry arrays" }
            for (i in edgeGeomOffset.indices) {
                check(edgeGeomOffset[i] in 0..geomLatRaw.size &&
                    (i == 0 || edgeGeomOffset[i] > edgeGeomOffset[i - 1])
                ) { "invalid edge geometry offsets" }
            }
            for (i in edgeU.indices) {
                check(edgeU[i] in nodeLatE6.indices && edgeV[i] in nodeLatE6.indices) { "edge node index out of range" }
                check(edgeClass[i] in classes.indices) { "edge class index out of range" }
                check(edgeName[i] == -1 || edgeName[i] in names.indices) { "edge name index out of range" }
                check(edgeWay[i] > 0L && edgePart[i] >= 0) { "invalid edge identity" }
            }
            for (i in nodeLatE6.indices) {
                check(nodeLatE6[i] / 1e6 in -90.0..90.0 && nodeLonE6[i] / 1e6 in -180.0..180.0) {
                    "node coordinate out of range"
                }
            }
            val nodeLat = DoubleArray(nodeLatE6.size) { nodeLatE6[it] / 1e6 }
            val nodeLon = DoubleArray(nodeLonE6.size) { nodeLonE6[it] / 1e6 }
            return RoadGraph(
                id, version, queryBounds, dataBounds, classes, names,
                nodeLat, nodeLon, edgeU, edgeV, edgeClass, edgeName,
                edgeWay, edgePart, edgeGeomOffset, geomLatRaw, geomLonRaw,
            )
        }

        private fun readInts(json: JsonReader): IntArray {
            val buffer = IntBuffer()
            json.beginArray()
            while (json.hasNext()) buffer.add(json.nextInt())
            json.endArray()
            return buffer.toArray()
        }

        private fun readDoubles(json: JsonReader): DoubleArray {
            val buffer = DoubleBuffer()
            json.beginArray()
            while (json.hasNext()) buffer.add(json.nextDouble())
            json.endArray()
            return buffer.toArray()
        }

        private fun readStrings(json: JsonReader): Array<String> {
            val list = ArrayList<String>()
            json.beginArray()
            while (json.hasNext()) list.add(json.nextString())
            json.endArray()
            return list.toTypedArray()
        }

        private class IntBuffer {
            private var data = IntArray(64)
            private var size = 0
            fun add(value: Int) {
                if (size == data.size) data = data.copyOf(size * 2)
                data[size++] = value
            }
            fun toArray(): IntArray = data.copyOf(size)
        }

        private class LongBuffer {
            private var data = LongArray(64)
            private var size = 0
            fun add(value: Long) {
                if (size == data.size) data = data.copyOf(size * 2)
                data[size++] = value
            }
            fun toArray(): LongArray = data.copyOf(size)
        }

        private class DoubleBuffer {
            private var data = DoubleArray(8)
            private var size = 0
            fun add(value: Double) {
                if (size == data.size) data = data.copyOf(size * 2)
                data[size++] = value
            }
            fun toArray(): DoubleArray = data.copyOf(size)
        }
    }
}
