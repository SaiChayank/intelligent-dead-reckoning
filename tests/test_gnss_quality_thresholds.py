"""The GNSS threshold tool must measure what it claims, on bytes it did not write casually.

The state machine's thresholds are only as good as this evidence, so the measurement is
itself tested: the percentile method is fixed, a directory that is not a session is
skipped, a rejected session is reported instead of crashing the run, and inter-fix
intervals are formed inside one session because consecutive recordings are days apart.
The last of those was a real defect in the first version of the tool: pooling a channel
across sessions reported a 419,976 s "fix age" that is a property of the corpus, not of
any provider.
"""
from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

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
    Source,
    Vector3,
)
from tools.gnss_quality_thresholds import (
    build,
    discover_sessions,
    main,
    markdown,
    percentile,
    profile,
)

BASE_NS = 9_007_199_254_740_993
SECOND_NS = 1_000_000_000
DAY_NS = 86_400 * SECOND_NS


def gnss(
    event_id,
    t_ns,
    *,
    provider="gps",
    horizontal=None,
    vertical=None,
    satellites=None,
    speed=None,
    bearing=None,
    received_ns=None,
):
    return Record(
        Header("acq-1", Source.REAL, "1.0.0"),
        Event(
            event_id,
            t_ns,
            t_ns if received_ns is None else received_ns,
            GnssMeasurement(
                latitude_deg=17.4,
                longitude_deg=78.4,
                altitude_m=None,
                altitude_reference=None,
                speed_m_s=speed,
                bearing_deg=bearing,
                horizontal_accuracy_m=horizontal,
                vertical_accuracy_m=vertical,
                satellites_used=satellites,
                provider=provider,
                utc_ms=None,
            ),
        ),
    )


def imu(event_id, t_ns):
    return Record(
        Header("acq-1", Source.REAL, "1.0.0"),
        Event(
            event_id,
            t_ns,
            t_ns,
            ImuMeasurement(
                Sensor.ACCELEROMETER,
                DeviceFrame.ANDROID_DEVICE,
                ImuUnit.METRES_PER_SECOND_SQUARED,
                Vector3(0.0, 0.0, 9.80665),
                SensorAccuracy.HIGH,
            ),
        ),
    )


def metadata(records, *, recording_id):
    tally: dict[str, int] = {}
    for record in records:
        tally[record.event.data.TYPE] = tally.get(record.event.data.TYPE, 0) + 1
    origin = min(record.event.t_ns for record in records)
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
            origin_ns=origin,
            started_ns=origin,
            ended_ns=max(record.event.received_ns for record in records),
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


class ThresholdToolFixture(unittest.TestCase):
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


class PercentileMethodTests(unittest.TestCase):
    def test_percentiles_are_nearest_rank_with_no_invented_interpolation(self):
        values = [1.0, 2.0, 3.0, 4.0]
        self.assertEqual(1.0, percentile(values, 0.25))
        self.assertEqual(2.0, percentile(values, 0.50))
        self.assertEqual(4.0, percentile(values, 0.99))
        self.assertEqual(5.0, percentile([5.0], 0.01))
        self.assertIsNone(percentile([], 0.5))

    def test_profile_of_nothing_is_empty_rather_than_zero_filled(self):
        empty = profile([])
        self.assertEqual(0, empty["count"])
        for key in ("min", "p50", "p95", "max"):
            self.assertIsNone(empty[key])


