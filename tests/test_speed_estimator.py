"""
tests/test_speed_estimator.py
============================================================
Verification tests for Model A (AI Speed & Vibration Estimator)
"""

import json
import unittest
from pathlib import Path

import numpy as np

from training.speed_estimator import NumpySpeedEstimator, compute_window_statistics, WINDOW_SIZE

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS_PATH = ROOT / "models" / "speed_estimator_weights.json"
ASSET_WEIGHTS_PATH = ROOT / "mobile" / "app" / "src" / "main" / "assets" / "models" / "speed_estimator_weights.json"
MANIFEST_PATH = ROOT / "models" / "ai_model_manifest.json"


class SpeedEstimatorTest(unittest.TestCase):
    def test_weights_exist_and_mirror_to_android_asset(self) -> None:
        self.assertTrue(WEIGHTS_PATH.is_file(), "Exported weights must exist in models/")
        self.assertTrue(ASSET_WEIGHTS_PATH.is_file(), "Weights must be mirrored to Android assets")
        self.assertEqual(WEIGHTS_PATH.read_bytes(), ASSET_WEIGHTS_PATH.read_bytes(),
                         "Android asset weights must match models/ weights exactly")

    def test_manifest_is_valid_and_approved(self) -> None:
        self.assertTrue(MANIFEST_PATH.is_file())
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.assertEqual(manifest["status"], "approved_model_artifacts")
        self.assertGreaterEqual(manifest["model_count"], 1)
        self.assertEqual(manifest["artifacts"][0]["file"], "speed_estimator_weights.json")

    def test_numpy_inference_stationary(self) -> None:
        engine = NumpySpeedEstimator.from_file(WEIGHTS_PATH)
        # Stationary window: zero mean, minimal noise
        win = np.random.normal(0, 0.005, (WINDOW_SIZE, 6)).astype(np.float32)
        speed, sigma, is_stationary, conf = engine.predict(win)
        self.assertTrue(is_stationary, "Quiet window must be classified as stationary")
        self.assertEqual(speed, 0.0, "Stationary speed must be clamped to 0.0 m/s")
        self.assertGreater(conf, 0.8, "Stationary confidence should be high")

    def test_numpy_inference_non_negative_and_bounded(self) -> None:
        engine = NumpySpeedEstimator.from_file(WEIGHTS_PATH)
        # Moving window: 12 m/s forward cruise with tire vibration harmonics
        t = np.arange(WINDOW_SIZE) * 0.01
        win = np.zeros((WINDOW_SIZE, 6), dtype=np.float32)
        win[:, 0] = 0.02 + 0.1 * np.sin(2 * np.pi * 6.0 * t)  # 6 Hz tire harmonic
        win[:, 1] = np.random.normal(0, 0.04, WINDOW_SIZE)
        win[:, 2] = 0.12 * np.sin(2 * np.pi * 12.0 * t)  # road noise
        win[:, 5] = 0.001  # small yaw rate

        speed, sigma, is_stationary, conf = engine.predict(win)
        self.assertGreaterEqual(speed, 0.0, "Speed must never be negative")
        self.assertGreater(sigma, 0.0, "Sigma must be strictly positive")
        self.assertLessEqual(speed, 50.0, "Speed must be bounded within plausible ground vehicle range")

    def test_feature_extractor_shape_and_finite(self) -> None:
        win = np.random.normal(0, 0.1, (WINDOW_SIZE, 6)).astype(np.float32)
        stats = compute_window_statistics(win)
        self.assertEqual(stats.shape, (10,))
        self.assertTrue(np.all(np.isfinite(stats)), "Feature stats must all be finite")


if __name__ == "__main__":
    unittest.main()
