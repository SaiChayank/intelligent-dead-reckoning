package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.map.EngineSessionMap
import com.intelligentdeadreckoning.app.matching.MapMatcher
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.contracts.v1.Confidence
import com.intelligentdeadreckoning.contracts.v1.ConfidenceState
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GeoOrigin
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.InitializationMode
import com.intelligentdeadreckoning.contracts.v1.LocalizationMode
import com.intelligentdeadreckoning.contracts.v1.NavigationState
import com.intelligentdeadreckoning.contracts.v1.NavigationStatus
import com.intelligentdeadreckoning.contracts.v1.Quaternion
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import com.intelligentdeadreckoning.contracts.v1.Vector3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

/**
 * The NavigationEngine → NavigationState → NavigationPresentation → MapOverlay → MapLibreRenderer
 * fold, on its own: what the map is allowed to claim from engine output, and what it must never
 * invent. Map matching appears only here as an evaluation overlay beside the raw position.
 */
class EngineSessionMapTest {

    private val header = Header("engine-map-session", Source.REAL, "1.1.0")
    private val epoch = 1_700_000_000_000_000_000L

    /** A yaw of 90 degrees east, as a unit quaternion: what a calibrated engine publishes. */
    private val q90 = Quaternion(0.7071067811865476, 0.0, 0.0, 0.7071067811865476)

    private fun nav(
        tNs: Long = epoch,
        mode: LocalizationMode? = LocalizationMode.FUSED,
        status: NavigationStatus = NavigationStatus.TRACKING,
        northM: Double = 0.0,
        eastM: Double = 0.0,
        headingDeg: Double? = 90.0,
        velocity: Vector3? = Vector3(3.0, 0.0, 0.0),
    ) = Record(
        header,
        Event(
            // The wire rule is exact: event_id is a decimal string, unique per stream.
            tNs.toString(), tNs, tNs,
            NavigationState(
                status, InitializationMode.DEPLOYABLE,
                GeoOrigin(RoadGraphFixtures.ANCHOR_LAT, RoadGraphFixtures.ANCHOR_LON, 500.0),
                Vector3(eastM, northM, 0.0), velocity, q90, headingDeg, "cal-1", true, mode,
            ),
        ),
    )

    private fun confidence(tNs: Long = epoch, accuracy: Double? = 8.0) = Record(
        header,
        Event((tNs + 1).toString(), tNs, tNs, Confidence(ConfidenceState.CALIBRATED, null, accuracy, null)),
    )

    private fun unvalidatedConfidence(tNs: Long = epoch, radius: Double? = 8.0, speedStd: Double? = 0.4) = Record(
        header,
        Event((tNs + 2).toString(), tNs, tNs, Confidence(ConfidenceState.UNVALIDATED, null, radius, speedStd)),
    )

    private fun graph(vararg roads: RoadGraphFixtures.Road): RoadGraph {
        val dir = Files.createTempDirectory("engine-map-test").toFile()
        dir.deleteOnExit()
        val file = File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(file, RoadGraphFixtures.graphJson(roads.toList()))
        return RoadGraph.load(file)
    }

    private fun lat(m: Double) = RoadGraphFixtures.lat(m)
    private fun lon(m: Double) = RoadGraphFixtures.lon(m)

    @Test
    fun aStateWaitsForItsOwnConfidenceAndNeverInventsOne() {
        val fold = EngineSessionMap()
        assertNull("a state with no confidence yet must not publish", fold.accept(nav(), epoch).point)
        val paired = fold.accept(confidence(), epoch)
        assertNotNull(paired.point)
        assertEquals(8.0, paired.accuracy95Metres!!, 0.0)
        assertEquals(90.0, paired.headingDegrees!!, 0.0)
    }

    @Test
    fun anUnpairedStateIsPublishedWithoutAnAccuracy() {
        val fold = EngineSessionMap()
        fold.accept(nav(), epoch)
        val second = fold.accept(nav(tNs = epoch + 100_000_000, eastM = 3.0), epoch + 100_000_000)
        assertNotNull(second.point)
        assertNull("a missing confidence is left missing, never estimated", second.accuracy95Metres)
    }

