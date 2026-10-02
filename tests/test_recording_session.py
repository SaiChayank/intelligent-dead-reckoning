"""Session-directory reader tests. Synthetic fixtures only; no raw dataset is required.

These mirror the device-side replay reader, so a Kotlin/Python disagreement about a
session shows up here rather than on a phone.
"""
from __future__ import annotations

import io
import json
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from contracts.v1.models import (
    DeviceFrame,
    DiagnosticEvent,
    Event,
    GnssMeasurement,
    Header,
    ImuMeasurement,
    ImuUnit,
    InitializationMode,
    NavigationState,
    NavigationStatus,
    Record,
    Sensor,
    SensorAccuracy,
    Severity,
    Source,
    Vector3,
)
from contracts.recording.v1.codec import encode_metadata, encode_record
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
)
from contracts.recording.v1.session import (
    MAX_RECORDS,
    SessionError,
    inspect_session,
    open_session,
    open_stream,
    read_session,
)

#: Above 2^53: must survive exactly, never via binary64.
BASE_NS = 9_007_199_254_740_993
SECOND_NS = 1_000_000_000

#: The measurement codec pairs each sensor with exactly one unit.
UNIT_BY_SENSOR = {
    Sensor.ACCELEROMETER: ImuUnit.METRES_PER_SECOND_SQUARED,
    Sensor.GRAVITY: ImuUnit.METRES_PER_SECOND_SQUARED,
    Sensor.GYROSCOPE: ImuUnit.RADIANS_PER_SECOND,
    Sensor.MAGNETOMETER: ImuUnit.MICROTESLA,
}


def imu_record(event_id, t_ns, *, session="acq-1", source=Source.REAL, version="1.0.0",
               sensor=Sensor.ACCELEROMETER, received_ns=None):
    return Record(
        Header(session, source, version),
        Event(
            event_id,
            t_ns,
            t_ns if received_ns is None else received_ns,
            ImuMeasurement(
                sensor=sensor,
                frame=DeviceFrame.ANDROID_DEVICE,
                unit=UNIT_BY_SENSOR[sensor],
                xyz=Vector3(0.0, 0.0, 9.80665),
                accuracy=SensorAccuracy.HIGH,
            ),
        ),
    )


def diagnostic_record(event_id, t_ns, *, session="acq-1", source=Source.REAL, version="1.0.0",
                      code="TIME_GAP", message="Synthetic fixture"):
    return Record(
        Header(session, source, version),
        Event(
            event_id,
            t_ns,
            t_ns,
            DiagnosticEvent(severity=Severity.INFO, code=code, message=message, dropped_count=0),
        ),
    )


def navigation_record(event_id, t_ns, mode, *, session="acq-1", source=Source.REAL, version="1.0.0"):
    return Record(
        Header(session, source, version),
        Event(
            event_id,
            t_ns,
            t_ns,
            NavigationState(
                status=NavigationStatus.UNINITIALIZED,
                initialization_mode=mode,
                origin_wgs84_deg_m=None,
                position_enu_m=None,
                velocity_enu_m_s=None,
                q_enu_from_vehicle_wxyz=None,
                heading_deg=None,
                calibration_id=None,
                gnss_used_after_initialization=False,
                localization_mode=None,
            ),
        ),
    )


