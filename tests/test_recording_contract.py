"""Recording-contract tests only; no files are opened by contract imports."""
import json
from dataclasses import replace
from pathlib import Path
import unittest

from contracts.v1.models import (
    AltitudeReference,
    Event,
    GnssMeasurement,
    Header,
    Record,
    Sensor,
    Source,
)
from contracts.recording.v1.codec import (
    RecordingContractError,
    decode_metadata,
    decode_record,
    encode_metadata,
    encode_record,
)
from contracts.recording.v1.models import (
    ApplicationInfo,
    CalibrationApplication,
    CalibrationInfo,
    ChannelCount,
    ClockDomain,
    ClockIdentity,
    CompletionState,
    DeviceInfo,
    LocationPermissionState,
    RecordingEndState,
    RecordingMetadata,
    RecordingStartState,
    RecoveryState,
    SensorDescriptor,
    SourceConfiguration,
    replay_source,
)

ROOT = Path(__file__).resolve().parents[1] / "contracts" / "recording" / "v1"


def metadata(source=Source.REAL):
    return RecordingMetadata(
        recording_id="rec-1",
        acquisition_session_id="acq-1",
        source=source,
        start_state=RecordingStartState.RECORDING,
        end_state=None,
        completion_state=CompletionState.OPEN,
        recovery_state=RecoveryState.NONE,
        created_utc_ms=1789900000000,
        started_utc_ms=1789900000100,
        ended_utc_ms=None,
        clock=ClockIdentity(
            domain=ClockDomain.ANDROID_ELAPSED_REALTIME_NS,
            boot_id="boot-1",
            session_clock_id="clock-1",
            origin_ns=9007199254740993,
            started_ns=9007199254740993,
            ended_ns=None,
        ),
        device=DeviceInfo("OnePlus", "CPH2585", "Android", "16", 36),
        application=ApplicationInfo(
            "com.intelligentdeadreckoning.app", "0.1.0-demo", 1, None
        ),
        sensors=(
            SensorDescriptor(
                Sensor.ACCELEROMETER,
                "fixture accelerometer",
                "fixture vendor",
                True,
                100.0,
                98.8,
            ),
        ),
        source_configuration=SourceConfiguration(
            LocationPermissionState.PRECISE,
            True,
            True,
            True,
            True,
            True,
        ),
        calibration=CalibrationInfo(CalibrationApplication.NOT_APPLIED, None),
        record_count=None,
        channel_counts=None,
    )


class RecordingContractTest(unittest.TestCase):
    def test_shared_golden_metadata_decodes_and_round_trips(self):
        raw = (ROOT / "golden_metadata.json").read_bytes()
        value = decode_metadata(raw)
        self.assertEqual(value.recording_id, "golden-recording")
        self.assertEqual(value.source, Source.REAL)
        self.assertEqual(value.clock.origin_ns, 9007199254740993)
        self.assertEqual(decode_metadata(encode_metadata(value)), value)

    def test_metadata_int64_values_are_decimal_strings(self):
        raw = json.loads(encode_metadata(metadata()))
        self.assertEqual(raw["clock"]["origin_ns"], "9007199254740993")
        self.assertEqual(raw["created_utc_ms"], "1789900000000")
        self.assertIsNone(raw["record_count"])

    def test_measurement_record_uses_existing_contract_and_preserves_nulls(self):
        value = metadata()
        record = Record(
            Header("acq-1", Source.REAL),
            Event(
                "0",
                9007199254740993,
                9007199254741000,
                GnssMeasurement(
                    latitude_deg=12.9716,
                    longitude_deg=77.5946,
                    altitude_m=None,
                    altitude_reference=None,
                    speed_m_s=None,
                    bearing_deg=None,
                    horizontal_accuracy_m=3.5,
                    vertical_accuracy_m=None,
                    satellites_used=None,
                    provider="gps",
                    utc_ms=None,
                ),
            ),
        )
        wire = encode_record(record, value)
        parsed = decode_record(wire, value)
        self.assertEqual(parsed, record)
        self.assertIsNone(parsed.event.data.speed_m_s)
        self.assertIsNone(parsed.event.data.bearing_deg)
        self.assertEqual(parsed.event.t_ns, 9007199254740993)

    def test_record_membership_rejects_session_source_and_version_mismatch(self):
        value = metadata()
        payload = GnssMeasurement(
            1.0, 2.0, None, None, None, None, None, None, None, "gps", None
        )
        base = Record(Header("acq-1", Source.REAL), Event("0", 1, 1, payload))
        for record, code in (
            (replace(base, header=Header("other", Source.REAL)), "SESSION_MISMATCH"),
            (replace(base, header=Header("acq-1", Source.SIMULATION)), "SOURCE_MISMATCH"),
            (replace(base, header=Header("acq-1", Source.REAL, "9.0.0")), "INVALID_VERSION"),
        ):
            with self.subTest(code=code):
                with self.assertRaises(RecordingContractError) as raised:
                    encode_record(record, value)
                self.assertEqual(raised.exception.code, code)

    def test_replay_source_mapping_is_explicit(self):
        self.assertEqual(replay_source(Source.REAL), Source.REPLAY_REAL)
        self.assertEqual(replay_source(Source.SIMULATION), Source.REPLAY_SIMULATION)
        for replay in (Source.REPLAY_REAL, Source.REPLAY_SIMULATION):
            with self.assertRaises(ValueError):
                replay_source(replay)

    def test_unsupported_versions_and_malformed_metadata_are_rejected(self):
        raw = json.loads(encode_metadata(metadata()))
        for key in ("recording_contract_version", "measurement_contract_version"):
            changed = dict(raw)
            changed[key] = "2.0.0"
            with self.subTest(key=key):
                with self.assertRaises(RecordingContractError) as raised:
                    decode_metadata(json.dumps(changed).encode())
                self.assertEqual(raised.exception.code, "INVALID_VERSION")
        for bad in (b"{", b"[]", b'{"recording_contract_version":"1.0.0"}'):
            with self.assertRaises(RecordingContractError):
                decode_metadata(bad)

    def test_completed_zero_record_session_is_valid(self):
        value = metadata()
        completed = replace(
            value,
            end_state=RecordingEndState.STOPPED,
            completion_state=CompletionState.COMPLETED,
            ended_utc_ms=1789900001000,
            clock=replace(value.clock, ended_ns=value.clock.started_ns + 1),
            record_count=0,
            channel_counts=(),
        )
        self.assertEqual(decode_metadata(encode_metadata(completed)), completed)

    def test_incomplete_recovery_and_count_invariants_are_explicit(self):
        value = metadata()
        recovered = replace(
            value,
            end_state=RecordingEndState.INTERRUPTED,
            completion_state=CompletionState.INCOMPLETE,
            recovery_state=RecoveryState.RECOVERED,
            record_count=2,
            channel_counts=(ChannelCount("accelerometer", 2),),
        )
        self.assertEqual(decode_metadata(encode_metadata(recovered)), recovered)
        with self.assertRaises(RecordingContractError):
            encode_metadata(replace(recovered, record_count=3))

    def test_project_calibration_and_original_source_are_not_invented(self):
        value = metadata()
        with self.assertRaises(RecordingContractError):
            encode_metadata(replace(value, source=Source.REPLAY_REAL))
        with self.assertRaises(RecordingContractError):
            encode_metadata(
                replace(
                    value,
                    calibration=CalibrationInfo(CalibrationApplication.APPLIED, None),
                )
            )


if __name__ == "__main__":
    unittest.main()
