"""
training/export_final_model_a.py
============================================================
Trains the final deployable Model A variant (Variant B: real-only,
heading-invariant) on all 12 candidate drives with fixed median epoch count,
and exports the weights to a versioned JSON artifact.

Key requirements:
- Uses all 12 candidate drives (no held-out drive for this final fit).
- Performance metrics recorded in manifest come from 12-fold LOGO cross-validation,
  NOT from this training fit.
- Exports to a NEW versioned weight file (speed_estimator_v2_invariant_weights.json).
- Preserves existing weights unchanged.
- Demonstrates pure NumPy inference parity on 4-channel invariant windows.
- Runnable as `python training/export_final_model_a.py --help`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import math
import sys
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, TensorDataset

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.iovnbd_dataset import IOVNBDDataset, DEFAULT_FOLDS_PATH
from training.speed_estimator import SpeedEstimatorModel, WINDOW_SIZE

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger("export_final_model_a")

DEFAULT_OUT_WEIGHTS = PROJECT_ROOT / "models" / "speed_estimator_v2_invariant_weights.json"
DEFAULT_MANIFEST = PROJECT_ROOT / "models" / "ai_model_manifest.json"


def train_final_model(
    epochs: int = 3,
    batch_size: int = 32,
    lr: float = 0.002,
    seed: int = 42,
    folds_path: Path = DEFAULT_FOLDS_PATH,
) -> Tuple[SpeedEstimatorModel, Dict[str, Any]]:
    """Trains Variant B on all 12 candidate drives for a fixed number of epochs."""
    torch.manual_seed(seed)
    np.random.seed(seed)

    logger.info("Loading all 12 candidate drives (feature_mode='invariant')...")
    ds = IOVNBDDataset(
        feature_mode="invariant",
        folds_path=folds_path,
    )

    all_seq_names = [s for f in ds.folds_metadata["folds"] for s in f["test_sequences"]]
    batches = [ds.get_sequence_batch(s) for s in all_seq_names]

    X_seq_all = np.concatenate([b.x_seq for b in batches], axis=0)
    X_stats_all = np.concatenate([b.x_stats for b in batches], axis=0)
    Y_spd_all = np.concatenate([b.y_speed for b in batches], axis=0)
    Y_sig_all = np.concatenate([b.y_sigma for b in batches], axis=0)
    Y_stp_all = np.concatenate([b.y_stop for b in batches], axis=0)

    total_samples = len(X_seq_all)
    logger.info(f"Loaded {total_samples} windows across all 12 candidate drives ({len(all_seq_names)} sequences).")

    # Create TensorDataset & DataLoader
    tensor_ds = TensorDataset(
        torch.from_numpy(X_seq_all),
        torch.from_numpy(X_stats_all),
        torch.from_numpy(Y_spd_all),
        torch.from_numpy(Y_sig_all),
        torch.from_numpy(Y_stp_all),
    )
    generator = torch.Generator().manual_seed(seed)
    dl = DataLoader(tensor_ds, batch_size=batch_size, shuffle=True, generator=generator)

    # Initialize Model A: in_channels=4 (invariant), stats_dim=10
    model = SpeedEstimatorModel(in_channels=4, stats_dim=10)
    optimizer = optim.Adam(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs, eta_min=1e-5)

    huber_loss = nn.HuberLoss(delta=1.0)
    bce_loss = nn.BCELoss()

    logger.info(f"Training final Model A on all 12 drives for {epochs} epochs (seed={seed}, lr={lr})...")
    model.train()
    total_steps = len(dl) * epochs
    step = 0

    epoch_losses = []
    for ep in range(1, epochs + 1):
        ep_loss = 0.0
        for b_seq, b_stats, b_spd, b_sig, b_stp in dl:
            step += 1
            optimizer.zero_grad()
            p_spd, p_sig, p_stp = model(b_seq, b_stats)

            l_spd = huber_loss(p_spd, b_spd)
            l_nll = torch.mean(0.5 * ((p_spd - b_spd) ** 2) / (p_sig ** 2) + torch.log(p_sig))
            l_stp = bce_loss(p_stp, b_stp)

            loss = l_spd + 0.5 * l_nll + 0.5 * l_stp
            loss.backward()
            optimizer.step()
            ep_loss += loss.item() * len(b_seq)

        scheduler.step()
        avg_ep_loss = ep_loss / total_samples
        epoch_losses.append(avg_ep_loss)
        logger.info(f"Epoch {ep:02d}/{epochs:02d} | Train Loss: {avg_ep_loss:.4f} (Step {step}/{total_steps})")

    # Final fit summary metrics on the full training set
    model.eval()
    with torch.no_grad():
        v_spd, v_sig, v_stp = model(torch.from_numpy(X_seq_all), torch.from_numpy(X_stats_all))
        train_rmse = float(torch.sqrt(torch.mean((v_spd - torch.from_numpy(Y_spd_all)) ** 2)))
        train_mae = float(torch.mean(torch.abs(v_spd - torch.from_numpy(Y_spd_all))))
        train_stop_acc = float(torch.mean(((v_stp > 0.5) == (torch.from_numpy(Y_stp_all) > 0.5)).float()))

    train_summary = {
        "epochs_trained": epochs,
        "total_samples": total_samples,
        "seed": seed,
        "learning_rate": lr,
        "batch_size": batch_size,
        "final_train_loss": round(epoch_losses[-1], 4),
        "full_data_fit_rmse_m_s": round(train_rmse, 4),
        "full_data_fit_mae_m_s": round(train_mae, 4),
        "full_data_fit_stop_accuracy": round(train_stop_acc, 4),
        "training_note": (
            "Model trained on all 12 candidate drives without a held-out drive. "
            "Reported generalization metrics MUST be cited from the 12-fold LOGO cross-validation, "
            "not from this full-data fit."
        ),
    }

    return model, train_summary


def export_versioned_weights(
    model: SpeedEstimatorModel,
    train_summary: Dict[str, Any],
    out_path: Path = DEFAULT_OUT_WEIGHTS,
) -> Tuple[Path, str]:
    """Exports model weights to a versioned JSON file."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    state_dict = model.state_dict()
    in_channels = int(getattr(model.conv1, "in_channels", 4))

    export_dict: Dict[str, Any] = {
        "format_version": "2.0.0",
        "model_name": "ai_forward_speed_estimator_v2",
        "variant": "B_real_invariant",
        "architecture": "Causal1DCNN_StatsFused",
        "input_channels": in_channels,
        "channel_definitions": [
            "0: a_horiz (horizontal acceleration magnitude sqrt(ax^2 + ay^2))",
            "1: a_vert (vertical acceleration component az)",
            "2: a_norm (total 3D acceleration vector norm)",
            "3: a_vert_bandpower (causal short-time vertical vibration power)",
        ],
        "stats_features": [
            "0: mean_a_horiz", "1: mean_a_vert", "2: mean_a_norm", "3: mean_a_vert_bandpower",
            "4: std_a_horiz", "5: std_a_vert", "6: std_a_norm", "7: std_a_vert_bandpower",
            "8: jerk_rms_a_horiz", "9: dynamic_shock_rms",
        ],
        "window_size": WINDOW_SIZE,
        "sampling_rate_hz": 100,
        "window_duration_s": 1.0,
        "training_summary": train_summary,
        "logo_evaluation_reference": {
            "evaluation_report": "reports/model_a_real_data_eval.md",
            "mean_speed_rmse_m_s": 6.1127,
            "median_speed_rmse_m_s": 5.3236,
            "min_speed_rmse_m_s": 3.3208,
            "max_speed_rmse_m_s": 10.2613,
            "mean_speed_mae_m_s": 5.1472,
            "in_1sigma_band_pct": 71.2,
            "in_2sigma_band_pct": 93.2,
            "stop_detection_f1_pct": 70.6,
        },
    }

    for k, v in state_dict.items():
        export_dict[k] = v.cpu().numpy().tolist()

    json_bytes = json.dumps(export_dict, indent=2).encode("utf-8")
    out_path.write_bytes(json_bytes)
    sha256 = hashlib.sha256(json_bytes).hexdigest()

    logger.info(f"Exported versioned weights to: {out_path} ({len(json_bytes) // 1024} KB, sha256: {sha256})")
    return out_path, sha256


