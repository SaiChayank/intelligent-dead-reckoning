package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.navigation.CalibratingFusionEngine
import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.*
import org.junit.Test

class CalibratingFusionEngineTest {
    private val header = Header("handoff", Source.REAL, "1.1.0")
    private fun calibration(status: CalibrationStatus, time: Long = 0) = Record(header,
        Event("0", time, time, CalibrationResult("mount", status,
            Quaternion(1.0,0.0,0.0,0.0), Vector3(0.0,0.0,0.0), null, 0.8)))
    private fun fix(seconds: Long, north: Double, bearing: Double? = 0.0, accuracy: Double? = 4.0) =
        Record(header, Event(seconds.toString(),seconds*1_000_000_000,seconds*1_000_000_000,
            GnssMeasurement(17.435+north/111194.9266,78.445,null,null,10.0,bearing,accuracy,null,8,"gps",null)))
    private class Stub : NavigationEngine {
        val out = mutableListOf<Record>()
        val received = mutableListOf<Record>()
        var initial: Record? = null
        var resets = 0
        override fun initialize(session: EngineSession, calibration: Record, mode: InitializationMode) { initial = calibration }
        override fun acceptImu(measurement: Record) { received += measurement }
        override fun acceptGnss(measurement: Record) { received += measurement }
        override fun drain(): Sequence<Record> = out.toList().also { out.clear() }.asSequence()
        override fun stop() {}
        override fun reset() { resets++; out.clear() }
    }
    @Test fun handoffWaitsForCalibrationAndCausalCourseAndDoesNotReplaySamples() {
        val cal = Stub(); val fuse = Stub(); var heading: Double? = null
        val engine = CalibratingFusionEngine(cal) { heading = it; fuse }
        engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
        engine.acceptGnss(fix(1,0.0)); engine.acceptGnss(fix(2,20.0))
        assertNull(fuse.initial)
        cal.out += calibration(CalibrationStatus.VALID,2_000_000_000)
        engine.acceptGnss(fix(3,40.0))
        assertNotNull(fuse.initial)
        assertEquals(0.0,heading!!,1e-9)
        assertEquals(listOf(fix(3,40.0)),fuse.received)
    }
    @Test fun missingOrContradictoryCourseNeverActivatesFusion() {
        for (bearing in listOf<Double?>(null,90.0)) {
            val cal=Stub(); val fuse=Stub()
            val engine=CalibratingFusionEngine(cal) { fuse }
            engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
            cal.out += calibration(CalibrationStatus.VALID)
            engine.acceptGnss(fix(1,0.0,bearing)); engine.acceptGnss(fix(2,20.0,bearing))
            assertNull(fuse.initial)
        }
    }
    @Test fun expiredMountClearsFusionAndRequiresFreshMotionEvidence() {
        val cal=Stub(); val fuse=Stub(); val engine=CalibratingFusionEngine(cal) { fuse }
        engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
        cal.out += calibration(CalibrationStatus.VALID)
        engine.acceptGnss(fix(1,0.0)); engine.acceptGnss(fix(2,20.0)); assertNotNull(fuse.initial)
        val count=fuse.received.size
        cal.out += calibration(CalibrationStatus.EXPIRED,3_000_000_000)
        engine.acceptGnss(fix(3,40.0)); assertEquals(1,fuse.resets)
        engine.acceptGnss(fix(4,60.0)); assertEquals(count,fuse.received.size)
        val output=engine.drain().toList()
        assertEquals(output.size,output.map { it.event.event_id }.toSet().size)
        output.forEach { assertEquals(it,Codec.decodeJson(Codec.encodeJson(it))) }
    }
    @Test fun stopDoesNotActivateCalibrationProducedDuringFinalization() {
        val cal=Stub(); val fuse=Stub(); val engine=CalibratingFusionEngine(cal) { fuse }
        engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
        engine.acceptGnss(fix(1,0.0)); engine.acceptGnss(fix(2,20.0))
        cal.out += calibration(CalibrationStatus.VALID,2_000_000_000)
        engine.stop()
        assertNull(fuse.initial)
    }
    @Test fun shortBaselineAndBadAccuracyCannotSupplyHeading() {
        val cal=Stub(); val fuse=Stub(); val engine=CalibratingFusionEngine(cal) { fuse }
        engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
        cal.out += calibration(CalibrationStatus.VALID)
        engine.acceptGnss(fix(1,0.0)); engine.acceptGnss(fix(2,3.0))
        engine.acceptGnss(fix(3,30.0,accuracy=50.0))
        assertNull(fuse.initial)
    }
    @Test fun incompleteCalibrationAndForeignSessionCannotReachFusion() {
        val cal=Stub(); val fuse=Stub(); val engine=CalibratingFusionEngine(cal) { fuse }
        engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
        val valid=calibration(CalibrationStatus.VALID)
        cal.out += valid.copy(event=valid.event.copy(data=(valid.event.data as CalibrationResult).copy(gyro_bias_rad_s=null)))
        engine.acceptGnss(fix(1,0.0)); engine.acceptGnss(fix(2,20.0))
        assertNull(fuse.initial)
        assertThrows(IllegalArgumentException::class.java) {
            engine.acceptGnss(fix(3,40.0).copy(header=header.copy(session_id="foreign")))
        }
    }
    @Test fun refinementDoesNotChangeActiveFilterMountOrPublishAnUnadoptedTransform() {
        val cal=Stub(); val fuse=Stub(); val engine=CalibratingFusionEngine(cal) { fuse }
        engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
        cal.out += calibration(CalibrationStatus.VALID)
        engine.acceptGnss(fix(1,0.0)); engine.acceptGnss(fix(2,20.0)); engine.drain().toList()
        val original=fuse.initial
        val valid=calibration(CalibrationStatus.VALID,3_000_000_000)
        cal.out += valid.copy(event=valid.event.copy(data=(valid.event.data as CalibrationResult).copy(confidence=0.9)))
        engine.acceptGnss(fix(3,40.0))
        assertEquals(original,fuse.initial)
        assertEquals(0,fuse.resets)
        assertTrue(engine.drain().none { it.event.data is CalibrationResult })
    }
    @Test fun frozenAcquisitionVersionIsAcceptedWithoutRewritingItsHeader() {
        val cal=Stub(); val fuse=Stub(); val engine=CalibratingFusionEngine(cal) { fuse }
        engine.initialize(EngineSession(header,"boot",0),calibration(CalibrationStatus.PENDING),InitializationMode.DEPLOYABLE)
        val input=fix(1,0.0).copy(header=header.copy(contract_version="1.0.0"))
        engine.acceptGnss(input)
        assertEquals(input,cal.received.single())
    }
}
