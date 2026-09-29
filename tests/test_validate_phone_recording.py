"""The device acceptance tool must report the same verdict as the reader it wraps.

Only the local path is exercised here: the device path needs a phone, and it reuses
`open_stream`, which the session reader tests already cover.
"""
from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

from contracts.v1.models import (
    DeviceFrame,
    Event,
    GnssMeasurement,
    Header,
    ImuMeasurement,
    ImuUnit,
    Record,
    Sensor,
    SensorAccuracy,
    Severity,
    Source,
    Vector3,
    DiagnosticEvent,
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
from tools.validate_phone_recording import local_report, main

BASE_NS = 9_007_199_254_740_993
SECOND_NS = 1_000_000_000


def imu(event_id, t_ns, *, sensor=Sensor.ACCELEROMETER, received_ns=None):
    unit = {
        Sensor.ACCELEROMETER: ImuUnit.METRES_PER_SECOND_SQUARED,
        Sensor.GYROSCOPE: ImuUnit.RADIANS_PER_SECOND,
    }[sensor]
    return Record(
        Header("acq-1", Source.REAL, "1.0.0"),
        Event(event_id, t_ns, t_ns if received_ns is None else received_ns, ImuMeasurement(
            sensor=sensor,
            frame=DeviceFrame.ANDROID_DEVICE,
            unit=unit,
            xyz=Vector3(0.0, 0.0, 9.80665),
            accuracy=SensorAccuracy.HIGH,
        )),
    )


def gnss(event_id, t_ns, *, speed=None):
    return Record(
        Header("acq-1", Source.REAL, "1.0.0"),
        Event(event_id, t_ns, t_ns, GnssMeasurement(
            latitude_deg=17.4,
            longitude_deg=78.4,
            altitude_m=None,
            altitude_reference=None,
            speed_m_s=speed,
            bearing_deg=None,
            horizontal_accuracy_m=4.0,
            vertical_accuracy_m=None,
            satellites_used=9,
            provider="fixture",
            utc_ms=None,
        )),
    )


def diagnostic(event_id, t_ns, code):
    return Record(
        Header("acq-1", Source.REAL, "1.0.0"),
        Event(event_id, t_ns, t_ns, DiagnosticEvent(
            severity=Severity.WARNING, code=code, message="Synthetic fixture", dropped_count=0
        )),
    )


def metadata(records, *, recording_id="rec-1"):
    tally: dict[str, int] = {}
    for record in records:
        tally[record.event.data.TYPE] = tally.get(record.event.data.TYPE, 0) + 1
    return RecordingMetadata(
        recording_id=recording_id,
        acquisition_session_id="acq-1",
        source=Source.REAL,
        start_state=RecordingStartState.RECORDING,
        end_state=RecordingEndState.STOPPED,
        completion_state=CompletionState.COMPLETED,
        recovery_state=RecoveryState.NONE,
        created_utc_ms=1,
        started_utc_ms=2,
        ended_utc_ms=3,
        clock=ClockIdentity(
            domain=ClockDomain.ANDROID_ELAPSED_REALTIME_NS,
            boot_id="boot-1",
            session_clock_id="clock-1",
            origin_ns=BASE_NS,
            started_ns=BASE_NS,
            ended_ns=BASE_NS + 2 * SECOND_NS,
        ),
        device=DeviceInfo("OnePlus", "CPH2585", "Android", "16", 36),
        application=ApplicationInfo("com.intelligentdeadreckoning.app", "0.1.0-demo", 1, None),
        sensors=(SensorDescriptor(Sensor.ACCELEROMETER, "a", "v", True, 100.0, 98.8),),
        source_configuration=SourceConfiguration(
            LocationPermissionState.PRECISE, True, True, True, True, True
        ),
        calibration=CalibrationInfo(CalibrationApplication.NOT_APPLIED, None),
        record_count=len(records),
        channel_counts=tuple(ChannelCount(k, v) for k, v in sorted(tally.items())),
    )


class ToolFixture(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def write(self, records, name="rec-1"):
        directory = self.root / name
        directory.mkdir()
        meta = metadata(records, recording_id=name)
        (directory / "metadata.json").write_bytes(encode_metadata(meta))
        (directory / "measurements.jsonl").write_bytes(
            b"".join(encode_record(record, meta) + b"\n" for record in records)
        )
        return directory


class LocalReportTest(ToolFixture):
    def test_report_counts_channels_and_rates(self):
        records = [imu("0", BASE_NS), imu("1", BASE_NS + SECOND_NS),
                   imu("2", BASE_NS + SECOND_NS, sensor=Sensor.GYROSCOPE)]
        report = local_report(self.write(records))
        self.assertEqual(report["metadata"]["completion"], "completed")
        self.assertEqual(report["metadata"]["duration_s"], 2.0)
        self.assertEqual(report["counts"], {"imu": 3})
        self.assertEqual(report["bytes"], (self.root / "rec-1" / "metadata.json").stat().st_size
                         + (self.root / "rec-1" / "measurements.jsonl").stat().st_size)
        self.assertEqual(report["channels"]["accelerometer"]["count"], 2)
        self.assertEqual(report["channels"]["accelerometer"]["span_s"], 1.0)
        self.assertEqual(report["channels"]["accelerometer"]["rate_hz"], 1.0)
        self.assertEqual(report["channels"]["gyroscope"]["count"], 1)
        self.assertIsNone(report["channels"]["gyroscope"]["rate_hz"])

    def test_report_surfaces_diagnostics_and_gnss_coverage(self):
        records = [
            gnss("0", BASE_NS, speed=12.5),
            gnss("1", BASE_NS + 1),
            diagnostic("2", BASE_NS + 2, "TIME_GAP"),
            diagnostic("3", BASE_NS + 3, "TIME_GAP"),
            diagnostic("4", BASE_NS + 4, "SENSOR_DROPOUT"),
        ]
        report = local_report(self.write(records))
        self.assertEqual(report["diagnostics"], {"SENSOR_DROPOUT": 1, "TIME_GAP": 2})
        self.assertEqual(report["gnss_null_counts"], {"altitude_m": 2, "bearing_deg": 2, "speed_m_s": 1})

    def test_report_measures_receipt_delay(self):
        records = [imu("0", BASE_NS, received_ns=BASE_NS + 7_000_000)]
        report = local_report(self.write(records))
        self.assertEqual(report["channels"]["accelerometer"]["max_receipt_delay_ms"], 7.0)
        self.assertEqual(report["channels"]["accelerometer"]["late_count"], 0)

    def test_report_counts_rows_past_the_reorder_window(self):
        """The 100 ms policy is only evidence if the rows that break it are counted."""
        records = [
            imu("0", BASE_NS, received_ns=BASE_NS + 99_000_000),
            imu("1", BASE_NS + 1, received_ns=BASE_NS + 1 + 101_000_000),
            imu("2", BASE_NS + 2, received_ns=BASE_NS + 2 + 100_000_000),
        ]
        report = local_report(self.write(records))
        stats = report["channels"]["accelerometer"]
        self.assertEqual(stats["count"], 3)
        self.assertEqual(stats["late_count"], 1)
        self.assertEqual(stats["max_receipt_delay_ms"], 101.0)


class MainTest(ToolFixture):
    def run_main(self, argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = main(argv)
        return code, out.getvalue()

    def test_accepted_session_exits_zero_with_json(self):
        directory = self.write([imu("0", BASE_NS), imu("1", BASE_NS + 1)])
        code, printed = self.run_main(["--local", str(directory)])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(printed)["counts"], {"imu": 2})

    def test_contract_violation_exits_three_with_the_code(self):
        directory = self.write([imu("7", BASE_NS), imu("7", BASE_NS + 1)])
        code, printed = self.run_main(["--local", str(directory)])
        self.assertEqual(code, 3)
        payload = json.loads(printed)
        self.assertEqual(payload["rejected"], "DUPLICATE_EVENT")
        self.assertEqual(payload["line"], 2)

    def test_unsafe_device_id_exits_three_without_touching_adb(self):
        code, printed = self.run_main(["SERIAL", "../etc/passwd"])
        self.assertEqual(code, 3)
        self.assertIn("usage_error", json.loads(printed))

    def test_missing_source_is_a_usage_error(self):
        with self.assertRaises(SystemExit) as raised:
            with contextlib.redirect_stderr(io.StringIO()):
                main([])
        self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
