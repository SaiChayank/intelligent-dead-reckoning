"""Tests for IO-VNBD Model A real-data sliding window dataset.

Asserts:
  - Correct window tensor shapes: x_seq is (N, 4, 100), x_stats is (N, 10), targets are (N, 1)
  - Zero NaNs or Infs in feature channels and targets
  - Target ranges: speed >= 0, stop in {0, 1}, stop condition matches speed < 0.2 m/s
  - Zero leakage across all 12 leave-one-drive-out folds
  - Invariant mode never touches heading columns (heading mutation yields identical features)
  - Diagnostic oracle mode produces valid tensors
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pandas as pd
import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.iovnbd_dataset import (
    DEFAULT_DATA_ROOT,
    DEFAULT_FOLDS_PATH,
    DEFAULT_TIME_ALIGN_PATH,
    NUM_INVARIANT_CHANNELS,
    STATS_DIM,
    WINDOW_SIZE_100HZ,
    IOVNBDDataset,
    build_invariant_channels,
    compute_causal_bandpower,
    compute_invariant_window_statistics,
    process_sequence_windows,
)


@pytest.fixture(scope="module")
def dataset_invariant() -> IOVNBDDataset:
    return IOVNBDDataset(
        folds_path=DEFAULT_FOLDS_PATH,
        time_align_path=DEFAULT_TIME_ALIGN_PATH,
        data_root=DEFAULT_DATA_ROOT,
        feature_mode="invariant",
        window_size=WINDOW_SIZE_100HZ,
        stride=10,
    )


def test_window_shapes_and_types(dataset_invariant: IOVNBDDataset) -> None:
    """Verify x_seq is (N, 4, 100) float32, x_stats is (N, 10) float32, targets are (N, 1) float32."""
    batch = dataset_invariant.get_sequence_batch("vta19")
    n = len(batch.x_seq)
    assert n > 0, "Batch must contain windows"

    assert batch.x_seq.shape == (n, NUM_INVARIANT_CHANNELS, WINDOW_SIZE_100HZ)
    assert batch.x_seq.dtype == np.float32

    assert batch.x_stats.shape == (n, STATS_DIM)
    assert batch.x_stats.dtype == np.float32

    assert batch.y_speed.shape == (n, 1)
    assert batch.y_speed.dtype == np.float32

    assert batch.y_stop.shape == (n, 1)
    assert batch.y_stop.dtype == np.float32

    assert batch.y_sigma.shape == (n, 1)
    assert batch.y_sigma.dtype == np.float32


def test_no_nans_or_infs(dataset_invariant: IOVNBDDataset) -> None:
    """Features and targets must be strictly finite across test folds."""
    train_b, test_b = dataset_invariant.get_fold_data(0)

    for name, batch in [("train", train_b), ("test", test_b)]:
        assert np.all(np.isfinite(batch.x_seq)), f"NaN/Inf found in {name} x_seq"
        assert np.all(np.isfinite(batch.x_stats)), f"NaN/Inf found in {name} x_stats"
        assert np.all(np.isfinite(batch.y_speed)), f"NaN/Inf found in {name} y_speed"
        assert np.all(np.isfinite(batch.y_stop)), f"NaN/Inf found in {name} y_stop"
        assert np.all(np.isfinite(batch.y_sigma)), f"NaN/Inf found in {name} y_sigma"


def test_target_ranges_and_consistency(dataset_invariant: IOVNBDDataset) -> None:
    """Speed must be >= 0; stop label must be {0, 1} and match speed < 0.2 m/s."""
    # Test on fold 3 (vta19, contains stops) and fold 5 (vta24, contains stops)
    for fold_idx in [3, 5]:
        _, test_b = dataset_invariant.get_fold_data(fold_idx)
        spd = test_b.y_speed.squeeze()
        stop = test_b.y_stop.squeeze()
        sig = test_b.y_sigma.squeeze()

        assert np.all(spd >= 0.0), "Negative speed encountered"
        assert np.all(np.isin(stop, [0.0, 1.0])), "Stop label must be binary 0 or 1"
        assert np.all(sig >= 0.25), "Target uncertainty must be >= 0.25 m/s"

        # Definition parity with synthetic generator: stop == 1.0 iff speed < 0.2
        expected_stop = (spd < 0.2).astype(np.float32)
        assert np.array_equal(stop, expected_stop), "Stop label does not match speed < 0.2 threshold"


def test_no_leakage_across_all_folds(dataset_invariant: IOVNBDDataset) -> None:
    """For every fold, test sequences must be completely disjoint from train sequences."""
    num_folds = len(dataset_invariant.folds_metadata["folds"])
    assert num_folds == 12, "Must evaluate all 12 LOGO folds"

    for fold_idx in range(num_folds):
        train_b, test_b = dataset_invariant.get_fold_data(fold_idx)
        train_seqs = set(train_b.sequence_names)
        test_seqs = set(test_b.sequence_names)

        overlap = train_seqs & test_seqs
        assert len(overlap) == 0, f"Fold {fold_idx} sequence leakage: {overlap}"
        assert len(test_seqs) > 0, f"Fold {fold_idx} has empty test set"
        assert len(train_seqs) > 0, f"Fold {fold_idx} has empty train set"


def test_invariant_mode_never_touches_heading() -> None:
    """Prove that 'invariant' mode is strictly heading-invariant.

    Corrupting/randomizing the VBOX heading channel must produce identical features.
    """
    vbox_files, phone_files = dataset_invariant = load_seq_paths = (
        IOVNBDDataset().vbox_files,
        IOVNBDDataset().phone_files,
    )
    offsets = IOVNBDDataset().offsets

    seq_id = "vta19"
    vbox_p = vbox_files[seq_id]
    phone_p = phone_files[seq_id]
    offset = offsets.get(seq_id, 0.0)

    # 1. Normal extraction
    batch_normal = process_sequence_windows(
        sequence_id=seq_id,
        vbox_path=vbox_p,
        phone_path=phone_p,
        time_offset_s=offset,
        feature_mode="invariant",
    )

    # 2. Extract with corrupted VBOX heading (NaNs, random values)
    from training.phase0.audit import read_frame as original_read_frame

    def mock_read_frame(path: Path) -> pd.DataFrame:
        df = original_read_frame(path)
        if "reference_heading" in df.columns:
            # Corrupt heading completely with random values and NaNs
            corrupted = np.random.uniform(0.0, 360.0, size=len(df))
            corrupted[::5] = np.nan
            df["reference_heading"] = corrupted
        return df

    with patch("training.iovnbd_dataset.read_frame", side_effect=mock_read_frame):
        batch_corrupted_heading = process_sequence_windows(
            sequence_id=seq_id,
            vbox_path=vbox_p,
            phone_path=phone_p,
            time_offset_s=offset,
            feature_mode="invariant",
        )

    # Features must be 100% byte-for-byte identical because invariant mode never touches heading
    assert np.array_equal(batch_normal.x_seq, batch_corrupted_heading.x_seq), (
        "Invariant mode features changed when heading was corrupted! It must not touch heading."
    )
    assert np.array_equal(batch_normal.x_stats, batch_corrupted_heading.x_stats), (
        "Invariant stats changed when heading was corrupted!"
    )


def test_longitudinal_oracle_mode() -> None:
    """Verify diagnostic longitudinal_oracle mode produces valid (N, 4, 100) tensors."""
    ds_oracle = IOVNBDDataset(feature_mode="longitudinal_oracle")
    batch = ds_oracle.get_sequence_batch("vta19")

    assert batch.x_seq.shape[1] == NUM_INVARIANT_CHANNELS
    assert np.all(np.isfinite(batch.x_seq))
    assert np.all(np.isfinite(batch.x_stats))


def test_cli_execution() -> None:
    """CLI must execute cleanly with --help and full execution."""
    cmd_help = [sys.executable, str(PROJECT_ROOT / "training" / "iovnbd_dataset.py"), "--help"]
    res_help = subprocess.run(cmd_help, capture_output=True, text=True)
    assert res_help.returncode == 0, f"--help failed: {res_help.stderr}"

    cmd_run = [
        sys.executable,
        str(PROJECT_ROOT / "training" / "iovnbd_dataset.py"),
        "--stride",
        "50",  # faster run for test
    ]
    res_run = subprocess.run(cmd_run, capture_output=True, text=True)
    assert res_run.returncode == 0, f"CLI execution failed: {res_run.stderr}"
    assert "IO-VNBD REAL-DATA DATASET SUMMARY" in res_run.stdout