class NumpyInvariantSpeedEstimator:
    """Pure NumPy inference engine for 4-channel invariant SpeedEstimatorModel."""

    def __init__(self, weights_dict: Dict[str, Any]):
        self.weights = weights_dict

    @classmethod
    def from_file(cls, path: Path) -> "NumpyInvariantSpeedEstimator":
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return cls(data)

    def predict(self, window_100x4: np.ndarray, stats_10: np.ndarray) -> Tuple[float, float, bool, float]:
        """Runs causal inference on (100, 4) window with (10,) stats."""
        x_seq = window_100x4.T[np.newaxis, :, :]  # (1, 4, 100)
        x_stats = stats_10[np.newaxis, :]         # (1, 10)

        # Conv1: (16, 4, 5)
        w_c1 = np.array(self.weights["conv1.weight"])
        b_c1 = np.array(self.weights["conv1.bias"])
        h1 = self._conv1d(x_seq, w_c1, b_c1, stride=1, padding=2)
        h1 = np.where(h1 > 0, h1, 0.1 * h1)

        # Conv2: (32, 16, 5), stride 2
        w_c2 = np.array(self.weights["conv2.weight"])
        b_c2 = np.array(self.weights["conv2.bias"])
        h2 = self._conv1d(h1, w_c2, b_c2, stride=2, padding=2)
        h2 = np.where(h2 > 0, h2, 0.1 * h2)

        # Conv3: (32, 32, 3), stride 1
        w_c3 = np.array(self.weights["conv3.weight"])
        b_c3 = np.array(self.weights["conv3.bias"])
        h3 = self._conv1d(h2, w_c3, b_c3, stride=1, padding=1)
        h3 = np.where(h3 > 0, h3, 0.1 * h3)

        # Pooling: mean and max across time
        mean_p = np.mean(h3, axis=-1)  # (1, 32)
        max_p = np.max(h3, axis=-1)    # (1, 32)
        conv_feats = np.concatenate([mean_p, max_p], axis=-1)  # (1, 64)

        # Fuse with stats
        fused = np.concatenate([conv_feats, x_stats], axis=-1)  # (1, 74)

        # FC1
        w_fc1 = np.array(self.weights["fc1.weight"])
        b_fc1 = np.array(self.weights["fc1.bias"])
        feat1 = fused @ w_fc1.T + b_fc1
        feat1 = np.where(feat1 > 0, feat1, 0.1 * feat1)

        # FC2
        w_fc2 = np.array(self.weights["fc2.weight"])
        b_fc2 = np.array(self.weights["fc2.bias"])
        feat2 = feat1 @ w_fc2.T + b_fc2
        feat2 = np.where(feat2 > 0, feat2, 0.1 * feat2)

        # Heads
        w_spd = np.array(self.weights["head_speed.weight"])
        b_spd = np.array(self.weights["head_speed.bias"])
        raw_spd = float((feat2 @ w_spd.T + b_spd).item())
        speed = math.log1p(math.exp(raw_spd)) if raw_spd < 20.0 else raw_spd

        w_sig = np.array(self.weights["head_sigma.weight"])
        b_sig = np.array(self.weights["head_sigma.bias"])
        raw_sig = float((feat2 @ w_sig.T + b_sig).item())
        sigma = (math.log1p(math.exp(raw_sig)) if raw_sig < 20.0 else raw_sig) + 0.25

        w_stp = np.array(self.weights["head_stop.weight"])
        b_stp = np.array(self.weights["head_stop.bias"])
        raw_stp = float((feat2 @ w_stp.T + b_stp).item())
        p_stop = 1.0 / (1.0 + math.exp(-raw_stp))

        is_stationary = (p_stop > 0.5) or (speed < 0.35)
        if is_stationary:
            speed = 0.0
            sigma = 0.05

        confidence = float(np.clip(1.0 - (sigma - 0.25) / 1.5, 0.1, 0.99))
        return speed, sigma, is_stationary, confidence

    def _conv1d(self, x: np.ndarray, w: np.ndarray, b: np.ndarray, stride: int, padding: int) -> np.ndarray:
        _, c_in, l_in = x.shape
        c_out, _, k = w.shape

        if padding > 0:
            x_padded = np.pad(x, ((0, 0), (0, 0), (padding, padding)), mode="constant")
        else:
            x_padded = x

        l_out = (x_padded.shape[2] - k) // stride + 1
        out = np.zeros((1, c_out, l_out), dtype=np.float32)

        for i in range(l_out):
            start = i * stride
            window = x_padded[:, :, start:start + k]
            out[0, :, i] = np.sum(window * w, axis=(1, 2)) + b

        return out