def session_metadata(
    records,
    *,
    recording_id="rec-1",
    session="acq-1",
    source=Source.REAL,
    completion=CompletionState.COMPLETED,
    recovery=RecoveryState.NONE,
    record_count=None,
    channel_counts=True,
    origin_ns=BASE_NS,
    started_ns=None,
    ended_ns=None,
):
    """Metadata consistent with `records`, unless an override makes it invalid on purpose."""
    started = origin_ns if started_ns is None else started_ns
    if ended_ns is None:
        ended_ns = started + SECOND_NS
    end_state = {
        CompletionState.COMPLETED: RecordingEndState.STOPPED,
        CompletionState.OPEN: None,
        CompletionState.INCOMPLETE: RecordingEndState.INTERRUPTED,
        CompletionState.FAILED: RecordingEndState.FAILED,
    }[completion]
    if completion is CompletionState.OPEN:
        counts = None
        count = None
        ended_ns = None
    else:
        count = len(records) if record_count is None else record_count
        if channel_counts is True:
            tally: dict[str, int] = {}
            for record in records:
                key = record.event.data.TYPE
                tally[key] = tally.get(key, 0) + 1
            counts = tuple(ChannelCount(k, v) for k, v in sorted(tally.items())) or None
        elif channel_counts is None:
            counts = None
        else:
            counts = channel_counts
    return RecordingMetadata(
        recording_id=recording_id,
        acquisition_session_id=session,
        source=source,
        start_state=RecordingStartState.RECORDING,
        end_state=end_state,
        completion_state=completion,
        recovery_state=recovery,
        created_utc_ms=1_789_900_000_000,
        started_utc_ms=1_789_900_000_100 if completion is not CompletionState.OPEN else None,
        ended_utc_ms=1_789_900_001_000 if completion is not CompletionState.OPEN else None,
        clock=ClockIdentity(
            domain=ClockDomain.ANDROID_ELAPSED_REALTIME_NS,
            boot_id="boot-1",
            session_clock_id="clock-1",
            origin_ns=origin_ns,
            started_ns=started,
            ended_ns=ended_ns,
        ),
        device=DeviceInfo("OnePlus", "CPH2585", "Android", "16", 36),
        application=ApplicationInfo("com.intelligentdeadreckoning.app", "0.1.0-demo", 1, None),
        sensors=(
            SensorDescriptor(Sensor.ACCELEROMETER, "fixture accelerometer", "fixture vendor", True, 100.0, 98.8),
        ),
        source_configuration=SourceConfiguration(
            LocationPermissionState.PRECISE, True, True, True, True, True
        ),
        calibration=CalibrationInfo(CalibrationApplication.NOT_APPLIED, None),
        record_count=count,
        channel_counts=counts,
    )


