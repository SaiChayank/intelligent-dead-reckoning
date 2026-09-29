package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.app.map.*
import com.intelligentdeadreckoning.contracts.v1.Event
import com.intelligentdeadreckoning.contracts.v1.GnssMeasurement
import com.intelligentdeadreckoning.contracts.v1.Header
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Source
import org.junit.Assert.*
import org.junit.Test

/** Real recorded fixes are displayed as recorded: never smoothed, bridged or given a mode. */
class RecordedSessionMapTest {
    private val ns = 9007199254740993L
    private val header = Header("acq-1", Source.REAL)

    private fun fix(id: String, timeNs: Long, latitude: Double = 17.40, longitude: Double = 78.45,
                    speed: Double? = null, bearing: Double? = null, accuracy: Double? = null,
                    provider: String = "gps") = Record(header, Event(id, timeNs, timeNs + 1_000_000,
        GnssMeasurement(latitude, longitude, null, null, speed, bearing, accuracy, null, null, provider, null)))

    @Test fun recordedFixIsDisplayedAsRecordedWithoutA95PercentClaim() {
        val map = RecordedSessionMap()
        val view = map.accept(fix("0", ns, accuracy = 8.0, speed = 12.5, provider = "gps"))
        assertEquals(Source.REPLAY_REAL, view.source)
        assertEquals(17.40, view.point!!.latitude, 1e-12)
        assertEquals(78.45, view.point!!.longitude, 1e-12)
        // A platform fix radius is not a calibrated 95% confidence and must not be relabelled as one.
        assertNull(view.accuracy95Metres)
        assertEquals(8.0, view.fixRadiusMetres!!, 1e-12)
        assertEquals(12.5, view.speedMetresPerSecond!!, 1e-12)
        assertEquals(1, map.stats().fixes)
        assertEquals(0, map.stats().lines)
        assertEquals(1, map.stats().points)
        assertEquals(1, view.trailSegments.single().size)
    }

    @Test fun aGapSplitsTheTrailInsteadOfBridgingIt() {
        val map = RecordedSessionMap()
        map.accept(fix("0", ns, longitude = 78.4500))
        map.accept(fix("1", ns + 1_000_000_000L, longitude = 78.4501))
        map.accept(fix("2", ns + 30_000_000_000L, longitude = 78.4502))
        map.accept(fix("3", ns + 31_000_000_000L, longitude = 78.4503))
        val view = map.snapshot()
        assertEquals(2, view.trailSegments.size)
        assertTrue(view.trailSegments.all { it.size == 2 })
        assertEquals(4, view.trail.size)
        assertEquals(1, map.stats().gaps)
        assertEquals(29_000_000_000L, map.stats().longestGapNs)
    }

    @Test fun headingFallsBackToCourseOverGroundBecauseFixesCarryNoBearing() {
        val northbound = RecordedSessionMap()
        northbound.accept(fix("0", ns, latitude = 17.4000))
        val view = northbound.accept(fix("1", ns + 1_000_000_000L, latitude = 17.4010))
        assertEquals(0.0, view.headingDegrees!!, 0.5)

        val tooShort = RecordedSessionMap()
        tooShort.accept(fix("0", ns, latitude = 17.400000))
        assertNull(tooShort.accept(fix("1", ns + 1_000_000_000L, latitude = 17.400001)).headingDegrees)
    }

    @Test fun reportedBearingIsUsedWhenThePlatformActuallySuppliesOne() {
        val map = RecordedSessionMap()
        map.accept(fix("0", ns))
        val view = map.accept(fix("1", ns + 1_000_000_000L, bearing = 271.5))
        assertEquals(271.5, view.headingDegrees!!, 1e-12)
    }

    @Test fun fixesOutsideBundledCoverageAreCountedAndNeverDrawn() {
        val map = RecordedSessionMap()
        val view = map.accept(fix("0", ns, latitude = 12.0, longitude = 77.0))
        assertNull(view.point)
        assertEquals(1, map.stats().outsideCoverage)
        assertEquals(0, map.stats().lines)
        assertEquals(0, map.stats().points)
        assertTrue(view.trailSegments.isEmpty())
        assertTrue(view.status.contains("coverage"))
    }

    @Test fun nonFiniteCoordinatesAreReportedAsMalformedNotPlotted() {
        val map = RecordedSessionMap()
        val view = map.accept(fix("0", ns, latitude = Double.NaN))
        assertNull(view.point)
        assertEquals(1, map.stats().malformed)
        assertTrue(map.snapshot().trailSegments.isEmpty())
    }

