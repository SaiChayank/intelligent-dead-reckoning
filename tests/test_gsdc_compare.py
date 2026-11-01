import csv
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import gsdc_compare as gc  # noqa: E402

HEADER = ["drive", "recording", "scenario", "config", "outcome", "start_ns", "drift_pct"]


def write_csv(path: Path, rows: list[dict]) -> None:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=HEADER, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def row(drive, scenario, config, start, drift, outcome="scored"):
    return {"drive": drive, "recording": f"rec-{drive}", "scenario": scenario, "config": config, "outcome": outcome, "start_ns": start, "drift_pct": drift}


class GsdcCompareTest(unittest.TestCase):
    def test_only_outages_both_configurations_scored_are_paired(self):
        rows = [row("a", "t30", "base", 1, 10.0), row("a", "t30", "cand", 1, 4.0),
                row("a", "t30", "base", 2, 7.0), row("a", "t30", "cand", 2, 0.0, outcome="engine_failed"),
                row("b", "t30", "base", 3, 5.0)]
        with tempfile.TemporaryDirectory() as tmp:
            write_csv(Path(tmp) / "o.csv", rows)
            paired = gc.paired_rows(Path(tmp) / "o.csv", "base", "cand", "t30")
        self.assertEqual(paired, [("a", 10.0, 4.0)])

    def test_a_consistent_improvement_across_drives_excludes_zero_and_a_coin_flip_does_not(self):
        rng = np.random.default_rng(1)
        better, flip = [], []
        for d in range(12):
            for k in range(6):
                base = float(rng.uniform(20, 60))
                better += [row(f"d{d}", "t60", "base", d * 10 + k, base), row(f"d{d}", "t60", "cand", d * 10 + k, base * 0.5)]
                sign = 1 if rng.random() < 0.5 else -1
                flip += [row(f"d{d}", "t60", "base", d * 10 + k, base), row(f"d{d}", "t60", "cand", d * 10 + k, base + sign * 5.0)]
        with tempfile.TemporaryDirectory() as tmp:
            write_csv(Path(tmp) / "better.csv", better)
            write_csv(Path(tmp) / "flip.csv", flip)
            improved = gc.compare(Path(tmp) / "better.csv", "base", "cand")["scenarios"]["t60"]
            noisy = gc.compare(Path(tmp) / "flip.csv", "base", "cand")["scenarios"]["t60"]
        self.assertTrue(improved["interval_excludes_zero"])
        self.assertGreater(improved["mean_paired_difference_pct_points"], 5.0)
        self.assertFalse(noisy["interval_excludes_zero"])

    def test_the_interval_resamples_drives_so_one_drive_cannot_pose_as_many_outages(self):
        rows = []
        for k in range(200):                     # one drive with 200 outages, all improved by 10 points
            rows += [row("only", "t30", "base", k, 30.0), row("only", "t30", "cand", k, 20.0)]
        for d in range(3):                       # three drives that get worse by 30 points
            for k in range(2):
                rows += [row(f"w{d}", "t30", "base", 1000 + d * 10 + k, 10.0), row(f"w{d}", "t30", "cand", 1000 + d * 10 + k, 40.0)]
        with tempfile.TemporaryDirectory() as tmp:
            write_csv(Path(tmp) / "o.csv", rows)
            result = gc.compare(Path(tmp) / "o.csv", "base", "cand")["scenarios"]["t30"]
        lo, hi = result["mean_difference_95pct_interval_drive_clustered"]
        self.assertLess(lo, 0.0)                 # an outage-level bootstrap would call this clearly positive
        self.assertEqual(result["drives"], 4)


if __name__ == "__main__":
    unittest.main()