class SessionFixture(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

    def write(self, records, *, name="rec-1", measurements=None, extra=None, **overrides):
        """Create one session directory. `measurements` replaces the encoded rows verbatim."""
        directory = self.root / name
        directory.mkdir()
        metadata = session_metadata(records, recording_id=overrides.pop("recording_id", name), **overrides)
        (directory / "metadata.json").write_bytes(encode_metadata(metadata))
        if measurements is None:
            body = b"".join(encode_record(record, metadata) + b"\n" for record in records)
        else:
            body = measurements
        (directory / "measurements.jsonl").write_bytes(body)
        if extra is not None:
            (directory / extra).write_bytes(b"leftover")
        return directory, metadata

    def assertSessionError(self, code, directory, **kwargs):
        with self.assertRaises(SessionError) as raised:
            list(read_session(directory, **kwargs))
        self.assertEqual(raised.exception.code, code, str(raised.exception))
        return raised.exception


class SessionReaderTest(SessionFixture):
    def test_completed_session_streams_in_arrival_order_and_maps_replay_source(self):
        records = [
            imu_record("0", BASE_NS),
            diagnostic_record("2", BASE_NS + 1),
            imu_record("1", BASE_NS + 2, sensor=Sensor.GYROSCOPE),
        ]
        directory, _ = self.write(records)
        summary = inspect_session(directory)
        self.assertTrue(summary.replayable)
        self.assertTrue(summary.exportable)
        self.assertIsNone(summary.error)
        self.assertEqual(summary.duration_ns, SECOND_NS)
        read = list(read_session(directory))
        self.assertEqual([r.event.event_id for r in read], ["0", "2", "1"])
        self.assertTrue(all(r.header.source is Source.REPLAY_REAL for r in read))
        self.assertEqual(read[2].event.data.sensor, Sensor.GYROSCOPE)

    def test_simulation_session_maps_to_replay_simulation(self):
        directory, _ = self.write(
            [imu_record("0", BASE_NS, source=Source.SIMULATION)],
            source=Source.SIMULATION,
        )
        read = list(read_session(directory))
        self.assertEqual(read[0].header.source, Source.REPLAY_SIMULATION)

    def test_exact_int64_timestamps_survive_above_2_53(self):
        received = BASE_NS + 30 * SECOND_NS
        directory, _ = self.write(
            [imu_record("0", BASE_NS, received_ns=received)],
            ended_ns=BASE_NS + 60 * SECOND_NS,
        )
        record = list(read_session(directory))[0]
        self.assertEqual(record.event.t_ns, 9_007_199_254_740_993)
        self.assertEqual(record.event.received_ns, 9_007_229_254_740_993)
        self.assertNotEqual(record.event.received_ns, int(float(received)))

    def test_final_record_without_trailing_newline_is_accepted(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1)]
        metadata = session_metadata(records)
        body = encode_record(records[0], metadata) + b"\n" + encode_record(records[1], metadata)
        directory, _ = self.write(records, measurements=body)
        self.assertEqual(len(list(read_session(directory))), 2)

    def test_duplicate_event_id_is_rejected_with_line(self):
        records = [imu_record("7", BASE_NS), imu_record("7", BASE_NS + 1)]
        directory, _ = self.write(records)
        error = self.assertSessionError("DUPLICATE_EVENT", directory)
        self.assertEqual(error.line, 2)

    def test_blank_line_is_rejected(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1)]
        metadata = session_metadata(records)
        body = encode_record(records[0], metadata) + b"\n\n" + encode_record(records[1], metadata) + b"\n"
        directory, _ = self.write(records, measurements=body)
        error = self.assertSessionError("MALFORMED_JSON", directory)
        self.assertEqual(error.line, 2)

    def test_interior_corruption_is_rejected_with_line_number(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1), imu_record("2", BASE_NS + 2)]
        metadata = session_metadata(records)
        body = b"".join(encode_record(r, metadata) + b"\n" for r in records)
        lines = body.split(b"\n")
        lines[1] = lines[1][:20]  # truncate the middle row, not the last
        directory, _ = self.write(records, measurements=b"\n".join(lines))
        error = self.assertSessionError("MALFORMED_JSON", directory)
        self.assertEqual(error.line, 2)

    def test_truncated_final_record_is_rejected(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1)]
        metadata = session_metadata(records)
        body = encode_record(records[0], metadata) + b"\n" + encode_record(records[1], metadata)[:25]
        directory, _ = self.write(records, measurements=body)
        error = self.assertSessionError("MALFORMED_JSON", directory)
        self.assertEqual(error.line, 2)

    def test_oversized_record_is_rejected(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, measurements=b"x" * 70_000 + b"\n")
        error = self.assertSessionError("RECORD_TOO_LARGE", directory)
        self.assertEqual(error.line, 1)

    def test_record_count_mismatch_is_rejected(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1)]
        # `channel_counts=None` keeps the metadata self-consistent so only the count is wrong.
        directory, _ = self.write(records, record_count=3, channel_counts=None)
        self.assertSessionError("RECORD_COUNT_MISMATCH", directory)

    def test_channel_count_mismatch_is_rejected(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1)]
        directory, _ = self.write(records, channel_counts=(ChannelCount("imu", 2), ChannelCount("gnss", 0)))
        # A zero-count channel is ignored, so this session still validates.
        self.assertEqual(len(list(read_session(directory))), 2)
        directory, _ = self.write(
            records,
            name="rec-2",
            channel_counts=(ChannelCount("imu", 1), ChannelCount("gnss", 1)),
        )
        self.assertSessionError("CHANNEL_COUNT_MISMATCH", directory)

    def test_session_source_and_version_membership_is_enforced(self):
        for code, header in (
            ("SESSION_MISMATCH", Header("other", Source.REAL)),
            ("SOURCE_MISMATCH", Header("acq-1", Source.SIMULATION)),
        ):
            with self.subTest(code=code):
                foreign = Record(header, imu_record("0", BASE_NS).event)
                body = encode_record(
                    foreign,
                    session_metadata([foreign], session=header.session_id, source=header.source),
                ) + b"\n"
                directory, _ = self.write(
                    [imu_record("0", BASE_NS)],
                    name=f"rec-{code.lower()}",
                    measurements=body,
                )
                self.assertSessionError(code, directory)

        with self.subTest(code="INVALID_VERSION"):
            # The writer refuses a foreign version, so the row is forged at the byte level.
            records = [imu_record("0", BASE_NS)]
            line = encode_record(records[0], session_metadata(records))
            body = line.replace(b'"contract_version":"1.0.0"', b'"contract_version":"9.0.0"')
            self.assertNotEqual(body, line, "the version field must be present to rewrite")
            directory, _ = self.write(records, name="rec-version", measurements=body + b"\n")
            self.assertSessionError("INVALID_VERSION", directory)

    def test_a_rejected_stream_releases_its_file_handle(self):
        records = [imu_record("7", BASE_NS), imu_record("7", BASE_NS + 1)]
        directory, _ = self.write(records)
        content = open_session(directory)
        try:
            list(content.records)
        except SessionError:
            pass
        # On Windows an unreleased handle would also block deleting the session.
        self.assertTrue(content.validator.closed)
        content.close()

    def test_an_exhausted_stream_releases_its_file_handle(self):
        directory, _ = self.write([imu_record("0", BASE_NS)])
        content = open_session(directory)
        self.assertEqual(len(list(content.records)), 1)
        self.assertTrue(content.validator.closed)

    def test_a_non_directory_stream_is_validated_by_the_same_rules(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1)]
        metadata = session_metadata(records)
        body = b"".join(encode_record(record, metadata) + b"\n" for record in records)
        content = open_stream(io.BytesIO(body), metadata)
        self.assertEqual([r.event.event_id for r in content.records], ["0", "1"])
        self.assertTrue(content.validator.closed)

    def test_a_non_directory_stream_takes_ownership_of_its_handle(self):
        records = [imu_record("7", BASE_NS), imu_record("7", BASE_NS + 1)]
        metadata = session_metadata(records)
        handle = io.BytesIO(b"".join(encode_record(r, metadata) + b"\n" for r in records))
        with self.assertRaises(SessionError) as raised:
            list(open_stream(handle, metadata).records)
        self.assertEqual(raised.exception.code, "DUPLICATE_EVENT")
        self.assertTrue(handle.closed)

    def test_a_stream_the_validator_refuses_is_released_before_it_is_returned(self):
        oversized = session_metadata([], record_count=MAX_RECORDS + 1, channel_counts=None)
        handle = io.BytesIO(b"")
        with self.assertRaises(SessionError) as raised:
            open_stream(handle, oversized)
        self.assertEqual(raised.exception.code, "REPLAY_RECORD_LIMIT")
        self.assertTrue(handle.closed)

    def test_pre_session_record_is_rejected(self):
        directory, _ = self.write([imu_record("0", BASE_NS - 1)])
        self.assertSessionError("PRE_SESSION_RECORD", directory)

    def test_end_time_before_last_receipt_is_rejected(self):
        records = [imu_record("0", BASE_NS, received_ns=BASE_NS + 5 * SECOND_NS)]
        directory, _ = self.write(records, ended_ns=BASE_NS + SECOND_NS)
        self.assertSessionError("END_TIME_MISMATCH", directory)

    def test_navigation_mode_switch_is_rejected(self):
        records = [
            navigation_record("0", BASE_NS, InitializationMode.EVALUATION),
            navigation_record("1", BASE_NS + 1, InitializationMode.DEPLOYABLE),
        ]
        directory, _ = self.write(records)
        error = self.assertSessionError("INITIALIZATION_MODE_CHANGED", directory)
        self.assertEqual(error.line, 2)

    def test_empty_completed_session_is_valid(self):
        directory, _ = self.write([])
        summary = inspect_session(directory)
        self.assertTrue(summary.replayable)
        self.assertEqual(summary.duration_ns, SECOND_NS)
        self.assertEqual(list(read_session(directory)), [])


