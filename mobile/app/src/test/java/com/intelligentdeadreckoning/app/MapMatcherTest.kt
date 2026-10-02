package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.matching.MapMatcher
import com.intelligentdeadreckoning.app.matching.MapMatchStatus
import com.intelligentdeadreckoning.app.matching.RoadGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The MVP causal matcher against synthetic scenarios with exact metre geometry.
 * Every case the brief names has a test: parallel roads, intersections, service
 * roads, no candidate, low confidence, outside coverage. The rules under test
 * are honesty rules as much as geometry: the raw fused position is never
 * rewritten, ambiguous evidence is reported instead of guessed, and the matcher
 * is causal — a prefix of a drive matches identically with or without its future.
 */
class MapMatcherTest {

    private val accuracy = 8.0

    private fun graph(vararg roads: RoadGraphFixtures.Road): RoadGraph {
        val dir = Files.createTempDirectory("map-matcher-test").toFile()
        dir.deleteOnExit()
        val file = File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(file, RoadGraphFixtures.graphJson(roads.toList()))
        return RoadGraph.load(file)
    }

    private fun lat(m: Double) = RoadGraphFixtures.lat(m)
    private fun lon(m: Double) = RoadGraphFixtures.lon(m)

    @Test
    fun aFixOnARoadMatchesItAndKeepsRawSeparate() {
        val matcher = MapMatcher(graph(RoadGraphFixtures.northSouth(1, 0.0)))
        val result = matcher.match(1L, lat(50.0), lon(1.0), 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, result.status)
        assertEquals("osm/1/0", result.matchedEdgeId)
        assertTrue("distance ${result.distanceToEdgeM}", result.distanceToEdgeM!! < 1.5)
        // RAW FUSED POSITION survives untouched, MAP-MATCHED POSITION sits beside it.
        assertEquals(lat(50.0), result.rawLatitudeDeg, 0.0)
        assertEquals(lon(1.0), result.rawLongitudeDeg, 0.0)
        assertEquals(MapMatcher.MATCHER_VERSION, result.matcherVersion)
        assertTrue(result.matchedLatitudeDeg != null)
    }

    @Test
    fun parallelRoadsAreChosenByProximityAndHeading() {
        val matcher = MapMatcher(
            graph(
                RoadGraphFixtures.eastWest(1, 10.0),
                RoadGraphFixtures.eastWest(2, -10.0),
            ),
        )
        val result = matcher.match(1L, lat(7.0), lon(0.0), Math.PI / 2.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, result.status)
        assertEquals("osm/1/0", result.matchedEdgeId)
        assertTrue("confidence ${result.confidence}", result.confidence > 0.9)
    }

    @Test
    fun anAmbiguousFixBetweenParallelRoadsIsRefusedNotGuessed() {
        val matcher = MapMatcher(
            graph(
                RoadGraphFixtures.eastWest(1, 10.0),
                RoadGraphFixtures.eastWest(2, -10.0),
            ),
        )
        // Dead centre between two parallel roads: distance and heading cannot
        // separate them, and without history the honest answer is "not decided".
        val result = matcher.match(1L, lat(0.0), lon(0.0), Math.PI / 2.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.LOW_CONFIDENCE, result.status)
        assertNull(result.matchedEdgeId)
        assertNull(result.matchedLatitudeDeg)
        assertEquals(2, result.candidateCount)
    }

    @Test
    fun previousEdgeContinuityResolvesParallelAmbiguity() {
        val matcher = MapMatcher(
            graph(
                RoadGraphFixtures.eastWest(1, 10.0),
                RoadGraphFixtures.eastWest(2, -10.0),
            ),
        )
        val first = matcher.match(1L, lat(10.0), lon(-50.0), Math.PI / 2.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, first.status)
        assertEquals("osm/1/0", first.matchedEdgeId)
        val second = matcher.match(2_000_000_000L, lat(0.0), lon(0.0), Math.PI / 2.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, second.status)
        assertEquals("osm/1/0", second.matchedEdgeId)
    }

