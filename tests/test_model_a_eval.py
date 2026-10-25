"""Tests for Model A Leave-One-Drive-Out (LOGO) evaluation pipeline.

Asserts:
  - Metric calculations (RMSE, MAE, calibration bands, stop detection) are mathematically sound.
  - Quick LOGO evaluation runs end-to-end and creates predictions and metrics CSV.
  - CLI executes cleanly with --help.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.model_a_logo_eval import compute_drive_metrics, run_logo_evaluation


def test_compute_drive_metrics() -> None:
    """Verify drive metrics calculation against synthetic ground truth."""
    y_true = np.array([0.0, 0.0, 10.0, 20.0], dtype=np.float32)
    y_pred = np.array([0.0, 0.5, 12.0, 18.0], dtype=np.float32)
    y_sigma = np.array([1.0, 1.0, 2.5, 2.5], dtype=np.float32)
    stop_true = np.array([1.0, 1.0, 0.0, 0.0], dtype=np.float32)
    stop_pred = np.array([1.0, 0.0, 0.0, 0.0], dtype=np.float32)

    m = compute_drive_metrics(y_true, y_pred, y_sigma, stop_true, stop_pred)

    # Errors: [0.0, 0.5, 2.0, 2.0]
    expected_rmse = np.sqrt(np.mean([0.0**2, 0.5**2, 2.0**2, 2.0**2]))
    assert np.isclose(m.speed_rmse, expected_rmse, atol=1e-3)
    assert np.isclose(m.speed_mae, 1.125, atol=1e-3)

    # Stop: TP=1, FP=0, FN=1 -> prec=1.0, rec=0.5, f1=2*(1*0.5)/(1.5) = 0.6667
    assert np.isclose(m.stop_precision, 1.0)
    assert np.isclose(m.stop_recall, 0.5)
    assert np.isclose(m.stop_f1, 2.0 / 3.0, atol=1e-3)

    # Calibration: e <= sigma:
    # 0.0 <= 1.0 (True)
    # 0.5 <= 1.0 (True)
    # 2.0 <= 2.5 (True)
    # 2.0 <= 2.5 (True)
    # All 4 inside 1-sigma!
    assert m.in_1sigma_rate == 1.0
    assert m.in_2sigma_rate == 1.0


def test_quick_logo_evaluation(tmp_path: Path) -> None:
    """Run quick evaluation (2 folds, 1 epoch) to verify full pipeline execution."""
    out_dir = tmp_path / "reports"
    pred_dir = out_dir / "predictions"

    df_agg, df_rmse, df_long = run_logo_evaluation(
        out_dir=out_dir,
        pred_dir=pred_dir,
        epochs=1,
        batch_size=32,
        seed=42,
        quick=True,
    )

    assert not df_agg.empty, "Aggregate table must not be empty"
    assert not df_rmse.empty, "Per-drive RMSE table must not be empty"
    assert not df_long.empty, "Long metrics table must not be empty"

    # Verify metrics CSV was saved
    csv_path = out_dir / "model_a_logo_metrics.csv"
    assert csv_path.is_file(), "model_a_logo_metrics.csv must be created"

    # Verify report was generated
    report_path = out_dir / "model_a_real_data_eval.md"
    assert report_path.is_file(), "model_a_real_data_eval.md must be created"

    # Verify predictions were written
    pred_files = list(pred_dir.glob("*.csv"))
    assert len(pred_files) > 0, "Prediction CSV files must be written"


def test_cli_execution() -> None:
    """Verify CLI --help runs cleanly."""
    cmd = [sys.executable, str(PROJECT_ROOT / "training" / "model_a_logo_eval.py"), "--help"]
    res = subprocess.run(cmd, capture_output=True, text=True)
    assert res.returncode == 0, f"CLI --help failed: {res.stderr}"
    assert "Leave-One-Drive-Out" in res.stdout
