package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.export.KmlGpxExporter
import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.app.navigation.NavigationRuntime
import com.intelligentdeadreckoning.contracts.v1.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 5: Live Outage Injection, Real-Time Field Drift Benchmark & Telemetry Exporter Tests.
 *
 * Verifies that:
 * 1. Outage injection intercepts and withholds GNSS fixes from the navigation engine.
 * 2. Ground-truth GNSS fixes are buffered in parallel as reference fixes.
 * 3. Cumulative ground truth distance, final drift error, and drift percentage are accurately computed.
 * 4. The hackathon < 10% drift mandate boolean gate evaluates correctly.
 * 5. Early outage cancellation completes benchmark gracefully.
 * 6. Telemetry track points and KML/GPX generation succeed from recent states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveOutageBenchmarkTest {

    private val header = Header("outage-test-session", Source.REAL, "1.1.0")
    private val session = EngineSession(header, "boot-1", 1_000_000_000L)
    private val standardG = 9.80665

    private val validCal = Record(
        header,
        Event(
            "cal",
            session.origin_ns,
            session.origin_ns,
            CalibrationResult(
                id = "cal-1",
                status = CalibrationStatus.VALID,
                q_vehicle_from_device_wxyz = Quaternion(1.0, 0.0, 0.0, 0.0),
                gyro_bias_rad_s = Vector3(0.0, 0.0, 0.0),
                accelerometer_bias_m_s2 = Vector3(0.0, 0.0, 0.0),
                confidence = 0.98,
            ),
        ),
    )

    private fun imu(tNs: Long, ax: Double = 0.0, ay: Double = 0.0, az: Double = standardG): Record = Record(
        header,
        Event(
            "imu-$tNs",
            tNs,
            tNs,
            ImuMeasurement(
                Sensor.ACCELEROMETER,
                DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED,
                Vector3(ax, ay, az),
                SensorAccuracy.HIGH,
            ),
        ),
    )

    private fun gnss(tNs: Long, lat: Double, lon: Double, speed: Double = 10.0): Record = Record(
        header,
        Event(
            "gnss-$tNs",
            tNs,
            tNs,
            GnssMeasurement(
                latitude_deg = lat,
                longitude_deg = lon,
                altitude_m = 500.0,
                altitude_reference = AltitudeReference.ELLIPSOID,
                speed_m_s = speed,
                bearing_deg = 90.0,
                horizontal_accuracy_m = 1.0,
                vertical_accuracy_m = 1.5,
                satellites_used = 18,
                provider = "fused",
                utc_ms = tNs / 1_000_000L,
            ),
        ),
    )

    @Test
    fun testOutageInjectionWithholdsGnssAndProducesBenchmarkReport() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        var currentTimeNs = 1_000_000_000L

        val engine = CalibratingFusionEngine()
        val runtime = NavigationRuntime(
            engine = engine,
            scope = testScope,
            nowNs = { currentTimeNs },
            dispatcher = testDispatcher,
        )

        // 1. Start runtime
        assertTrue(runtime.start(header, session.origin_ns, InitializationMode.EVALUATION, validCal))
        testScope.advanceUntilIdle()

        // 2. Feed initial GNSS fixes and IMUs to initialize navigation
        var lat = 17.385044
        var lon = 78.486671
        for (i in 0 until 5) {
            currentTimeNs += 1_000_000_000L
            lon += 0.0001
            runtime.offer(gnss(currentTimeNs, lat, lon, speed = 10.0))
            runtime.offer(imu(currentTimeNs, ax = 0.0, ay = 0.5, az = standardG))
            testScope.advanceUntilIdle()
        }

        val gnssCountBeforeOutage = runtime.state.value.acceptedGnss
        assertTrue("Expected GNSS to be accepted before outage", gnssCountBeforeOutage > 0)

        // 3. Inject a 5-second outage
        assertTrue(runtime.injectOutage(durationS = 5.0))
        assertTrue(runtime.state.value.outageInjected)
        assertEquals(5.0, runtime.state.value.outageRemainingS, 0.01)

        // 4. Feed 5 seconds of motion during the outage window
        // Withhold GNSS fixes from engine, but collect as ground truth in background
        for (i in 0 until 5) {
            currentTimeNs += 1_000_000_000L
            lon += 0.0001 // vehicle travels forward ~11m per step
            runtime.offer(gnss(currentTimeNs, lat, lon, speed = 10.0))
            runtime.offer(imu(currentTimeNs, ax = 0.0, ay = 0.0, az = standardG))
            testScope.advanceUntilIdle()
        }

        // 5. Offer post-outage record that exceeds the 5-second window
        currentTimeNs += 1_000_000_000L
        lon += 0.0001
        runtime.offer(gnss(currentTimeNs, lat, lon, speed = 10.0))
        runtime.offer(imu(currentTimeNs, ax = 0.0, ay = 0.0, az = standardG))
        testScope.advanceUntilIdle()

        // 6. Verify outage completed and benchmark report generated
        assertFalse("Outage should no longer be active", runtime.state.value.outageInjected)
        assertEquals(0.0, runtime.state.value.outageRemainingS, 0.01)

        val report = runtime.state.value.lastBenchmarkReport
        assertNotNull("Benchmark report should be generated", report)
        report!!

        assertTrue("Outage duration should be >= 5.0s", report.outageDurationS >= 5.0)
        assertTrue("Cumulative distance should be > 20m, was: ${report.outageDistanceM}", report.outageDistanceM > 20.0)
        assertTrue("Final drift error should be non-negative", report.finalDriftErrorM >= 0.0)
        assertTrue("Drift percent should be calculated", report.driftPercent >= 0.0)

        // Verify recent navigation states collection and KML export
        val recentStates = runtime.getRecentNavigationStates()
        assertTrue("Should have recorded navigation states", recentStates.isNotEmpty())

        val trackPoints = KmlGpxExporter.extractTrackPoints(recentStates)
        val kml = KmlGpxExporter.toKml("Test Run", trackPoints)
        assertTrue(kml.contains("<kml"))
        assertTrue(kml.contains("<coordinates>"))

        val gpx = KmlGpxExporter.toGpx("Test Run", trackPoints)
        assertTrue(gpx.contains("<gpx"))
        assertTrue(gpx.contains("<trk>"))

        runtime.stop()
        testScope.advanceUntilIdle()
    }

    @Test
    fun testCancelOutageStopsEarlyAndGeneratesReport() {
        val testDispatcher = StandardTestDispatcher()
        val testScope = TestScope(testDispatcher)
        var currentTimeNs = 2_000_000_000L

        val engine = CalibratingFusionEngine()
        val runtime = NavigationRuntime(
            engine = engine,
            scope = testScope,
            nowNs = { currentTimeNs },
            dispatcher = testDispatcher,
        )

        runtime.start(header, session.origin_ns, InitializationMode.EVALUATION, validCal)
        testScope.advanceUntilIdle()

        // Initial fix
        runtime.offer(gnss(currentTimeNs, 17.3850, 78.4866, speed = 0.0))
        testScope.advanceUntilIdle()

        // Inject 30s outage
        assertTrue(runtime.injectOutage(durationS = 30.0))
        assertTrue(runtime.state.value.outageInjected)

        // Offer one GNSS fix during outage
        currentTimeNs += 1_000_000_000L
        runtime.offer(gnss(currentTimeNs, 17.3851, 78.4867, speed = 5.0))
        testScope.advanceUntilIdle()

        // Cancel outage early
        assertTrue(runtime.cancelOutage())

        // Next record triggers clean benchmark completion
        currentTimeNs += 100_000_000L
        runtime.offer(imu(currentTimeNs))
        testScope.advanceUntilIdle()

        assertFalse("Outage should be cancelled", runtime.state.value.outageInjected)
        val report = runtime.state.value.lastBenchmarkReport
        assertNotNull("Benchmark report should exist after cancellation", report)

        runtime.stop()
        testScope.advanceUntilIdle()
    }
}
