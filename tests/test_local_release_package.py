"""Integrity tests for the local prototype release package's declared artifacts."""
import json
import unittest
from pathlib import Path

from contracts.recording.v1.session import open_session

ROOT = Path(__file__).resolve().parents[1]


class LocalReleasePackageTest(unittest.TestCase):
    def test_model_manifest_truthfully_declares_no_weights(self):
        manifest = json.loads((ROOT / "models/model_manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("no_model_artifacts", manifest["status"])
        self.assertEqual(0, manifest["model_count"])
        self.assertEqual([], manifest["artifacts"])
        admission = json.loads((ROOT / "reports/training_admission.json").read_text(encoding="utf-8"))
        self.assertEqual("no-go", admission["decision"])
        self.assertEqual([], admission["approved_sequence_names"])

    def test_ground_truth_manifest_does_not_relabel_scripted_truth_as_field_evidence(self):
        manifest = json.loads((ROOT / "evaluation/ground_truth_manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("blocked_no_field_ground_truth", manifest["status"])
        self.assertEqual([], manifest["field_ground_truth_artifacts"])
        self.assertEqual([], manifest["field_experiments"])
        scripted = manifest["scripted_evaluation"]
        self.assertEqual("scripted_truth", scripted["reference_kind"])
        self.assertFalse(scripted["field_performance_claim"])

    def test_replay_fixture_validates_as_synthetic_and_has_no_location_or_imu(self):
        fixture = ROOT / "mobile/app/src/androidTest/assets/demo-replay-v1"
        content = open_session(fixture)
        try:
            records = list(content.records)
            self.assertEqual("simulation", content.metadata.source.value)
            self.assertEqual(2, len(records))
            self.assertTrue(all(record.event.data.TYPE == "diagnostic" for record in records))
            self.assertTrue(all(record.header.source.value == "replay_simulation" for record in records))
            self.assertTrue(all("SYNTHETIC_FIXTURE" in record.event.data.code for record in records))
        finally:
            content.close()

    def test_environment_manifest_matches_pinned_build_and_asset_manifests(self):
        manifest = json.loads((ROOT / "release/environment-manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("9.3.1", manifest["android_build"]["gradle"])
        self.assertEqual("9.1.1", manifest["android_build"]["agp"])
        self.assertFalse(manifest["android_build"]["distribution_signing_configured"])
        map_manifest = json.loads(
            (ROOT / "mobile/app/src/main/assets/offline/hyderabad/manifest.json").read_text(encoding="utf-8")
        )
        graph_manifest = json.loads(
            (ROOT / "mobile/app/src/main/assets/roadgraph/hyderabad-v1/manifest.json").read_text(encoding="utf-8")
        )
        self.assertEqual(map_manifest["files"][0]["sha256"], manifest["offline_assets"]["map"]["mbtiles_sha256"])
        self.assertEqual(graph_manifest["files"][0]["sha256"], manifest["offline_assets"]["road_graph"]["sha256"])


if __name__ == "__main__":
    unittest.main()
