package com.intelligentdeadreckoning.contracts.recording.v1

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.intelligentdeadreckoning.contracts.v1.Codec
import com.intelligentdeadreckoning.contracts.v1.ContractException
import com.intelligentdeadreckoning.contracts.v1.Record
import com.intelligentdeadreckoning.contracts.v1.Sensor
import com.intelligentdeadreckoning.contracts.v1.Source
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

class RecordingContractException(
    val code: String,
    val path: String = "$",
) : IllegalArgumentException("$code at $path")

private const val MAX_METADATA_BYTES = 262_144
private const val MAX_TEXT_UNITS = 2048
private val decimal = Regex("0|[1-9][0-9]*")
private val integerToken = Regex("-?(0|[1-9][0-9]*)")
private val channelToken = Regex("[a-z][a-z0-9_]{0,63}")

private fun fail(code: String, path: String = "$"): Nothing =
    throw RecordingContractException(code, path)

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
    try {
        Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
    } catch (_: java.nio.charset.CharacterCodingException) {
        fail("INVALID_UNICODE", path)
    }
    if (value.isBlank() || value.length > MAX_TEXT_UNITS) fail("OUT_OF_RANGE", path)
    return value
}

private class Fields(value: JsonElement, val path: String) {
    val obj = if (value.isJsonObject) value.asJsonObject else fail("INVALID_TYPE", path)

    fun keys(vararg names: String) {
        if (obj.keySet() != names.toSet()) fail("INVALID_KEYS", path)
    }

    private fun at(key: String) = "$path.$key"

    operator fun get(key: String): JsonElement = obj[key] ?: fail("INVALID_KEYS", path)

    fun string(key: String): String {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
        return checkedString(v.asString, at(key))
    }

    fun nullableString(key: String): String? = if (get(key).isJsonNull) null else string(key)

    fun decimalString(key: String): Long {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) fail("INVALID_TYPE", at(key))
        val text = v.asString
        if (text.length > 19 || !decimal.matches(text)) fail("INVALID_TYPE", at(key))
        return text.toLongOrNull()?.takeIf { it >= 0 } ?: fail("OUT_OF_RANGE", at(key))
    }

    fun nullableDecimalString(key: String): Long? =
        if (get(key).isJsonNull) null else decimalString(key)

    fun integer(key: String): Long {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber || !integerToken.matches(v.asString))
            fail("INVALID_TYPE", at(key))
        return v.asString.toLongOrNull()?.takeIf { it >= 0 } ?: fail("OUT_OF_RANGE", at(key))
    }

    fun nullableInteger(key: String): Long? = if (get(key).isJsonNull) null else integer(key)

    fun positiveNumber(key: String): Double {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) fail("INVALID_TYPE", at(key))
        val result = v.asDouble
        if (!result.isFinite() || result <= 0.0) fail("OUT_OF_RANGE", at(key))
        return result
    }

    fun nullablePositiveNumber(key: String): Double? =
        if (get(key).isJsonNull) null else positiveNumber(key)

    fun boolean(key: String): Boolean {
        val v = get(key)
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isBoolean) fail("INVALID_TYPE", at(key))
        return v.asBoolean
    }

    fun nullableBoolean(key: String): Boolean? = if (get(key).isJsonNull) null else boolean(key)

    fun array(key: String): JsonArray {
        val v = get(key)
        if (!v.isJsonArray) fail("INVALID_TYPE", at(key))
        return v.asJsonArray
    }
}

