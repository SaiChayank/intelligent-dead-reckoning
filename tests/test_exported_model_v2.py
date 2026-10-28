"""
tests/test_exported_model_v2.py
============================================================
Verification tests for exported Model A v2 (Variant B: real-data invariant).
Asserts:
- Versioned weights file exists in models/ and matches SHA-256 in manifest.
- Existing v1 weights file remains unchanged and mirrored to Android assets.
- Manifest reflects 2 models with honest metadata, split method, and limitations.
- NumPy invariant inference engine executes deterministic inference on (100, 4) windows.
- CLI --help runs cleanly.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import unittest
from pathlib import Path

import numpy as np
import pytest

try:
    import torch  # noqa: F401  (training.export_final_model_a needs it)
except ImportError:
    raise unittest.SkipTest("PyTorch is not installed; Model A export tests need it")

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.export_final_model_a import NumpyInvariantSpeedEstimator

MODELS_DIR = PROJECT_ROOT / "models"
V1_WEIGHTS_PATH = MODELS_DIR / "speed_estimator_weights.json"
V2_WEIGHTS_PATH = MODELS_DIR / "speed_estimator_v2_invariant_weights.json"
ASSET_V1_PATH = PROJECT_ROOT / "mobile" / "app" / "src" / "main" / "assets" / "models" / "speed_estimator_weights.json"
MANIFEST_PATH = MODELS_DIR / "ai_model_manifest.json"


def test_v1_weights_unchanged() -> None:
    """Verify v1 synthetic baseline weights are untouched and mirrored to Android."""
    assert V1_WEIGHTS_PATH.is_file(), "v1 weights file must exist"
    assert ASSET_V1_PATH.is_file(), "v1 asset file must exist"
    assert V1_WEIGHTS_PATH.read_bytes() == ASSET_V1_PATH.read_bytes(), "v1 models and assets must match exactly"


def test_v2_weights_exist_and_match_manifest() -> None:
    """Verify v2 versioned invariant weights file exists and matches manifest hash."""
    assert V2_WEIGHTS_PATH.is_file(), "v2 invariant weights file must exist"

    manifest_bytes = MANIFEST_PATH.read_bytes()
    manifest = json.loads(manifest_bytes.decode("utf-8"))

    assert manifest["manifest_version"] == "2.0.0"
    assert manifest["model_count"] == 2
    assert len(manifest["artifacts"]) == 2

    # Check v2 artifact
    v2_entry = manifest["artifacts"][1]
    assert v2_entry["file"] == "speed_estimator_v2_invariant_weights.json"
    assert v2_entry["variant"] == "B_real_invariant"
    assert v2_entry["channels"] == 4

    # Verify sha256
    v2_bytes = V2_WEIGHTS_PATH.read_bytes()
    actual_sha = hashlib.sha256(v2_bytes).hexdigest()
    assert v2_entry["sha256"] == actual_sha, f"Manifest sha256 {v2_entry['sha256']} must match actual {actual_sha}"

    # Verify honest limitations documented
    assert "limitations" in v2_entry
    assert len(v2_entry["limitations"]) >= 4


def test_numpy_invariant_inference() -> None:
    """Verify pure NumPy invariant inference produces bounded, non-negative outputs on real data."""
    from training.iovnbd_dataset import IOVNBDDataset

    estimator = NumpyInvariantSpeedEstimator.from_file(V2_WEIGHTS_PATH)
    ds = IOVNBDDataset(feature_mode="invariant")

    # Ingest actual candidate sequence window
    batch = ds.get_sequence_batch("vta1b")
    assert len(batch.x_seq) > 0

    win = batch.x_seq[0].T  # (100, 4)
    stats = batch.x_stats[0]  # (10,)

    spd, sig, is_stop, conf = estimator.predict(win, stats)
    assert spd >= 0.0, "Estimated speed must be non-negative"
    assert sig > 0.0, "Uncertainty sigma must be strictly positive"
    assert 0.0 <= conf <= 1.0, "Confidence must be bounded in [0, 1]"
    assert isinstance(is_stop, bool), "Stop verdict must be boolean"


def test_export_script_cli_help() -> None:
    """Verify export script runs cleanly with --help."""
    cmd = [sys.executable, str(PROJECT_ROOT / "training" / "export_final_model_a.py"), "--help"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    assert res.returncode == 0
    assert "usage:" in res.stdout