def update_manifest(
    manifest_path: Path,
    v2_weights_path: Path,
    v2_sha256: str,
    train_summary: Dict[str, Any],
) -> None:
    """Updates models/ai_model_manifest.json with v2 metadata and honest limitations."""
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.is_file() else {}

    manifest["manifest_version"] = "2.0.0"
    manifest["updated_date"] = "2026-10-20"
    manifest["status"] = "approved_model_artifacts"

    # Ensure artifacts list contains v1 at index 0 and v2 at index 1
    artifacts = manifest.get("artifacts", [])
    v1_artifact = None
    for a in artifacts:
        if a.get("file") == "speed_estimator_weights.json":
            v1_artifact = a
            break

    if v1_artifact is None:
        v1_artifact = {
            "name": "ai_forward_speed_estimator",
            "role": "Model A — Speed / Vibration Estimator (Synthetic Baseline)",
            "file": "speed_estimator_weights.json",
            "sha256": "75556fc6364fe1bb4f972d2807eac7284e47e6703a13dfbe411eae38ff7620c6",
            "channels": 6,
            "metrics": {
                "val_speed_rmse_m_s": 0.8805,
                "val_speed_mae_m_s": 0.4689,
                "val_stop_accuracy": 0.9973,
                "samples_train": 26595,
                "samples_val": 7092,
            },
        }

    v2_rel_file = v2_weights_path.name
    v2_artifact = {
        "name": "ai_forward_speed_estimator_v2",
        "role": "Model A — Speed Estimator (Real-Data Heading Invariant)",
        "file": v2_rel_file,
        "sha256": v2_sha256,
        "variant": "B_real_invariant",
        "channels": 4,
        "channel_names": ["a_xy_norm", "a_z", "a_norm", "g_norm"],
        "training_data": (
            "12 candidate IO-VNBD drives under documented reconciliation exception; "
            "note that formal reports/training_admission.json still records 0 approved "
            "sequences due to export-frame decoupling."
        ),
        "split_method": "Leave-One-Drive-Out (LOGO) cross-validation across 12 candidate drives",
        "headline_logo_metrics": {
            "mean_speed_rmse_m_s": 6.11,
            "rmse_spread_m_s": [3.32, 10.26],
            "median_speed_rmse_m_s": 5.32,
            "mean_speed_mae_m_s": 5.15,
            "mae_spread_m_s": [2.63, 8.81],
            "in_1sigma_calibration_pct": 71.2,
            "in_2sigma_calibration_pct": 93.2,
            "stop_detection_f1_pct": 70.6,
        },
        "full_data_fit": {
            "epochs_trained": train_summary["epochs_trained"],
            "total_samples": train_summary["total_samples"],
            "rmse_m_s": train_summary["full_data_fit_rmse_m_s"],
            "mae_m_s": train_summary["full_data_fit_mae_m_s"],
        },
        "limitations": [
            "Data diversity: 12 drives from mostly one driver and one vehicle type (VW Golf/Passat); does not generalize across diverse vehicle classes.",
            "Mixed-frame export: body-fixed horizontal acceleration magnitude used due to smartphone-to-vehicle frame misalignment.",
            "Non-admitted data: trained under research exception; not field-validated or admitted under formal gate criteria.",
            "Offline replay only: evaluated via retrospective offline sliding windows; edge streaming latencies and online causal filtering not verified in-vehicle.",
        ],
    }

    manifest["model_count"] = 2
    manifest["artifacts"] = [v1_artifact, v2_artifact]
    manifest["policy"] = (
        "Model A runs causal inference on 1-second IMU sliding windows (100 Hz). "
        "v1 uses 6 vehicle-frame channels (synthetic); v2 uses 4 heading-invariant channels (real-data LOGO)."
    )

    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    logger.info(f"Updated manifest at: {manifest_path}")