object RecordingCodec {
    private fun tree(reader: JsonReader, depth: Int = 0): JsonElement {
        if (depth > 16) fail("RESOURCE_LIMIT")
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val obj = JsonObject()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    if (obj.has(key)) fail("DUPLICATE_KEY")
                    obj.add(key, tree(reader, depth + 1))
                }
                reader.endObject()
                obj
            }
            JsonToken.BEGIN_ARRAY -> {
                val arr = JsonArray()
                reader.beginArray()
                while (reader.hasNext()) arr.add(tree(reader, depth + 1))
                reader.endArray()
                arr
            }
            JsonToken.STRING -> JsonPrimitive(checkedString(reader.nextString(), "$"))
            JsonToken.NUMBER -> JsonPrimitive(LexicalNumber(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> fail("MALFORMED_JSON")
        }
    }

    private fun parse(bytes: ByteArray): JsonElement {
        if (bytes.size > MAX_METADATA_BYTES) fail("RESOURCE_LIMIT")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail("INVALID_UTF8")
        }
        if (text.startsWith('\uFEFF')) fail("MALFORMED_JSON")
        return try {
            val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
            val value = tree(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) fail("MALFORMED_JSON")
            value
        } catch (e: RecordingContractException) {
            throw e
        } catch (_: IOException) {
            fail("MALFORMED_JSON")
        } catch (_: IllegalStateException) {
            fail("MALFORMED_JSON")
        } catch (_: NumberFormatException) {
            fail("MALFORMED_JSON")
        }
    }

    private fun <T : RecordingWireEnum> recordingEnum(value: String, entries: List<T>, path: String): T =
        entries.firstOrNull { it.wire == value } ?: fail("INVALID_ENUM", path)

    private fun source(value: String, path: String): Source =
        Source.entries.firstOrNull { it.wire == value } ?: fail("INVALID_ENUM", path)

    private fun sensor(value: String, path: String): Sensor =
        Sensor.entries.firstOrNull { it.wire == value } ?: fail("INVALID_ENUM", path)

    private fun clock(raw: JsonElement): ClockIdentity {
        val p = "$.clock"
        val v = Fields(raw, p)
        v.keys("domain", "boot_id", "session_clock_id", "origin_ns", "started_ns", "ended_ns")
        return ClockIdentity(
            domain = recordingEnum(v.string("domain"), ClockDomain.entries, "$p.domain"),
            bootId = v.nullableString("boot_id"),
            sessionClockId = v.string("session_clock_id"),
            originNs = v.decimalString("origin_ns"),
            startedNs = v.decimalString("started_ns"),
            endedNs = v.nullableDecimalString("ended_ns"),
        )
    }

    private fun device(raw: JsonElement): DeviceInfo {
        val p = "$.device"
        val v = Fields(raw, p)
        v.keys("manufacturer", "model", "os_name", "os_version", "api_level")
        return DeviceInfo(
            manufacturer = v.nullableString("manufacturer"),
            model = v.nullableString("model"),
            osName = v.nullableString("os_name"),
            osVersion = v.nullableString("os_version"),
            apiLevel = v.nullableInteger("api_level"),
        )
    }

    private fun application(raw: JsonElement): ApplicationInfo {
        val p = "$.application"
        val v = Fields(raw, p)
        v.keys("package_name", "version_name", "version_code", "source_version")
        return ApplicationInfo(
            packageName = v.nullableString("package_name"),
            versionName = v.nullableString("version_name"),
            versionCode = v.nullableInteger("version_code"),
            sourceVersion = v.nullableString("source_version"),
        )
    }

    private fun sensorDescriptor(raw: JsonElement, index: Int): SensorDescriptor {
        val p = "$.sensors[$index]"
        val v = Fields(raw, p)
        v.keys("sensor", "name", "vendor", "available", "requested_hz", "measured_hz")
        return SensorDescriptor(
            sensor = sensor(v.string("sensor"), "$p.sensor"),
            name = v.nullableString("name"),
            vendor = v.nullableString("vendor"),
            available = v.boolean("available"),
            requestedHz = v.nullablePositiveNumber("requested_hz"),
            measuredHz = v.nullablePositiveNumber("measured_hz"),
        )
    }

    private fun sourceConfiguration(raw: JsonElement): SourceConfiguration {
        val p = "$.source_configuration"
        val v = Fields(raw, p)
        v.keys(
            "location_permission",
            "location_service_enabled",
            "gps_provider_enabled",
            "network_provider_enabled",
            "satellite_status_enabled",
            "foreground_only",
        )
        return SourceConfiguration(
            locationPermission = recordingEnum(
                v.string("location_permission"),
                LocationPermissionState.entries,
                "$p.location_permission",
            ),
            locationServiceEnabled = v.nullableBoolean("location_service_enabled"),
            gpsProviderEnabled = v.nullableBoolean("gps_provider_enabled"),
            networkProviderEnabled = v.nullableBoolean("network_provider_enabled"),
            satelliteStatusEnabled = v.nullableBoolean("satellite_status_enabled"),
            foregroundOnly = v.boolean("foreground_only"),
        )
    }

    private fun calibration(raw: JsonElement): CalibrationInfo {
        val p = "$.calibration"
        val v = Fields(raw, p)
        v.keys("state", "calibration_id")
        return CalibrationInfo(
            state = recordingEnum(v.string("state"), CalibrationApplication.entries, "$p.state"),
            calibrationId = v.nullableString("calibration_id"),
        )
    }

    private fun channelCount(raw: JsonElement, index: Int): ChannelCount {
        val p = "$.channel_counts[$index]"
        val v = Fields(raw, p)
        v.keys("channel", "count")
        val channel = v.string("channel")
        if (!channelToken.matches(channel)) fail("OUT_OF_RANGE", "$p.channel")
        return ChannelCount(channel, v.decimalString("count"))
    }

    fun decodeMetadata(bytes: ByteArray): RecordingMetadata {
        val root = Fields(parse(bytes), "$")
        root.keys(
            "recording_contract_version",
            "measurement_contract_version",
            "recording_id",
            "acquisition_session_id",
            "source",
            "start_state",
            "end_state",
            "completion_state",
            "recovery_state",
            "created_utc_ms",
            "started_utc_ms",
            "ended_utc_ms",
            "clock",
            "device",
            "application",
            "sensors",
            "source_configuration",
            "calibration",
            "record_count",
            "channel_counts",
        )
        if (root.string("recording_contract_version") != RECORDING_CONTRACT_VERSION)
            fail("INVALID_VERSION", "$.recording_contract_version")
        if (root.string("measurement_contract_version") != MEASUREMENT_CONTRACT_VERSION)
            fail("INVALID_VERSION", "$.measurement_contract_version")

        val endState = if (root["end_state"].isJsonNull) null else
            recordingEnum(root.string("end_state"), RecordingEndState.entries, "$.end_state")
        val sensors =
            root.array("sensors").mapIndexed { index, raw ->
                sensorDescriptor(raw, index)
            }

        val channelCounts =
            if (root["channel_counts"].isJsonNull) {
                null
            } else {
                    root.array("channel_counts").mapIndexed { index, raw ->
                        channelCount(raw, index)
                    }
                }

        return validate(
            RecordingMetadata(
                recordingId = root.string("recording_id"),
                acquisitionSessionId = root.string("acquisition_session_id"),
                source = source(root.string("source"), "$.source"),
                startState = recordingEnum(root.string("start_state"), RecordingStartState.entries, "$.start_state"),
                endState = endState,
                completionState = recordingEnum(root.string("completion_state"), CompletionState.entries, "$.completion_state"),
                recoveryState = recordingEnum(root.string("recovery_state"), RecoveryState.entries, "$.recovery_state"),
                createdUtcMs = root.nullableDecimalString("created_utc_ms"),
                startedUtcMs = root.nullableDecimalString("started_utc_ms"),
                endedUtcMs = root.nullableDecimalString("ended_utc_ms"),
                clock = clock(root["clock"]),
                device = device(root["device"]),
                application = application(root["application"]),
                sensors = sensors,
                sourceConfiguration = sourceConfiguration(root["source_configuration"]),
                calibration = calibration(root["calibration"]),
                recordCount = root.nullableDecimalString("record_count"),
                channelCounts = channelCounts,
            ),
        )
    }

    private fun validate(value: RecordingMetadata): RecordingMetadata {
        if (value.recordingContractVersion != RECORDING_CONTRACT_VERSION)
            fail("INVALID_VERSION", "$.recording_contract_version")
        if (value.measurementContractVersion != MEASUREMENT_CONTRACT_VERSION)
            fail("INVALID_VERSION", "$.measurement_contract_version")
        checkedString(value.recordingId, "$.recording_id")
        checkedString(value.acquisitionSessionId, "$.acquisition_session_id")
        if (value.source !in listOf(Source.REAL, Source.SIMULATION)) fail("INVALID_ENUM", "$.source")
        if (value.startState != RecordingStartState.RECORDING) fail("INVARIANT", "$.start_state")
        if (value.clock.originNs < 0 || value.clock.startedNs < value.clock.originNs)
            fail("INVARIANT", "$.clock.started_ns")
        if (value.clock.endedNs != null && value.clock.endedNs < value.clock.startedNs)
            fail("INVARIANT", "$.clock.ended_ns")
        if (value.sensors.map { it.sensor }.toSet().size != value.sensors.size)
            fail("DUPLICATE_SENSOR", "$.sensors")
        value.sensors.forEach {
            if (it.requestedHz != null && (!it.requestedHz.isFinite() || it.requestedHz <= 0.0))
                fail("OUT_OF_RANGE", "$.sensors.requested_hz")
            if (it.measuredHz != null && (!it.measuredHz.isFinite() || it.measuredHz <= 0.0))
                fail("OUT_OF_RANGE", "$.sensors.measured_hz")
        }
        if (!value.sourceConfiguration.foregroundOnly)
            fail("INVARIANT", "$.source_configuration.foreground_only")
        if ((value.calibration.state == CalibrationApplication.APPLIED) != (value.calibration.calibrationId != null))
            fail("INVARIANT", "$.calibration.calibration_id")
        if (value.recordCount != null && value.recordCount < 0) fail("OUT_OF_RANGE", "$.record_count")
        value.channelCounts?.let { counts ->
            if (counts.map { it.channel }.toSet().size != counts.size)
                fail("DUPLICATE_CHANNEL", "$.channel_counts")
            counts.forEach {
                if (!channelToken.matches(it.channel) || it.count < 0) fail("OUT_OF_RANGE", "$.channel_counts")
            }
            if (value.recordCount == null || counts.sumOf { it.count } != value.recordCount)
                fail("INVARIANT", "$.channel_counts")
        }
        when (value.completionState) {
            CompletionState.OPEN -> if (
                value.endState != null || value.endedUtcMs != null || value.clock.endedNs != null ||
                value.recordCount != null || value.channelCounts != null || value.recoveryState != RecoveryState.NONE
            ) fail("INVARIANT", "$.completion_state")
            CompletionState.COMPLETED -> {
                if (value.endState != RecordingEndState.STOPPED || value.clock.endedNs == null || value.recordCount == null)
                    fail("INVARIANT", "$.completion_state")
                if (value.recoveryState != RecoveryState.NONE) fail("INVARIANT", "$.recovery_state")
            }
            CompletionState.INCOMPLETE -> {
                if (value.endState != RecordingEndState.INTERRUPTED) fail("INVARIANT", "$.end_state")
                if (value.recoveryState == RecoveryState.NONE) fail("INVARIANT", "$.recovery_state")
                if (value.recoveryState == RecoveryState.RECOVERED && value.recordCount == null)
                    fail("INVARIANT", "$.record_count")
            }
            CompletionState.FAILED -> if (value.endState != RecordingEndState.FAILED)
                fail("INVARIANT", "$.end_state")
        }
        return value
    }

    private fun nullableString(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE
    private fun nullableDecimal(value: Long?): JsonElement = value?.let { JsonPrimitive(it.toString()) } ?: JsonNull.INSTANCE
    private fun nullableInteger(value: Long?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE
    private fun nullableNumber(value: Double?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE
    private fun nullableBoolean(value: Boolean?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull.INSTANCE

    fun encodeMetadata(value: RecordingMetadata): ByteArray {
        validate(value)
        val root = JsonObject().apply {
            addProperty("recording_contract_version", value.recordingContractVersion)
            addProperty("measurement_contract_version", value.measurementContractVersion)
            addProperty("recording_id", value.recordingId)
            addProperty("acquisition_session_id", value.acquisitionSessionId)
            addProperty("source", value.source.wire)
            addProperty("start_state", value.startState.wire)
            add("end_state", value.endState?.let { JsonPrimitive(it.wire) } ?: JsonNull.INSTANCE)
            addProperty("completion_state", value.completionState.wire)
            addProperty("recovery_state", value.recoveryState.wire)
            add("created_utc_ms", nullableDecimal(value.createdUtcMs))
            add("started_utc_ms", nullableDecimal(value.startedUtcMs))
            add("ended_utc_ms", nullableDecimal(value.endedUtcMs))
            add("clock", JsonObject().apply {
                addProperty("domain", value.clock.domain.wire)
                add("boot_id", nullableString(value.clock.bootId))
                addProperty("session_clock_id", value.clock.sessionClockId)
                addProperty("origin_ns", value.clock.originNs.toString())
                addProperty("started_ns", value.clock.startedNs.toString())
                add("ended_ns", nullableDecimal(value.clock.endedNs))
            })
            add("device", JsonObject().apply {
                add("manufacturer", nullableString(value.device.manufacturer))
                add("model", nullableString(value.device.model))
                add("os_name", nullableString(value.device.osName))
                add("os_version", nullableString(value.device.osVersion))
                add("api_level", nullableInteger(value.device.apiLevel))
            })
            add("application", JsonObject().apply {
                add("package_name", nullableString(value.application.packageName))
                add("version_name", nullableString(value.application.versionName))
                add("version_code", nullableInteger(value.application.versionCode))
                add("source_version", nullableString(value.application.sourceVersion))
            })
            add("sensors", JsonArray().apply {
                value.sensors.forEach { s ->
                    add(JsonObject().apply {
                        addProperty("sensor", s.sensor.wire)
                        add("name", nullableString(s.name))
                        add("vendor", nullableString(s.vendor))
                        addProperty("available", s.available)
                        add("requested_hz", nullableNumber(s.requestedHz))
                        add("measured_hz", nullableNumber(s.measuredHz))
                    })
                }
            })
            add("source_configuration", JsonObject().apply {
                addProperty("location_permission", value.sourceConfiguration.locationPermission.wire)
                add("location_service_enabled", nullableBoolean(value.sourceConfiguration.locationServiceEnabled))
                add("gps_provider_enabled", nullableBoolean(value.sourceConfiguration.gpsProviderEnabled))
                add("network_provider_enabled", nullableBoolean(value.sourceConfiguration.networkProviderEnabled))
                add("satellite_status_enabled", nullableBoolean(value.sourceConfiguration.satelliteStatusEnabled))
                addProperty("foreground_only", value.sourceConfiguration.foregroundOnly)
            })
            add("calibration", JsonObject().apply {
                addProperty("state", value.calibration.state.wire)
                add("calibration_id", nullableString(value.calibration.calibrationId))
            })
            add("record_count", nullableDecimal(value.recordCount))
            add("channel_counts", value.channelCounts?.let { counts ->
                JsonArray().apply {
                    counts.forEach { c ->
                        add(JsonObject().apply {
                            addProperty("channel", c.channel)
                            addProperty("count", c.count.toString())
                        })
                    }
                }
            } ?: JsonNull.INSTANCE)
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_METADATA_BYTES) fail("RESOURCE_LIMIT")
        decodeMetadata(bytes)
        return bytes
    }

    private fun membership(record: Record, metadata: RecordingMetadata): Record {
        validate(metadata)
        if (record.header.contract_version != metadata.measurementContractVersion)
            fail("INVALID_VERSION", "$.measurement_contract_version")
        if (record.header.session_id != metadata.acquisitionSessionId)
            fail("SESSION_MISMATCH", "$.acquisition_session_id")
        if (record.header.source != metadata.source) fail("SOURCE_MISMATCH", "$.source")
        return record
    }

    fun encodeRecord(record: Record, metadata: RecordingMetadata): ByteArray {
        membership(record, metadata)
        return try {
            Codec.encodeJson(record)
        } catch (e: ContractException) {
            throw RecordingContractException(e.code, e.path)
        }
    }

    fun decodeRecord(bytes: ByteArray, metadata: RecordingMetadata): Record {
        val record = try {
            Codec.decodeJson(bytes)
        } catch (e: ContractException) {
            throw RecordingContractException(e.code, e.path)
        }
        return membership(record, metadata)
    }
}
