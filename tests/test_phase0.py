"""Phase 0 tests use small synthetic fixtures; no raw dataset is written."""

from contextlib import contextmanager
import shutil
import unittest
from uuid import uuid4
from pathlib import Path

import numpy as np
import pandas as pd

from training.phase0.schema import column_spec, convert_unit, normalize_header, read_header, resolve_root, observed_schema
from training.phase0.statistics import agreement, change_stats, clock_stats, haversine, monotonic_interp, valid_coordinates
from training.phase0.audit import canonical, read_frame, snapshot, time_array, unit_evidence


@contextmanager
def fixture_directory():
    # Python 3.12 TemporaryDirectory creates mode 0700 directories on Windows;
    # that can exclude the sandbox's secondary token. Inherit workspace ACLs.
    parent = Path(__file__).resolve().parent
    path = parent / ("phase0-fixture-" + uuid4().hex)
    path.mkdir(mode=0o777)
    try:
        yield path
    finally:
        if path.resolve().parent != parent or not path.name.startswith("phase0-fixture-"):
            raise RuntimeError("Refusing cleanup outside the synthetic fixture directory")
        shutil.rmtree(path)


class SchemaTests(unittest.TestCase):
    def test_shifted_payload_is_detected_from_values_and_quarantined(self):
        headers = ["GPS LATITUDE (degrees)", "GPS LONGITUDE (degrees)", "GPS ALTITUDE (m)", "GPS SPEED (Kmh)",
            "GPS ACCURACY (m)", "GPS ORIENTATION (°)", "GPS SATELLITES IN RANGE", "TIME SINCE START (ms)",
            "DATE (YYYY-MO-DD HH-MI-SS_SSS)"]
        headers += [f"{sensor} {axis} ({unit})" for sensor, axes, unit in [
            ("ACCELEROMETER", ["X", "Y", "Z"], "m/s²"), ("GRAVITY", ["X", "Y", "Z"], "m/s²"),
            ("GYROSCOPE", ["Yaw", "Pitch", "Roll"], "rad/s"), ("MAGNETIC FIELD", ["X", "Y", "Z"], "μT")] for axis in axes]
        headers += ["ORIENTATION (Yaw) (°)", "ORIENTATION (Pitch) (°)", "ORIENTATION (Roll) (°)", ""]
        payload = pd.DataFrame([[52, -1, 78, 0, 10, 0, np.nan, "13 / 24", 45, "2019-08-16 11:29:24:414"] + [0.] * 15])
        specs = observed_schema(headers, "smartphone", payload)
        self.assertEqual(specs[9]["normalized_name"], "datetime_raw")
        self.assertEqual(specs[9]["original_name"], headers[9])
        self.assertEqual(specs[9]["header_normalized_name"], "accel_x")
        self.assertEqual(specs[24]["normalized_name"], "orientation_roll")
        self.assertTrue(all(not c["permitted_as_training_feature"] for c in specs))
        payload.iloc[0, 9] = "invalid date"
        with self.assertRaises(ValueError):
            observed_schema(headers, "smartphone", payload)

    def test_whitespace_bom_and_mixed_encoding_symbols(self):
        self.assertEqual(normalize_header("\ufeff  ACCELEROMETER  X (m/s²) "), "accelerometer x (m/s2)")
        self.assertEqual(normalize_header("GPS ORIENTATION (Â°)"), "gps orientation (°)")
        self.assertEqual(normalize_header("MAGNETIC FIELD X (Î¼T)"), "magnetic field x (μt)")

    def test_gyro_labels_remain_labels(self):
        self.assertEqual(column_spec(" GYROSCOPE Pitch (rad/s)", "smartphone")["normalized_name"], "gyro_channel_pitch")
        self.assertEqual(column_spec(" GYROSCOPE Y (rad/s)", "smartphone")["normalized_name"], "gyro_channel_y")

    def test_speed_matching_does_not_select_vertical_velocity(self):
        self.assertEqual(column_spec(" Velocity (km/hr)", "vbox")["normalized_name"], "reference_speed")
        self.assertEqual(column_spec(" Vertical velocity (km/hr)", "vbox")["normalized_name"], "reference_vertical_speed")

    def test_empty_header_and_bad_date_header_are_explicit(self):
        self.assertEqual(column_spec("", "smartphone")["normalized_name"], "unused_trailing_column")
        self.assertEqual(column_spec("DATE (YYYY-MO-DD HH-MI-SS_SSS", "smartphone")["normalized_name"], "datetime_raw")
        with self.assertRaises(ValueError):
            column_spec("invented sensor", "smartphone")

    def test_feature_policy_blocks_reference_leakage(self):
        ref = column_spec("Velocity (km/hr)", "vbox")
        self.assertFalse(ref["permitted_as_training_feature"])
        self.assertTrue(ref["reference_only_ground_truth"])
        self.assertFalse(column_spec("GPS SPEED (Kmh)", "smartphone")["permitted_as_training_feature"])
        self.assertTrue(column_spec("ACCELEROMETER X (m/s²)", "smartphone")["permitted_as_training_feature"])

    def test_source_header_and_values_are_not_silently_corrected(self):
        with fixture_directory() as tmp:
            p = Path(tmp) / "S-example.csv"
            p.write_bytes("GPS SPEED (Kmh), GYROSCOPE Pitch (rad/s)\n10,0.1\n".encode("cp1252"))
            before = p.read_bytes()
            data = read_frame(p)
            self.assertEqual(data["gps_speed_raw"].iloc[0], 10)
            self.assertEqual(p.read_bytes(), before)
            self.assertEqual(read_header(p)[1][1], " GYROSCOPE Pitch (rad/s)")

    def test_dataset_root_can_be_explicit(self):
        with fixture_directory() as tmp:
            root = Path(tmp)
            (root / "Synchronised V abd S datasets").mkdir()
            self.assertEqual(resolve_root(root), root.resolve())
            with self.assertRaises(FileNotFoundError):
                resolve_root(root / "missing")


