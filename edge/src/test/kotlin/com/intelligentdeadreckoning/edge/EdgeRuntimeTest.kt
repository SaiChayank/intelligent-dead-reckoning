package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.contracts.v1.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeRuntimeTest {
    private val header = Header("edge-test", Source.REAL, "1.1.0")
    private val origin = 1_000_000_000L
    private val calibration = Record(
        header, Event("0", origin, origin, CalibrationResult(
            "cal-1", CalibrationStatus.VALID, Quaternion(1.0, 0.0, 0.0, 0.0),
            Vector3(0.0, 0.0, 0.0), null, 0.9,
        )),
    )

    private fun imu(id: Long, sensor: Sensor, timeNs: Long, header: Header = this.header,
                    unit: ImuUnit = if (sensor == Sensor.GYROSCOPE) ImuUnit.RADIANS_PER_SECOND else ImuUnit.METRES_PER_SECOND_SQUARED) =
        Record(header, Event(id.toString(), timeNs, timeNs, ImuMeasurement(
            sensor, DeviceFrame.ANDROID_DEVICE, unit,
            if (sensor == Sensor.ACCELEROMETER) Vector3(0.0, 0.0, 9.80665) else Vector3(0.0, 0.0, 0.0),
            SensorAccuracy.HIGH,
        )))

    private fun gnss(id: Long, timeNs: Long) = Record(
        header, Event(id.toString(), timeNs, timeNs, GnssMeasurement(
            17.5, 78.4, 500.0, AltitudeReference.ELLIPSOID, null, null, 3.0, 5.0, 10L, "gps", null,
        )),
    )

    private fun runtime(ingress: Int = 1024, output: Int = 4096, intervalNs: Long = 5_000_000L) =
        EdgeRuntime(ingressCapacity = ingress, outputCapacity = output, publicationIntervalNs = intervalNs)

    @Test
    fun calibratedSessionUsesDeployableContractAndPassesCanonicalOutputs() {
        val runtime = runtime()
        assertTrue(runtime.start(header, origin, calibration))
        assertTrue(runtime.offer(gnss(1, origin + 1_000_000L)))
        assertTrue(runtime.offer(imu(2, Sensor.ACCELEROMETER, origin + 2_000_000L)))
        assertTrue(runtime.offer(imu(3, Sensor.GYROSCOPE, origin + 2_000_000L)))
        assertTrue(runtime.stop())
        val records = runtime.drain()
        assertTrue(records.isNotEmpty())
        records.forEach { record ->
            assertEquals(header, record.header)
            assertEquals(record, Codec.decodeJson(Codec.encodeJson(record)))
        }
        val states = records.mapNotNull { it.event.data as? NavigationState }
        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it.initialization_mode == InitializationMode.DEPLOYABLE })
        val metrics = runtime.metrics()
        assertEquals(2L, metrics.acceptedImuRecords)
        assertEquals(1L, metrics.acceptedGnssRecords)
        assertEquals(0L, metrics.dropped)
        assertEquals(0L, metrics.outputDropped)
        assertEquals("stopped", metrics.phase)
        assertFalse(metrics.aiAvailable)
        assertNotNull(metrics.inferenceUnavailableReason)
        assertTrue(metrics.stageLatency.containsKey("ins_ekf_constraints_confidence"))
        assertTrue(metrics.stageLatency.containsKey("output_contract_validation"))
        assertTrue(metrics.totalCycleLatency.count > 0)
    }

    @Test
    fun rejectsWrongSessionUnitsPreCalibrationAndDuplicateChannelTimestamps() {
        val runtime = runtime()
        assertTrue(runtime.start(header, origin, calibration))
        val other = Header("other", Source.REAL, "1.1.0")
        assertFalse(runtime.offer(imu(1, Sensor.ACCELEROMETER, origin + 1, other)))
        assertFalse(runtime.offer(imu(2, Sensor.GYROSCOPE, origin + 2, unit = ImuUnit.METRES_PER_SECOND_SQUARED)))
        assertFalse(runtime.offer(imu(3, Sensor.GYROSCOPE, origin - 1)))
        assertTrue(runtime.offer(imu(4, Sensor.GYROSCOPE, origin + 10)))
        assertFalse(runtime.offer(imu(5, Sensor.GYROSCOPE, origin + 10)))
        assertTrue(runtime.stop())
        val metrics = runtime.metrics()
        assertEquals(1L, metrics.invalid)
        assertEquals(1L, metrics.late)
        assertEquals(1L, metrics.duplicate)
        assertEquals(4L, metrics.rejected)
    }

    @Test
    fun boundedIngressRefusesNewRowsInsteadOfCreatingUnboundedBacklog() {
        val runtime = runtime(ingress = 8)
        assertTrue(runtime.start(header, origin, calibration))
        var accepted = 0
        repeat(80) { index ->
            val time = origin + 10_000_000L * index
            if (runtime.offer(imu(10L + index * 2, Sensor.ACCELEROMETER, time))) accepted++
            if (runtime.offer(imu(11L + index * 2, Sensor.GYROSCOPE, time))) accepted++
        }
        assertTrue(runtime.stop())
        val metrics = runtime.metrics()
        assertTrue(metrics.dropped > 0)
        assertTrue(metrics.ingressHighWater <= metrics.ingressCapacity)
        assertEquals(accepted.toLong(), metrics.acceptedImuRecords + metrics.dropped)
    }

    @Test
    fun sourceCadenceIsIndependentOfFastWallAcceptanceAndNotUpsampled() {
        val runtime = runtime()
        assertTrue(runtime.start(header, origin, calibration))
        repeat(5) { index ->
            val time = origin + index * 100_000_000L
            runtime.offer(imu(10L + index * 2, Sensor.ACCELEROMETER, time))
            runtime.offer(imu(11L + index * 2, Sensor.GYROSCOPE, time))
        }
        assertTrue(runtime.stop())
        val metrics = runtime.metrics()
        assertEquals(10.0, metrics.accelerometerInput.hz!!, 1e-6)
        assertEquals(10.0, metrics.gyroscopeInput.hz!!, 1e-6)
        assertEquals(10.0, metrics.pairedImuSource.hz!!, 1e-6)
        assertTrue(metrics.navigationOutput.count < metrics.pairedImuSource.count)
    }

    @Test
    fun refusesWrongSessionVersionAndInvalidCalibration() {
        val runtime = runtime()
        val old = Header("old", Source.REAL, "1.0.0")
        assertFalse(runtime.start(old, origin, calibration.copy(header = old)))
        val pending = calibration.copy(event = calibration.event.copy(data = CalibrationResult(
            "pending", CalibrationStatus.PENDING, null, null, null, null,
        )))
        assertFalse(runtime.start(header, origin, pending))
        val simulation = header.copy(source = Source.SIMULATION)
        assertFalse(runtime.start(simulation, origin, calibration.copy(header = simulation)))
    }
}
