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
        assertEquals(1, map.stats().drawn)
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
        assertEquals(0, map.stats().drawn)
        assertTrue(view.status.contains("coverage"))
    }

    @Test fun nonFiniteCoordinatesAreReportedAsMalformedNotPlotted() {
        val map = RecordedSessionMap()
        val view = map.accept(fix("0", ns, latitude = Double.NaN))
        assertNull(view.point)
        assertEquals(1, map.stats().malformed)
        assertEquals(0, map.stats().drawn)
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
    }

    @Test fun trailHistoryStaysBounded() {
        val map = RecordedSessionMap(maxTrail = 8)
        for (i in 0 until 200) map.accept(fix("$i", ns + i * 1_000_000_000L, longitude = 78.45 + i * 1e-5))
        assertTrue(map.snapshot().trail.size <= 8)
        assertEquals(200, map.stats().drawn)
    }
}