class UnitTests(unittest.TestCase):
    def test_speed_hypothesis_uses_motion_and_scale_not_correlation_alone(self):
        n = 400
        speed_ms = np.linspace(0, 30, n)
        s = pd.DataFrame({"gps_speed_raw": speed_ms, "gps_altitude": np.full(n, 100.),
            "accel_x": np.zeros(n), "accel_y": np.zeros(n), "accel_z": np.full(n, 9.80665),
            "gravity_x": np.zeros(n), "gravity_y": np.zeros(n), "gravity_z": np.full(n, 9.80665),
            "gyro_channel_pitch": np.linspace(-.2, .2, n)})
        v = pd.DataFrame({"reference_speed": speed_ms * 3.6, "reference_time_of_day": np.arange(n) * .1,
            "reference_height_raw": np.full(n, 100.), "vehicle_accel_long": np.full(n, 30 / ((n - 1) * .1) / 9.80665),
            "vehicle_accel_lat": np.zeros(n), "vehicle_yaw_rate": np.degrees(s["gyro_channel_pitch"])})
        result = unit_evidence(s, v)
        self.assertEqual(result["speed_unit_inference"], "m/s")
        self.assertAlmostEqual(result["speed_x3_6_vs_vbox_kmh"]["mae"], 0)
        self.assertAlmostEqual(result["speed_raw_assumed_kmh_vs_vbox_kmh"]["correlation"],
                               result["speed_x3_6_vs_vbox_kmh"]["correlation"])
        self.assertAlmostEqual(result["gyro_channel_tests"][0]["assuming_rad_s"]["mae"], 0)
        s["gps_speed_raw"] *= 3.6
        self.assertEqual(unit_evidence(s, v)["speed_unit_inference"], "km/h")
        s["gps_speed_raw"], v["reference_speed"] = 0., 0.
        result = unit_evidence(s, v)
        self.assertEqual(result["speed_unit_inference"], "unresolved")
        self.assertAlmostEqual(result["stationary_accel_norm_m_s2"]["mean"], 9.80665)

    def test_speed_and_missing_values(self):
        values = np.array([0., 36., 72., np.nan])
        result = convert_unit(values, "km/h", "m/s")
        np.testing.assert_allclose(result[:3], [0, 10, 20])
        self.assertTrue(np.isnan(result[3]))
        np.testing.assert_allclose(values[:3], [0, 36, 72])
        np.testing.assert_allclose(convert_unit([10], "m/s", "km/h"), [36])

    def test_acceleration_angular_rate_time_and_distance(self):
        np.testing.assert_allclose(convert_unit([1], "g", "m/s2"), [9.80665])
        np.testing.assert_allclose(convert_unit([180], "deg/s", "rad/s"), [np.pi])
        np.testing.assert_allclose(convert_unit([np.pi], "rad/s", "deg/s"), [180])
        np.testing.assert_allclose(convert_unit([100], "ms", "s"), [.1])
        np.testing.assert_allclose(convert_unit([2], "km", "m"), [2000])

    def test_unknown_unit_is_not_guessed(self):
        with self.assertRaises(ValueError):
            convert_unit([100], "unknown", "m/s")


