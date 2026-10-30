package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.GeodeticAnchor
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A vehicle-frame drive east at 12 m/s with one left turn (0.2 rad/s for 8 s) and a 20 s GNSS outage, replayed without
 * real-time pacing (the same trajectory as tools/generate_fog_200hz.py, noise-free). With no initial heading supplied
 * the engine used to start facing north whatever the first fix's course said; the turn then sent it 300-500 m off.
 */
class HeadingFromCourseTest {
    private val header = Header("heading-init", Source.SIMULATION, "1.1.0")
    private val g = 9.80665
    private fun rec(tNs: Long, id: String, d: Payload) = Record(header, Event(id, tNs, tNs, d))

    private class Outcome(val headingAt21S: Double?, val worstOutageErrorM: Double, val finalErrorM: Double)

    private fun run(config: FusionConfig, firstFixSpeed: Double = 12.0, initialHeadingRad: Double? = null): Outcome {
        val engine = FusionNavigationEngine(config = config, publicationIntervalNs = 100_000_000L, initialHeadingRad = initialHeadingRad)
        engine.initialize(EngineSession(header, "s", 0L),
            rec(0L, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)), InitializationMode.EVALUATION)
        var heading = 0.0
        var east = 0.0
        var north = 0.0
        val mLat = 110660.0
        val mLon = 106200.0
        var worst = 0.0
        var last = 0.0
        var headingAt21: Double? = null
        for (i in 0 until 70 * 200) {
            val t = i / 200.0
            val w = if (t >= 20.0 && t < 28.0) 0.2 else 0.0
            heading += w / 200.0
            east += 12.0 * cos(heading) / 200.0
            north += 12.0 * sin(heading) / 200.0
            val tNs = (t * 1e9).toLong()
            engine.acceptImu(rec(tNs, "a$i", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, Vector3(0.0, 12.0 * w, g), SensorAccuracy.UNKNOWN)))
            engine.acceptImu(rec(tNs, "g$i", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, Vector3(0.0, 0.0, w), SensorAccuracy.UNKNOWN)))
            if (i % 200 == 0 && !(t >= 30.0 && t < 50.0)) {
                val course = ((90.0 - Math.toDegrees(heading)) % 360.0 + 360.0) % 360.0
                engine.acceptGnss(rec(tNs, "n$i", GnssMeasurement(17.435 + north / mLat, 78.445 + east / mLon, null, null,
                    if (i == 0) firstFixSpeed else 12.0, course, 2.5, null, 12L, "gps", null)))
            }
            for (r in engine.drain()) {
                val s = r.event.data as? NavigationState ?: continue
                val p = s.position_enu_m ?: continue
                val o = s.origin_wgs84_deg_m ?: continue
                val geo = Geodesy.geodeticFromEnu(GeodeticAnchor(o.latitude_deg, o.longitude_deg, o.altitude_m, true), Vector3(p.x, p.y, 0.0))
                val dn = Math.toRadians(geo.latitudeDeg - (17.435 + north / mLat)) * 6_371_000.0
                val de = Math.toRadians(geo.longitudeDeg - (78.445 + east / mLon)) * 6_371_000.0 * cos(Math.toRadians(17.435))
                last = hypot(dn, de)
                if (t in 30.0..50.0) worst = maxOf(worst, last)
                if (t >= 21.0 && headingAt21 == null) headingAt21 = s.heading_deg
            }
        }
        return Outcome(headingAt21, worst, last)
    }

    @Test
    fun withoutTheOptionAndWithoutAHeadingTheEngineStartsFacingNorthAndTheTurnSendsItOff() {
        val o = run(FusionConfig())
        println("no heading, no option: heading at 21 s ${o.headingAt21S}, worst outage error %.0f m, final %.0f m".format(o.worstOutageErrorM, o.finalErrorM))
        assertTrue("expected the documented failure, got ${o.worstOutageErrorM} m", o.worstOutageErrorM > 100.0)
    }

    @Test
    fun theFirstFixCourseGivesTheInitialHeadingAndTheTurnIsSurvived() {
        val o = run(FusionConfig(initialHeadingFromGnssCourse = true))
        println("heading from course: heading at 21 s ${o.headingAt21S}, worst outage error %.1f m, final %.1f m".format(o.worstOutageErrorM, o.finalErrorM))
        assertTrue("heading at 21 s ${o.headingAt21S}", o.headingAt21S != null && Math.abs(o.headingAt21S!! - 78.3) < 5.0)
        assertTrue("worst outage error ${o.worstOutageErrorM}", o.worstOutageErrorM < 60.0)
        assertTrue("final error ${o.finalErrorM}", o.finalErrorM < 30.0)
    }

    @Test
    fun aSuppliedHeadingWinsOverTheCourseAndASlowFirstFixIsNotTrusted() {
        val supplied = run(FusionConfig(initialHeadingFromGnssCourse = true), initialHeadingRad = Math.PI / 2.0)
        assertTrue("supplied heading run: ${supplied.worstOutageErrorM}", supplied.worstOutageErrorM < 60.0)
        val slow = run(FusionConfig(initialHeadingFromGnssCourse = true), firstFixSpeed = 0.5)
        println("first fix at 0.5 m/s: worst outage error %.0f m".format(slow.worstOutageErrorM))
        assertEquals("the option is off by default", false, FusionConfig().initialHeadingFromGnssCourse)
    }
}
