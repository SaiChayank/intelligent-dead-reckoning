package com.intelligentdeadreckoning.contracts.v1

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlin.math.abs
import kotlin.math.hypot

class ContractException(val code: String, val path: String = "$", val line: Long? = null) :
    IllegalArgumentException("$code at $path" + (line?.let { " (line $it)" } ?: ""))

data class Limits(val maxRecordBytes: Int = 65536, val maxRecords: Int = 1_000_000) {
    init { require(maxRecordBytes in 1..Int.MAX_VALUE-3 && maxRecords > 0) }
}

private fun fail(code: String, path: String = "$"): Nothing = throw ContractException(code, path)
private val decimal = Regex("0|[1-9][0-9]*")
private val integerToken = Regex("-?(0|[1-9][0-9]*)")
private val codeToken = Regex("[A-Z][A-Z0-9_]*")

// Preserve number tokens without relying on Gson internal classes or losing 1e0 vs 1.
private class LexicalNumber(private val token: String) : Number() {
    override fun toDouble() = token.toDouble()
    override fun toFloat() = token.toFloat()
    override fun toInt() = token.toBigDecimal().toInt()
    override fun toLong() = token.toBigDecimal().toLong()
    override fun toByte() = toInt().toByte()
    override fun toShort() = toInt().toShort()
    override fun toString() = token
}

private fun checkedString(value: String, path: String): String {
    try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)) }
    catch (_: java.nio.charset.CharacterCodingException) { fail("INVALID_UNICODE", path) }
    if (value.isBlank() || value.length > 2048) fail("OUT_OF_RANGE", path)
    return value
}

private fun number(value: JsonElement, path: String): Double {
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) fail("INVALID_TYPE", path)
    val result = value.asDouble
    if (!result.isFinite()) fail("NONFINITE", path)
    return result
}

private fun array(value: JsonElement, size: Int, path: String): List<Double> {
    if (!value.isJsonArray || value.asJsonArray.size() != size) fail("INVALID_SHAPE", path)
    return value.asJsonArray.map { number(it, path) }
}

/** Proper-rotation validation only. No matrix-to-quaternion projection or INS. */
fun validateRotation(matrix: List<List<Double>>) {
    val path = "$.rotation"
    if (matrix.size != 3 || matrix.any { it.size != 3 }) fail("INVALID_SHAPE", path)
    if (matrix.flatten().any { !it.isFinite() }) fail("NONFINITE", path)
    for (i in 0..2) for (j in 0..2) {
        val dot = (0..2).sumOf { matrix[it][i]*matrix[it][j] }
        if (!dot.isFinite() || abs(dot-(if (i == j) 1 else 0)) > 1e-6) fail("INVALID_ROTATION", path)
    }
    val (a,b,c) = matrix
    val det = a[0]*(b[1]*c[2]-b[2]*c[1])-a[1]*(b[0]*c[2]-b[2]*c[0])+a[2]*(b[0]*c[1]-b[1]*c[0])
    if (!det.isFinite() || abs(det-1) > 1e-6) fail("INVALID_ROTATION", path)
}