    @Test
    fun headingSelectsTheDrivenRoadAtACrossing() {
        val matcher = MapMatcher(
            graph(
                RoadGraphFixtures.northSouth(1, 0.0),
                RoadGraphFixtures.eastWest(2, 0.0),
            ),
        )
        // Two metres north of the crossing ON the north-south road; both roads
        // are two metres away or less, so only heading separates them.
        val result = matcher.match(1L, lat(2.0), lon(0.0), 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, result.status)
        assertEquals("osm/1/0", result.matchedEdgeId)
        assertTrue("confidence ${result.confidence}", result.confidence > 0.9)
    }

    @Test
    fun withoutHeadingACrossingIsAdmittedOnlyWithHistory() {
        val roads = arrayOf(
            RoadGraphFixtures.northSouth(1, 0.0),
            RoadGraphFixtures.eastWest(2, 0.0),
        )
        val cold = MapMatcher(graph(*roads))
        val undecided = cold.match(1L, lat(2.0), lon(0.0), null, accuracy, null)
        assertEquals(MapMatchStatus.LOW_CONFIDENCE, undecided.status)
        assertNull(undecided.matchedEdgeId)

        val warm = MapMatcher(graph(*roads))
        val first = warm.match(1L, lat(-10.0), lon(0.0), null, accuracy, null)
        assertEquals("osm/1/0", first.matchedEdgeId)
        val second = warm.match(2_000_000_000L, lat(2.0), lon(0.0), null, accuracy, null)
        assertEquals(MapMatchStatus.MATCHED, second.status)
        assertEquals("osm/1/0", second.matchedEdgeId)
    }

    @Test
    fun serviceRoadsMatchLikeAnyOtherRoadWhenClosest() {
        val roads = arrayOf(
            RoadGraphFixtures.northSouth(1, 0.0),
            RoadGraphFixtures.northSouth(2, 10.0, "service"),
        )
        val onService = MapMatcher(graph(*roads))
            .match(1L, lat(10.0), lon(11.0), 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, onService.status)
        assertEquals("osm/2/0", onService.matchedEdgeId)

        val onResidential = MapMatcher(graph(*roads))
            .match(1L, lat(10.0), lon(-2.0), 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, onResidential.status)
        assertEquals("osm/1/0", onResidential.matchedEdgeId)
    }

    @Test
    fun aFixWithNoRoadWithinReachIsReportedNotSnapped() {
        val matcher = MapMatcher(graph(RoadGraphFixtures.northSouth(1, 0.0)))
        val result = matcher.match(1L, lat(0.0), lon(100.0), 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.NO_CANDIDATE, result.status)
        assertNull(result.matchedEdgeId)
        assertNull(result.distanceToEdgeM)
        assertEquals(0, result.candidateCount)
    }

    @Test
    fun hugeReportedUncertaintyRefusesToDecide() {
        val matcher = MapMatcher(graph(RoadGraphFixtures.northSouth(1, 0.0)))
        val result = matcher.match(1L, lat(0.0), lon(0.0), 0.0, 80.0, 10.0)
        assertEquals(MapMatchStatus.LOW_CONFIDENCE, result.status)
        assertNull(result.matchedEdgeId)
    }

    @Test
    fun nonFiniteInputsAreRejectedRatherThanManufacturingCandidates() {
        val matcher = MapMatcher(graph(RoadGraphFixtures.northSouth(1, 0.0)))
        for (input in listOf(
            { matcher.match(1L, Double.NaN, 0.0, 0.0, accuracy, 10.0) },
            { matcher.match(1L, lat(0.0), lon(0.0), Double.POSITIVE_INFINITY, accuracy, 10.0) },
            { matcher.match(1L, lat(0.0), lon(0.0), 0.0, Double.NaN, 10.0) },
        )) {
            val error = runCatching { input() }.exceptionOrNull()
            assertTrue("invalid match input must be refused", error is IllegalArgumentException)
        }
    }

    @Test
    fun aFixOutsideCoverageIsReportedNotProjected() {
        val matcher = MapMatcher(graph(RoadGraphFixtures.northSouth(1, 0.0)))
        val result = matcher.match(1L, lat(5000.0), lon(0.0), 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.OUTSIDE_COVERAGE, result.status)
        assertNull(result.matchedEdgeId)
        assertEquals(0, result.candidateCount)
    }

