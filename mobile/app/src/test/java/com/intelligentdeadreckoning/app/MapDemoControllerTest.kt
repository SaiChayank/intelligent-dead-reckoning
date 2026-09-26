package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.map.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class MapDemoControllerTest {
    private val origin = 9_007_199_254_740_993L
    private fun started() = MapDemoController().apply { start(origin) }
    @Test fun noPositionBeforeExplicitStartAndRepeatedStartRejected() {
        val d = MapDemoController(); assertNull(d.state.presentation.point)
        d.start(origin); assertNotNull(d.state.presentation.point)
        assertThrows(IllegalStateException::class.java) { d.start(origin) }
    }
    @Test fun largeClockOriginAndHalfSpeedStayPrecise() {
        val d = started(); d.rate(0.5,origin); d.tick(origin+2_000_000_000)
        assertEquals(1000,d.state.elapsedMs); assertEquals(10.0,d.state.distanceM,0.0)
    }
    @Test fun pauseExcludesWallTimeAndResumeKeepsPosition() {
        val d = started(); d.pause(origin+1_000_000_000)
        val paused = d.state; d.tick(origin+10_000_000_000)
        assertEquals(paused,d.state)
        d.resume(origin+20_000_000_000); d.tick(origin+21_000_000_000)
        assertEquals(2000,d.state.elapsedMs)
    }
    @Test fun rateChangeDoesNotRestartOrReinterpretElapsedTime() {
        val d = started(); d.rate(2.0,origin+1_000_000_000); d.tick(origin+2_000_000_000)
        assertEquals(3000,d.state.elapsedMs)
    }
    @Test fun delayedTickSplitsAutomaticOutageExactly() {
        val d = started(); d.tick(origin+25_000_000_000)
        assertEquals(10_000,d.state.outageMs); assertEquals(100.0,d.state.outageDistanceM,0.0)
        assertEquals(0,d.state.currentOutageMs); assertEquals(250.0,d.state.distanceM,0.0)
        assertTrue(d.state.label.contains("recovery"))
    }
    @Test fun manualBlackoutAndRecoveryCountOnlyDeniedVirtualTime() {
        val d = started(); d.signal(DemoSignal.BLACKOUT,origin+1_000_000_000)
        d.signal(DemoSignal.AVAILABLE,origin+3_000_000_000)
        assertEquals(2000,d.state.outageMs); assertEquals(20.0,d.state.outageDistanceM,0.0)
        assertEquals(0,d.state.currentOutageMs); assertTrue(d.state.label.contains("recovery"))
    }
    @Test fun pausedOverrideChangesDisplayNotTimeOrCounters() {
        val d = started(); d.pause(origin+1_000_000_000)
        d.signal(DemoSignal.BLACKOUT,origin+10_000_000_000)
        assertEquals(1000,d.state.elapsedMs); assertEquals(0,d.state.outageMs)
        assertTrue(d.state.label.contains("DR")); assertEquals(35.0,d.state.presentation.accuracy95Metres!!,0.0)
    }
    @Test fun scriptedComparisonDivergesThenConvergesContinuously() {
        val d = started(); d.tick(origin+15_000_000_000)
        assertNotEquals(d.state.presentation.point,d.state.presentation.comparisonPoint)
        d.tick(origin+20_000_000_000)
        val before = d.state.presentation.comparisonPoint!!
        d.tick(origin+20_001_000_000)
        assertTrue(kotlin.math.abs(before.latitude-d.state.presentation.comparisonPoint!!.latitude)<1e-6)
        d.tick(origin+26_000_000_000)
        assertEquals(d.state.presentation.point,d.state.presentation.comparisonPoint)
    }
    @Test fun resetClearsDataAndSignalButRetainsChosenScenarioAndRate() {
        val d = MapDemoController(); d.select(DemoScenario.STRAIGHT); d.rate(2.0,origin)
        d.start(origin); d.signal(DemoSignal.BLACKOUT,origin); d.tick(origin+1_000_000_000); d.reset()
        assertEquals(DemoScenario.STRAIGHT,d.state.scenario); assertEquals(2.0,d.state.rate,0.0)
        assertEquals(DemoSignal.AUTOMATIC,d.state.signal); assertNull(d.state.presentation.point)
        assertEquals(0,d.state.outageMs); assertEquals(0,d.state.elapsedMs)
    }
    @Test fun stopClearsOverlaysAndDoesNotAutomaticallyResume() {
        val d = started(); d.tick(origin+1_000_000_000); d.stop(); d.tick(origin+20_000_000_000)
        assertEquals(DemoPlayback.STOPPED,d.state.playback); assertNull(d.state.presentation.point)
        assertTrue(d.state.presentation.comparisonTrail.isEmpty())
    }
    @Test fun completionClampsAndDoesNotRestart() {
        val d = started(); d.tick(origin+100_000_000_000)
        assertEquals(30_000,d.state.elapsedMs); assertEquals(DemoPlayback.COMPLETED,d.state.playback)
        val complete = d.state; d.tick(origin+200_000_000_000); assertEquals(complete,d.state)
    }
    @Test fun lateSignalClickCannotRelabelACompletedRun() {
        val d = started(); d.signal(DemoSignal.BLACKOUT,origin+31_000_000_000)
        assertEquals(DemoPlayback.COMPLETED,d.state.playback)
        assertTrue(d.state.label.contains("completed"))
        assertEquals(DemoSignal.AUTOMATIC,d.state.signal)
    }
    @Test fun invalidControlsAreRejected() {
        val d = started()
        assertThrows(IllegalArgumentException::class.java) { d.rate(Double.NaN,origin) }
        assertThrows(IllegalArgumentException::class.java) { d.tick(origin-1) }
        assertThrows(IllegalStateException::class.java) { d.select(DemoScenario.STRAIGHT) }
    }
    @Test fun pathsAreDistinctValidAndStayWithinCoverage() {
        val endpoints = DemoScenario.entries.map { scenario ->
            for(ms in 0L..30_000L step 500) {
                val record = SyntheticMapDemo.record(ms,0,scenario)
                assertEquals(record,Codec.decodeJson(Codec.encodeJson(record)))
                val nav = record.event.data as NavigationState
                val point = MapCoordinates.fromEnu(nav.origin_wgs84_deg_m!!,nav.position_enu_m!!)
                assertTrue(HyderabadMap.contains(point.latitude,point.longitude))
            }
            SyntheticMapDemo.record(30_000,0,scenario)
        }
        assertEquals(3,endpoints.toSet().size)
    }
    @Test fun boundedBuffersAndDeterministicSnapshots() {
        val a = started(); val b = started()
        for(i in 1L..29_999L step 20) { a.tick(origin+i*1_000_000); b.tick(origin+i*1_000_000) }
        assertEquals(a.state,b.state)
        assertTrue(a.state.presentation.trail.size<=512); assertTrue(a.state.presentation.comparisonTrail.size<=512)
        assertEquals(61,a.state.presentation.scenarioPath.size)
    }
    @Test fun overlayTogglesRemoveOnlyTheirOwnFeatures() {
        val d = started(); d.tick(origin+11_000_000_000); d.tick(origin+11_050_000_000)
        fun kinds(options: DemoOverlays) = JsonParser.parseString(MapOverlay.json(d.state.presentation,options))
            .asJsonObject["features"].asJsonArray.map { it.asJsonObject["properties"].asJsonObject["kind"].asString }.toSet()
        assertTrue(kinds(DemoOverlays()).containsAll(listOf("comparison","scenario","outage","accuracy","trail")))
        assertEquals(setOf("position","heading"),kinds(DemoOverlays(trail=false,comparison=false,uncertainty=false,scenario=false)))
        val real = d.state.presentation.copy(source=Source.REAL)
        assertFalse(MapOverlay.json(real).contains("comparison-trail"))
    }
    @Test fun repeatedRunsNeverRetainPreviousSessionGeometryOrCounters() {
        val d = MapDemoController()
        repeat(30) { run ->
            d.select(DemoScenario.entries[run % DemoScenario.entries.size])
            val start = origin + run * 60_000_000_000L
            d.start(start)
            assertEquals(0L,d.state.outageMs)
            assertEquals(1,d.state.presentation.trail.size)
            assertEquals(1,d.state.presentation.comparisonTrail.size)
            for (ms in 500L..30_000L step 500) d.tick(start + ms * 1_000_000)
            assertEquals(DemoPlayback.COMPLETED,d.state.playback)
            assertEquals(300.0,d.state.distanceM,0.0)
            assertEquals(10_000L,d.state.outageMs)
            assertTrue(d.state.presentation.trail.size <= 61)
            assertTrue(d.state.presentation.comparisonTrail.size <= 61)
            d.stop()
            assertNull(d.state.presentation.point)
            assertTrue(d.state.presentation.scenarioPath.isEmpty())
        }
    }
    @Test fun hugeClockGapCompletesWithoutOverflowAtEveryPlaybackRate() {
        for (rate in listOf(0.5,1.0,2.0)) {
            val d = MapDemoController()
            d.rate(rate,0); d.start(0); d.tick(Long.MAX_VALUE)
            assertEquals(DemoPlayback.COMPLETED,d.state.playback)
            assertEquals(30_000L,d.state.elapsedMs)
            assertEquals(300.0,d.state.distanceM,0.0)
            assertEquals(10_000L,d.state.outageMs)
            assertEquals(d.state.presentation.point,d.state.presentation.comparisonPoint)
        }
    }
    @Test fun completedCountersAreIndependentOfCadenceAndPlaybackSpeed() {
        for (rate in listOf(0.5,1.0,2.0)) for (stepMs in listOf(17L,250L,7000L)) {
            val d = started(); d.rate(rate,origin)
            var elapsed = 0L
            while (d.state.playback == DemoPlayback.RUNNING) {
                elapsed += stepMs
                d.tick(origin + elapsed * 1_000_000)
            }
            assertEquals(30_000L,d.state.elapsedMs)
            assertEquals(10_000L,d.state.outageMs)
            assertEquals(100.0,d.state.outageDistanceM,0.0)
            assertEquals(0L,d.state.currentOutageMs)
            assertTrue(d.state.presentation.trail.size <= 512)
            assertTrue(d.state.presentation.comparisonTrail.size <= 512)
        }
    }
}
