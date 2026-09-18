"""Repository setup contracts; fixtures never touch the raw dataset."""

import contextlib
import importlib
import io
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

import numpy as np
import pandas as pd

from test_phase0 import fixture_directory
from training import common
from training.phase0 import audit, schema, statistics


class DatasetConfigurationTests(unittest.TestCase):
    def test_default_is_repository_relative_even_from_another_directory(self):
        with fixture_directory() as fixture:
            dataset = fixture / "iovnbd"
            (dataset / common.SYNC_DIRECTORY).mkdir(parents=True)
            previous = Path.cwd()
            try:
                os.chdir(fixture)
                with patch.object(common, "DEFAULT_DATA_ROOT", dataset):
                    self.assertEqual(common.resolve_dataset_root(), dataset.resolve())
            finally:
                os.chdir(previous)

    def test_explicit_alternate_name_and_relative_root(self):
        with fixture_directory() as fixture:
            dataset = fixture / "dataset with spaces"
            sequence = dataset / common.M_SEQUENCE
            sequence.mkdir(parents=True)
            for name in ("S-M.csv", "V-M.csv"):
                (sequence / name).touch()
            relative = dataset.relative_to(Path.cwd())
            root, smartphone, vbox = common.sequence_paths(relative)
            self.assertEqual(root, dataset.resolve())
            self.assertEqual((smartphone.name, vbox.name), ("S-M.csv", "V-M.csv"))
            self.assertEqual(schema.resolve_root(relative), root)

    def test_missing_default_does_not_select_a_legacy_sibling(self):
        with fixture_directory() as fixture:
            (fixture / "iovnbd_git" / common.SYNC_DIRECTORY).mkdir(parents=True)
            with patch.object(common, "DEFAULT_DATA_ROOT", fixture / "iovnbd"):
                with self.assertRaisesRegex(FileNotFoundError, "Expected structure") as caught:
                    common.resolve_dataset_root()
            message = str(caught.exception)
            for text in ("--data-root", "S-M.csv", "V-M.csv", common.SYNC_DIRECTORY):
                self.assertIn(text, message)

    def test_missing_pair_reports_the_exact_missing_file(self):
        with fixture_directory() as fixture:
            sequence = fixture / common.M_SEQUENCE
            sequence.mkdir(parents=True)
            (sequence / "S-M.csv").touch()
            with self.assertRaises(FileNotFoundError) as caught:
                common.sequence_paths(fixture)
            self.assertIn(str(sequence / "V-M.csv"), str(caught.exception))

    def test_cli_missing_root_exits_cleanly(self):
        with fixture_directory() as fixture:
            result = subprocess.run(
                [sys.executable, "-B", "-X", "utf8", "-m", "training.inspect_dataset",
                 "--data-root", str(fixture / "missing"), "--no-write"],
                cwd=common.PROJECT_ROOT, capture_output=True, text=True, encoding="utf-8",
            )
            self.assertEqual(result.returncode, 2)
            self.assertIn("Expected structure", result.stderr)
            self.assertNotIn("Traceback", result.stderr)