    @Test
    fun matchingIsCausalAndIndependentOfFutureFixes() {
        val roads = arrayOf(
            RoadGraphFixtures.northSouth(1, 0.0),
            RoadGraphFixtures.northSouth(2, 12.0),
        )
        val fixes = listOf(
            Triple(1L, lat(-40.0), lon(0.5)),
            Triple(3L, lat(-20.0), lon(-0.5)),
            Triple(5L, lat(0.0), lon(0.5)),
            Triple(7L, lat(20.0), lon(-0.5)),
            Triple(9L, lat(40.0), lon(0.5)),
        )
        val full = MapMatcher(graph(*roads))
        val fullResults = fixes.map { (t, la, lo) -> full.match(t, la, lo, 0.0, accuracy, 10.0) }
        val prefix = MapMatcher(graph(*roads))
        val prefixResults = fixes.take(3).map { (t, la, lo) -> prefix.match(t, la, lo, 0.0, accuracy, 10.0) }
        for (i in prefixResults.indices) {
            assertEquals("fix $i diverged with future knowledge", fullResults[i], prefixResults[i])
            assertEquals("osm/1/0", fullResults[i].matchedEdgeId)
        }
    }

    @Test
    fun atLowSpeedTheHeadingTermStandsDown() {
        val roads = arrayOf(
            RoadGraphFixtures.northSouth(1, 0.0),
            RoadGraphFixtures.eastWest(2, 0.0),
        )
        // Three metres north of the east-west road, seven metres east of the
        // north-south one. Heading north at speed picks the farther road; at
        // parking speed the heading term drops and proximity picks the nearer.
        val driving = MapMatcher(graph(*roads))
            .match(1L, lat(3.0), lon(7.0), 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.MATCHED, driving.status)
        assertEquals("osm/1/0", driving.matchedEdgeId)

        val parking = MapMatcher(graph(*roads))
            .match(1L, lat(3.0), lon(7.0), 0.0, accuracy, 0.5)
        assertEquals(MapMatchStatus.MATCHED, parking.status)
        assertEquals("osm/2/0", parking.matchedEdgeId)
    }

    @Test
    fun theRealGraphMatchesAnIsolatedEdgeExactly() {
        val root = File("src/main/assets/roadgraph/hyderabad-v1")
        val graph = RoadGraph.load(File(root, RoadGraph.GRAPH_FILE))
        var chosen = -1
        var midLat = 0.0
        var midLon = 0.0
        var edge = 0
        while (edge < graph.edgeCount && chosen < 0) {
            val (lats, lons) = graph.decodeGeometry(edge)
            if (lats.size >= 2) {
                val candidateLat = (lats[0] + lats[1]) / 2.0
                val candidateLon = (lons[0] + lons[1]) / 2.0
                val near = graph.candidatesNear(candidateLat, candidateLon, 25.0)
                var isolated = true
                for (other in near) {
                    if (other != edge &&
                        graph.project(other, candidateLat, candidateLon).distanceM < 25.0
                    ) {
                        isolated = false
                    }
                }
                if (isolated) {
                    chosen = edge
                    midLat = candidateLat
                    midLon = candidateLon
                }
            }
            edge += 137
        }
        assertTrue("no isolated edge found in the packaged graph", chosen >= 0)
        val result = MapMatcher(graph).match(1L, midLat, midLon, null, accuracy)
        assertEquals(MapMatchStatus.MATCHED, result.status)
        assertEquals(graph.edgeId(chosen), result.matchedEdgeId)
        assertTrue("distance ${result.distanceToEdgeM}", result.distanceToEdgeM!! < 0.5)
    }

    @Test
    fun theRealGraphReportsPointsOutsideItsRegion() {
        val root = File("src/main/assets/roadgraph/hyderabad-v1")
        val graph = RoadGraph.load(File(root, RoadGraph.GRAPH_FILE))
        val result = MapMatcher(graph).match(1L, 12.0, 72.0, 0.0, accuracy, 10.0)
        assertEquals(MapMatchStatus.OUTSIDE_COVERAGE, result.status)
        assertNull(result.matchedEdgeId)
    }
}
