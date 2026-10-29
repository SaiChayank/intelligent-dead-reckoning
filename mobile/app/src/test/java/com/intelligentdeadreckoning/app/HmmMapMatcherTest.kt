package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionInitialState
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.GnssInsEkf
import com.intelligentdeadreckoning.app.fusion.GnssUpdateResult
import com.intelligentdeadreckoning.app.matching.HmmMapMatcher
import com.intelligentdeadreckoning.app.matching.MapMatchStatus
import com.intelligentdeadreckoning.app.matching.MapMatcher
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.math.hypot

class HmmMapMatcherTest {
    private fun graph(vararg roads: RoadGraphFixtures.Road): RoadGraph {
        val dir = Files.createTempDirectory("hmm-test").toFile().also { it.deleteOnExit() }
        val file = File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(file, RoadGraphFixtures.graphJson(roads.toList()))
        return RoadGraph.load(file)
    }

    private val east = Math.PI / 2.0

    /**
     * Two parallel east-west roads 15 m apart; the vehicle drives the southern one with 6 m GPS-like noise.
     * Both matchers are scored and printed; on this mild case they are close (the HMM is asserted not to be worse).
     */
    @Test
    fun staysOnTheTrueRoadBetweenParallelRoadsWithNoisyFixes() {
        val g = graph(RoadGraphFixtures.eastWest(1, 0.0), RoadGraphFixtures.eastWest(2, 15.0))
        val truthWay = "osm/1/0"
        val random = Random(5)
        val hmm = HmmMapMatcher(g)
        val greedy = MapMatcher(g)
        var hmmCorrect = 0
        var greedyCorrect = 0
        var steps = 0
        for (k in 0 until 40) {
            val eastM = -190.0 + k * 9.0
            val lat = RoadGraphFixtures.lat(0.0 + random.nextGaussian() * 6.0)
            val lon = RoadGraphFixtures.lon(eastM + random.nextGaussian() * 2.0)
            val t = 1_000_000_000L + k * 1_000_000_000L
            val h = hmm.step(t, lat, lon, east, accuracy95M = 15.0, speedM_S = 9.0)
            val m = greedy.match(t, lat, lon, east, accuracy95M = 15.0, speedM_S = 9.0)
            steps++
            if (h.result.matchedEdgeId == truthWay) hmmCorrect++
            if (m.matchedEdgeId == truthWay) greedyCorrect++
        }
        println("Parallel roads: HMM on the true road $hmmCorrect/$steps, greedy $greedyCorrect/$steps")
        assertTrue("HMM matched the true road only $hmmCorrect/$steps", hmmCorrect >= steps * 0.9)
        assertTrue("HMM ($hmmCorrect) must not be worse than greedy ($greedyCorrect)", hmmCorrect >= greedyCorrect)
    }

    @Test
    fun followsATurnOntoTheConnectedRoad() {
        val g = graph(
            RoadGraphFixtures.Road(1, points = listOf(RoadGraphFixtures.lat(0.0) to RoadGraphFixtures.lon(-200.0),
                RoadGraphFixtures.lat(0.0) to RoadGraphFixtures.lon(0.0))),
            RoadGraphFixtures.Road(2, points = listOf(RoadGraphFixtures.lat(0.0) to RoadGraphFixtures.lon(0.0),
                RoadGraphFixtures.lat(200.0) to RoadGraphFixtures.lon(0.0))),
        )
        val hmm = HmmMapMatcher(g)
        var last: String? = null
        for (k in 0 until 20) {                                   // east along road 1
            val r = hmm.step(1_000_000_000L * (k + 1), RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(-190.0 + k * 9.0), east, 15.0, 9.0)
            last = r.result.matchedEdgeId ?: last
        }
        assertEquals("osm/1/0", last)
        for (k in 1..20) {                                        // then north along road 2
            val r = hmm.step(1_000_000_000L * (20 + k), RoadGraphFixtures.lat(k * 9.0), RoadGraphFixtures.lon(0.0), 0.0, 15.0, 9.0)
            last = r.result.matchedEdgeId ?: last
        }
        assertEquals("osm/2/0", last)
    }

    @Test
    fun crossTrackCorrectionPointsFromTheRawPositionTowardTheRoad() {
        val g = graph(RoadGraphFixtures.eastWest(1, 0.0))
        val hmm = HmmMapMatcher(g)
        var r = hmm.step(1_000_000_000L, RoadGraphFixtures.lat(-5.0), RoadGraphFixtures.lon(-100.0), east, 15.0, 9.0)
        r = hmm.step(2_000_000_000L, RoadGraphFixtures.lat(-5.0), RoadGraphFixtures.lon(-90.0), east, 15.0, 9.0)
        assertEquals(MapMatchStatus.MATCHED, r.result.status)
        val nE = r.roadNormalEast!!
        val nN = r.roadNormalNorth!!
        assertEquals(1.0, hypot(nE, nN), 1e-9)
        assertEquals(5.0, Math.abs(r.crossTrackCorrectionM!!), 0.2)
        // The correction is the road normal dotted with (matched point - raw position).
        val mPerDegLat = Math.PI / 180.0 * 6_335_000.0
        val northM = (r.result.matchedLatitudeDeg!! - r.result.rawLatitudeDeg) * mPerDegLat
        assertEquals(nN * northM, r.crossTrackCorrectionM!!, 0.2)
    }