    @Test
    fun localizationModeIsCarriedIntoThePresentation() {
        val fold = EngineSessionMap()
        fold.accept(nav(mode = LocalizationMode.FUSED), epoch)
        assertEquals("fused", fold.accept(confidence(), epoch).localizationMode)
        val recovery = EngineSessionMap()
        recovery.accept(nav(mode = LocalizationMode.RECOVERY), epoch)
        assertEquals("recovery", recovery.accept(confidence(), epoch).localizationMode)
    }

    @Test
    fun localizationModeCannotCrossTheVersionBoundary() {
        val v1 = Header("engine-map-session", Source.REAL, "1.0.0")
        val fold = EngineSessionMap()
        fold.accept(nav(mode = null).copy(header = v1), epoch)
        val state = fold.accept(confidence().copy(header = v1), epoch)
        assertNotNull(state.point)
        assertNull(state.localizationMode)
        // A 1.1.0 field under a 1.0.0 header is refused rather than silently dropped.
        val t = epoch + 100_000_000
        fold.accept(nav(tNs = t, mode = LocalizationMode.FUSED).copy(header = v1), t)
        val refused = fold.accept(confidence(t).copy(header = v1), t)
        assertNull(refused.point)
        assertEquals("Invalid navigation record", refused.status)
    }

    @Test
    fun staleStateExpiresAndTakesTheMatchedOverlayWithIt() {
        val fold = EngineSessionMap()
        fold.evaluation = graph(RoadGraphFixtures.eastWest(1, 0.0))
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        val live = fold.accept(confidence(), epoch)
        assertNotNull(live.point)
        assertNotNull("evaluation matching accepted the fix", live.matchedPoint)
        val expired = fold.snapshot(epoch + 3_000_000_000L + 1)
        assertNull("a stopped stream leaves the screen", expired.point)
        assertNull(expired.matchedPoint)
        assertTrue(expired.matchedTrail.isEmpty())
    }

    @Test
    fun evaluationMatchesPublishedPositionsBesideTheRawOne() {
        val fold = EngineSessionMap()
        fold.evaluation = graph(RoadGraphFixtures.eastWest(1, 0.0))
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        val first = fold.accept(confidence(), epoch)
        assertEquals(lat(10.0), first.point!!.latitude, 5e-7)
        assertEquals(lon(50.0), first.point.longitude, 5e-7)
        val matched = first.matchedPoint
        assertNotNull(matched)
        assertNotEquals(first.point, matched)
        assertTrue("matched ${matched!!.latitude}", abs(matched.latitude - lat(0.0)) < 1e-5)
        assertTrue("matched ${matched.longitude}", abs(matched.longitude - lon(50.0)) < 1e-5)
        assertEquals(MapMatcher.MATCHER_VERSION, first.matcherVersion)
        assertEquals("osm/1/0", first.matchedEdgeId)
        val second = epoch + 100_000_000
        fold.accept(nav(tNs = second, northM = 10.0, eastM = 60.0), second)
        val paired = fold.accept(confidence(second), second)
        assertEquals("the raw trail is the published trail", 2, paired.trail.size)
        assertEquals(2, paired.matchedTrail.size)
        assertEquals(lat(10.0), paired.trail.last().latitude, 5e-7)
    }

    @Test
    fun matchingRefusesWhenTheReportedAccuracyRefuses() {
        val fold = EngineSessionMap()
        fold.evaluation = graph(RoadGraphFixtures.eastWest(1, 0.0))
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        val published = fold.accept(confidence(accuracy = 60.0), epoch)
        assertNotNull("the raw position is still drawn", published.point)
        assertNull("a 60 m 95% radius is no basis for a match", published.matchedPoint)
    }