private class Fields(value: JsonElement, val path: String) {
    val obj = if (value.isJsonObject) value.asJsonObject else fail("INVALID_TYPE", path)
    fun keys(vararg names: String) { if (obj.keySet() != names.toSet()) fail("INVALID_KEYS", path) }
    private fun at(key: String) = "$path.$key"
    operator fun get(key: String): JsonElement = obj[key] ?: fail("INVALID_KEYS", path)
    fun string(key: String): String {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
        return checkedString(v.asString, at(key))
    }
    fun decimal(key: String): Long {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
        val text = v.asString
        if (text.length > 19 || !decimal.matches(text)) fail("OUT_OF_RANGE", at(key))
        return text.toLongOrNull() ?: fail("OUT_OF_RANGE", at(key))
    }
    fun integer(key: String): Long {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber || !integerToken.matches(v.asString))
            fail("INVALID_TYPE", at(key))
        val n = v.asString.toLongOrNull() ?: fail("OUT_OF_RANGE", at(key))
        if (n < 0) fail("OUT_OF_RANGE", at(key))
        return n
    }
    fun number(key: String, min: Double = -Double.MAX_VALUE, max: Double = Double.MAX_VALUE, exclusive: Boolean = false): Double {
        val n = number(get(key), at(key))
        if (n < min || n > max || (exclusive && n == max)) fail("OUT_OF_RANGE", at(key))
        return n
    }
    fun boolean(key: String): Boolean {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isBoolean) fail("INVALID_TYPE", at(key))
        return v.asBoolean
    }
    fun <T : WireEnum> enum(key: String, values: List<T>): T {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
        return values.firstOrNull { it.wire == v.asString } ?: fail("INVALID_ENUM", at(key))
    }
    fun <T> nullable(key: String, read: () -> T): T? = if (get(key).isJsonNull) null else read()
    fun vector(key: String): Vector3 {
        val a = array(get(key), 3, at(key)); return Vector3(a[0],a[1],a[2])
    }
    fun quaternion(key: String): Quaternion {
        val a = array(get(key), 4, at(key))
        val norm = a.fold(0.0) { n, x -> hypot(n,x) }
        if (abs(norm-1) > 1e-6) fail("INVALID_ROTATION", at(key))
        return Quaternion(a[0],a[1],a[2],a[3])
    }
    fun origin(key: String): GeoOrigin {
        val a = array(get(key), 3, at(key))
        if (a[0] !in -90.0..90.0 || a[1] !in -180.0..180.0) fail("OUT_OF_RANGE", at(key))
        return GeoOrigin(a[0],a[1],a[2])
    }
    fun code(key: String): String = string(key).also { if (!codeToken.matches(it)) fail("OUT_OF_RANGE", at(key)) }
    fun codes(key: String): List<String> {
        val a = get(key)
        if (!a.isJsonArray || a.asJsonArray.size() > 64) fail("INVALID_SHAPE", at(key))
        return a.asJsonArray.map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
            checkedString(it.asString, at(key)).also { s -> if (!codeToken.matches(s)) fail("OUT_OF_RANGE", at(key)) }
        }
    }
}

object Codec {
    private val gson = GsonBuilder().serializeNulls().disableHtmlEscaping().create()

