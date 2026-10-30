import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import t5_trust_comparison as tc  # noqa: E402


def row(arm, seq, start, drift, scenario="t30", outcome="scored"):
    return {"arm": arm, "sequence": seq, "scenario": scenario, "start_ns": str(start), "drift_pct": str(drift), "outcome": outcome}


class PairedTest(unittest.TestCase):
    def test_only_outages_scored_by_both_arms_are_paired(self):
        rows = [row(tc.OFF, "m", 1, 20), row(tc.ON, "m", 1, 15),
                row(tc.OFF, "m", 2, 30), row(tc.ON, "m", 2, "", outcome="failed"),     # on arm failed: dropped from the pairing
                row(tc.OFF, "s1", 3, 40), row(tc.ON, "s1", 3, 38)]                      # other drive: excluded by the split
        off, on = tc.paired(rows, {"m"}, "t30")
        self.assertEqual(off.tolist(), [20.0])
        self.assertEqual(on.tolist(), [15.0])

    def test_bootstrap_interval_brackets_a_clear_difference_and_straddles_zero_for_noise(self):
        clear = np.full(60, 3.0) + np.random.default_rng(1).normal(0, 0.2, 60)
        lo, hi = tc.bootstrap_mean_ci(clear)
        self.assertGreater(lo, 2.5)
        self.assertLess(hi, 3.5)
        noise = np.random.default_rng(2).normal(0, 5.0, 40)
        lo, hi = tc.bootstrap_mean_ci(noise)
        self.assertLess(lo, 0.0)
        self.assertGreater(hi, 0.0)


if __name__ == "__main__":
    unittest.main()
