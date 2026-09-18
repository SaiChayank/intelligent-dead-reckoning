package com.intelligentdeadreckoning.app

import com.intelligentdeadreckoning.app.acquisition.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import org.junit.Assert.*
import org.junit.Test

class AcquisitionTest {
    private val origin = 9007199254740993L
    private val events = mutableListOf<Record>()
    private fun processor() = AcquisitionProcessor(Header("test-real", Source.REAL), origin, events::add)
    private fun imu(t: Long = origin, received: Long = t, sensor: Sensor = Sensor.ACCELEROMETER) = Sample(t, received,
        ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE,
            if (sensor == Sensor.GYROSCOPE) ImuUnit.RADIANS_PER_SECOND else ImuUnit.METRES_PER_SECOND_SQUARED,
            Vector3(0.0,0.0,9.80665), SensorAccuracy.HIGH))
    private fun snapshot(p: AcquisitionProcessor, t: Long = origin) = p.snapshot(t, LocationAccess.PRECISE, true, null)
    private fun fix(t: Long = origin) = Sample(t,t,
        gnssPayload(12.0,77.0,null,null,null,null,null,null,"gps",null))
    private fun codes() = events.mapNotNull { (it.event.data as? DiagnosticEvent)?.code }

    @Test fun permissionStatesCoverBothGrantsDenialDowngradeAndRevocation() {
        assertEquals(LocationAccess.NOT_REQUESTED, locationAccess(false,false,false,false))
        assertEquals(LocationAccess.DENIED, locationAccess(false,false,true,false))
        assertEquals(LocationAccess.APPROXIMATE, locationAccess(false,true,true,false))
        assertEquals(LocationAccess.PRECISE, locationAccess(true,true,true,true))
        assertEquals(LocationAccess.APPROXIMATE, locationAccess(false,true,true,true))
        assertEquals(LocationAccess.REVOKED, locationAccess(false,false,true,true))
        assertEquals(LocationAccess.PRECISE, locationAccess(true,true,true,true))
    }

    @Test fun timestampsBeyond2pow53AndIndependentSensorTimesRemainExact() {
        val p = processor()
        p.accept(imu()); p.accept(imu(origin+1,origin+2,Sensor.GYROSCOPE))
        assertEquals(listOf(origin, origin+1), events.map { it.event.t_ns })
        assertEquals(origin+2, events.last().event.received_ns)
        assertEquals(1e-9, elapsedSeconds(origin+1,origin), 0.0)
        events.forEach { assertEquals(it, Codec.decodeJson(Codec.encodeJson(it))) }
    }

    @Test fun noWallClockFallbackForInvalidOrPreSessionTimes() {
        val p = processor()
        p.accept(imu(origin-1)); p.accept(imu(origin+1,origin)); p.accept(imu(-1,origin))
        assertEquals(0L, p.accepted); assertEquals(3L,p.invalid)
        assertEquals(3, codes().count { it == "INVALID_MEASUREMENT" })
    }

    @Test fun missingFieldsAndRealZeroAreDifferent() {
        val missing = fix().payload as GnssMeasurement
        assertNull(missing.altitude_m); assertNull(missing.altitude_reference)
        assertNull(missing.speed_m_s); assertNull(missing.bearing_deg)
        assertNull(missing.horizontal_accuracy_m); assertNull(missing.vertical_accuracy_m)
        assertNull(missing.satellites_used); assertNull(missing.utc_ms)
        val zero = gnssPayload(0.0,0.0,0.0,0.0,0.0,0.0,0.0,0,"network",0)
        assertEquals(0.0, zero.speed_m_s!!,0.0); assertEquals(0.0,zero.bearing_deg!!,0.0)
        assertEquals(AltitudeReference.ELLIPSOID,zero.altitude_reference)
        val p = processor(); p.accept(fix()); assertEquals(1L,p.accepted)
    }

    @Test fun invalidOptionalProviderValuesBecomeUnavailableAndCoordinatesReject() {
        val sanitized = gnssPayload(12.0,77.0,Double.NaN,-1.0,360.0,Double.POSITIVE_INFINITY,-1.0,-1,"gps",-1)
        assertNull(sanitized.altitude_m); assertNull(sanitized.speed_m_s); assertNull(sanitized.bearing_deg)
        assertNull(sanitized.horizontal_accuracy_m); assertNull(sanitized.satellites_used)
        val p = processor(); p.accept(Sample(origin,origin,sanitized.copy(latitude_deg=100.0)))
        assertEquals(1L,p.invalid)
    }

    @Test fun duplicatesArePerChannelNotEqualTimestampsAcrossSensors() {
        val p = processor(); p.accept(imu()); p.accept(imu(sensor=Sensor.GYROSCOPE)); p.accept(imu())
        assertEquals(2L,p.accepted); assertEquals(1L,p.duplicates); assertEquals(1L,p.dropped)
        assertTrue("DUPLICATE_EVENT" in codes())
        assertEquals(events.size,events.map { it.event.event_id }.toSet().size)
    }

    @Test fun boundedReorderingPreservesArrivalButCannotRegressLatestDisplay() {
        val p = processor(); p.accept(imu(origin+20_000_000)); p.accept(imu(origin+10_000_000,origin+30_000_000))
        assertEquals(listOf(origin+20_000_000,origin+10_000_000),events.map { it.event.t_ns })
        assertEquals(origin+20_000_000,snapshot(p,origin+30_000_000).readings["accelerometer"]!!.record.event.t_ns)
    }

    @Test fun tooOldEventsAndDelayedReceiptHaveDifferentOutcomes() {
        val p = processor(); p.accept(imu(origin+200_000_000)); p.accept(imu(origin,origin+300_000_000))
        assertEquals(1L,p.accepted); assertEquals(1L,p.dropped)
        p.accept(imu(origin+210_000_000,origin+400_000_000))
        assertEquals(2L,p.accepted); assertEquals(2L,p.delayed)
        assertTrue("LATE_MEASUREMENT" in codes())
    }

    @Test fun historyCapDoesNotPermitAncientDuplicatesBackIn() {
        val p = processor()
        repeat(500) { p.accept(imu(origin+it*1_000_000L)) }
        p.accept(imu(origin,origin+501_000_000L))
        assertEquals(500L,p.accepted); assertEquals(1L,p.dropped)
        assertTrue(snapshot(p,origin+501_000_000L).diagnostics.size <= 16)
    }

    @Test fun measuredRatesUseEventIntervalsAndIdleChannelsBecomeStale() {
        val p = processor(); repeat(11) { p.accept(imu(origin+it*10_000_000L)) }
        assertEquals(100.0,snapshot(p,origin+100_000_000).readings["accelerometer"]!!.rateHz!!,1e-9)
        val stopped = snapshot(p,origin+2_000_000_000).readings["accelerometer"]!!
        assertTrue(stopped.stale); assertEquals(0.0,stopped.rateHz!!,0.0)
    }

    @Test fun noFixDisabledDeniedAndStaleAreExplicitWithoutInventingGoodQuality() {
        val p = processor()
        assertEquals(GnssState.ACQUIRING,snapshot(p).quality.state)
        assertEquals(GnssState.UNAVAILABLE,p.snapshot(origin,LocationAccess.PRECISE,false,null).quality.state)
        assertEquals(GnssState.DENIED,p.snapshot(origin,LocationAccess.REVOKED,true,null).quality.state)
        p.accept(fix()); assertEquals(GnssState.DEGRADED,snapshot(p).quality.state)
        assertEquals(GnssState.STALE,snapshot(p,origin+6_000_000_000L).quality.state)
        assertTrue("APPROXIMATE_LOCATION" in p.snapshot(origin,LocationAccess.APPROXIMATE,true,null).quality.reasons)
    }

    @Test fun permissionTransitionClearsPreciseFixButKeepsImu() {
        val p = processor(); p.accept(imu()); p.accept(fix()); p.clearLocation(); p.discardForPermission(origin)
        assertEquals(setOf("accelerometer"),snapshot(p).readings.keys)
        assertEquals(1L,p.dropped); assertTrue("PERMISSION_CHANGED" in codes())
    }

    @Test fun boundedInboxDropsNewestWithoutBlockingAndReportsOverflow() {
        val inbox = BoundedInbox<Int>(2)
        assertTrue(inbox.offer(1)); assertTrue(inbox.offer(2)); assertFalse(inbox.offer(3))
        assertEquals(2,inbox.size); assertEquals(2,inbox.highWater)
        assertEquals(1,inbox.poll()); assertEquals(2,inbox.poll()); assertNull(inbox.poll())
        val p = processor(); p.overflow(inbox.takeDrops(),origin)
        assertEquals(1L,p.dropped); assertEquals(0L,inbox.takeDrops()); assertTrue("QUEUE_OVERFLOW" in codes())
    }

    @Test fun missingHardwareNeverRegistersOrInventsSamples() {
        val missing = registerSensor(Sensor.GRAVITY,null,null) { error("Must not register absent hardware") }
        assertFalse(missing.available)
        var calls = 0
        val refused = registerSensor(Sensor.GYROSCOPE,"gyro","vendor") { calls++; false }
        assertEquals(1,calls); assertFalse(refused.available)
        assertTrue(processor().snapshot(origin,LocationAccess.DENIED,false,null).readings.isEmpty())
    }

    private class FakeSource : SourceControl {
        var starts = 0; var running = false
        override fun start() { if (!running) { starts++; running = true } }
        override fun stop() { running = false }
    }

    @Test fun lifecycleOwnsBothSourcesAndNeverAutoResumes() {
        val sim = FakeSource(); val real = FakeSource(); val c = SourceCoordinator(sim,real)
        c.start(); assertFalse(sim.running)
        c.foreground(); c.start(); assertTrue(sim.running)
        c.background(); assertFalse(sim.running); assertFalse(real.running)
        c.foreground(); assertFalse(sim.running)
        c.select(InputSource.REAL); c.start(); assertTrue(real.running)
        c.background(); c.foreground(); assertFalse(real.running)
    }

    @Test fun sourceSwitchStopsOldAndRequiresExplicitStartOfNew() {
        val sim = FakeSource(); val real = FakeSource(); val c = SourceCoordinator(sim,real)
        c.foreground(); c.start(); c.start(); assertEquals(1,sim.starts)
        c.select(InputSource.REAL); assertFalse(sim.running); assertFalse(real.running)
        c.start(); assertTrue(real.running)
        c.select(InputSource.SIMULATION); assertFalse(real.running); assertFalse(sim.running)
    }

    @Test fun nonfiniteImuIsRejectedAndAllOutputsUseRealSourceAndDeviceFrame() {
        val p = processor(); val sample = imu()
        p.accept(sample.copy(payload=(sample.payload as ImuMeasurement).copy(xyz=Vector3(Double.NaN,0.0,0.0))))
        p.accept(imu()); assertEquals(1L,p.invalid); assertEquals(1L,p.accepted)
        assertTrue(events.all { it.header.source == Source.REAL })
        assertEquals(DeviceFrame.ANDROID_DEVICE,(events.last().event.data as ImuMeasurement).frame)
    }

    @Test fun sessionStopAccountsForDiscardedPendingMeasurements() {
        val p = processor(); p.accept(imu()); p.stopped(3,origin+1)
        assertEquals(1L,p.accepted); assertEquals(3L,p.dropped)
        val stop = events.last().event.data as DiagnosticEvent
        assertEquals("SESSION_STOPPED",stop.code); assertEquals(3L,stop.dropped_count)
    }
}