    private fun payload(kind: String, raw: JsonElement): Payload {
        val v = Fields(raw, "$.event.data")
        val result: Payload = when (kind) {
            "imu" -> {
                v.keys("sensor", "frame", "unit", "xyz", "accuracy")
                ImuMeasurement(
                    v.enum("sensor", Sensor.entries),
                    v.enum("frame", DeviceFrame.entries),
                    v.enum("unit", ImuUnit.entries),
                    v.vector("xyz"),
                    v.enum("accuracy", SensorAccuracy.entries)
                )
            }
            "gnss" -> {
                v.keys("latitude_deg", "longitude_deg", "altitude_m", "altitude_reference", "speed_m_s", "bearing_deg", "horizontal_accuracy_m", "vertical_accuracy_m", "satellites_used", "provider", "utc_ms")
                GnssMeasurement(
                    v.number("latitude_deg", -90.0, 90.0),
                    v.number("longitude_deg", -180.0, 180.0),
                    v.nullable("altitude_m") { v.number("altitude_m") },
                    v.nullable("altitude_reference") { v.enum("altitude_reference", AltitudeReference.entries) },
                    v.nullable("speed_m_s") { v.number("speed_m_s", 0.0) },
                    v.nullable("bearing_deg") { v.number("bearing_deg", 0.0, 360.0, true) },
                    v.nullable("horizontal_accuracy_m") { v.number("horizontal_accuracy_m", 0.0) },
                    v.nullable("vertical_accuracy_m") { v.number("vertical_accuracy_m", 0.0) },
                    v.nullable("satellites_used") { v.integer("satellites_used") },
                    v.string("provider"),
                    v.nullable("utc_ms") { v.integer("utc_ms") }
                )
            }
            "calibration" -> {
                v.keys("id", "status", "q_vehicle_from_device_wxyz", "gyro_bias_rad_s", "accelerometer_bias_m_s2", "confidence")
                CalibrationResult(
                    v.string("id"),
                    v.enum("status", CalibrationStatus.entries),
                    v.nullable("q_vehicle_from_device_wxyz") { v.quaternion("q_vehicle_from_device_wxyz") },
                    v.nullable("gyro_bias_rad_s") { v.vector("gyro_bias_rad_s") },
                    v.nullable("accelerometer_bias_m_s2") { v.vector("accelerometer_bias_m_s2") },
                    v.nullable("confidence") { v.number("confidence", 0.0, 1.0) }
                )
            }
            "navigation" -> {
                v.keys("status", "initialization_mode", "origin_wgs84_deg_m", "position_enu_m", "velocity_enu_m_s", "q_enu_from_vehicle_wxyz", "heading_deg", "calibration_id", "gnss_used_after_initialization")
                NavigationState(
                    v.enum("status", NavigationStatus.entries),
                    v.enum("initialization_mode", InitializationMode.entries),
                    v.nullable("origin_wgs84_deg_m") { v.origin("origin_wgs84_deg_m") },
                    v.nullable("position_enu_m") { v.vector("position_enu_m") },
                    v.nullable("velocity_enu_m_s") { v.vector("velocity_enu_m_s") },
                    v.nullable("q_enu_from_vehicle_wxyz") { v.quaternion("q_enu_from_vehicle_wxyz") },
                    v.nullable("heading_deg") { v.number("heading_deg", 0.0, 360.0, true) },
                    v.nullable("calibration_id") { v.string("calibration_id") },
                    v.boolean("gnss_used_after_initialization")
                )
            }
            "gnss_quality" -> {
                v.keys("state", "fix_age_s", "satellites_used", "reasons")
                GnssQualityState(
                    v.enum("state", GnssState.entries),
                    v.nullable("fix_age_s") { v.number("fix_age_s", 0.0) },
                    v.nullable("satellites_used") { v.integer("satellites_used") },
                    v.codes("reasons")
                )
            }
            "confidence" -> {
                v.keys("state", "probability", "horizontal_accuracy_95_m", "speed_std_m_s")
                Confidence(
                    v.enum("state", ConfidenceState.entries),
                    v.nullable("probability") { v.number("probability", 0.0, 1.0) },
                    v.nullable("horizontal_accuracy_95_m") { v.number("horizontal_accuracy_95_m", 0.0) },
                    v.nullable("speed_std_m_s") { v.number("speed_std_m_s", 0.0) }
                )
            }
            "diagnostic" -> {
                v.keys("severity", "code", "message", "dropped_count")
                DiagnosticEvent(
                    v.enum("severity", Severity.entries),
                    v.code("code"),
                    v.string("message"),
                    v.integer("dropped_count")
                )
            }
            else -> fail("INVALID_ENUM", "$.event.type")
        }
        val p = "$.event.data"
        when (result) {
            is ImuMeasurement -> {
                val expected = when (result.sensor) {
                    Sensor.ACCELEROMETER, Sensor.GRAVITY -> ImuUnit.METRES_PER_SECOND_SQUARED
                    Sensor.GYROSCOPE -> ImuUnit.RADIANS_PER_SECOND
                    Sensor.MAGNETOMETER -> ImuUnit.MICROTESLA
                }
                if (result.unit != expected) fail("INVARIANT", "$p.unit")
            }
            is GnssMeasurement -> if ((result.altitude_m == null) != (result.altitude_reference == null)) fail("INVARIANT", "$p.altitude_reference")
            is CalibrationResult -> if (result.status == CalibrationStatus.VALID &&
                (result.q_vehicle_from_device_wxyz == null || result.gyro_bias_rad_s == null)) fail("INVARIANT", p)
            is NavigationState -> {
                val spatial = listOf(result.origin_wgs84_deg_m, result.position_enu_m, result.velocity_enu_m_s,
                    result.q_enu_from_vehicle_wxyz, result.calibration_id)
                if (result.status == NavigationStatus.TRACKING && spatial.any { it == null }) fail("INVARIANT", p)
                if ((result.position_enu_m != null || result.velocity_enu_m_s != null) && result.origin_wgs84_deg_m == null)
                    fail("INVARIANT", "$p.origin_wgs84_deg_m")
                if (result.status in listOf(NavigationStatus.UNINITIALIZED, NavigationStatus.CALIBRATING, NavigationStatus.FAILED) &&
                    (spatial.any { it != null } || result.heading_deg != null)) fail("INVARIANT", p)
            }
            is Confidence -> if (result.state != ConfidenceState.CALIBRATED && result.probability != null) fail("INVARIANT", "$p.probability")
            else -> Unit
        }
        return result
    }

    private fun record(raw: JsonElement): Record {
        val h = Fields(raw, "$")
        h.keys("contract_version", "session_id", "source", "event")
        if (h["contract_version"] != JsonPrimitive("1.0.0")) fail("INVALID_VERSION", "$.contract_version")
        val header = Header(h.string("session_id"), h.enum("source", Source.entries))
        val e = Fields(h["event"], "$.event")
        e.keys("event_id", "type", "t_ns", "received_ns", "data")
        e.decimal("event_id")
        val t = e.decimal("t_ns"); val received = e.decimal("received_ns")
        if (received < t) fail("INVARIANT", "$.event.received_ns")
        return Record(header, Event(e.string("event_id"), t, received, payload(e.string("type"), e["data"])))
    }