class SessionEligibilityTest(SessionFixture):
    def test_open_session_is_not_replayable_but_is_inspectable(self):
        directory, _ = self.write([], completion=CompletionState.OPEN)
        summary = inspect_session(directory)
        self.assertFalse(summary.replayable)
        self.assertFalse(summary.exportable)
        self.assertIsNone(summary.duration_ns)
        with self.assertRaises(SessionError):
            open_session(directory)
        content = open_session(directory, require_replayable=False)
        self.assertIsNone(content.metadata.record_count)
        with self.assertRaises(SessionError) as raised:
            list(content.records)
        self.assertEqual(raised.exception.code, "SESSION_NOT_FINALIZED")

    def test_recovery_required_session_is_neither_replayable_nor_exportable(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, completion=CompletionState.INCOMPLETE,
                                  recovery=RecoveryState.REQUIRED)
        summary = inspect_session(directory)
        self.assertFalse(summary.replayable)
        self.assertFalse(summary.exportable)
        with self.assertRaises(SessionError) as raised:
            open_session(directory)
        self.assertEqual(raised.exception.code, "SESSION_NOT_REPLAYABLE")

    def test_unrecoverable_session_is_not_replayable(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, completion=CompletionState.INCOMPLETE,
                                  recovery=RecoveryState.UNRECOVERABLE)
        self.assertFalse(inspect_session(directory).replayable)

    def test_failed_session_is_not_replayable(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, completion=CompletionState.FAILED)
        summary = inspect_session(directory)
        self.assertFalse(summary.replayable)
        self.assertTrue(summary.exportable)

    def test_incomplete_recovered_session_reads_with_visible_flag(self):
        records = [imu_record("0", BASE_NS), imu_record("1", BASE_NS + 1)]
        directory, _ = self.write(records, completion=CompletionState.INCOMPLETE,
                                  recovery=RecoveryState.RECOVERED)
        summary = inspect_session(directory)
        self.assertTrue(summary.incomplete)
        self.assertTrue(summary.replayable)
        self.assertEqual(len(list(read_session(directory))), 2)

    def test_recording_id_must_match_directory_name(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, recording_id="something-else")
        summary = inspect_session(directory)
        self.assertIsNotNone(summary.error)
        self.assertIn("ID_MISMATCH", summary.error)
        with self.assertRaises(SessionError):
            read_session(directory)

    def test_open_session_reports_the_specific_inspection_failure(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, recording_id="something-else")
        with self.assertRaises(SessionError) as mismatched:
            open_session(directory)
        self.assertEqual(mismatched.exception.code, "ID_MISMATCH")
        unsafe = self.root / "not a session id"
        unsafe.mkdir()
        with self.assertRaises(SessionError) as bad_name:
            open_session(unsafe)
        self.assertEqual(bad_name.exception.code, "UNSAFE_PATH")
        with self.assertRaises(SessionError) as absent:
            open_session(self.root / "absent")
        self.assertEqual(absent.exception.code, "MISSING_SESSION")

    def test_unsafe_directory_name_is_rejected(self):
        directory = self.root / "not a session id"
        directory.mkdir()
        self.assertIn("UNSAFE_PATH", inspect_session(directory).error)

    def test_oversized_declared_count_is_rejected_before_reading(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, record_count=MAX_RECORDS + 1, channel_counts=None)
        with self.assertRaises(SessionError) as raised:
            open_session(directory)
        self.assertEqual(raised.exception.code, "REPLAY_RECORD_LIMIT")

    def test_extra_entries_are_reported_not_fatal(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records, extra="metadata.json.tmp")
        summary = inspect_session(directory)
        self.assertTrue(summary.replayable)
        self.assertEqual(summary.extra_entries, ("metadata.json.tmp",))
        self.assertEqual(len(list(read_session(directory))), 1)

    def test_missing_measurements_artifact_is_reported(self):
        records = [imu_record("0", BASE_NS)]
        directory, _ = self.write(records)
        (directory / "measurements.jsonl").unlink()
        self.assertIn("MISSING_ARTIFACT", inspect_session(directory).error)

    def test_symlinked_artifacts_are_rejected(self):
        records = [imu_record("0", BASE_NS)]
        # The metadata names the link's directory, so only the symlink can be rejected.
        directory, _ = self.write(records, name="rec-real", recording_id="rec-link")
        link = self.root / "rec-link"
        link.mkdir()
        (link / "metadata.json").write_bytes((directory / "metadata.json").read_bytes())
        try:
            (link / "measurements.jsonl").symlink_to(directory / "measurements.jsonl")
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable in this environment")
        summary = inspect_session(link)
        self.assertIn("UNSAFE_PATH", summary.error)

    def test_missing_directory_is_reported(self):
        self.assertIn("MISSING_SESSION", inspect_session(self.root / "absent").error)


if __name__ == "__main__":
    unittest.main()
