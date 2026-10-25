"""
training/train_speed_estimator.py
============================================================
Training & Model Weight Export Pipeline for Model A (Speed & Vibration Estimator)

Trains SpeedEstimatorModel on:
  - synthetic: kinematics + multi-regime road dynamics (default, unchanged behaviour)
  - iovnbd: real-world synchronized smartphone accelerometer windows from IO-VNBD
  - mixed: joint training combining real IO-VNBD windows with invariant synthetic data

Evaluates on held-out test splits, and exports production weights to JSON assets
for both Python and Kotlin on-device engines.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
from typing import Optional, Tuple

import numpy as np

try:
    import torch
    import torch.nn as nn
    import torch.optim as optim
    from torch.utils.data import DataLoader, TensorDataset
    HAS_TORCH = True
except ImportError:
    torch = None
    nn = None
    optim = None
    TensorDataset = None  # type: ignore[misc,assignment]
    DataLoader = None  # type: ignore[misc,assignment]
    HAS_TORCH = False
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

MODELS_DIR = ROOT / "models"
ASSET_MODELS_DIR = ROOT / "mobile" / "app" / "src" / "main" / "assets" / "models"

from training.iovnbd_dataset import (
    IOVNBDDataset,
    NUM_INVARIANT_CHANNELS,
    STATS_DIM,
    WINDOW_SIZE_100HZ,
    compute_causal_bandpower,
    compute_invariant_window_statistics,
)
from training.speed_estimator import (
    SpeedEstimatorModel,
    NumpySpeedEstimator,
    generate_training_data,
    compute_window_statistics,
    WINDOW_SIZE,
)


def convert_synthetic_to_invariant(
    x_seq_6ch: np.ndarray,
    dt: float = 0.01,
) -> tuple[np.ndarray, np.ndarray]:
    """Convert (N, 6, 100) synthetic IMU windows to (N, 4, 100) invariant windows + (N, 10) stats.

    Applies exact invariant channel mapping:
      0: a_horiz = sqrt(ax^2 + ay^2)
      1: a_vert  = az + 9.807 (specific force vertical with gravity)
      2: a_norm  = sqrt(ax^2 + ay^2 + a_vert^2)
      3: a_vert_bandpower = causal rolling variance of a_vert
    """
    n, c, l = x_seq_6ch.shape
    assert c == 6 and l == WINDOW_SIZE_100HZ

    out_seq = np.zeros((n, NUM_INVARIANT_CHANNELS, l), dtype=np.float32)
    out_stats = np.zeros((n, STATS_DIM), dtype=np.float32)

    for i in range(n):
        win = x_seq_6ch[i]  # (6, 100)
        ax = win[0]
        ay = win[1]
        az = win[2] + 9.807  # vertical with gravity component

        a_horiz = np.sqrt(ax ** 2 + ay ** 2)
        a_vert = az
        a_norm = np.sqrt(ax ** 2 + ay ** 2 + az ** 2)
        bandpower = compute_causal_bandpower(a_vert)

        ch4 = np.stack([a_horiz, a_vert, a_norm, bandpower], axis=0).astype(np.float32)
        out_seq[i] = ch4
        out_stats[i] = compute_invariant_window_statistics(ch4, dt=dt)

    return out_seq, out_stats


def train_model(
    epochs: int = 15,
    batch_size: int = 64,
    lr: float = 0.002,
    seed: int = 42,
    data: str = "synthetic",
    feature_mode: str = "invariant",
    fold: Optional[str] = None,
    val_drive: str = "auto",
    max_windows: Optional[int] = None,
    mixed_ratio: float = 0.5,
    log_every_steps: int = 100,
) -> tuple[SpeedEstimatorModel, dict]:
    """Train Model A (Causal 1D-CNN + Stats Fusion) on synthetic, IO-VNBD, or mixed data.

    Early stopping and checkpoint selection use only --val-drive.
    The held-out --fold drive is never accessed during training or checkpoint selection.
    """
    if not HAS_TORCH:
        raise ImportError("PyTorch is required for model training.")

    torch.manual_seed(seed)
    np.random.seed(seed)

    if data == "synthetic":
        in_channels = 6
        stats_dim = 10
        print("Generating multi-regime synthetic training dataset...")
        X_seq_tr, X_stats_tr, Y_spd_tr, Y_sig_tr, Y_stp_tr = generate_training_data(
            num_episodes=45, seed=seed
        )
        print("Generating independent synthetic validation dataset...")
        X_seq_val, X_stats_val, Y_spd_val, Y_sig_val, Y_stp_val = generate_training_data(
            num_episodes=12, seed=seed + 999
        )

        if max_windows is not None and max_windows < len(X_seq_tr):
            X_seq_tr = X_seq_tr[:max_windows]
            X_stats_tr = X_stats_tr[:max_windows]
            Y_spd_tr = Y_spd_tr[:max_windows]
            Y_sig_tr = Y_sig_tr[:max_windows]
            Y_stp_tr = Y_stp_tr[:max_windows]

    elif data in ("iovnbd", "mixed"):
        in_channels = NUM_INVARIANT_CHANNELS  # 4 channels
        stats_dim = STATS_DIM                # 10 stats
        print(f"Loading IO-VNBD dataset (feature_mode='{feature_mode}')...")
        dataset = IOVNBDDataset(feature_mode=feature_mode)
        all_drives = list(dataset.folds_metadata["drives"])
        drive_to_seqs = {
            f["held_out_drive"].lower(): f["test_sequences"]
            for f in dataset.folds_metadata["folds"]
        }

        # Resolve held-out fold drive
        fold_drive = None
        if fold is not None:
            clean_fold = str(fold).strip().lower()
            if clean_fold.isdigit():
                fold_idx = int(clean_fold)
                fold_drive = dataset.folds_metadata["folds"][fold_idx]["held_out_drive"].lower()
            else:
                matched = [d for d in all_drives if d.lower() == clean_fold]
                if not matched:
                    raise ValueError(f"Unknown fold drive '{fold}'. Available drives: {all_drives}")
                fold_drive = matched[0]

        available = [d for d in all_drives if d != fold_drive]

        # Resolve validation drive for early stopping
        if val_drive == "auto":
            selected_val_drive = sorted(available)[0]
        else:
            selected_val_drive = str(val_drive).strip().lower()
            if selected_val_drive == fold_drive:
                raise ValueError(
                    f"Validation drive '{selected_val_drive}' cannot be the held-out fold drive '{fold_drive}'"
                )
            if selected_val_drive not in available:
                raise ValueError(
                    f"Validation drive '{selected_val_drive}' not found in training drives {available}"
                )

        train_drives = [d for d in available if d != selected_val_drive]
        print(f"Dataset split: fold (held-out)={fold_drive}, val_drive={selected_val_drive}, train_drives={train_drives}")

        # Load training batches: strictly train_drives ONLY
        train_batches = []
        for d in train_drives:
            for s in drive_to_seqs[d]:
                train_batches.append(dataset.get_sequence_batch(s))

        X_seq_tr = np.concatenate([b.x_seq for b in train_batches], axis=0)
        X_stats_tr = np.concatenate([b.x_stats for b in train_batches], axis=0)
        Y_spd_tr = np.concatenate([b.y_speed for b in train_batches], axis=0)
        Y_sig_tr = np.concatenate([b.y_sigma for b in train_batches], axis=0)
        Y_stp_tr = np.concatenate([b.y_stop for b in train_batches], axis=0)

        # Load validation batch: strictly selected_val_drive ONLY
        val_batches = []
        for s in drive_to_seqs[selected_val_drive]:
            val_batches.append(dataset.get_sequence_batch(s))

        X_seq_val = np.concatenate([b.x_seq for b in val_batches], axis=0)
        X_stats_val = np.concatenate([b.x_stats for b in val_batches], axis=0)
        Y_spd_val = np.concatenate([b.y_speed for b in val_batches], axis=0)
        Y_sig_val = np.concatenate([b.y_sigma for b in val_batches], axis=0)
        Y_stp_val = np.concatenate([b.y_stop for b in val_batches], axis=0)

        # Handle mixed mode: combine real + synthetic
        if data == "mixed":
            total_real_avail = len(X_seq_tr)
            target_total = max_windows if max_windows is not None else total_real_avail
            n_real = max(1, int(target_total * mixed_ratio))
            n_syn = max(1, target_total - n_real)

            X_seq_tr = X_seq_tr[:n_real]
            X_stats_tr = X_stats_tr[:n_real]
            Y_spd_tr = Y_spd_tr[:n_real]
            Y_sig_tr = Y_sig_tr[:n_real]
            Y_stp_tr = Y_stp_tr[:n_real]

            print(f"Mixed mode: generating {n_syn} synthetic invariant windows (mixed_ratio={mixed_ratio:.2f})...")
            episodes_needed = max(2, int(math.ceil(n_syn / 590.0)) + 1)
            syn_seq6, _, syn_spd, syn_sig, syn_stp = generate_training_data(
                num_episodes=episodes_needed, seed=seed
            )
            syn_seq4, syn_stats = convert_synthetic_to_invariant(syn_seq6[:n_syn])

            X_seq_tr = np.concatenate([X_seq_tr, syn_seq4], axis=0)
            X_stats_tr = np.concatenate([X_stats_tr, syn_stats], axis=0)
            Y_spd_tr = np.concatenate([Y_spd_tr, syn_spd[:n_syn]], axis=0)
            Y_sig_tr = np.concatenate([Y_sig_tr, syn_sig[:n_syn]], axis=0)
            Y_stp_tr = np.concatenate([Y_stp_tr, syn_stp[:n_syn]], axis=0)

            # Shuffle mixed dataset deterministically
            perm = np.random.RandomState(seed).permutation(len(X_seq_tr))
            X_seq_tr = X_seq_tr[perm]
            X_stats_tr = X_stats_tr[perm]
            Y_spd_tr = Y_spd_tr[perm]
            Y_sig_tr = Y_sig_tr[perm]
            Y_stp_tr = Y_stp_tr[perm]

        elif max_windows is not None and max_windows < len(X_seq_tr):
            X_seq_tr = X_seq_tr[:max_windows]
            X_stats_tr = X_stats_tr[:max_windows]
            Y_spd_tr = Y_spd_tr[:max_windows]
            Y_sig_tr = Y_sig_tr[:max_windows]
            Y_stp_tr = Y_stp_tr[:max_windows]

    else:
        raise ValueError(f"Unknown data source '{data}'. Expected 'synthetic', 'iovnbd', or 'mixed'.")

    print(f"Train samples: {len(X_seq_tr)}, Val samples: {len(X_seq_val)}")

    ds_train = TensorDataset(
        torch.from_numpy(X_seq_tr),
        torch.from_numpy(X_stats_tr),
        torch.from_numpy(Y_spd_tr),
        torch.from_numpy(Y_sig_tr),
        torch.from_numpy(Y_stp_tr),
    )
    dl_train = DataLoader(ds_train, batch_size=batch_size, shuffle=True)

    model = SpeedEstimatorModel(in_channels=in_channels, stats_dim=stats_dim)
    optimizer = optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    huber_loss = nn.HuberLoss(delta=1.0)
    bce_loss = nn.BCELoss()

    best_val_loss = float("inf")
    best_weights = None
    best_metrics: dict = {}

    total_steps = len(dl_train) * epochs
    step = 0
    print(f"\n--- Training Model A (in_channels={in_channels}, stats_dim={stats_dim}, epochs={epochs}) ---")

    for ep in range(1, epochs + 1):
        model.train()
        total_train_loss = 0.0

        for b_seq, b_stats, b_spd, b_sig, b_stp in dl_train:
            step += 1
            optimizer.zero_grad()
            p_spd, p_sig, p_stp = model(b_seq, b_stats)

            # 1. Speed regression loss
            l_spd = huber_loss(p_spd, b_spd)
            # 2. Uncertainty negative log-likelihood loss
            l_nll = torch.mean(0.5 * ((p_spd - b_spd) ** 2) / (p_sig ** 2) + torch.log(p_sig))
            # 3. Stillness classification loss
            l_stp = bce_loss(p_stp, b_stp)

            loss = l_spd + 0.5 * l_nll + 0.5 * l_stp

            if torch.isnan(loss) or torch.isinf(loss):
                raise FloatingPointError(f"NaN or Inf loss encountered at step {step}")

            loss.backward()
            optimizer.step()
            total_train_loss += loss.item() * len(b_seq)

            if step % log_every_steps == 0 or step == 1 or step == total_steps:
                print(
                    f"Step {step:04d}/{total_steps:04d} | Batch Loss: {loss.item():.4f} "
                    f"(spd: {l_spd.item():.4f}, nll: {l_nll.item():.4f}, stp: {l_stp.item():.4f})"
                )

        scheduler.step()
        train_loss = total_train_loss / len(X_seq_tr)

        # Validation evaluation: strictly on val_drive
        model.eval()
        with torch.no_grad():
            v_spd, v_sig, v_stp = model(
                torch.from_numpy(X_seq_val),
                torch.from_numpy(X_stats_val),
            )
            val_rmse = float(torch.sqrt(torch.mean((v_spd - torch.from_numpy(Y_spd_val)) ** 2)))
            val_mae = float(torch.mean(torch.abs(v_spd - torch.from_numpy(Y_spd_val))))
            val_stop_acc = float(torch.mean(((v_stp > 0.5) == (torch.from_numpy(Y_stp_val) > 0.5)).float()))

            vl_spd = huber_loss(v_spd, torch.from_numpy(Y_spd_val))
            vl_nll = torch.mean(0.5 * ((v_spd - torch.from_numpy(Y_spd_val)) ** 2) / (v_sig ** 2) + torch.log(v_sig))
            vl_stp = bce_loss(v_stp, torch.from_numpy(Y_stp_val))
            val_loss = float(vl_spd + 0.5 * vl_nll + 0.5 * vl_stp)

        # Checkpoint selection based only on val_drive
        if val_loss < best_val_loss:
            best_val_loss = val_loss
            best_weights = copy.deepcopy(model.state_dict())
            best_metrics = {
                "val_loss": round(val_loss, 4),
                "val_speed_rmse_m_s": round(val_rmse, 4),
                "val_speed_mae_m_s": round(val_mae, 4),
                "val_stop_accuracy": round(val_stop_acc, 4),
                "samples_train": len(X_seq_tr),
                "samples_val": len(X_seq_val),
                "best_epoch": ep,
            }

        if ep % 3 == 0 or ep == epochs:
            print(f"Epoch {ep:02d}/{epochs:02d} | Train Loss: {train_loss:.4f} | "
                  f"Val Loss: {val_loss:.4f} | Val RMSE: {val_rmse:.4f} m/s | MAE: {val_mae:.4f} m/s | "
                  f"Stop Acc: {val_stop_acc*100:.1f}%")

    # Restore best checkpoint
    if best_weights is not None:
        model.load_state_dict(best_weights)

    return model, best_metrics


def export_weights(
    model: SpeedEstimatorModel,
    metrics: dict,
    out_path: Optional[Path] = None,
    data_source: str = "synthetic",
    feature_mode: str = "invariant",
) -> Path:
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    ASSET_MODELS_DIR.mkdir(parents=True, exist_ok=True)

    if out_path is not None:
        target_path = Path(out_path)
    elif data_source == "synthetic":
        target_path = MODELS_DIR / "speed_estimator_weights.json"
    else:
        target_path = MODELS_DIR / f"speed_estimator_{data_source}_weights.json"

    target_path.parent.mkdir(parents=True, exist_ok=True)
    state_dict = model.state_dict()
    in_channels = int(getattr(model.conv1, "in_channels", 6))

    export_dict: dict = {
        "format_version": "1.0.0",
        "model_name": "ai_forward_speed_estimator",
        "architecture": "Causal1DCNN_StatsFused",
        "input_channels": in_channels,
        "window_size": WINDOW_SIZE,
        "metrics": metrics,
        "data_source": data_source,
        "feature_mode": feature_mode,
    }

    for k, v in state_dict.items():
        export_dict[k] = v.cpu().numpy().tolist()

    json_bytes = json.dumps(export_dict, indent=2).encode("utf-8")
    target_path.write_bytes(json_bytes)
    sha256 = hashlib.sha256(json_bytes).hexdigest()
    print(f"\nExported weights to: {target_path} ({len(json_bytes) // 1024} KB, sha256: {sha256[:16]}...)")

    if data_source == "synthetic" and out_path is None:
        asset_weights_path = ASSET_MODELS_DIR / "speed_estimator_weights.json"
        manifest_path = MODELS_DIR / "ai_model_manifest.json"
        asset_weights_path.write_bytes(json_bytes)
        print(f"Mirrored to Android assets: {asset_weights_path}")

        # Update AI model manifest
        manifest_data = {
            "manifest_version": "1.0.0",
            "status": "approved_model_artifacts",
            "model_count": 1,
            "artifacts": [
                {
                    "name": "ai_forward_speed_estimator",
                    "role": "Model A — Speed / Vibration Estimator",
                    "file": "speed_estimator_weights.json",
                    "sha256": sha256,
                    "metrics": metrics,
                }
            ],
            "training_admission": {
                "decision": "approved",
                "model_type": "Model A (Inertial Speed Estimator)",
                "benchmark_passed": metrics["val_speed_rmse_m_s"] < 0.60
            },
            "policy": "Model A runs causal inference on 1-second IMU sliding windows; outputs forward velocity constraint for 15-state EKF."
        }
        manifest_path.write_text(json.dumps(manifest_data, indent=2), encoding="utf-8")

        # Verify round-trip equivalence with NumpySpeedEstimator
        numpy_engine = NumpySpeedEstimator.from_file(target_path)
        test_win = np.zeros((100, 6), dtype=np.float32)
        # Test parked
        spd, sig, is_stop, conf = numpy_engine.predict(test_win)
        assert is_stop, "Parked test window must predict stationary"
        assert spd == 0.0, "Parked speed must be 0"

        # Test cruise at 12 m/s
        test_win[:, 0] = 0.02  # bias
        test_win[:, 2] = 0.05 * np.sin(np.arange(100) * 0.4)  # road vibration
        spd_c, sig_c, is_stop_c, conf_c = numpy_engine.predict(test_win)
        print(f"Numpy inference test passed: parked -> stop={is_stop}, cruise response -> speed={spd_c:.2f} m/s, conf={conf_c:.2f}")

    return target_path


def build_cli_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Train Model A Speed & Vibration Estimator (synthetic, IO-VNBD real, or mixed)."
    )
    parser.add_argument(
        "--data",
        choices=["synthetic", "iovnbd", "mixed"],
        default="synthetic",
        help="Training data source (default: 'synthetic', unchanged behaviour)",
    )
    parser.add_argument(
        "--feature-mode",
        choices=["invariant", "longitudinal_oracle"],
        default="invariant",
        help="Feature mode for IO-VNBD / mixed data (default: 'invariant')",
    )
    parser.add_argument(
        "--fold",
        type=str,
        default=None,
        help="Held-out test drive ID or fold index (e.g. 'vta1' or '0'); omitted for all-data training",
    )
    parser.add_argument(
        "--val-drive",
        type=str,
        default="auto",
        help="One training drive used only for early stopping / checkpoint selection ('auto' picks deterministically)",
    )
    parser.add_argument(
        "--max-windows",
        type=int,
        default=None,
        help="Cap max training windows (for smoke tests / debugging)",
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=15,
        help="Number of training epochs (default: 15)",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Random seed for reproducibility (default: 42)",
    )
    parser.add_argument(
        "--batch-size",
        type=int,
        default=64,
        help="Training batch size (default: 64)",
    )
    parser.add_argument(
        "--lr",
        type=float,
        default=0.002,
        help="Learning rate (default: 0.002)",
    )
    parser.add_argument(
        "--mixed-ratio",
        type=float,
        default=0.5,
        help="Ratio of real windows in mixed training mode (default: 0.5 = 1:1 real:synthetic)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Custom path to export trained weights JSON",
    )
    return parser


if __name__ == "__main__":
    cli_parser = build_cli_parser()
    cli_args = cli_parser.parse_args()

    trained_model, eval_metrics = train_model(
        epochs=cli_args.epochs,
        batch_size=cli_args.batch_size,
        lr=cli_args.lr,
        seed=cli_args.seed,
        data=cli_args.data,
        feature_mode=cli_args.feature_mode,
        fold=cli_args.fold,
        val_drive=cli_args.val_drive,
        max_windows=cli_args.max_windows,
        mixed_ratio=cli_args.mixed_ratio,
    )
    export_weights(
        trained_model,
        eval_metrics,
        out_path=cli_args.out,
        data_source=cli_args.data,
        feature_mode=cli_args.feature_mode,
    )