    /** Regression: every candidate score underflowed to zero after an impossible jump and the matcher threw. */
    @Test
    fun greedyMatcherReportsLowConfidenceInsteadOfCrashingOnAnImpossibleJump() {
        val g = graph(RoadGraphFixtures.eastWest(1, 0.0), RoadGraphFixtures.eastWest(2, 1500.0))
        val greedy = MapMatcher(g)
        assertEquals(MapMatchStatus.MATCHED,
            greedy.match(1_000_000_000L, RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(0.0), east, 15.0, 9.0).status)
        val jump = greedy.match(2_000_000_000L, RoadGraphFixtures.lat(1500.0), RoadGraphFixtures.lon(0.0), east, 15.0, 9.0)
        assertTrue("expected a refusal, got ${jump.status}", jump.status != MapMatchStatus.MATCHED)
        val hmm = HmmMapMatcher(g)
        hmm.step(1_000_000_000L, RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(0.0), east, 15.0, 9.0)
        hmm.step(2_000_000_000L, RoadGraphFixtures.lat(1500.0), RoadGraphFixtures.lon(0.0), east, 15.0, 9.0)   // must not throw
    }

    @Test
    fun reportsWhyNothingWasMatched() {
        val g = graph(RoadGraphFixtures.eastWest(1, 0.0))
        val hmm = HmmMapMatcher(g)
        assertEquals(MapMatchStatus.NO_CANDIDATE,
            hmm.step(1_000_000_000L, RoadGraphFixtures.lat(300.0), RoadGraphFixtures.lon(0.0), null, 15.0).result.status)
        assertEquals(MapMatchStatus.OUTSIDE_COVERAGE,
            hmm.step(2_000_000_000L, 40.0, 10.0, null, 15.0).result.status)
        assertEquals(MapMatchStatus.LOW_CONFIDENCE,
            hmm.step(3_000_000_000L, RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(0.0), null, 90.0).result.status)
    }

    @Test
    fun aStepOnlyAddsAColumnAfterTheMinimumMovement() {
        val g = graph(RoadGraphFixtures.eastWest(1, 0.0))
        val hmm = HmmMapMatcher(g)
        hmm.step(1_000_000_000L, RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(0.0), east, 15.0, 9.0)
        hmm.step(2_000_000_000L, RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(2.0), east, 15.0, 9.0)   // 2 m: no new column
        assertEquals(1, hmm.steps)
        hmm.step(3_000_000_000L, RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(12.0), east, 15.0, 9.0)
        assertEquals(2, hmm.steps)
        hmm.reset()
        assertEquals(0, hmm.steps)
    }

    @Test
    fun theLaggedPathIsAvailableOnceEnoughStepsExist() {
        val g = graph(RoadGraphFixtures.eastWest(1, 0.0))
        val hmm = HmmMapMatcher(g)
        var r = hmm.step(1_000_000_000L, RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(-150.0), east, 15.0, 9.0)
        assertNull(r.smoothedEdgeId)
        for (k in 1..8) {
            r = hmm.step(1_000_000_000L * (k + 1), RoadGraphFixtures.lat(0.0), RoadGraphFixtures.lon(-150.0 + k * 9.0), east, 15.0, 9.0)
        }
        assertNotNull(r.smoothedEdgeId)
        assertEquals("osm/1/0", r.smoothedEdgeId)
    }

    @Test
    fun crossTrackRowMovesTheFilterAlongTheNormalOnly() {
        val filter = GnssInsEkf(FusionConfig())
        val anchor = GeodeticAnchor(17.4, 78.45, 500.0, true)
        assertTrue(filter.align(FusionInitialState(
            anchor = anchor, qEnuFromDevice = Quaternion(1.0, 0.0, 0.0, 0.0),
            velocityEnuM_S = Vector3(0.0, 10.0, 0.0), gyroBiasDeviceRad_S = Vector3(0.0, 0.0, 0.0),
            accelBiasDeviceM_S2 = Vector3(0.0, 0.0, 0.0), positionVarianceM2 = Vector3(4.0, 4.0, 4.0),
            velocityVarianceM2 = Vector3(0.1, 0.1, 0.1), attitudeVarianceRad2 = Vector3(0.05, 0.05, 0.05),
            gyroBiasVarianceRad2_S2 = 1e-4, accelBiasVarianceM2_S4 = 1e-4,
        ), 1_000_000_000L))
        val before = filter.solution.positionEnuM
        // A road running north, 10 m to the east: normal (1, 0), matched point 10 m east of the filter.
        val outcome = filter.updateCrossTrack(1_050_000_000L, 1.0, 0.0, 10.0, 25.0)
        assertEquals(GnssUpdateResult.ACCEPTED, outcome.result)
        val after = filter.solution.positionEnuM
        assertTrue("east should move toward the road: ${after.x}", after.x > before.x + 1.0 && after.x < before.x + 10.0)
        assertEquals(before.y, after.y, 0.05)
        assertEquals(GnssUpdateResult.REJECTED_INVALID,
            filter.updateCrossTrack(1_100_000_000L, 2.0, 0.0, 1.0, 25.0).result)       // not a unit normal
    }
}
