package com.intelligentdeadreckoning.app

import com.google.gson.JsonParser
import com.intelligentdeadreckoning.contracts.recording.v1.*
import com.intelligentdeadreckoning.contracts.v1.*
import com.intelligentdeadreckoning.contracts.v1.Record
import org.junit.Assert.*
import org.junit.Test

class RecordingContractTest {
    private fun metadata(source: Source = Source.REAL) = RecordingMetadata(
        recordingId = "rec-1",
        acquisitionSessionId = "acq-1",
        source = source,
        startState = RecordingStartState.RECORDING,
        endState = null,
        completionState = CompletionState.OPEN,
        recoveryState = RecoveryState.NONE,
        createdUtcMs = 1_789_900_000_000L,
        startedUtcMs = 1_789_900_000_100L,
        endedUtcMs = null,
        clock = ClockIdentity(
            domain = ClockDomain.ANDROID_ELAPSED_REALTIME_NS,
            bootId = "boot-1",
            sessionClockId = "clock-1",
            originNs = 9_007_199_254_740_993L,
            startedNs = 9_007_199_254_740_993L,
            endedNs = null,
        ),
        device = DeviceInfo("OnePlus", "CPH2585", "Android", "16", 36),
        application = ApplicationInfo(
            "com.intelligentdeadreckoning.app",
            "0.1.0-demo",
            1,
            null,
        ),
        sensors = listOf(
            SensorDescriptor(
                Sensor.ACCELEROMETER,
                "fixture accelerometer",
                "fixture vendor",
                true,
                100.0,
                98.8,
            ),
        ),
        sourceConfiguration = SourceConfiguration(
            LocationPermissionState.PRECISE,
            true,
            true,
            true,
            true,
            true,
        ),
        calibration = CalibrationInfo(CalibrationApplication.NOT_APPLIED, null),
        recordCount = null,
        channelCounts = null,
    )

    private fun gnssRecord(header: Header = Header("acq-1", Source.REAL)) = Record(
        header,
        Event(
            event_id = "0",
            t_ns = 9_007_199_254_740_993L,
            received_ns = 9_007_199_254_741_000L,
            data = GnssMeasurement(
                latitude_deg = 12.9716,
                longitude_deg = 77.5946,
                altitude_m = null,
                altitude_reference = null,
                speed_m_s = null,
                bearing_deg = null,
                horizontal_accuracy_m = 3.5,
                vertical_accuracy_m = null,
                satellites_used = null,
                provider = "gps",
                utc_ms = null,
            ),
        ),
    )

    private fun failure(code: String? = null, action: () -> Unit): RecordingContractException {
        val e = assertThrows(RecordingContractException::class.java, action)
        if (code != null) assertEquals(code, e.code)
        return e
    }

    @Test fun sharedGoldenMetadataDecodesAndRoundTrips() {
        val bytes = javaClass.getResourceAsStream("/golden_metadata.json")!!.readBytes()
        val value = RecordingCodec.decodeMetadata(bytes)
        assertEquals("golden-recording", value.recordingId)
        assertEquals(Source.REAL, value.source)
        assertEquals(9_007_199_254_740_993L, value.clock.originNs)
        assertEquals(value, RecordingCodec.decodeMetadata(RecordingCodec.encodeMetadata(value)))
    }

    @Test fun metadataInt64ValuesRemainDecimalStrings() {
        val raw = JsonParser.parseString(RecordingCodec.encodeMetadata(metadata()).toString(Charsets.UTF_8)).asJsonObject
        assertEquals("9007199254740993", raw.getAsJsonObject("clock")["origin_ns"].asString)
        assertEquals("1789900000000", raw["created_utc_ms"].asString)
        assertTrue(raw["record_count"].isJsonNull)
    }