    @Test fun recordedSourceCannotProduceTheSyntheticComparisonOrScenarioLayers() {
        val map = RecordedSessionMap()
        map.accept(fix("0", ns, longitude = 78.4500))
        map.accept(fix("1", ns + 1_000_000_000L, longitude = 78.4501))
        map.accept(fix("2", ns + 30_000_000_000L, longitude = 78.4502))
        map.accept(fix("3", ns + 31_000_000_000L, longitude = 78.4503))
        val features = JsonParser.parseString(MapOverlay.json(map.snapshot(), DemoOverlays()))
            .asJsonObject["features"].asJsonArray.map { it.asJsonObject }
        val kinds = features.map { it["properties"].asJsonObject["kind"].asString }
        assertEquals(2, kinds.count { it == "trail" })
        listOf("comparison", "comparison-trail", "scenario", "outage").forEach {
            assertFalse("recorded data must not draw $it", kinds.contains(it))
        }
        assertTrue(kinds.contains("position"))
        assertEquals(0, kinds.count { it == "trail-fix" })
    }

    @Test fun trailHistoryStaysBounded() {
        val map = RecordedSessionMap(maxTrail = 8)
        for (i in 0 until 200) map.accept(fix("$i", ns + i * 1_000_000_000L, longitude = 78.45 + i * 1e-5))
        assertTrue(map.snapshot().trail.size <= 8)
        // 200 fixes were accepted, but the trail is bounded: the report states what is plotted.
        assertEquals(200, map.stats().fixes)
        assertEquals(1, map.stats().lines)
        assertEquals(0, map.stats().points)
    }

    @Test fun isolatedFixesAreDrawnAsPointsInsteadOfBeingCountedAndDropped() {
        val map = RecordedSessionMap()
        map.accept(fix("0", ns, longitude = 78.4500))
        val view = map.accept(fix("1", ns + 40_000_000_000L, longitude = 78.4502))
        // 40 s apart is two observations, not a path: no line is invented between them.
        assertEquals(2, view.trailSegments.size)
        assertTrue(view.trailSegments.all { it.size == 1 })
        assertEquals(0, map.stats().lines)
        assertEquals(2, map.stats().points)
        val kinds = JsonParser.parseString(MapOverlay.json(view, DemoOverlays()))
            .asJsonObject["features"].asJsonArray.map { it.asJsonObject["properties"].asJsonObject["kind"].asString }
        // Regression: these fixes used to be counted as drawn and then never emitted at all.
        assertEquals(2, kinds.count { it == "trail-fix" })
        assertEquals(0, kinds.count { it == "trail" })
        assertTrue(kinds.contains("position"))
    }

    @Test fun recordedModeKeepsTheLayersARecordingDependsOnWhateverTheConsoleLeftSwitchedOff() {
        val abandoned = DemoOverlays(trail = false, comparison = false, uncertainty = false, scenario = false, roads = false)
        assertEquals(
            DemoOverlays(trail = true, comparison = false, uncertainty = true, scenario = false, roads = true),
            abandoned.forConsoleHidden(),
        )
        // The synthetic console's own behaviour is untouched: a default switch stays default.
        assertEquals(DemoOverlays(), DemoOverlays().forConsoleHidden())
    }

    @Test fun aSwitchTheRecordedConsoleCannotReachNoLongerBlanksTheRecording() {
        val map = RecordedSessionMap()
        map.accept(fix("0", ns, longitude = 78.4500))
        map.accept(fix("1", ns + 1_000_000_000L, longitude = 78.4501, accuracy = 8.0))
        fun kinds(options: DemoOverlays) = JsonParser.parseString(MapOverlay.json(map.snapshot(), options))
            .asJsonObject["features"].asJsonArray.map { it.asJsonObject["properties"].asJsonObject["kind"].asString }
        // These switches are reachable only from the synthetic console, which recorded mode hides,
        // so leaving one off there used to blank the recording with no way to switch it back on.
        val abandoned = DemoOverlays(trail = false, comparison = false, uncertainty = false, scenario = false, roads = false)
        assertFalse(kinds(abandoned).contains("trail"))
        assertFalse(kinds(abandoned).contains("accuracy"))
        val drawn = kinds(abandoned.forConsoleHidden())
        assertEquals(1, drawn.count { it == "trail" })
        assertTrue(drawn.contains("accuracy"))
        assertTrue(drawn.contains("position"))
    }

    @Test fun aLiveStreamIsPresentedAsLiveAndNeverAsAReplay() {
        val map = RecordedSessionMap(source = Source.REAL)
        map.accept(fix("0", ns, longitude = 78.4500))
        val view = map.accept(fix("1", ns + 1_000_000_000L, longitude = 78.4501))
        assertEquals(Source.REAL, view.source)
        // The default stays a replay, so nothing starts claiming live hardware by accident.
        assertEquals(Source.REPLAY_REAL, RecordedSessionMap().snapshot().source)
        assertEquals(Source.REAL, NO_LIVE_GNSS.presentation.source)
    }

