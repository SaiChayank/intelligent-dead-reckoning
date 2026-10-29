package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.calibration.CalibrationStore
import com.intelligentdeadreckoning.app.calibration.FileCalibrationStore
import com.intelligentdeadreckoning.app.calibration.SavedCalibration
import com.intelligentdeadreckoning.app.calibration.SavedCalibrationPolicy
import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin

class SavedCalibrationTest {
    private val device = "Pixel_7"
    private val nowMs = 1_800_000_000_000L
    private val day = 24L * 3600 * 1000
    private val identity = Quaternion(1.0, 0.0, 0.0, 0.0)
    private fun yaw(deg: Double) = Math.toRadians(deg / 2).let { Quaternion(cos(it), 0.0, 0.0, sin(it)) }

    private fun saved(q: Quaternion = identity, at: Long = nowMs - day, key: String = device, confidence: Double = 0.9,
                      bias: Vector3 = Vector3(0.001, 0.0, -0.002)) =
        SavedCalibration(key, at, q, bias, null, confidence)

    private class MemoryStore(var value: SavedCalibration? = null) : CalibrationStore {
        var saves = 0
        override fun load(deviceKey: String) = value?.takeIf { it.deviceKey == deviceKey }
        override fun save(calibration: SavedCalibration) { value = calibration; saves++ }
    }

    private val header = Header("cold", Source.REAL, "1.1.0")
    private fun record(t: Long, data: Payload) = Record(header, Event(t.toString(), t, t, data))
    private fun pending() = record(0, CalibrationResult("pending", CalibrationStatus.PENDING, null, null, null, null))
    private fun live(id: String, q: Quaternion, status: CalibrationStatus = CalibrationStatus.VALID, t: Long = 2_000_000_000) =
        record(t, CalibrationResult(id, status, q, Vector3(0.0, 0.0, 0.0), null, 0.8))
    private fun fix(seconds: Long, bearing: Double? = 90.0, accuracy: Double = 4.0, speed: Double = 10.0,
                    source: Source = Source.REAL, north: Double = 0.0) =
        Record(Header("cold", source, "1.1.0"), Event(seconds.toString(), seconds * 1_000_000_000, seconds * 1_000_000_000,
            GnssMeasurement(17.435 + north / 111194.9266, 78.445, null, null, speed, bearing, accuracy, null, 8, "gps", null)))