    @Test fun existingMeasurementContractIsUsedAndNullsRemainNull() {
        val m = metadata()
        val record = gnssRecord()
        val wire = RecordingCodec.encodeRecord(record, m)
        val parsed = RecordingCodec.decodeRecord(wire, m)
        assertEquals(record, parsed)
        val fix = parsed.event.data as GnssMeasurement
        assertNull(fix.speed_m_s)
        assertNull(fix.bearing_deg)
        assertEquals(9_007_199_254_740_993L, parsed.event.t_ns)
    }

    @Test fun recordMembershipRejectsSessionSourceAndVersionMismatch() {
        val m = metadata()
        failure("SESSION_MISMATCH") {
            RecordingCodec.encodeRecord(gnssRecord(Header("other", Source.REAL)), m)
        }
        failure("SOURCE_MISMATCH") {
            RecordingCodec.encodeRecord(gnssRecord(Header("acq-1", Source.SIMULATION)), m)
        }
        failure("INVALID_VERSION") {
            RecordingCodec.encodeRecord(gnssRecord(Header("acq-1", Source.REAL, "9.0.0")), m)
        }
    }

    @Test fun replaySourceMappingIsExplicit() {
        assertEquals(Source.REPLAY_REAL, replaySource(Source.REAL))
        assertEquals(Source.REPLAY_SIMULATION, replaySource(Source.SIMULATION))
        assertThrows(IllegalArgumentException::class.java) { replaySource(Source.REPLAY_REAL) }
        assertThrows(IllegalArgumentException::class.java) { replaySource(Source.REPLAY_SIMULATION) }
    }

    @Test fun unsupportedVersionsAndMalformedMetadataAreRejected() {
        val wire = RecordingCodec.encodeMetadata(metadata()).toString(Charsets.UTF_8)
        failure("INVALID_VERSION") {
            RecordingCodec.decodeMetadata(wire.replace(
                "\"recording_contract_version\":\"1.0.0\"",
                "\"recording_contract_version\":\"2.0.0\"",
            ).toByteArray())
        }
        failure("INVALID_VERSION") {
            RecordingCodec.decodeMetadata(wire.replace(
                "\"measurement_contract_version\":\"1.0.0\"",
                "\"measurement_contract_version\":\"2.0.0\"",
            ).toByteArray())
        }
        listOf("{", "[]", "{\"recording_contract_version\":\"1.0.0\"}").forEach {
            failure { RecordingCodec.decodeMetadata(it.toByteArray()) }
        }
    }

    @Test fun completedZeroRecordSessionIsValid() {
        val m = metadata()
        val complete = m.copy(
            endState = RecordingEndState.STOPPED,
            completionState = CompletionState.COMPLETED,
            endedUtcMs = 1_789_900_001_000L,
            clock = m.clock.copy(endedNs = m.clock.startedNs + 1),
            recordCount = 0,
            channelCounts = emptyList(),
        )
        assertEquals(complete, RecordingCodec.decodeMetadata(RecordingCodec.encodeMetadata(complete)))
    }

    @Test fun incompleteRecoveryAndCountsAreExplicit() {
        val m = metadata()
        val recovered = m.copy(
            endState = RecordingEndState.INTERRUPTED,
            completionState = CompletionState.INCOMPLETE,
            recoveryState = RecoveryState.RECOVERED,
            recordCount = 2,
            channelCounts = listOf(ChannelCount("accelerometer", 2)),
        )
        assertEquals(recovered, RecordingCodec.decodeMetadata(RecordingCodec.encodeMetadata(recovered)))
        failure("INVARIANT") { RecordingCodec.encodeMetadata(recovered.copy(recordCount = 3)) }
    }

    @Test fun projectCalibrationAndOriginalSourceCannotBeInvented() {
        failure("INVALID_ENUM") { RecordingCodec.encodeMetadata(metadata(Source.REPLAY_REAL)) }
        failure("INVARIANT") {
            RecordingCodec.encodeMetadata(
                metadata().copy(calibration = CalibrationInfo(CalibrationApplication.APPLIED, null)),
            )
        }
    }
}