    @Test fun outagesAreRetainedAsIntervalsOverTheObservedWindow() {
        val map = RecordedSessionMap()
        map.accept(fix("0", ns))
        map.accept(fix("1", ns + 30_000_000_000L))
        val stats = map.stats()
        // The loss began when the first fix went stale, not when the next one happened to arrive.
        val outage = stats.outages.single()
        assertEquals(ns + 5_000_000_000L, outage.startNs)
        assertEquals(ns + 30_000_000_000L, outage.endNs)
        assertEquals(25_000_000_000L, outage.durationNs)
        assertEquals(ns, stats.observedStartNs)
        assertEquals(ns + 30_000_000_000L, stats.observedEndNs)
        assertEquals(30_000_000_000L, stats.spanNs)
        val mark = stats.outageMarks().single()
        assertEquals(5.0 / 30.0, mark.startFraction.toDouble(), 1e-6)
        assertEquals(1.0, mark.endFraction.toDouble(), 1e-6)
        assertEquals(25_000_000_000L, mark.durationNs)
    }

    @Test fun aTimelineNeedsAWindowAndStaysInsideIt() {
        assertTrue(RecordedSessionMap().stats().outageMarks().isEmpty())
        val single = RecordedSessionMap()
        single.accept(fix("0", ns))
        // One fix is a position, not an interval, so there is no window to draw over.
        assertTrue(single.stats().outageMarks().isEmpty())
        // Retention is bounded, but the counts stay totals over the whole stream.
        val bounded = RecordedSessionMap(maxOutages = 2)
        for (i in 0 until 6) bounded.accept(fix("$i", ns + i * 30_000_000_000L))
        assertEquals(5, bounded.stats().gaps)
        assertEquals(2, bounded.stats().outages.size)
        assertTrue(bounded.stats().outageMarks().all { it.startFraction in 0f..1f && it.endFraction in 0f..1f })
    }

    @Test fun aWindowWithoutFixesBehindItIsPlacedByTheSameRule() {
        // The scripted demo has no fixes to derive a window from, so its window is passed in whole.
        val out = outageMarks(0L, 30_000_000_000L, listOf(Outage(10_000_000_000L, 20_000_000_000L)))
        assertEquals(1, out.size)
        assertEquals(1.0 / 3.0, out.single().startFraction.toDouble(), 1e-6)
        assertEquals(2.0 / 3.0, out.single().endFraction.toDouble(), 1e-6)
        assertEquals(10_000_000_000L, out.single().durationNs)
        // A loss reaching past the end of the window is clamped into it, never drawn outside it.
        val open = outageMarks(0L, 15_000_000_000L, listOf(Outage(10_000_000_000L, 20_000_000_000L)))
        assertEquals(2.0 / 3.0, open.single().startFraction.toDouble(), 1e-6)
        assertEquals(1.0, open.single().endFraction.toDouble(), 1e-6)
        // No window to speak of: nothing is drawn, rather than a bar of unknown history.
        assertTrue(outageMarks(null, 30_000_000_000L, listOf(Outage(0L, 10L))).isEmpty())
        assertTrue(outageMarks(0L, 0L, listOf(Outage(0L, 10L))).isEmpty())
        // A loss shorter than the window can resolve is dropped rather than widened to look real.
        assertTrue(outageMarks(0L, 1_000_000_000L, listOf(Outage(500L, 500L))).isEmpty())
    }

    @Test fun aCurrentLossIsCountedFromWhenTheFixWentStale() {
        assertNull(currentOutageSeconds(null))
        assertNull(currentOutageSeconds(0.0))
        assertNull(currentOutageSeconds(Double.NaN))
        // Five seconds of age is the threshold itself: the fix is not lost yet.
        assertNull(currentOutageSeconds(5.0))
        assertEquals(7.9, currentOutageSeconds(12.9)!!, 1e-9)
    }

    @Test fun liveFixesCannotDrawTheSyntheticComparisonLayers() {
        val map = RecordedSessionMap(source = Source.REAL)
        map.accept(fix("0", ns, longitude = 78.4500))
        map.accept(fix("1", ns + 1_000_000_000L, longitude = 78.4501))
        val kinds = JsonParser.parseString(MapOverlay.json(map.snapshot(), DemoOverlays()))
            .asJsonObject["features"].asJsonArray.map { it.asJsonObject["properties"].asJsonObject["kind"].asString }
        assertEquals(1, kinds.count { it == "trail" })
        listOf("comparison", "comparison-trail", "scenario", "outage").forEach {
            assertFalse("live data must not draw $it", kinds.contains(it))
        }
        assertTrue(kinds.contains("position"))
    }
}
