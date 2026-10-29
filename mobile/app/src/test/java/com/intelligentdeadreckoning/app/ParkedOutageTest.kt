package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.fusion.FusionConfig
import com.intelligentdeadreckoning.app.fusion.FusionNavigationEngine
import com.intelligentdeadreckoning.app.fusion.Geodesy
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/**
 * The NHC stand-down after 3 s without GNSS exists so a parked vehicle is not constrained along a corrupted
 * attitude. With [FusionConfig.nhcActiveDuringOutage] on, a vehicle that is simply parked through a 90 s outage must
 * still stay put: the ZUPT and the speed floor own that case.
 */
class ParkedOutageTest {
    private val header = Header("parked", Source.SIMULATION, "1.1.0")
    private val originNs = 1_000_000_000L
    private val anchor = ScriptedDrive.ANCHOR
    private fun record(tNs: Long, id: String, data: Payload) = Record(header, Event(id, tNs, tNs, data))

    /** Largest distance from the start reached during a parked outage, metres. */
    private fun maxDrift(config: FusionConfig): Double {
        val engine = FusionNavigationEngine(config = config)
        engine.initialize(
            EngineSession(header, "parked-session", originNs),
            record(originNs, "cal", CalibrationResult("cal", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
                Vector3(0.0, 0.0, 0.0), Vector3(0.0, 0.0, 0.0), 0.9)),
            InitializationMode.EVALUATION,
        )
        val random = Random(11)
        var worst = 0.0
        val total = 110.0
        for (step in 1..(total / 0.01).toInt()) {
            val t = step * 0.01
            val tNs = originNs + (t * 1e9).toLong()
            val accel = Vector3(random.nextGaussian() * 0.03, random.nextGaussian() * 0.03, 9.80665 + random.nextGaussian() * 0.03)
            val gyro = Vector3(random.nextGaussian() * 0.002, random.nextGaussian() * 0.002, random.nextGaussian() * 0.002)
            engine.acceptImu(record(tNs, "a$tNs", ImuMeasurement(Sensor.ACCELEROMETER, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED, accel, SensorAccuracy.HIGH)))
            engine.acceptImu(record(tNs, "g$tNs", ImuMeasurement(Sensor.GYROSCOPE, DeviceFrame.ANDROID_DEVICE,
                ImuUnit.RADIANS_PER_SECOND, gyro, SensorAccuracy.HIGH)))
            if (step % 100 == 0 && t <= 20.0) {                          // GNSS for 20 s, then a 90 s outage
                val g = Geodesy.geodeticFromEnu(anchor, Vector3(random.nextGaussian() * 1.5, random.nextGaussian() * 1.5, 0.0))
                engine.acceptGnss(record(tNs, "n$tNs", GnssMeasurement(g.latitudeDeg, g.longitudeDeg,
                    anchor.altitudeM, AltitudeReference.ELLIPSOID, 0.0, null, 3.0, 6.0, 20L, "gps", null)))
            }
            for (rec in engine.drain()) {
                val nav = rec.event.data as? NavigationState ?: continue
                val pos = nav.position_enu_m ?: continue
                if (t > 20.0) worst = maxOf(worst, hypot(pos.x, pos.y))
            }
        }
        return worst
    }

    @Test
    fun aParkedVehicleStaysPutThroughAnOutageWithNhcActive() {
        val standard = maxDrift(FusionConfig())
        val withNhc = maxDrift(FusionConfig(nhcActiveDuringOutage = true))
        println("parked 90 s outage, max distance from start: standard %.2f m, NHC active %.2f m".format(standard, withNhc))
        assertTrue("NHC active let a parked vehicle drift $withNhc m (standard $standard m)", withNhc <= standard + 2.0)
        assertTrue("parked drift $withNhc m", withNhc < 15.0)
    }
}
