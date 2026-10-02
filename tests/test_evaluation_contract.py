"""Evaluation contract 1.0.0: the golden document, the shared negative corpus, and the rules that
keep a comparison table honest — an accuracy figure only against a declared independent reference
the arm did not consume, and `not_run`/`not_implemented` carrying a reason and no metrics."""
import json
import unittest
from dataclasses import replace
from pathlib import Path

from contracts.evaluation.v1 import (
    Arm,
    ArmId,
    ArmStatus,
    EvaluationError,
    ReferenceKind,
    decode_json,
    decode_report,
    encode_report,
)

ROOT = Path(__file__).resolve().parent.parent / "contracts" / "evaluation" / "v1"
GOLDEN = (ROOT / "golden_report.json").read_text(encoding="utf-8")


class GoldenReportTest(unittest.TestCase):
    def setUp(self):
        self.report = decode_json(GOLDEN)

    def test_golden_document_decodes_and_round_trips_to_the_same_types(self):
        self.assertEqual(self.report.evaluation_contract_version, "1.0.0")
        self.assertEqual(self.report.evaluation_id, "scripted-outage-70s")
        self.assertEqual(decode_report(encode_report(self.report)), self.report)

    def test_golden_names_every_arm_once_and_is_anchored_in_a_reference(self):
        self.assertEqual([arm.arm_id for arm in self.report.arms], list(ArmId))
        self.assertIs(self.report.reference.kind, ReferenceKind.SCRIPTED_TRUTH)
        self.assertTrue(self.report.reference.independent)
        self.assertTrue(self.report.platform.host)
        self.assertIsNone(self.report.platform.device_model)

    def test_every_evaluated_arm_carries_measured_figures_and_nothing_is_invented(self):
        for arm in self.report.arms:
            with self.subTest(arm=arm.arm_id.value):
                if arm.status is ArmStatus.EVALUATED:
                    self.assertTrue(arm.accuracy is not None or arm.timing is not None)
                    if arm.accuracy is not None:
                        self.assertFalse(arm.accuracy.reference_consumed)
                        self.assertGreater(arm.accuracy.position_rmse_m, 0.0)
                        self.assertGreater(arm.accuracy.samples, 0)
                else:
                    self.assertIsNotNone(arm.reason)
                    self.assertIsNone(arm.accuracy)
                    self.assertIsNone(arm.timing)

    def test_the_model_arm_is_absent_with_a_reason_rather_than_a_number(self):
        ai = next(arm for arm in self.report.arms if arm.arm_id is ArmId.FUSION_AI)
        self.assertIs(ai.status, ArmStatus.NOT_IMPLEMENTED)
        self.assertIn("No trained", ai.reason)

    def test_a_host_run_cannot_claim_device_memory_or_a_sensor_latency_path(self):
        timing = next(
            arm for arm in self.report.arms if arm.arm_id is ArmId.FUSION_CONSTRAINTS
        ).timing
        self.assertIsNone(timing.memory_peak_mb)
        self.assertIsNone(timing.end_to_end_p50_ms)
        self.assertIsNone(timing.end_to_end_p95_ms)
        self.assertIsNone(timing.inference_latency_p50_ms)
        self.assertGreater(timing.output_hz, 0.0)


class NegativeCorpusTest(unittest.TestCase):
    def test_shared_negative_corpus_is_rejected_with_exact_codes(self):
        cases = json.loads((ROOT / "invalid_reports.json").read_text(encoding="utf-8"))
        self.assertGreaterEqual(len(cases), 30)
        for case in cases:
            with self.subTest(case=case["name"]):
                with self.assertRaises(EvaluationError) as raised:
                    decode_json(case["input"])
                self.assertEqual(raised.exception.code, case["code"])


