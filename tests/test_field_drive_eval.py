import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import field_drive_eval as fd  # noqa: E402


def write_session(directory: Path, fixes: int = 600, provider: str = "gps", speed: float | None = 15.0, source: str = "real") -> None:
    """A straight drive north at 15 m/s with a GPS fix per second (and one network fix that must be ignored)."""
    lines = []
    for i in range(fixes):
        lat = 17.0 + float(np.degrees(15.0 * i / 6_371_000.0))
        data = {"latitude_deg": lat, "longitude_deg": 78.0, "altitude_m": None, "altitude_reference": "unknown", "speed_m_s": speed,
                "bearing_deg": 0.0, "horizontal_accuracy_m": 4.0, "vertical_accuracy_m": None, "satellites_used": None,
                "provider": provider, "utc_ms": None}
        lines.append(json.dumps({"event": {"event_id": str(i), "type": "gnss", "t_ns": str((i + 1) * 1_000_000_000), "data": data}}))
    lines.append(json.dumps({"event": {"event_id": "n", "type": "gnss", "t_ns": "1500000000",
                                       "data": {"latitude_deg": 0.0, "longitude_deg": 0.0, "provider": "network", "speed_m_s": None,
                                                "horizontal_accuracy_m": 500.0}}}))
    (directory / "measurements.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (directory / "metadata.json").write_text(json.dumps({"source": source, "recording_id": "fixture",
                                                          "device": {"manufacturer": "Acme", "model": "P1"}}), encoding="utf-8")


class FieldDriveEvalTest(unittest.TestCase):
    def test_truth_uses_only_gps_fixes_and_measures_the_distance_travelled(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_session(Path(tmp))
            truth = fd.load_fixes(Path(tmp))
        self.assertEqual(len(truth["t"]), 600)                       # the network fix is not truth
        self.assertAlmostEqual(truth["dist"][-1], 15.0 * 599, delta=5.0)
        self.assertTrue(np.all(np.diff(truth["t"]) > 0))
        self.assertAlmostEqual(float(np.nanmedian(truth["accuracy"])), 4.0)

    def test_a_missing_doppler_speed_falls_back_to_the_track_speed(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_session(Path(tmp), speed=None)
            truth = fd.load_fixes(Path(tmp))
        self.assertAlmostEqual(float(np.median(truth["speed"][1:])), 15.0, delta=0.2)

    def test_a_drive_without_enough_gps_fixes_is_refused_not_scored(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_session(Path(tmp), fixes=5)
            with self.assertRaises(SystemExit) as caught:
                fd.load_fixes(Path(tmp))
        self.assertIn("at least", str(caught.exception))

    def test_outages_start_after_the_warmup_and_only_while_moving(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_session(Path(tmp))
            truth = fd.load_fixes(Path(tmp))
        planned = fd.plan(truth, warmup_s=100.0)
        self.assertTrue(planned["t30"])
        for start, end in planned["t30"]:
            self.assertGreaterEqual(start, int(truth["t"][0]) + 100 * 10 ** 9)
            self.assertAlmostEqual((end - start) / 1e9, 30.0, places=3)
        for start, end in planned["d50"]:
            self.assertGreater(end, start)
        self.assertEqual(fd.plan(truth, warmup_s=10_000.0)["t30"], [])

    def test_jobs_replay_the_recording_untouched_with_the_phone_profile_and_the_in_drive_calibrator(self):
        with tempfile.TemporaryDirectory() as tmp:
            recording = Path(tmp)
            write_session(recording)
            truth = fd.load_fixes(recording)
            jobs = fd.jobs_for(recording, recording / "out", fd.plan(truth, 100.0), ("app", "ins"))
        self.assertEqual(len(jobs), 8)                                 # 4 scenarios x 2 arms
        for job in jobs:
            self.assertEqual(job["profile"], "phone")
            self.assertIsNone(job["calibration"])
            self.assertEqual(Path(job["session"]), recording)
            self.assertTrue(Path(job["output"]).as_posix().startswith((recording / "out").as_posix()))

    def test_an_offline_calibration_and_profile_are_passed_through_to_every_job(self):
        calibration = {"id": "batch-x", "q_vehicle_from_device_wxyz": [1, 0, 0, 0], "gyro_bias_rad_s": [0, 0, 0], "available_ns": "1"}
        with tempfile.TemporaryDirectory() as tmp:
            recording = Path(tmp)
            write_session(recording)
            truth = fd.load_fixes(recording)
            jobs = fd.jobs_for(recording, recording / "out", fd.plan(truth, 100.0), ("app",), calibration, "iovnbd")
        self.assertTrue(jobs)
        for job in jobs:
            self.assertEqual(job["calibration"], calibration)
            self.assertEqual(job["profile"], "iovnbd")

    def test_the_summary_labels_a_non_field_recording_and_names_the_reference(self):
        with tempfile.TemporaryDirectory() as tmp:
            recording = Path(tmp)
            write_session(recording, source="simulation")
            truth = fd.load_fixes(recording)
            planned = fd.plan(truth, 100.0)
            summary = fd.write_outputs(recording / "out", recording, truth, planned, [], ("app",), 100.0, [])
        self.assertTrue(summary["data_kind"].startswith("NOT A FIELD DRIVE"))
        self.assertIn("not an independent receiver", summary["reference"])

    def test_an_engine_that_never_navigates_is_explained_in_the_report(self):
        results = [{"arm": "app", "first_position_ns": None, "calibrations": 1, "diagnostics": {"CALIBRATION_YAW_UNMEASURED": 1},
                    "first_messages": {"CALIBRATION_YAW_UNMEASURED": "t_ns=1: need straight driving"}}]
        report = fd.engine_report(results, ("app",), 0)
        self.assertIsNone(report["app"]["first_position_s_after_first_fix"])
        text = fd.markdown({"recording_id": "x", "data_kind": "FIELD", "device": "d", "duration_s": 1, "distance_km": 1, "reference_fixes": 1,
                            "reference_reported_accuracy_median_m": 3.0, "reference": "r", "planned_outages": {"t30": 0},
                            "scenarios": {"t30": {"app": {"scored": 0, "planned": 0, "engine_failed": 0, "not_navigating_at_start": 0}}},
                            "engine": report}, ("app",))
        self.assertIn("never produced a position", text)
        self.assertIn("need straight driving", text)


if __name__ == "__main__":
    unittest.main()
