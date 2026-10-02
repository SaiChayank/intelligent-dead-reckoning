package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.contracts.v1.*
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InputAdapterTest {
    private val header = Header("input-test", Source.REAL, "1.1.0")
    private val time = 1_000_000L

    private fun imu(id: Long, sensor: Sensor = Sensor.ACCELEROMETER, unit: ImuUnit =
        if (sensor == Sensor.GYROSCOPE) ImuUnit.RADIANS_PER_SECOND else ImuUnit.METRES_PER_SECOND_SQUARED,
        tNs: Long = time) = Record(
        header,
        Event(id.toString(), tNs, tNs, ImuMeasurement(sensor, DeviceFrame.ANDROID_DEVICE, unit,
            Vector3(0.0, 0.0, 9.8), SensorAccuracy.HIGH)),
    )

    private inline fun rejected(block: () -> Unit): EdgeInputValidationException = try {
        block(); fail("expected validation rejection"); error("unreachable")
    } catch (e: EdgeInputValidationException) { e }

    @Test
    fun acceptsCanonicalImuAndRejectsFrameUnitAndNonCausalCalibration() {
        assertEquals("imu:accelerometer", EdgeInputValidator.validate(imu(1), header, 0L, time))
        assertEquals("imu:gyroscope", EdgeInputValidator.validate(imu(2, Sensor.GYROSCOPE), header, 0L, time))
        assertEquals(InputRejection.INVALID, rejected {
            EdgeInputValidator.validate(imu(3, Sensor.GYROSCOPE, ImuUnit.MICROTESLA), header, 0L, time)
        }.rejection)
        assertEquals(InputRejection.INVALID, rejected {
            EdgeInputValidator.validate(imu(4), Header("other", Source.REAL, "1.1.0"), 0L, time)
        }.rejection)
        assertEquals(InputRejection.LATE, rejected {
            EdgeInputValidator.validate(imu(5, tNs = time - 1), header, 0L, time)
        }.rejection)
    }

    @Test
    fun streamsCodecJsonlWithoutReordering() {
        val records = listOf(imu(1, tNs = time), imu(2, Sensor.GYROSCOPE, tNs = time + 1))
        val file = Files.createTempFile("edge-input", ".jsonl")
        try {
            Files.newOutputStream(file).use { Codec.writeJsonl(records.asSequence(), it) }
            val found = ArrayList<Record>()
            EdgeInputAdapter.forEachRecord(file, found::add)
            assertEquals(records, found)
            assertTrue(found.zipWithNext().all { (a, b) -> a.event.t_ns < b.event.t_ns })
        } finally { Files.deleteIfExists(file) }
    }
}