class SharedUtilityTests(unittest.TestCase):
    def test_encoding_preserves_original_labels(self):
        with fixture_directory() as fixture:
            path = fixture / "sensor.csv"
            path.write_bytes("ACCELEROMETER X (m/s²),DATE\n9.8,example\n".encode("cp1252"))
            self.assertEqual(common.detect_encoding(path), "cp1252")
            encoding, labels = common.read_header(path)
            self.assertEqual(encoding, "cp1252")
            self.assertEqual(labels[0], "ACCELEROMETER X (m/s²)")
            path.write_text("\ufeffGYROSCOPE Yaw (rad/s),DATE\n0,example\n", encoding="utf-8")
            self.assertEqual(common.read_header(path)[1][0], "GYROSCOPE Yaw (rad/s)")

    def test_normalization_keeps_unit_and_channel_meaning(self):
        self.assertEqual(common.normalize_header("\ufeff  GPS  ORIENTATION (Â°) "), "gps orientation (°)")
        self.assertEqual(common.normalize_name(" GRAVITY Z (m/s²) "), "gravity z (m/s²)")
        self.assertEqual(common.normalize_header("GYROSCOPE Pitch (rad/s)"), "gyroscope pitch (rad/s)")
        self.assertIs(schema.normalize_header, common.normalize_header)

    def test_numeric_missing_infinity_and_copy_semantics(self):
        values = pd.Series(["1.5", "bad", None, "inf"])
        result = common.safe_numeric(values)
        np.testing.assert_allclose(result, [1.5, np.nan, np.nan, np.inf])
        self.assertTrue(np.isnan(common.safe_numeric(values, finite_only=True)[-1]))
        source = np.array([1., 2.])
        copy = common.safe_numeric(source)
        copy[0] = 9
        self.assertEqual(source[0], 1)
        nullable = pd.Series([1, pd.NA], dtype="Float64")
        np.testing.assert_allclose(common.safe_numeric(nullable), [1, np.nan])

    def test_geography_and_explicit_units(self):
        np.testing.assert_allclose(common.haversine([0, 0], [0, 0], [0, 0], [0, 180]),
                                   [0, np.pi * common.EARTH_RADIUS_M])
        self.assertIs(statistics.haversine, common.haversine)
        np.testing.assert_allclose(schema.convert_unit([0, 10], "m/s", "km/h"), [0, 36])
        with self.assertRaises(ValueError):
            schema.convert_unit([1], "unknown", "m/s")

    def test_shared_import_does_not_access_dataset_or_write(self):
        # Dependencies are already imported; trap work triggered by our reloads.
        forbidden = AssertionError("Import must not perform I/O")
        with patch.object(Path, "open", side_effect=forbidden), \
             patch.object(Path, "mkdir", side_effect=forbidden), \
             patch.object(Path, "is_dir", side_effect=forbidden), \
             patch.object(pd, "read_csv", side_effect=forbidden), \
             contextlib.redirect_stdout(io.StringIO()) as output:
            importlib.reload(common)
            importlib.reload(schema)
            importlib.reload(statistics)
        self.assertEqual(output.getvalue(), "")

    def test_no_write_inspection_never_calls_report_writer(self):
        from training import inspect_dataset
        with patch.object(sys, "argv", ["inspect_dataset", "--no-write"]), \
             patch.object(inspect_dataset, "m_pair_from_args", return_value=(Path(), Path("S"), Path("V"))), \
             patch.object(inspect_dataset, "inspect_csv", return_value="synthetic results"), \
             patch.object(Path, "mkdir", side_effect=AssertionError("Unexpected directory write")), \
             patch.object(Path, "write_text", side_effect=AssertionError("Unexpected report write")), \
             contextlib.redirect_stdout(io.StringIO()):
            inspect_dataset.main()

    def test_audit_no_write_skips_all_report_writes(self):
        # Exercise the CLI write gate using synthetic analysis, not a dataset rerun.
        with fixture_directory() as fixture:
            (fixture / common.SYNC_DIRECTORY).mkdir()
            args = ["audit", "--data-root", str(fixture), "--no-write"]
            with patch.object(sys, "argv", args), \
                 patch.object(audit, "snapshot", return_value=[]), \
                 patch.object(audit, "collect", return_value=({}, {}, {})), \
                 patch.object(audit, "build_outputs", return_value={"result": "synthetic"}), \
                 patch.object(Path, "mkdir", side_effect=AssertionError("Unexpected directory write")), \
                 patch.object(Path, "write_text", side_effect=AssertionError("Unexpected report write")), \
                 contextlib.redirect_stdout(io.StringIO()) as output:
                audit.main()
            self.assertIn("no reports written", output.getvalue())


if __name__ == "__main__":
    unittest.main()