    private class Stub : NavigationEngine {
        val out = mutableListOf<Record>()
        var initial: Record? = null
        var resets = 0
        override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) { initial = calibration }
        override fun acceptImu(measurement: Record) {}
        override fun acceptGnss(measurement: Record) {}
        override fun drain(): Sequence<Record> = out.toList().also { out.clear() }.asSequence()
        override fun stop() {}
        override fun reset() { resets++; out.clear() }
    }

    private class Rig(store: CalibrationStore?, nowMs: Long, source: Source = Source.REAL) {
        val calibrator = Stub()
        val fusion = Stub()
        var heading: Double? = null
        val sessionHeader = Header("cold", source, "1.1.0")
        val engine = CalibratingFusionEngine(
            calibrator = calibrator, store = store, deviceKey = "Pixel_7", clockEpochMs = { nowMs },
            fusionFactory = { heading = it; fusion },
        ).also {
            it.initialize(EngineSession(sessionHeader, "boot", 0L),
                Record(sessionHeader, Event("0", 0, 0, CalibrationResult("pending", CalibrationStatus.PENDING, null, null, null, null))),
                InitializationMode.DEPLOYABLE)
        }
    }

    // ---- store and policy ---------------------------------------------------------------------------------

    @Test fun fileStoreRoundTripsAndIsolatesDevices() {
        val dir = Files.createTempDirectory("calstore").toFile()
        val store = FileCalibrationStore(dir)
        assertNull(store.load(device))
        val c = saved(yaw(30.0))
        store.save(c)
        assertEquals(c, store.load(device))
        assertNull(store.load("Other_Phone"))
        store.save(c.copy(savedAtEpochMs = c.savedAtEpochMs + 1))
        assertEquals(c.savedAtEpochMs + 1, store.load(device)!!.savedAtEpochMs)
    }

    @Test fun malformedOrTruncatedFileIsTreatedAsNothingSaved() {
        val dir = Files.createTempDirectory("calstore").toFile()
        val store = FileCalibrationStore(dir)
        store.save(saved())
        val file = dir.listFiles()!!.single { it.name.endsWith(".json") }
        file.writeText("{\"device_key\": \"Pixel_7\", \"saved_at")
        assertNull(store.load(device))
        file.writeText("{\"device_key\":\"Pixel_7\",\"saved_at_epoch_ms\":1,\"q_vehicle_from_device_wxyz\":[1,0,0],\"gyro_bias_rad_s\":[0,0,0],\"confidence\":0.9}")
        assertNull(store.load(device))
    }

    @Test fun policyRejectsStaleForeignAndImplausibleCalibrations() {
        fun why(c: SavedCalibration, key: String = device) = SavedCalibrationPolicy.rejection(c, key, nowMs)
        assertNull(why(saved()))
        assertNotNull(why(saved(), "Other"))
        assertNotNull(why(saved(at = nowMs - 31 * day)))
        assertNotNull(why(saved(at = nowMs + day)))
        assertNotNull(why(saved(q = Quaternion(2.0, 0.0, 0.0, 0.0))))
        assertNotNull(why(saved(confidence = 0.3)))
        assertNotNull(why(saved(bias = Vector3(0.5, 0.0, 0.0))))
        assertNotNull(why(saved(bias = Vector3(Double.NaN, 0.0, 0.0))))
    }

    // ---- engine ------------------------------------------------------------------------------------------

    @Test fun savedMountStartsFusionAtTheFirstAccurateFixWithNoLiveCalibration() {
        val rig = Rig(MemoryStore(saved()), nowMs)
        assertNull(rig.fusion.initial)
        rig.engine.acceptGnss(fix(1, bearing = 90.0))
        val adopted = rig.fusion.initial!!.event.data as CalibrationResult
        assertEquals("saved-${nowMs - day}", adopted.id)
        assertEquals(CalibrationStatus.VALID, adopted.status)
        assertEquals(Math.toRadians(90.0), rig.heading!!, 1e-9)
    }

    @Test fun withoutASavedMountOneFixDoesNotStartFusion() {
        for (store in listOf<CalibrationStore?>(null, MemoryStore(null))) {
            val rig = Rig(store, nowMs)
            rig.engine.acceptGnss(fix(1))
            assertNull(rig.fusion.initial)
        }
    }

    @Test fun staleOrForeignSavedMountIsNeverUsed() {
        for (c in listOf(saved(at = nowMs - 40 * day), saved(key = "Other"), saved(confidence = 0.1))) {
            val rig = Rig(MemoryStore(c), nowMs)
            rig.engine.acceptGnss(fix(1))
            assertNull(rig.fusion.initial)
        }
    }

    @Test fun poorOrCourselessFixesDoNotStartFusionFromASavedMount() {
        for (f in listOf(fix(1, bearing = null), fix(1, accuracy = 40.0), fix(1, speed = 1.0))) {
            val rig = Rig(MemoryStore(saved()), nowMs)
            rig.engine.acceptGnss(f)
            assertNull(rig.fusion.initial)
        }
    }

    @Test fun replayedRecordingsNeverUseOrOverwriteTheDeviceMount() {
        val store = MemoryStore(saved())
        val rig = Rig(store, nowMs, Source.REPLAY_REAL)
        rig.engine.acceptGnss(fix(1, source = Source.REPLAY_REAL))
        assertNull(rig.fusion.initial)
        rig.calibrator.out += live("live-1", yaw(10.0))
        rig.engine.acceptGnss(fix(2, source = Source.REPLAY_REAL))
        assertEquals(0, store.saves)
    }

    @Test fun pendingOrAgreeingLiveCalibrationLeavesTheRunningFilterAlone() {
        val rig = Rig(MemoryStore(saved(yaw(5.0))), nowMs)
        rig.engine.acceptGnss(fix(1))
        val before = rig.fusion.resets
        rig.calibrator.out += pending()
        rig.engine.acceptGnss(fix(2))
        rig.calibrator.out += live("live-1", yaw(12.0))           // 7 degrees from the saved mount
        rig.engine.acceptGnss(fix(3))
        assertEquals(before, rig.fusion.resets)
        assertTrue(rig.engine.drain().none { (it.event.data as? CalibrationResult)?.id == "live-1" })
    }

    @Test fun contradictoryLiveCalibrationDropsTheSavedMountAndAdoptsTheLiveOne() {
        val rig = Rig(MemoryStore(saved(identity)), nowMs)
        rig.engine.acceptGnss(fix(1))
        assertNotNull(rig.fusion.initial)
        val before = rig.fusion.resets
        rig.calibrator.out += live("live-1", yaw(90.0))          // phone was remounted
        rig.engine.acceptGnss(fix(2))
        assertEquals(before + 1, rig.fusion.resets)
        rig.fusion.initial = null
        rig.engine.acceptGnss(fix(3))                             // the saved mount is not reused
        assertNull(rig.fusion.initial)
    }

    @Test fun expiredLiveCalibrationEndsTheSavedMount() {
        val rig = Rig(MemoryStore(saved()), nowMs)
        rig.engine.acceptGnss(fix(1))
        val before = rig.fusion.resets
        rig.calibrator.out += live("live-1", identity, CalibrationStatus.EXPIRED)
        rig.engine.acceptGnss(fix(2))
        assertEquals(before + 1, rig.fusion.resets)
    }

    @Test fun everyNewValidLiveMountIsSavedOnce() {
        val store = MemoryStore(null)
        val rig = Rig(store, nowMs)
        rig.calibrator.out += live("live-1", yaw(10.0))
        rig.engine.acceptGnss(fix(1)); rig.engine.acceptGnss(fix(2))
        assertEquals(1, store.saves)
        assertEquals(yaw(10.0), store.value!!.qVehicleFromDevice)
        assertEquals(device, store.value!!.deviceKey)
        rig.calibrator.out += live("live-2", yaw(11.0))
        rig.engine.acceptGnss(fix(3))
        assertEquals(2, store.saves)
    }

    @Test fun aFailingStoreNeverStopsNavigation() {
        val broken = object : CalibrationStore {
            override fun load(deviceKey: String): SavedCalibration? = null
            override fun save(calibration: SavedCalibration) = throw java.io.IOException("disk full")
        }
        val rig = Rig(broken, nowMs)
        rig.calibrator.out += live("live-1", identity)
        rig.engine.acceptGnss(fix(1, bearing = 0.0))
        rig.engine.acceptGnss(fix(3, bearing = 0.0, north = 20.0))
        assertNotNull(rig.fusion.initial)
    }
}
