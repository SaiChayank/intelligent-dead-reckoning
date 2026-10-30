package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.matching.RoadGraph
import com.intelligentdeadreckoning.app.navigation.AppFusion
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.hypot

/**
 * The map-matching switch against the REAL fusion engine (the toggle test uses a stub). SYNTHETIC: a constant-speed drive north
 * along a fixture road. This checks plumbing and safety (off equals the shipped path exactly, on never fails, a running engine
 * ignores later switch changes). It makes no accuracy claim; that needs a recorded drive.
 */
class MapMatchingSwitchEngineTest {
    private val header = Header("switch-engine", Source.SIMULATION, "1.1.0")
    private val g = 9.80665
    private fun rec(tNs: Long, id: String, d: Payload) = Record(header, Event(id, tNs, tNs, d))

    private val graph: RoadGraph by lazy {
        val dir = Files.createTempDirectory("switch-engine").toFile()
        dir.deleteOnExit()
        val file = File(dir, RoadGraph.GRAPH_FILE)
        RoadGraphFixtures.writeGraph(file, RoadGraphFixtures.graphJson(listOf(RoadGraphFixtures.northSouth(1L, 0.0))))
        RoadGraph.load(file)
    }

    private class Run(val statuses: List<NavigationStatus>, val finalEnuM: Pair<Double, Double>, val published: Int)

    /** 70 s north at 10 m/s with a fix per second except during an outage from 30 s to 55 s. */
    private fun drive(engine: NavigationEngine): Run {
        engine.initialize(EngineSession(header, "s", 0L),
            rec(0L, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)), InitializationMode.EVALUATION)
        val statuses = ArrayList<NavigationStatus>()
        var published = 0
        var last = Pair(0.0, 0.0)
        for (i in 0 until 70 * 100) {
            val t = i / 100.0
            val tNs = Math.round(t * 1e9)
            engine.acceptImu(rec(tNs, "a$i", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, Vector3(0.0, 0.0, g), SensorAccuracy.HIGH)))
            engine.acceptImu(rec(tNs, "g$i", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, Vector3(0.0, 0.0, 0.0), SensorAccuracy.HIGH)))
            if (i % 100 == 0 && !(t >= 30.0 && t < 55.0)) {
                engine.acceptGnss(rec(tNs, "n$i", GnssMeasurement(RoadGraphFixtures.lat(10.0 * t), RoadGraphFixtures.lon(0.0), 500.0,
                    AltitudeReference.ELLIPSOID, 10.0, 0.0, 3.0, 6.0, 12L, "gps", null)))
            }
            for (r in engine.drain()) {
                val s = r.event.data as? NavigationState ?: continue
                statuses += s.status
                published++
                s.position_enu_m?.let { last = Pair(it.x, it.y) }
            }
        }
        return Run(statuses, last, published)
    }

    private fun factory(enabled: () -> Boolean, graph: () -> RoadGraph?) = AppFusion.factory(enabled, graph)

    @Test
    fun withTheSwitchOffTheAppEngineIsExactlyTheShippedEngineWithoutAGraph() {
        val viaFactory = drive(factory({ false }, { graph })(0.0))
        val shipped = drive(FusionNavigationEngine(initialHeadingRad = 0.0, publicationIntervalNs = AppFusion.PUBLICATION_INTERVAL_NS,
            config = AppFusion.config(experimentalMapMatching = false), roadGraph = null))
        assertEquals(shipped.published, viaFactory.published)
        assertEquals(shipped.finalEnuM.first, viaFactory.finalEnuM.first, 0.0)
        assertEquals(shipped.finalEnuM.second, viaFactory.finalEnuM.second, 0.0)
        assertEquals(shipped.statuses, viaFactory.statuses)
    }

    @Test
    fun withTheSwitchOnTheEngineRunsThroughAnOutageWithoutFailingAndStaysNearTheRoad() {
        val on = drive(factory({ true }, { graph })(0.0))
        assertFalse("no failed status", on.statuses.any { it == NavigationStatus.FAILED })
        assertTrue("published at 10 Hz for 70 s, got ${on.published}", on.published >= 600)
        val truthNorth = 700.0
        val miss = hypot(on.finalEnuM.first, on.finalEnuM.second - truthNorth)
        assertTrue("final position within 30 m of the road end (synthetic): $miss m", miss < 30.0)
    }

    @Test
    fun aRunningEngineIgnoresLaterSwitchChanges() {
        var enabled = true
        val make = factory({ enabled }, { graph })
        val engine = make(0.0)
        enabled = false
        val afterFlip = drive(engine)
        enabled = true
        val reference = drive(factory({ true }, { graph })(0.0))
        assertEquals("the engine built while on keeps behaving as on", reference.finalEnuM.first, afterFlip.finalEnuM.first, 0.0)
        assertEquals(reference.finalEnuM.second, afterFlip.finalEnuM.second, 0.0)
    }
}