class MeasurementTests(ThresholdToolFixture):
    def test_a_directory_without_both_session_files_is_not_a_session(self):
        (self.root / "not-a-session").mkdir()
        (self.root / "not-a-session" / "metadata.json").write_text("{}", encoding="utf-8")
        self.write([imu("1", BASE_NS)], name="rec-1")
        self.assertEqual(
            [self.root / "rec-1"], discover_sessions(self.root)
        )

    def test_inter_fix_intervals_are_formed_inside_one_session_only(self):
        self.write(
            [
                gnss("1", BASE_NS, horizontal=5.0, satellites=9),
                gnss("2", BASE_NS + SECOND_NS, horizontal=5.0, satellites=9),
                gnss("3", BASE_NS + 2 * SECOND_NS, horizontal=5.0, satellites=9),
            ],
            name="rec-1",
        )
        # Ten days later, a second recording on the same channel. Pooling across sessions
        # would report one enormous interval; the provider is 1 Hz in both.
        later = BASE_NS + 10 * DAY_NS
        self.write(
            [
                gnss("1", later, horizontal=5.0, satellites=9),
                gnss("2", later + SECOND_NS, horizontal=5.0, satellites=9),
            ],
            name="rec-2",
        )
        report = build(self.root)
        gps = report["per_provider"]["gps"]["inter_fix_interval_s"]
        self.assertEqual(3, gps["count"])
        self.assertEqual(1.0, gps["min"])
        self.assertEqual(1.0, gps["max"])
        self.assertEqual(1.0, report["census"]["inter_fix_interval_all_providers_s"]["max"])
        self.assertEqual(2, report["corpus"]["sessions_with_gnss"])
        self.assertEqual(2, report["corpus"]["sessions_read"])

    def test_missing_optionals_are_counted_as_null_not_as_zero(self):
        self.write(
            [
                gnss(
                    "1",
                    BASE_NS,
                    horizontal=9.9,
                    vertical=2.5,
                    satellites=22,
                    speed=0.0,
                    bearing=0.0,
                ),
                gnss(
                    "2",
                    BASE_NS + SECOND_NS,
                    horizontal=9.9,
                    vertical=2.5,
                    satellites=22,
                    speed=0.0,
                    bearing=0.0,
                ),
                gnss("3", BASE_NS + 2 * SECOND_NS, horizontal=None, satellites=None),
            ],
            name="rec-1",
        )
        report = build(self.root)
        availability = report["availability"]
        # A recorded zero and a missing field are counted differently, which is the whole
        # reason the availability table exists.
        self.assertEqual({"present": 2, "null": 1, "total": 3}, availability["bearing_deg"])
        self.assertEqual({"present": 2, "null": 1, "total": 3}, availability["speed_m_s"])
        self.assertEqual({"present": 2, "null": 1, "total": 3}, availability["satellites_used"])
        repeats = report["census"]["horizontal_accuracy_repeats"]
        self.assertEqual(2, repeats[0]["count"])

    def test_candidate_grids_report_the_cost_of_each_bound(self):
        self.write(
            [
                gnss("1", BASE_NS, horizontal=9.9, satellites=9),
                gnss("2", BASE_NS + SECOND_NS, horizontal=9.9, satellites=9),
                # One late arrival, so one interval is longer than the bound.
                gnss("3", BASE_NS + 5 * SECOND_NS, horizontal=9.9, satellites=9),
            ],
            name="rec-1",
        )
        report = build(self.root)
        stale = report["candidate_stale_bounds"]["2s"]["gps"]
        self.assertEqual(2, stale["intervals"])
        self.assertEqual(1, stale["over_bound"])
        self.assertEqual(0.5, stale["over_bound_fraction"])
        self.assertEqual(4.0, stale["max_over_bound_s"])
        self.assertEqual(0, report["candidate_stale_bounds"]["30s"]["gps"]["over_bound"])

        accuracy = report["candidate_horizontal_accuracy_bounds"]["10m"]["gps"]
        self.assertEqual(3, accuracy["known"])
        self.assertEqual(3, accuracy["within_bound"])
        strict = report["candidate_horizontal_accuracy_bounds"]["5m"]["gps"]
        self.assertEqual(0, strict["within_bound"])

        satellites = report["candidate_satellite_bounds"]["8"]["gps"]
        self.assertEqual(3, satellites["at_least_bound"])
        self.assertEqual(3, satellites["known"])
        self.assertEqual(0, report["candidate_satellite_bounds"]["10"]["gps"]["at_least_bound"])
        self.assertEqual(0, report["candidate_satellite_bounds"]["3"]["gps"]["unknown"])

    def test_a_rejected_session_is_reported_and_does_not_abort_the_run(self):
        self.write([gnss("1", BASE_NS, horizontal=5.0, satellites=9)], name="rec-1")
        broken = self.root / "rec-2"
        broken.mkdir()
        (broken / "metadata.json").write_bytes(
            encode_metadata(metadata([imu("1", BASE_NS)], recording_id="rec-2"))
        )
        (broken / "measurements.jsonl").write_bytes(b"{not json}\n")
        report = build(self.root)
        self.assertEqual(2, report["corpus"]["sessions_discovered"])
        self.assertEqual(1, report["corpus"]["sessions_read"])
        self.assertEqual(1, len(report["corpus"]["sessions_unreadable"]))
        self.assertIn("rec-2", report["corpus"]["sessions_unreadable"][0])
        self.assertEqual(1, report["corpus"]["fixes"])


class ToolEntryPointTests(ThresholdToolFixture):
    def test_main_writes_both_artifacts_and_touches_no_recorded_byte(self):
        directory = self.write(
            [
                gnss("1", BASE_NS, horizontal=5.0, satellites=9),
                gnss("2", BASE_NS + SECOND_NS, horizontal=5.0, satellites=9),
            ],
            name="rec-1",
        )
        before = (directory / "measurements.jsonl").read_bytes()
        before_meta = (directory / "metadata.json").read_bytes()
        out = self.root / "out" / "metrics.json"
        md = self.root / "out" / "report.md"
        stdout = io.StringIO()
        argv = ["--root", str(self.root), "--out", str(out), "--md", str(md)]
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(0, main(argv))
        # The entry point summarises to stdout; the suite output stays clean.
        self.assertIn("sessions_read", stdout.getvalue())
        self.assertEqual(before, (directory / "measurements.jsonl").read_bytes())
        self.assertEqual(before_meta, (directory / "metadata.json").read_bytes())
        report = json.loads(out.read_text(encoding="utf-8"))
        for key in (
            "corpus",
            "census",
            "availability",
            "per_provider",
            "candidate_stale_bounds",
            "candidate_horizontal_accuracy_bounds",
            "candidate_satellite_bounds",
            "sessions",
        ):
            self.assertIn(key, report)
        rendered = md.read_text(encoding="utf-8")
        self.assertIn("# GNSS quality thresholds", rendered)
        self.assertIn("## Candidate stale bounds", rendered)
        self.assertIn("rec-1", rendered)

    def test_a_missing_root_is_a_usage_error(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            main(["--root", str(self.root / "absent")])

    def test_markdown_renders_profile_entries_without_a_method_key(self):
        self.write([gnss("1", BASE_NS, horizontal=5.0, satellites=9)], name="rec-1")
        rendered = markdown(build(self.root))
        self.assertIn("nearest-rank", rendered)
        self.assertNotIn("method", rendered.split("## Corpus")[1].split("## Optional")[0])


if __name__ == "__main__":
    unittest.main()