class HonestyRuleTest(unittest.TestCase):
    """The rules the corpus exercises, stated directly: they are the reason the contract exists."""

    def setUp(self):
        self.report = decode_json(GOLDEN)

    def _with_reference(self, **changes):
        return replace(self.report, reference=replace(self.report.reference, **changes))

    def test_accuracy_cannot_exist_without_an_independent_reference(self):
        for reference in (
            replace(self.report.reference, kind=ReferenceKind.NONE, independent=False),
            replace(self.report.reference, independent=False),
        ):
            with self.subTest(reference=reference.kind.value):
                document = encode_report(replace(self.report, reference=reference))
                with self.assertRaises(EvaluationError) as raised:
                    decode_report(document)
                self.assertEqual(raised.exception.code, "INVARIANT")

    def test_an_arm_consuming_its_own_reference_is_refused(self):
        accuracy = replace(
            next(a for a in self.report.arms if a.accuracy is not None).accuracy,
            reference_consumed=True,
        )
        arms = tuple(
            replace(arm, accuracy=accuracy) if arm.accuracy is not None else arm
            for arm in self.report.arms
        )
        with self.assertRaises(EvaluationError) as raised:
            decode_report(encode_report(replace(self.report, arms=arms)))
        self.assertEqual(raised.exception.code, "INVARIANT")

    def test_a_not_run_arm_cannot_smuggle_metrics_or_omit_its_reason(self):
        measured = next(arm for arm in self.report.arms if arm.accuracy is not None)
        for broken in (
            replace(measured, status=ArmStatus.NOT_RUN, reason="stopped early"),
            replace(measured, status=ArmStatus.NOT_RUN, reason=None),
        ):
            with self.subTest(reason=broken.reason):
                document = encode_report(replace(self.report, arms=(broken,)))
                with self.assertRaises(EvaluationError) as raised:
                    decode_report(document)
                self.assertEqual(raised.exception.code, "INVARIANT")

    def test_an_unknown_arm_or_reference_kind_is_refused(self):
        for key, value, document in (
            ("kind", "oracle", encode_report(self._with_reference())),
            ("arm_id", "fusion_magic", encode_report(self.report)),
        ):
            with self.subTest(key=key):
                mutated = json.loads(json.dumps(document))
                if key == "kind":
                    mutated["reference"]["kind"] = value
                else:
                    mutated["arms"][0]["arm_id"] = value
                with self.assertRaises(EvaluationError) as raised:
                    decode_report(mutated)
                self.assertEqual(raised.exception.code, "INVALID_ENUM")


class NonFiniteNumberTest(unittest.TestCase):
    """A non-finite token is `NONFINITE` whether it arrives bare or quoted.

    Python's `json` parses a bare `NaN` into a float while Gson hands the same token over as a
    string, so the Kotlin codec sees these documents at a different layer than this one does. The
    shared corpus pins the bare spelling; this test pins both, so neither layer can drift.
    """

    def _with_metric(self, spelling) -> dict:
        document = json.loads(GOLDEN)
        document["arms"][0]["accuracy"]["position_rmse_m"] = spelling
        return document

    def _refusal(self, document) -> str:
        with self.assertRaises(EvaluationError) as raised:
            decode_report(document)
        return raised.exception.code

    def test_a_bare_non_finite_token_is_refused_as_nonfinite(self):
        self.assertEqual(self._refusal(self._with_metric(float("nan"))), "NONFINITE")
        self.assertEqual(self._refusal(self._with_metric(float("inf"))), "NONFINITE")

    def test_a_quoted_non_finite_token_is_refused_the_same_way(self):
        for token in ("NaN", "Infinity", "-Infinity", "+Infinity"):
            with self.subTest(token=token):
                self.assertEqual(self._refusal(self._with_metric(token)), "NONFINITE")

    def test_an_ordinary_string_is_still_a_type_error(self):
        self.assertEqual(self._refusal(self._with_metric("nan")), "INVALID_TYPE")
        self.assertEqual(self._refusal(self._with_metric("258.18")), "INVALID_TYPE")


if __name__ == "__main__":
    unittest.main()