class StatisticsTests(unittest.TestCase):
    def test_hand_computable_error_metrics(self):
        m = agreement([1, 2, 5, np.nan], [1, 3, 3, 2])
        self.assertEqual(m["count"], 3)
        self.assertAlmostEqual(m["mae"], 1.)
        self.assertAlmostEqual(m["rmse"], np.sqrt(5 / 3))
        self.assertEqual(m["absolute_error"]["max"], 2)
        self.assertIsNone(agreement([1, 1], [2, 2])["correlation"])

    def test_timestamp_gaps_repetitions_and_resets(self):
        r = clock_stats([0, .1, .2, 1.358, 1.458, 1.458, .2, np.nan])
        self.assertEqual(r["large_gap_count"], 1)
        self.assertEqual(r["large_gaps"][0]["after_row_0based"], 2)
        self.assertAlmostEqual(r["large_gaps"][0]["delta_s"], 1.158)
        self.assertEqual(r["repeated_timestamps"], 1)
        self.assertEqual(r["negative_intervals"], 1)
        self.assertEqual(r["missing_timestamps"], 1)

    def test_timestamp_missing_is_preserved(self):
        df = pd.DataFrame({"datetime_raw": ["2019-09-07 09:13:29:506", "bad", "2019-09-07 09:13:29:706"]})
        a = time_array(df, "smartphone")
        self.assertTrue(np.isnan(a[1]))
        self.assertAlmostEqual(a[-1], .2)

    def test_observed_changes_not_called_hardware_frequency(self):
        r = change_stats(np.repeat([1., 2., 3.], 10), np.arange(30) * .1)
        self.assertEqual(r["observed_changes"], 2)
        self.assertAlmostEqual(r["typical_value_change_rate_hz"], 1)
        stationary = change_stats(np.zeros(30), np.arange(30) * .1)
        self.assertIsNone(stationary["typical_value_change_rate_hz"])

    def test_interpolation_no_extrapolation_duplicate_averaging_and_backward_rejection(self):
        a = monotonic_interp([0, 1, 1, 2], [0, 8, 12, 20], [-1, .5, 1, 1.5, 3])
        np.testing.assert_allclose(a[1:4], [5, 10, 15])
        self.assertTrue(np.isnan(a[[0, 4]]).all())
        self.assertTrue(np.isnan(monotonic_interp([0, 2, 1], [0, 20, 10], [1])).all())

    def test_alignment_interpolation_does_not_bridge_missing_reference_data(self):
        a = monotonic_interp([0, .1, 5, 5.1], [0, 1, 50, 51], [.05, 1, 5, 5.05], max_gap_s=.25)
        self.assertTrue(np.isnan(a[1]))
        np.testing.assert_allclose(a[[0, 2, 3]], [.5, 50, 50.5])

    def test_position_distance_and_invalid_sentinel(self):
        self.assertAlmostEqual(float(haversine(0, 0, 0, 1)), 111194.9266, places=3)
        np.testing.assert_equal(valid_coordinates([0, 52, 91, np.nan], [0, -1, 1, 1]), [False, True, False, False])

    def test_deterministic_serialization_and_integrity_snapshot(self):
        self.assertEqual(canonical({"b": 2, "a": 1}), canonical({"a": 1, "b": 2}))
        with self.assertRaises(ValueError):
            canonical({"invalid": float("nan")})
        with fixture_directory() as tmp:
            p = Path(tmp) / "data.txt"
            p.write_text("original", encoding="utf-8")
            first = snapshot(Path(tmp))
            self.assertEqual(first, snapshot(Path(tmp)))
            p.write_text("changed", encoding="utf-8")
            self.assertNotEqual(first, snapshot(Path(tmp)))


if __name__ == "__main__":
    unittest.main()
