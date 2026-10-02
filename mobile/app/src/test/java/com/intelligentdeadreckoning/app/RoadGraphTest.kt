package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.matching.RoadGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class RoadGraphTest {

    private fun loadFixture(vararg roads: RoadGraphFixtures.Road): RoadGraph {
        val dir = Files.createTempDirectory("road-graph-test").toFile()
        dir.deleteOnExit()
        val file = File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(file, RoadGraphFixtures.graphJson(roads.toList()))
        return RoadGraph.load(file)
    }

    @Test
    fun aFixtureGraphParsesWithItsTopology() {
        // Two roads meeting end-to-end at one coordinate share exactly one node.
        val meeting = RoadGraphFixtures.lat(200.0) to RoadGraphFixtures.lon(0.0)
        val graph = loadFixture(
            RoadGraphFixtures.Road(1, points = listOf(
                RoadGraphFixtures.lat(-200.0) to RoadGraphFixtures.lon(0.0),
                meeting,
            )),
            RoadGraphFixtures.Road(2, points = listOf(
                meeting,
                RoadGraphFixtures.lat(200.0) to RoadGraphFixtures.lon(200.0),
            )),
        )
        assertEquals(2, graph.edgeCount)
        assertEquals(3, graph.nodeCount)
        assertEquals("osm/1/0", graph.edgeId(0))
        assertEquals("osm/2/0", graph.edgeId(1))
        assertTrue(graph.sharesNode(0, 1))
        assertFalse(graph.sameWay(0, 1))
        assertEquals("residential", graph.roadClass(0))
        assertNull(graph.edgeName(0))
    }

    @Test
    fun projectionFindsTheClosestPointAndRoadHeading() {
        val graph = loadFixture(RoadGraphFixtures.northSouth(1, 0.0))
        val projection = graph.project(0, RoadGraphFixtures.lat(50.0), RoadGraphFixtures.lon(2.0))
        assertTrue("distance ${projection.distanceM}", abs(projection.distanceM - 2.0) < 0.01)
        assertTrue("heading ${projection.headingRad}", abs(projection.headingRad) < 0.01)
        assertTrue(abs(projection.latitudeDeg - RoadGraphFixtures.lat(50.0)) < 1e-6)
        assertTrue(abs(projection.longitudeDeg - RoadGraphFixtures.lon(0.0)) < 1e-6)

        val eastRoad = loadFixture(RoadGraphFixtures.eastWest(2, 0.0))
        val onto = eastRoad.project(0, RoadGraphFixtures.lat(1.0), RoadGraphFixtures.lon(-30.0))
        assertTrue(abs(onto.distanceM - 1.0) < 0.01)
        assertTrue("heading ${onto.headingRad}", abs(onto.headingRad - Math.PI / 2.0) < 0.01)
    }

    @Test
    fun candidatesNearReturnsOnlyLocalEdges() {
        val graph = loadFixture(
            RoadGraphFixtures.northSouth(1, 0.0),
            RoadGraphFixtures.northSouth(2, 500.0),
        )
        val near = graph.candidatesNear(RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(0.0), 20.0)
        assertEquals(1, near.size)
        assertEquals(0, near[0])
    }

    @Test
    fun coverageRejectsPointsOutsideTheGraphExtent() {
        val graph = loadFixture(RoadGraphFixtures.northSouth(1, 0.0))
        assertTrue(graph.covers(RoadGraphFixtures.ANCHOR_LAT, RoadGraphFixtures.ANCHOR_LON, 0.005))
        assertFalse(graph.covers(12.0, 72.0, 0.005))
    }

    @Test
    fun thePackagedGraphMatchesItsManifest() {
        val root = File("src/main/assets/roadgraph/hyderabad-v1")
        val graphFile = File(root, RoadGraph.GRAPH_FILE)
        RoadGraph.verifyManifest(graphFile, File(root, "manifest.json"))
        val graph = RoadGraph.load(graphFile)
        assertEquals("hyderabad-road-graph-v1", graph.id)
        assertEquals(1, graph.version)
        assertTrue("edgeCount ${graph.edgeCount}", graph.edgeCount > 100_000)
        assertTrue(graph.covers(17.3616, 78.4747, 0.005)) // Charminar
        assertFalse(graph.covers(12.0, 72.0, 0.005))
    }

    @Test
    fun aStructurallyCorruptGraphIsRefusedByTheLoader() {
        val dir = Files.createTempDirectory("road-graph-corrupt").toFile()
        dir.deleteOnExit()
        val graphFile = File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(graphFile, """{"format":"idr-road-graph/1","id":"broken","version":1,"query_bounds":[0,0,1,1],"data_bounds":[0,0,1,1],"classes":["residential"],"names":[],"nodes":[[0,0],[100,100]],"edge_u":[0],"edge_v":[1],"edge_class":[9],"edge_name":[-1],"edge_way":[1],"edge_part":[0],"edge_geom_offset":[0,2],"geom":[[0,0],[100,100]]}""")
        val error = runCatching { RoadGraph.load(graphFile) }.exceptionOrNull()
        assertTrue("corrupt graph should be refused, got $error", error is IllegalStateException)
    }

    @Test
    fun missingGraphFailsAsAnIoErrorWithoutAUsableGraph() {
        val dir = Files.createTempDirectory("road-graph-missing").toFile()
        val missing = File(dir, RoadGraph.GRAPH_FILE)
        val error = runCatching { RoadGraph.load(missing) }.exceptionOrNull()
        assertTrue(error is java.io.FileNotFoundException)
    }

    @Test
    fun aTamperedGraphIsRefusedByChecksum() {
        val root = File("src/main/assets/roadgraph/hyderabad-v1")
        val dir = Files.createTempDirectory("road-graph-tamper").toFile()
        dir.deleteOnExit()
        val copy = File(dir, RoadGraph.GRAPH_FILE)
        val bytes = File(root, RoadGraph.GRAPH_FILE).readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        copy.writeBytes(bytes)
        val error = runCatching {
            RoadGraph.verifyManifest(copy, File(root, "manifest.json"))
        }.exceptionOrNull()
        assertTrue("expected checksum failure, got $error", error is IllegalStateException)
    }
}