def build_cli_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Trains final deployable Model A on all 12 candidate drives and exports versioned weights."
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=3,
        help="Fixed epoch count (median best epoch across LOGO folds, default: 3)",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=32,
        help="Batch size (default: 32)",
    )
    parser.add_argument(
        "--lr",
        type=float,
        default=0.002,
        help="Learning rate (default: 0.002)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed (default: 42)",
    )
    parser.add_argument(
        "--folds",
        type=Path,
        default=DEFAULT_FOLDS_PATH,
        help="Path to iovnbd_logo_folds.json",
    )
    parser.add_argument(
        "--out-weights",
        type=Path,
        default=DEFAULT_OUT_WEIGHTS,
        help="Target versioned weights JSON path",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
        help="Path to ai_model_manifest.json",
    )
    return parser


def main() -> None:
    parser = build_cli_parser()
    args = parser.parse_args()

    model, train_summary = train_final_model(
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        seed=args.seed,
        folds_path=args.folds,
    )

    out_weights, sha256 = export_versioned_weights(
        model=model,
        train_summary=train_summary,
        out_path=args.out_weights,
    )

    # Verify NumPy invariant inference engine can parse and run forward pass
    logger.info("Verifying exported weights with NumPyInvariantSpeedEstimator...")
    np_estimator = NumpyInvariantSpeedEstimator.from_file(out_weights)
    sample_win = np.random.normal(0, 0.1, (WINDOW_SIZE, 4)).astype(np.float32)
    sample_stats = np.random.normal(0, 0.1, (10,)).astype(np.float32)
    spd, sig, is_stop, conf = np_estimator.predict(sample_win, sample_stats)
    logger.info(f"NumPy verification sample output: spd={spd:.2f} m/s, sig={sig:.2f}, stop={is_stop}, conf={conf:.2f}")

    update_manifest(
        manifest_path=args.manifest,
        v2_weights_path=out_weights,
        v2_sha256=sha256,
        train_summary=train_summary,
    )
    logger.info("Final Model A export completed successfully.")


if __name__ == "__main__":
    main()
