package com.intelligentdeadreckoning.edge

import com.intelligentdeadreckoning.contracts.v1.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Stream canonical JSONL records without sorting or converting their payloads. */
object EdgeInputAdapter {
    fun forEachRecord(path: Path, consume: (Record) -> Unit) {
        require(Files.isRegularFile(path)) { "input JSONL not found" }
        Files.newInputStream(path).buffered().use { Codec.readJsonl(it).forEach(consume) }
    }

    fun decodeLine(line: String): Record = Codec.decodeJson(line.toByteArray(StandardCharsets.UTF_8))
}

enum class InputRejection { INVALID, LATE, DUPLICATE }

class EdgeInputValidationException(val rejection: InputRejection, message: String) : IllegalArgumentException(message)

/** Same 1.1.0 sensor frame/unit semantics as mobile: no guesses and no implicit conversion. */
object EdgeInputValidator {
    fun validate(record: Record, header: Header, sessionOriginNs: Long, calibrationTimeNs: Long): String {
        if (record.header != header) invalid("session/source/version mismatch")
        try { Codec.encodeJson(record) } catch (e: RuntimeException) { invalid("record contract invalid: ${e.message}") }
        if (record.event.t_ns < sessionOriginNs) invalid("pre-session timestamp")
        if (record.event.t_ns < calibrationTimeNs) late("measurement predates calibration")
        return when (val payload = record.event.data) {
            is ImuMeasurement -> {
                if (payload.frame != DeviceFrame.ANDROID_DEVICE) invalid("unsupported physical frame")
                val unit = when (payload.sensor) {
                    Sensor.ACCELEROMETER, Sensor.GRAVITY -> ImuUnit.METRES_PER_SECOND_SQUARED
                    Sensor.GYROSCOPE -> ImuUnit.RADIANS_PER_SECOND
                    Sensor.MAGNETOMETER -> ImuUnit.MICROTESLA
                }
                if (payload.unit != unit) invalid("sensor unit mismatch")
                if (!payload.xyz.x.isFinite() || !payload.xyz.y.isFinite() || !payload.xyz.z.isFinite()) invalid("non-finite IMU value")
                "imu:${payload.sensor.wire}"
            }
            is GnssMeasurement -> {
                if (!payload.latitude_deg.isFinite() || payload.latitude_deg !in -90.0..90.0 ||
                    !payload.longitude_deg.isFinite() || payload.longitude_deg !in -180.0..180.0) invalid("invalid GNSS coordinates")
                if (payload.provider != "gps" && payload.provider != "network") invalid("unsupported GNSS provider")
                if (payload.altitude_m != null && !payload.altitude_m.isFinite()) invalid("invalid GNSS altitude")
                if ((payload.altitude_m == null) != (payload.altitude_reference == null)) invalid("altitude and reference must be null together")
                if (payload.speed_m_s != null && (!payload.speed_m_s.isFinite() || payload.speed_m_s < 0.0)) invalid("invalid GNSS speed")
                if (payload.bearing_deg != null && (!payload.bearing_deg.isFinite() || payload.bearing_deg !in 0.0..<360.0)) invalid("invalid GNSS bearing")
                if (payload.horizontal_accuracy_m != null && (!payload.horizontal_accuracy_m.isFinite() || payload.horizontal_accuracy_m < 0.0)) invalid("invalid horizontal accuracy")
                if (payload.vertical_accuracy_m != null && (!payload.vertical_accuracy_m.isFinite() || payload.vertical_accuracy_m < 0.0)) invalid("invalid vertical accuracy")
                "gnss:${payload.provider}"
            }
            else -> invalid("unsupported input type")
        }
    }

    private fun invalid(message: String): Nothing = throw EdgeInputValidationException(InputRejection.INVALID, message)
    private fun late(message: String): Nothing = throw EdgeInputValidationException(InputRejection.LATE, message)
    fun duplicate(message: String): Nothing = throw EdgeInputValidationException(InputRejection.DUPLICATE, message)
}
