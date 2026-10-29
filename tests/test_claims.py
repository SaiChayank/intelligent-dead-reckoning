"""The README results block and reports/claims.md must be regenerated from the result files."""
from __future__ import annotations

import unittest
from pathlib import Path

from tools import build_claims

ROOT = Path(__file__).resolve().parents[1]


class ClaimsTest(unittest.TestCase):
    def test_claims_and_readme_block_are_up_to_date(self):
        self.assertEqual(build_claims.main(["--check"]), 0, "run python tools/build_claims.py")

    def test_readme_does_not_reintroduce_the_retired_headline_numbers(self):
        text = (ROOT / "README.md").read_text(encoding="utf-8")
        for retired in ("3.12%", "1.76%", "13.1 m", "5.7x better", "3.2x better"):
            self.assertNotIn(retired, text)

    def test_shipped_evaluation_report_matches_the_measured_file(self):
        import json
        report = json.loads((ROOT / "mobile/app/src/main/assets/sample_drive/hyderabad-demo-drive/evaluation_report.json")
                            .read_text(encoding="utf-8"))
        measured = {a["arm_id"]: a for a in json.loads((ROOT / "reports/synthetic_outage_measured.json")
                                                        .read_text(encoding="utf-8"))["arms"]}
        for arm in report["arms"]:
            if arm["status"] != "evaluated":
                self.assertIsNone(arm["accuracy"])
                continue
            m = measured[arm["arm_id"]]
            self.assertAlmostEqual(arm["accuracy"]["final_position_error_m"], m["end_error_m"], places=6)
            self.assertAlmostEqual(arm["accuracy"]["drift_percent"], m["drift_pct"], places=6)
            self.assertIsNone(arm["timing"]["inference_latency_p50_ms"])


if __name__ == "__main__":
    unittest.main()
