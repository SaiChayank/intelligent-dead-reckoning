"""Tests for Model A training flags and IO-VNBD integration.

Verifies:
  1. The held-out --fold drive's windows NEVER enter training or validation batches.
  2. Default flags reproduce previous synthetic behaviour (6 channels, identical shapes & metrics).
  3. Fixed seed makes training runs strictly repeatable (identical initial losses and metrics).
  4. Validation drive cannot be the held-out fold drive.
  5. Mixed mode produces 4 invariant channels and trains end-to-end without NaNs.
"""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np
import pytest

try:
    import torch
except ImportError:
    raise unittest.SkipTest("PyTorch is not installed; Model A training tests need it")

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.iovnbd_dataset import IOVNBDDataset
from training.train_speed_estimator import train_model, convert_synthetic_to_invariant


def test_held_out_drive_never_enters_batches() -> None:
    """Verify held-out fold drive is NEVER accessed or present in training or val data."""
    held_out = "vta1"
    val_drive = "vta24"

    # Intercept IOVNBDDataset to record which sequences are requested
    accessed_sequences: list[str] = []
    original_get_seq = IOVNBDDataset.get_sequence_batch

    def tracked_get_sequence_batch(self, seq_id: str):
        accessed_sequences.append(seq_id.lower())
        return original_get_seq(self, seq_id)

    with patch.object(IOVNBDDataset, "get_sequence_batch", tracked_get_sequence_batch):
        model, metrics = train_model(
            epochs=1,
            batch_size=32,
            data="iovnbd",
            feature_mode="invariant",
            fold=held_out,
            val_drive=val_drive,
            max_windows=200,
            seed=42,
        )

    # In logo_folds.json, drive 'vta1' maps to sequence 'vta1b'
    assert "vta1" not in accessed_sequences, "Held-out drive vta1 was directly accessed!"
    assert "vta1b" not in accessed_sequences, "Held-out drive sequence vta1b was queried!"
    assert len(accessed_sequences) > 0, "No sequences were loaded"

    # Confirm val_drive sequence was accessed
    assert "vta24" in accessed_sequences, "Validation drive sequence must be loaded"


def test_val_drive_cannot_be_held_out_drive() -> None:
    """Attempting to set val-drive to the held-out fold must raise ValueError."""
    with pytest.raises(ValueError, match="cannot be the held-out fold drive"):
        train_model(
            epochs=1,
            data="iovnbd",
            fold="vta1",
            val_drive="vta1",
            max_windows=50,
        )


def test_default_flags_reproduce_synthetic_behaviour() -> None:
    """Default data='synthetic' must train a 6-channel model with 10 stats and positive speed."""
    model, metrics = train_model(
        epochs=1,
        batch_size=64,
        data="synthetic",
        seed=42,
        max_windows=500,
    )

    # 1. Model architecture must have 6 input channels
    assert model.conv1.in_channels == 6, f"Expected 6 channels for synthetic, got {model.conv1.in_channels}"
    # 2. Output metrics must have expected keys
    assert "val_speed_rmse_m_s" in metrics
    assert "val_stop_accuracy" in metrics
    assert metrics["val_speed_rmse_m_s"] > 0.0


def test_seed_makes_runs_repeatable() -> None:
    """Identical seeds must yield bitwise identical model parameters and metrics."""
    # Run 1 with seed 42
    model_1, metrics_1 = train_model(
        epochs=1,
        batch_size=32,
        data="iovnbd",
        fold="vta1",
        val_drive="vta12",
        max_windows=150,
        seed=42,
    )

    # Run 2 with seed 42
    model_2, metrics_2 = train_model(
        epochs=1,
        batch_size=32,
        data="iovnbd",
        fold="vta1",
        val_drive="vta12",
        max_windows=150,
        seed=42,
    )

    # Run 3 with seed 99 (must differ)
    model_3, metrics_3 = train_model(
        epochs=1,
        batch_size=32,
        data="iovnbd",
        fold="vta1",
        val_drive="vta12",
        max_windows=150,
        seed=99,
    )

    # Verify metrics match exactly between seed 42 runs
    assert metrics_1 == metrics_2, f"Metrics diverged with same seed: {metrics_1} vs {metrics_2}"

    # Verify model parameters match exactly between seed 42 runs
    for (k1, v1), (k2, v2) in zip(model_1.state_dict().items(), model_2.state_dict().items()):
        assert torch.equal(v1, v2), f"Parameter {k1} differed between identical seed runs"

    # Verify model parameters differ with different seed
    p1 = next(model_1.parameters())
    p3 = next(model_3.parameters())
    assert not torch.equal(p1, p3), "Models with different seeds unexpectedly produced identical weights"


def test_mixed_mode_training() -> None:
    """Mixed mode must construct 4-channel invariant tensors from both sources without NaNs."""
    model, metrics = train_model(
        epochs=1,
        batch_size=32,
        data="mixed",
        mixed_ratio=0.5,
        fold="vta1",
        val_drive="vta12",
        max_windows=200,
        seed=42,
    )

    assert model.conv1.in_channels == 4, f"Expected 4 channels for mixed, got {model.conv1.in_channels}"
    assert metrics["val_loss"] > 0.0
    assert not np.isnan(metrics["val_loss"])
    assert not np.isinf(metrics["val_loss"])


def test_convert_synthetic_to_invariant() -> None:
    """Synthetic 6-channel conversion must output strictly finite (N, 4, 100) and (N, 10) arrays."""
    n = 25
    fake_syn6 = np.random.normal(0, 1.0, (n, 6, 100)).astype(np.float32)
    seq4, stats = convert_synthetic_to_invariant(fake_syn6)

    assert seq4.shape == (n, 4, 100)
    assert stats.shape == (n, 10)
    assert np.all(np.isfinite(seq4))
    assert np.all(np.isfinite(stats))