    @Test
    fun anUnvalidatedCovarianceFillsItsOwnFieldAndStillGatesTheMatcher() {
        val fold = EngineSessionMap()
        fold.evaluation = graph(RoadGraphFixtures.eastWest(1, 0.0))
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        val published = fold.accept(unvalidatedConfidence(), epoch)
        assertNotNull(published.point)
        assertEquals(8.0, published.unvalidatedAccuracy95Metres!!, 0.0)
        assertEquals("unvalidated", published.confidenceState)
        assertEquals(0.4, published.speedStdMetresPerSecond!!, 0.0)
        assertNull("an unvalidated model radius is never the calibrated radius", published.accuracy95Metres)
        // The evaluation matcher's precision gate consumes the uncertainty the engine published,
        // whichever state it is in. What it was never fed is the platform's own fix radius.
        assertNotNull("the published covariance is the gate's input", published.matchedPoint)
        assertNull(published.fixRadiusMetres)
    }

    @Test
    fun aStateThatHasValidatedNothingIsNotGivenARadius() {
        val fold = EngineSessionMap()
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        val ignored = fold.accept(
            Record(
                header,
                Event((epoch + 3).toString(), epoch, epoch, Confidence(ConfidenceState.UNAVAILABLE, null, 12.0, 0.9)),
            ),
            epoch,
        )
        assertNotNull(ignored.point)
        assertNull(ignored.accuracy95Metres)
        assertNull(ignored.unvalidatedAccuracy95Metres)
        assertNull(ignored.speedStdMetresPerSecond)
    }

    @Test
    fun graphInstallationFailureKeepsRawPositionAndExplainsMissingOverlay() {
        val fold = EngineSessionMap()
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        fold.accept(confidence(), epoch)
        val graphless = EngineSessionMap()
        graphless.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        graphless.accept(confidence(), epoch)
        graphless.matchingIssue = "Road graph unavailable: injected storage failure"
        val shown = graphless.snapshot(epoch)
        assertNotNull("graph failure does not remove raw navigation", shown.point)
        assertNull(shown.matchedPoint)
        assertTrue(shown.matchingIssue!!.contains("injected storage failure"))
    }

    @Test
    fun withoutAnEvaluationGraphNothingIsMatched() {
        val fold = EngineSessionMap()
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        val shown = fold.accept(confidence(), epoch)
        assertNull(shown.matchedPoint)
        assertFalse(shown.trail.isEmpty())
    }

    @Test
    fun anInvalidRecordIsRejectedRatherThanDrawn() {
        val fold = EngineSessionMap()
        val broken = nav().let { r ->
            r.copy(event = r.event.copy(data = (r.event.data as NavigationState).copy(position_enu_m = Vector3(Double.NaN, 0.0, 0.0))))
        }
        // The state is held for its confidence, and the held state is rejected when it is published.
        fold.accept(broken, epoch)
        val shown = fold.accept(confidence(), epoch)
        assertNull(shown.point)
        assertEquals("Invalid navigation record", shown.status)
    }

    @Test
    fun aNewEngineSessionRestartsTheFoldWithoutMixingSessions() {
        val fold = EngineSessionMap()
        fold.evaluation = graph(RoadGraphFixtures.eastWest(1, 0.0))
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        val first = fold.accept(confidence(), epoch)
        assertEquals(1, first.trail.size)
        assertEquals(1, first.matchedTrail.size)
        val t = epoch + 100_000_000
        val next = nav(tNs = t, northM = 10.0, eastM = 30.0).copy(header = header.copy(session_id = "second-session"))
        // The previous session's position and trail are gone at once: nothing stands in for the
        // new session until the new session publishes its own position.
        val restarted = fold.accept(next, t)
        assertNull(restarted.point)
        assertTrue(restarted.matchedTrail.isEmpty())
        assertEquals("No engine output yet", restarted.status)
        val shown = fold.accept(confidence(t).copy(header = next.header), t)
        assertEquals(1, shown.trail.size)
        assertEquals(lon(30.0), shown.point!!.longitude, 5e-7)
        assertEquals(1, shown.matchedTrail.size)
    }

    @Test
    fun resetReturnsTheFoldToAnEmptySession() {
        val fold = EngineSessionMap()
        fold.accept(nav(northM = 10.0, eastM = 50.0), epoch)
        fold.accept(confidence(), epoch)
        fold.reset()
        val empty = fold.snapshot(epoch)
        assertNull(empty.point)
        assertEquals("No engine output yet", empty.status)
    }
}
