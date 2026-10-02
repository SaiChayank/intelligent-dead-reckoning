"""The evaluation report the app ships must be the checked-in contract document, byte for byte.

The Evaluation tab renders exactly this asset and nothing else, so a copy that had drifted from
`contracts/evaluation/v1/golden_report.json` would put stale numbers on screen while the Kotlin
reproducibility suite stayed green against the contract's own copy: two documents, one of them
wrong, and no test complaining. Two copies exist because the contract owns the fixture while the
app must package a document; this test is what keeps them one document.
"""
from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

from contracts.evaluation.v1 import decode_json

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "contracts" / "evaluation" / "v1" / "golden_report.json"
ASSET = ROOT / "mobile" / "app" / "src" / "main" / "assets" / "evaluation" / "golden_report.json"


class EvaluationAssetTest(unittest.TestCase):
    def test_the_shipped_report_is_the_contract_document_byte_for_byte(self) -> None:
        self.assertTrue(ASSET.is_file(), "the app ships its evaluation report as an asset")
        contract_bytes = CONTRACT.read_bytes()
        asset_bytes = ASSET.read_bytes()
        self.assertEqual(asset_bytes, contract_bytes)
        self.assertEqual(
            hashlib.sha256(asset_bytes).hexdigest(),
            hashlib.sha256(contract_bytes).hexdigest(),
        )

    def test_the_shipped_bytes_decode_as_a_valid_report_of_this_contract(self) -> None:
        report = decode_json(ASSET.read_text(encoding="utf-8"))
        self.assertEqual(report.evaluation_contract_version, "1.0.0")
        self.assertEqual(report.evaluation_id, "scripted-outage-70s")
        self.assertTrue(report.reference.independent)
        self.assertTrue(report.platform.host)
        self.assertEqual(len(report.arms), 5)

    def test_the_shipped_report_states_its_own_limits_rather_than_leaving_them_implicit(self) -> None:
        document = json.loads(ASSET.read_text(encoding="utf-8"))
        # A host run: no device model or Android release may be claimed, and the note says what was
        # not measured. The surface prints all of this beside the numbers.
        self.assertIsNone(document["platform"]["device_model"])
        self.assertIsNone(document["platform"]["android_release"])
        self.assertIn("not measured", document["platform"]["note"])
        # Not-a-number is never spelled as a number anywhere in the shipped document.
        self.assertNotIn("NaN", ASSET.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