    // Explicit token types prevent Gson's normal numeric/string coercion. Never use fromJson DTO reflection.
    private fun tree(reader: JsonReader, depth: Int = 0): JsonElement {
        if (depth > 16) fail("RESOURCE_LIMIT")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val obj = JsonObject(); reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (obj.has(key)) fail("DUPLICATE_KEY")
                    obj.add(key, tree(reader, depth+1))
                }
                reader.endObject(); obj
            }
            JsonToken.BEGIN_ARRAY -> {
                val arr = JsonArray(); reader.beginArray()
                while (reader.hasNext()) arr.add(tree(reader, depth+1))
                reader.endArray(); arr
            }
            JsonToken.STRING -> {
                val s = reader.nextString()
                try { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(s)) }
                catch (_: java.nio.charset.CharacterCodingException) { fail("INVALID_UNICODE") }
                JsonPrimitive(s)
            }
            JsonToken.NUMBER -> {
                val token = reader.nextString()
                // Preserve the lexical integer/float distinction, including exponent tokens.
                JsonPrimitive(LexicalNumber(token))
            }
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> fail("MALFORMED_JSON")
        }
    }

    fun decodeJson(bytes: ByteArray, limits: Limits = Limits()): Record {
        if (bytes.size > limits.maxRecordBytes) fail("RESOURCE_LIMIT")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { fail("INVALID_UTF8") }
        if (text.startsWith('\uFEFF')) fail("MALFORMED_JSON")
        try {
            val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
            val value = tree(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) fail("MALFORMED_JSON")
            return record(value)
        } catch (e: ContractException) { throw e }
        catch (_: IOException) { fail("MALFORMED_JSON") }
        catch (_: IllegalStateException) { fail("MALFORMED_JSON") }
        catch (_: NumberFormatException) { fail("MALFORMED_JSON") }
    }

    private fun wire(v: Any?): JsonElement = when (v) {
        null -> JsonNull.INSTANCE
        is WireEnum -> JsonPrimitive(v.wire)
        is String -> JsonPrimitive(checkedString(v, "$"))
        is Boolean -> JsonPrimitive(v)
        is Number -> {
            if (!v.toDouble().isFinite()) fail("NONFINITE")
            JsonPrimitive(v)
        }
        is Vector3 -> wire(listOf(v.x,v.y,v.z))
        is Quaternion -> wire(listOf(v.w,v.x,v.y,v.z))
        is GeoOrigin -> wire(listOf(v.latitude_deg,v.longitude_deg,v.altitude_m))
        is List<*> -> JsonArray().apply { v.forEach { add(wire(it)) } }
        is Map<*,*> -> JsonObject().apply { v.forEach { (k,value) -> add(k as String, wire(value)) } }
        else -> fail("INVALID_MODEL")
    }

    fun encodeJson(value: Record, limits: Limits = Limits()): ByteArray {
        val p = value.event.data
        val data = when (p) {
            is ImuMeasurement -> linkedMapOf(
                "sensor" to p.sensor,
                "frame" to p.frame,
                "unit" to p.unit,
                "xyz" to p.xyz,
                "accuracy" to p.accuracy
            )
            is GnssMeasurement -> linkedMapOf(
                "latitude_deg" to p.latitude_deg,
                "longitude_deg" to p.longitude_deg,
                "altitude_m" to p.altitude_m,
                "altitude_reference" to p.altitude_reference,
                "speed_m_s" to p.speed_m_s,
                "bearing_deg" to p.bearing_deg,
                "horizontal_accuracy_m" to p.horizontal_accuracy_m,
                "vertical_accuracy_m" to p.vertical_accuracy_m,
                "satellites_used" to p.satellites_used,
                "provider" to p.provider,
                "utc_ms" to p.utc_ms
            )
            is CalibrationResult -> linkedMapOf(
                "id" to p.id,
                "status" to p.status,
                "q_vehicle_from_device_wxyz" to p.q_vehicle_from_device_wxyz,
                "gyro_bias_rad_s" to p.gyro_bias_rad_s,
                "accelerometer_bias_m_s2" to p.accelerometer_bias_m_s2,
                "confidence" to p.confidence
            )
            is NavigationState -> linkedMapOf(
                "status" to p.status,
                "initialization_mode" to p.initialization_mode,
                "origin_wgs84_deg_m" to p.origin_wgs84_deg_m,
                "position_enu_m" to p.position_enu_m,
                "velocity_enu_m_s" to p.velocity_enu_m_s,
                "q_enu_from_vehicle_wxyz" to p.q_enu_from_vehicle_wxyz,
                "heading_deg" to p.heading_deg,
                "calibration_id" to p.calibration_id,
                "gnss_used_after_initialization" to p.gnss_used_after_initialization
            )
            is GnssQualityState -> linkedMapOf(
                "state" to p.state,
                "fix_age_s" to p.fix_age_s,
                "satellites_used" to p.satellites_used,
                "reasons" to p.reasons
            )
            is Confidence -> linkedMapOf(
                "state" to p.state,
                "probability" to p.probability,
                "horizontal_accuracy_95_m" to p.horizontal_accuracy_95_m,
                "speed_std_m_s" to p.speed_std_m_s
            )
            is DiagnosticEvent -> linkedMapOf(
                "severity" to p.severity,
                "code" to p.code,
                "message" to p.message,
                "dropped_count" to p.dropped_count
            )
        }
        val h = value.header; val e = value.event
        if (e.t_ns < 0 || e.received_ns < 0) fail("OUT_OF_RANGE", "$.event.t_ns")
        val raw = wire(linkedMapOf("contract_version" to h.contract_version, "session_id" to h.session_id,
            "source" to h.source, "event" to linkedMapOf("event_id" to e.event_id, "type" to p.type,
                "t_ns" to e.t_ns.toString(), "received_ns" to e.received_ns.toString(), "data" to data)))
        record(raw) // Same constraints for typed callers as for the wire. Never silently repair.
        val out = gson.toJson(raw).toByteArray(Charsets.UTF_8)
        if (out.size > limits.maxRecordBytes) fail("RESOURCE_LIMIT")
        return out
    }

    private class Guard(val limits: Limits) {
        val ids = HashSet<String>()
        var header: Header? = null
        var mode: InitializationMode? = null
        fun accept(value: Record) {
            if (header != null && value.header != header) fail("SESSION_MISMATCH")
            if (value.event.event_id in ids) fail("DUPLICATE_EVENT", "$.event.event_id")
            if (ids.size >= limits.maxRecords) fail("RESOURCE_LIMIT")
            (value.event.data as? NavigationState)?.let {
                if (mode != null && mode != it.initialization_mode) fail("INVARIANT", "$.event.data.initialization_mode")
                mode = it.initialization_mode
            }
            header = value.header; ids.add(value.event.event_id)
        }
    }

    fun readJson(input: InputStream, limits: Limits = Limits()): Record {
        try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() <= limits.maxRecordBytes) {
                val n = input.read(buffer, 0, minOf(buffer.size, limits.maxRecordBytes+1-out.size()))
                if (n < 0) break
                if (n == 0) fail("IO_ERROR")
                out.write(buffer,0,n)
            }
            return decodeJson(out.toByteArray(), limits)
        } catch (_: IOException) { fail("IO_ERROR") }
    }

    fun writeJson(value: Record, output: OutputStream, limits: Limits = Limits()) {
        val raw = encodeJson(value, limits)
        try { output.write(raw) } catch (_: IOException) { fail("IO_ERROR") }
    }

    /** Lazy, fail-fast and single-consumption. Caller owns/closes the input stream. */
    fun readJsonl(input: InputStream, limits: Limits = Limits()): Sequence<Record> = sequence {
        val guard = Guard(limits)
        var line = 0L
        while (true) {
            line++
            try {
                val bytes = ByteArrayOutputStream()
                var ended = false
                while (bytes.size() <= limits.maxRecordBytes+2) {
                    val b = input.read()
                    if (b < 0) { ended = true; break }
                    bytes.write(b)
                    if (b == 10) break
                }
                var raw = bytes.toByteArray()
                if (ended && raw.isEmpty()) break
                if (raw.lastOrNull() == 10.toByte()) {
                    raw = raw.copyOf(raw.size-1)
                    if (raw.lastOrNull() == 13.toByte()) raw = raw.copyOf(raw.size-1)
                }
                val value = decodeJson(raw, limits)
                guard.accept(value)
                yield(value)
            } catch (e: ContractException) { throw ContractException(e.code, e.path, line) }
            catch (_: IOException) { throw ContractException("IO_ERROR", "$", line) }
        }
    }.constrainOnce()

    fun writeJsonl(values: Sequence<Record>, output: OutputStream, limits: Limits = Limits()) {
        val guard = Guard(limits)
        var line = 0L
        for (value in values) {
            line++
            try {
                val raw = encodeJson(value, limits)
                guard.accept(value)
                output.write(raw); output.write(10)
            } catch (e: ContractException) { throw ContractException(e.code, e.path, line) }
            catch (_: IOException) { throw ContractException("IO_ERROR", "$", line) }
        }
    }
}
