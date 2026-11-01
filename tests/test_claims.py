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


class GeneratedDocumentsTest(unittest.TestCase):
    """The report, the deck and the visualizer pages carry generated blocks and values, never typed results."""

    def test_every_generated_file_is_up_to_date_and_has_markers(self):
        for path in build_claims.GENERATED_FILES:
            text = path.read_text(encoding="utf-8")
            self.assertTrue(build_claims.BLOCK_RE.search(text) or build_claims.VALUE_RE.search(text),
                            f"{path.name} has no generated markers")

    def test_retired_and_unsupported_numbers_are_gone_from_every_presentation_file(self):
        for path in list(build_claims.GENERATED_FILES) + [ROOT / "README.md"]:
            text = path.read_text(encoding="utf-8")
            for retired in ("< 3.2% drift", "&lt; 3.2% drift", "&gt; 99.8%", "78% Lower Drift", "23% – 26%", "23% to 26%",
                            "34 of 43", "< 5 ms Jerk"):
                self.assertNotIn(retired, text, f"{path.name} still contains '{retired}'")

    def test_unknown_marker_names_are_an_error(self):
        with self.assertRaises(SystemExit):
            build_claims.apply_markers("<!-- CLAIMS:nope:BEGIN -->\n<!-- CLAIMS:nope:END -->", {}, {}, "x")
        with self.assertRaises(SystemExit):
            build_claims.apply_markers("<!--CV:nope-->1<!--/CV-->", {}, {}, "x")

    def test_markers_are_filled_and_the_text_outside_them_is_untouched(self):
        out = build_claims.apply_markers("a <!--CV:k-->old<!--/CV--> b\n<!-- CLAIMS:t:BEGIN -->\nstale\n<!-- CLAIMS:t:END -->\nc",
                                         {"t": "fresh"}, {"k": "new"}, "x")
        self.assertEqual(out, "a <!--CV:k-->new<!--/CV--> b\n<!-- CLAIMS:t:BEGIN -->\nfresh\n<!-- CLAIMS:t:END -->\nc")

    def test_the_ten_percent_verdict_follows_the_held_out_medians(self):
        from unittest import mock

        def held(t30, t60, d1000):
            def cell(v):
                return {"best_arm": {"median_drift_pct": v}, "hold_last_gnss": {"median_drift_pct": 50.0}}
            return {"t30": cell(t30), "t60": cell(t60), "d1000": cell(d1000)}

        with mock.patch.object(build_claims, "_held", return_value=held(9.0, 8.0, 7.0)):
            self.assertTrue(build_claims.drift_verdict()[0])
            self.assertEqual(build_claims.values()["verdict_word"], "met")
        with mock.patch.object(build_claims, "_held", return_value=held(9.0, 8.0, 11.0)):
            self.assertFalse(build_claims.drift_verdict()[0])
            self.assertEqual(build_claims.values()["verdict_word"], "not met")
            self.assertIn("Not met", build_claims.status_table())
        with mock.patch.object(build_claims, "_held", return_value=None):
            self.assertFalse(build_claims.drift_verdict()[0])

    def test_the_status_row_quotes_the_generated_held_out_numbers(self):
        import json
        held = json.loads((ROOT / "reports" / "t4_split_comparison.json").read_text(encoding="utf-8"))["comparison"]["held_out"]
        text = build_claims.status_table()
        for sc in ("t30", "t60", "d1000"):
            self.assertIn(f"{held[sc]['best_arm']['median_drift_pct']:.0f} %", text)


    def test_the_real_phone_in_car_claims_quote_the_held_out_result_files(self):
        import json
        summary = json.loads((ROOT / "reports" / "gsdc_eval_held_out" / "summary.json").read_text(encoding="utf-8"))
        shipped, previous = build_claims.GSDC_SHIPPED, build_claims.GSDC_PREVIOUS
        headline = build_claims.gsdc_headline()
        for sc in ("t30", "t60", "d1000"):
            self.assertIn(f"{summary['scenarios'][sc][shipped]['drift_pct_median']:.1f} %", headline)
            self.assertIn(f"{summary['scenarios'][sc][previous]['drift_pct_median']:.0f} %", headline)
        self.assertIn(f"{summary['drives_calibrated']} held-out drives", headline)
        section = "\n".join(build_claims.gsdc_section())
        self.assertIn("CC BY-NC-SA 3.0 IGO", section)
        self.assertIn("doi:10.33012/2020.17628", section)
        self.assertIn(build_claims.gsdc_headline(), build_claims.status_paragraph())

    def test_the_real_phone_rows_say_what_was_and_was_not_done(self):
        rows = {name: (evidence, status) for name, evidence, status in build_claims.status_rows()}
        evidence, status = rows["Dead-reckoning drift under 10 %, real phone-in-car drives"]
        self.assertIn("not a drive recorded with this app", evidence)
        calibration_evidence, calibration_status = rows["Auto mount calibration"]
        self.assertIn("Met on real phone-in-car data", calibration_status)
        self.assertIn("not field-tested on a device", calibration_status)
        self.assertIn("0 of 38", calibration_evidence)
        self.assertIn("batch calibrator", calibration_evidence)
        self.assertEqual(rows["Phone IMU in a vehicle"][1], "Partly met")

    def test_without_the_real_phone_files_the_old_wording_returns(self):
        from unittest import mock
        with mock.patch.object(build_claims, "_gsdc", return_value=None):
            self.assertIsNone(build_claims.gsdc_headline())
            rows = {name: status for name, _, status in build_claims.status_rows()}
            self.assertEqual(rows["Phone IMU in a vehicle"], "**Not evaluated**")
            self.assertNotIn("gsdc_headline", build_claims.values())


if __name__ == "__main__":
    unittest.main()
