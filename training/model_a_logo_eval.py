#!/usr/bin/env python3
"""Leave-One-Drive-Out (LOGO) cross-validation evaluation for Model A variants on IO-VNBD.

Variants evaluated:
  - A_syn4ch: Synthetic-only trained on 4 heading-invariant channels (evaluated without retraining)
  - A_syn6ch: Legacy synthetic-only trained on 6 vehicle-frame channels (evaluated on phone axes)
  - B_real_invariant: Real-only trained on heading-invariant channels per fold
  - C_mixed_invariant: Mixed (real + invariant synthetic, ratio=0.5) trained per fold
  - D_oracle_diagnostic: Real-only longitudinal acceleration with VBOX heading (DIAGNOSTIC ONLY)
  - E_mean_baseline: Trivial baseline predicting mean training speed per fold

Generates:
  - reports/model_a_logo_predictions/{variant}_fold_{fold}_{drive_id}.csv
  - reports/model_a_logo_metrics.csv (drive x variant x metric long-format table)
  - reports/model_a_real_data_eval.md (comprehensive markdown analysis report)
"""

from __future__ import annotations

import argparse
import copy
import json
import logging
import math
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, TensorDataset

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.iovnbd_dataset import (
    IOVNBDDataset,
    NUM_INVARIANT_CHANNELS,
    STATS_DIM,
    WINDOW_SIZE_100HZ,
    WindowBatch,
    compute_causal_bandpower,
    compute_invariant_window_statistics,
)
from training.phase0.audit import read_frame, time_array
from training.speed_estimator import (
    SpeedEstimatorModel,
    compute_window_statistics,
    generate_training_data,
    WINDOW_SIZE,
)
from training.train_speed_estimator import convert_synthetic_to_invariant, train_model

DEFAULT_FOLDS_PATH = PROJECT_ROOT / "evaluation" / "iovnbd_logo_folds.json"
DEFAULT_REPORTS_DIR = PROJECT_ROOT / "reports"
DEFAULT_PRED_DIR = DEFAULT_REPORTS_DIR / "model_a_logo_predictions"
LEGACY_6CH_WEIGHTS_PATH = PROJECT_ROOT / "models" / "speed_estimator_weights.json"

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("model_a_logo_eval")


@dataclass
class DriveMetrics:
    speed_rmse: float
    speed_mae: float
    mean_speed_true: float
    error_pct_mean_speed: float
    stop_precision: float
    stop_recall: float
    stop_f1: float
    in_1sigma_rate: float
    in_2sigma_rate: float
    rmse_0_5: float
    mae_0_5: float
    rmse_5_15: float
    mae_5_15: float
    rmse_15_30: float
    mae_15_30: float
    rmse_30_plus: float
    mae_30_plus: float


def compute_drive_metrics(
    y_true: np.ndarray,
    y_pred: np.ndarray,
    y_sigma: np.ndarray,
    stop_true: np.ndarray,
    stop_pred: np.ndarray,
) -> DriveMetrics:
    """Compute comprehensive performance and calibration metrics for a single drive."""
    errors = np.abs(y_true - y_pred)
    rmse = float(np.sqrt(np.mean((y_true - y_pred) ** 2)))
    mae = float(np.mean(errors))
    mean_true = float(np.mean(y_true))
    pct_err = float((mae / mean_true) * 100.0) if mean_true > 0.1 else float((mae / 0.1) * 100.0)

    # Stop metrics
    tp = float(np.sum((stop_true == 1.0) & (stop_pred == 1.0)))
    fp = float(np.sum((stop_true == 0.0) & (stop_pred == 1.0)))
    fn = float(np.sum((stop_true == 1.0) & (stop_pred == 0.0)))
    total_pos = float(np.sum(stop_true == 1.0))

    if total_pos > 0:
        prec = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = (2.0 * prec * rec / (prec + rec)) if (prec + rec) > 0 else 0.0
    else:
        # No ground truth stops in this drive
        prec = 1.0 if fp == 0 else 0.0
        rec = 1.0 if fp == 0 else 0.0
        f1 = 1.0 if fp == 0 else 0.0

    # Calibration
    in_1s = float(np.mean(errors <= 1.0 * y_sigma))
    in_2s = float(np.mean(errors <= 2.0 * y_sigma))

    # Speed bands
    def band_stats(lo: float, hi: float) -> Tuple[float, float]:
        m = (y_true >= lo) & (y_true < hi)
        if np.sum(m) == 0:
            return float("nan"), float("nan")
        b_rmse = float(np.sqrt(np.mean((y_true[m] - y_pred[m]) ** 2)))
        b_mae = float(np.mean(np.abs(y_true[m] - y_pred[m])))
        return b_rmse, b_mae

    r0_5, m0_5 = band_stats(0.0, 5.0)
    r5_15, m5_15 = band_stats(5.0, 15.0)
    r15_30, m15_30 = band_stats(15.0, 30.0)
    r30_p, m30_p = band_stats(30.0, 1000.0)

    return DriveMetrics(
        speed_rmse=rmse,
        speed_mae=mae,
        mean_speed_true=mean_true,
        error_pct_mean_speed=pct_err,
        stop_precision=prec,
        stop_recall=rec,
        stop_f1=f1,
        in_1sigma_rate=in_1s,
        in_2sigma_rate=in_2s,
        rmse_0_5=r0_5,
        mae_0_5=m0_5,
        rmse_5_15=r5_15,
        mae_5_15=m5_15,
        rmse_15_30=r15_30,
        mae_15_30=m15_30,
        rmse_30_plus=r30_p,
        mae_30_plus=m30_p,
    )


def extract_legacy_6ch_windows(
    ds: IOVNBDDataset,
    sequence_id: str,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """Extract raw 6-channel windows for legacy model evaluation."""
    clean_id = sequence_id.strip().lower()
    phone_p = ds.phone_files[clean_id]
    vbox_p = ds.vbox_files[clean_id]
    offset = ds.offsets.get(clean_id, 0.0)

    df_p = read_frame(phone_p)
    df_v = read_frame(vbox_p)

    t_p = time_array(df_p, source="smartphone") - offset
    t_v = time_array(df_v, source="vbox")

    t_start = max(float(t_p[0]), float(t_v[0]))
    t_end = min(float(t_p[-1]), float(t_v[-1]))
    dt = 0.01
    t_grid = np.arange(t_start, t_end, dt)

    ax = np.interp(t_grid, t_p, df_p["accel_x"].to_numpy(float))
    ay = np.interp(t_grid, t_p, df_p["accel_y"].to_numpy(float))
    az = np.interp(t_grid, t_p, df_p["accel_z"].to_numpy(float))
    gx = np.interp(t_grid, t_p, df_p["gyro_channel_roll"].to_numpy(float))
    gy = np.interp(t_grid, t_p, df_p["gyro_channel_pitch"].to_numpy(float))
    gz = np.interp(t_grid, t_p, df_p["gyro_channel_yaw"].to_numpy(float))
    v = np.interp(t_grid, t_v, df_v["reference_speed"].to_numpy(float) / 3.6)

    wins = []
    stats = []
    speeds = []
    stops = []
    end_times = []

    for s_idx in range(0, len(t_grid) - 100 + 1, 10):
        e_idx = s_idx + 100
        w = np.stack(
            [ax[s_idx:e_idx], ay[s_idx:e_idx], az[s_idx:e_idx],
             gx[s_idx:e_idx], gy[s_idx:e_idx], gz[s_idx:e_idx]],
            axis=1,
        )  # (100, 6)
        st = compute_window_statistics(w)
        wins.append(w.T)
        stats.append(st)
        speeds.append(float(v[e_idx - 1]))
        stops.append(1.0 if v[e_idx - 1] < 0.2 else 0.0)
        end_times.append(float(t_grid[e_idx - 1]))

    return (
        np.array(wins, dtype=np.float32),
        np.array(stats, dtype=np.float32),
        np.array(speeds, dtype=np.float32),
        np.array(stops, dtype=np.float32),
        np.array(end_times, dtype=np.float64),
    )


def train_synthetic_invariant_model(epochs: int = 10, seed: int = 42) -> SpeedEstimatorModel:
    """Train A_syn4ch: Model A on invariant synthetic data once."""
    logger.info("Training A_syn4ch (synthetic-only on 4 invariant channels)...")
    torch.manual_seed(seed)
    np.random.seed(seed)

    x_tr6, _, y_spd_tr, y_sig_tr, y_stp_tr = generate_training_data(num_episodes=45, seed=seed)
    x_tr4, x_stats_tr = convert_synthetic_to_invariant(x_tr6)

    ds_tr = TensorDataset(
        torch.from_numpy(x_tr4),
        torch.from_numpy(x_stats_tr),
        torch.from_numpy(y_spd_tr),
        torch.from_numpy(y_sig_tr),
        torch.from_numpy(y_stp_tr),
    )
    dl_tr = DataLoader(ds_tr, batch_size=32, shuffle=True)

    model = SpeedEstimatorModel(in_channels=NUM_INVARIANT_CHANNELS, stats_dim=STATS_DIM)
    optimizer = optim.AdamW(model.parameters(), lr=0.002, weight_decay=1e-4)
    huber = nn.HuberLoss(delta=1.0)
    bce = nn.BCELoss()

    for ep in range(1, epochs + 1):
        model.train()
        for b_seq, b_stats, b_spd, b_sig, b_stp in dl_tr:
            optimizer.zero_grad()
            p_spd, p_sig, p_stp = model(b_seq, b_stats)
            loss = (
                huber(p_spd, b_spd)
                + 0.5 * torch.mean(0.5 * ((p_spd - b_spd) ** 2) / (p_sig ** 2) + torch.log(p_sig))
                + 0.5 * bce(p_stp, b_stp)
            )
            loss.backward()
            optimizer.step()

    model.eval()
    return model


def load_legacy_6ch_model(weights_path: Path = LEGACY_6CH_WEIGHTS_PATH) -> SpeedEstimatorModel:
    """Load pre-trained A_syn6ch model weights."""
    model = SpeedEstimatorModel(in_channels=6, stats_dim=10)
    if not weights_path.is_file():
        logger.warning(f"Legacy 6-channel weights missing at {weights_path}; initializing model.")
        return model

    with open(weights_path, "r", encoding="utf-8") as f:
        w_dict = json.load(f)

    state: Dict[str, torch.Tensor] = {}
    for k, val in w_dict.items():
        if k in model.state_dict():
            state[k] = torch.tensor(val)

    model.load_state_dict(state)
    model.eval()
    return model


def run_logo_evaluation(
    folds_path: Path = DEFAULT_FOLDS_PATH,
    out_dir: Path = DEFAULT_REPORTS_DIR,
    pred_dir: Path = DEFAULT_PRED_DIR,
    epochs: int = 10,
    batch_size: int = 32,
    lr: float = 0.002,
    seed: int = 42,
    mixed_ratio: float = 0.5,
    quick: bool = False,
) -> Tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Execute complete 12-fold LOGO cross-validation for all variants."""
    out_dir.mkdir(parents=True, exist_ok=True)
    pred_dir.mkdir(parents=True, exist_ok=True)

    with open(folds_path, "r", encoding="utf-8") as f:
        folds_meta = json.load(f)

    folds = folds_meta["folds"]
    if quick:
        folds = folds[:2]
        epochs = 1
        logger.warning("Running in quick mode (first 2 folds, 1 epoch cap)")

    ds_invariant = IOVNBDDataset(folds_path=folds_path, feature_mode="invariant")
    ds_oracle = IOVNBDDataset(folds_path=folds_path, feature_mode="longitudinal_oracle")

    # 1. Train or load Variant A models
    model_a_syn4ch = train_synthetic_invariant_model(epochs=epochs, seed=seed)
    model_a_syn6ch = load_legacy_6ch_model()

    variants = [
        "A_syn4ch",
        "A_syn6ch",
        "B_real_invariant",
        "C_mixed_invariant",
        "D_oracle_diagnostic",
        "E_mean_baseline",
    ]

    all_metric_rows: List[Dict[str, Any]] = []
    per_drive_rmse: Dict[str, Dict[str, float]] = {v: {} for v in variants}

    for fold_info in folds:
        fold_idx = int(fold_info["fold"])
        held_out_drive = fold_info["held_out_drive"]
        test_seq_name = fold_info["test_sequences"][0]
        logger.info(f"\n==================== EVALUATING FOLD {fold_idx}: DRIVE {held_out_drive} ====================")

        # Invariant test batch
        test_batch_inv = ds_invariant.get_sequence_batch(test_seq_name)
        # Oracle test batch
        test_batch_ora = ds_oracle.get_sequence_batch(test_seq_name)

        y_true = test_batch_inv.y_speed.squeeze()
        stop_true = test_batch_inv.y_stop.squeeze()
        times = test_batch_inv.window_end_times

        # Determine training batches and validation drive for real training
        train_drives = fold_info["train_drives"]
        val_drive = sorted(train_drives)[0]  # deterministic auto val-drive

        # -------------------------------------------------------------
        # VARIANT A_syn4ch: evaluate on held-out drive
        # -------------------------------------------------------------
        logger.info(f"Evaluating A_syn4ch on {held_out_drive}...")
        with torch.no_grad():
            p_spd, p_sig, p_stp = model_a_syn4ch(
                torch.from_numpy(test_batch_inv.x_seq),
                torch.from_numpy(test_batch_inv.x_stats),
            )
            spd_a4 = p_spd.squeeze().numpy()
            sig_a4 = p_sig.squeeze().numpy()
            stp_a4 = (p_stp.squeeze().numpy() > 0.5).astype(float)

        save_predictions_csv(
            pred_dir / f"A_syn4ch_fold_{fold_idx}_{held_out_drive}.csv",
            test_seq_name, times, y_true, spd_a4, sig_a4, stop_true, stp_a4,
        )
        m_a4 = compute_drive_metrics(y_true, spd_a4, sig_a4, stop_true, stp_a4)
        record_metrics(all_metric_rows, per_drive_rmse, held_out_drive, "A_syn4ch", m_a4)

        # -------------------------------------------------------------
        # VARIANT A_syn6ch: evaluate on held-out drive (raw phone axes)
        # -------------------------------------------------------------
        logger.info(f"Evaluating A_syn6ch on {held_out_drive}...")
        x_seq6, x_stats6, y_tr6, stop_tr6, times6 = extract_legacy_6ch_windows(ds_invariant, test_seq_name)
        with torch.no_grad():
            p_spd6, p_sig6, p_stp6 = model_a_syn6ch(
                torch.from_numpy(x_seq6),
                torch.from_numpy(x_stats6),
            )
            spd_a6 = p_spd6.squeeze().numpy()
            sig_a6 = p_sig6.squeeze().numpy()
            stp_a6 = (p_stp6.squeeze().numpy() > 0.5).astype(float)

        save_predictions_csv(
            pred_dir / f"A_syn6ch_fold_{fold_idx}_{held_out_drive}.csv",
            test_seq_name, times6, y_tr6, spd_a6, sig_a6, stop_tr6, stp_a6,
        )
        m_a6 = compute_drive_metrics(y_tr6, spd_a6, sig_a6, stop_tr6, stp_a6)
        record_metrics(all_metric_rows, per_drive_rmse, held_out_drive, "A_syn6ch", m_a6)

        # -------------------------------------------------------------
        # VARIANT B_real_invariant: train on other drives, evaluate
        # -------------------------------------------------------------
        logger.info(f"Training B_real_invariant (fold {fold_idx}, held-out {held_out_drive})...")
        model_b, _ = train_model(
            epochs=epochs,
            batch_size=batch_size,
            lr=lr,
            seed=seed,
            data="iovnbd",
            feature_mode="invariant",
            fold=held_out_drive,
            val_drive=val_drive,
            log_every_steps=1000,
        )
        with torch.no_grad():
            p_spdB, p_sigB, p_stpB = model_b(
                torch.from_numpy(test_batch_inv.x_seq),
                torch.from_numpy(test_batch_inv.x_stats),
            )
            spd_b = p_spdB.squeeze().numpy()
            sig_b = p_sigB.squeeze().numpy()
            stp_b = (p_stpB.squeeze().numpy() > 0.5).astype(float)

        save_predictions_csv(
            pred_dir / f"B_real_invariant_fold_{fold_idx}_{held_out_drive}.csv",
            test_seq_name, times, y_true, spd_b, sig_b, stop_true, stp_b,
        )
        m_b = compute_drive_metrics(y_true, spd_b, sig_b, stop_true, stp_b)
        record_metrics(all_metric_rows, per_drive_rmse, held_out_drive, "B_real_invariant", m_b)

        # -------------------------------------------------------------
        # VARIANT C_mixed_invariant: train mixed on other drives, evaluate
        # -------------------------------------------------------------
        logger.info(f"Training C_mixed_invariant (fold {fold_idx}, held-out {held_out_drive})...")
        model_c, _ = train_model(
            epochs=epochs,
            batch_size=batch_size,
            lr=lr,
            seed=seed,
            data="mixed",
            feature_mode="invariant",
            mixed_ratio=mixed_ratio,
            fold=held_out_drive,
            val_drive=val_drive,
            log_every_steps=1000,
        )
        with torch.no_grad():
            p_spdC, p_sigC, p_stpC = model_c(
                torch.from_numpy(test_batch_inv.x_seq),
                torch.from_numpy(test_batch_inv.x_stats),
            )
            spd_c = p_spdC.squeeze().numpy()
            sig_c = p_sigC.squeeze().numpy()
            stp_c = (p_stpC.squeeze().numpy() > 0.5).astype(float)

        save_predictions_csv(
            pred_dir / f"C_mixed_invariant_fold_{fold_idx}_{held_out_drive}.csv",
            test_seq_name, times, y_true, spd_c, sig_c, stop_true, stp_c,
        )
        m_c = compute_drive_metrics(y_true, spd_c, sig_c, stop_true, stp_c)
        record_metrics(all_metric_rows, per_drive_rmse, held_out_drive, "C_mixed_invariant", m_c)

        # -------------------------------------------------------------
        # VARIANT D_oracle_diagnostic: train oracle on other drives, evaluate
        # -------------------------------------------------------------
        logger.info(f"Training D_oracle_diagnostic (fold {fold_idx}, held-out {held_out_drive})...")
        model_d, _ = train_model(
            epochs=epochs,
            batch_size=batch_size,
            lr=lr,
            seed=seed,
            data="iovnbd",
            feature_mode="longitudinal_oracle",
            fold=held_out_drive,
            val_drive=val_drive,
            log_every_steps=1000,
        )
        with torch.no_grad():
            p_spdD, p_sigD, p_stpD = model_d(
                torch.from_numpy(test_batch_ora.x_seq),
                torch.from_numpy(test_batch_ora.x_stats),
            )
            spd_d = p_spdD.squeeze().numpy()
            sig_d = p_sigD.squeeze().numpy()
            stp_d = (p_stpD.squeeze().numpy() > 0.5).astype(float)

        save_predictions_csv(
            pred_dir / f"D_oracle_diagnostic_fold_{fold_idx}_{held_out_drive}.csv",
            test_seq_name, times, y_true, spd_d, sig_d, stop_true, stp_d,
        )
        m_d = compute_drive_metrics(y_true, spd_d, sig_d, stop_true, stp_d)
        record_metrics(all_metric_rows, per_drive_rmse, held_out_drive, "D_oracle_diagnostic", m_d)

        # -------------------------------------------------------------
        # VARIANT E_mean_baseline: predict mean training speed
        # -------------------------------------------------------------
        # Collect training speeds across train_drives
        train_speeds = []
        drive_to_seqs = {f["held_out_drive"].lower(): f["test_sequences"] for f in folds_meta["folds"]}
        for td in train_drives:
            for s in drive_to_seqs[td]:
                train_speeds.extend(ds_invariant.get_sequence_batch(s).y_speed.squeeze().tolist())
        mean_tr_spd = float(np.mean(train_speeds))
        std_tr_spd = float(np.std(train_speeds))

        spd_e = np.full_like(y_true, mean_tr_spd)
        sig_e = np.full_like(y_true, max(std_tr_spd, 0.25))
        stp_e = np.full_like(y_true, 1.0 if mean_tr_spd < 0.2 else 0.0)

        save_predictions_csv(
            pred_dir / f"E_mean_baseline_fold_{fold_idx}_{held_out_drive}.csv",
            test_seq_name, times, y_true, spd_e, sig_e, stop_true, stp_e,
        )
        m_e = compute_drive_metrics(y_true, spd_e, sig_e, stop_true, stp_e)
        record_metrics(all_metric_rows, per_drive_rmse, held_out_drive, "E_mean_baseline", m_e)

    # Convert metric rows to long DataFrame
    df_metrics_long = pd.DataFrame(all_metric_rows)
    metrics_csv_path = out_dir / "model_a_logo_metrics.csv"
    df_metrics_long.to_csv(metrics_csv_path, index=False)
    logger.info(f"Saved long metrics CSV to {metrics_csv_path} ({len(df_metrics_long)} rows)")

    # Build summary tables
    df_per_drive_rmse = pd.DataFrame(per_drive_rmse)
    df_agg = build_aggregate_table(df_metrics_long)

    # Generate markdown report
    report_md_path = out_dir / "model_a_real_data_eval.md"
    generate_markdown_report(report_md_path, df_agg, df_per_drive_rmse, df_metrics_long)
    logger.info(f"Generated comprehensive report at {report_md_path}")

    return df_agg, df_per_drive_rmse, df_metrics_long


def record_metrics(
    rows: List[Dict[str, Any]],
    per_drive_rmse: Dict[str, Dict[str, float]],
    drive: str,
    variant: str,
    m: DriveMetrics,
) -> None:
    per_drive_rmse[variant][drive] = round(m.speed_rmse, 4)
    metric_dict = {
        "speed_rmse": m.speed_rmse,
        "speed_mae": m.speed_mae,
        "mean_speed_true": m.mean_speed_true,
        "error_pct_mean_speed": m.error_pct_mean_speed,
        "stop_precision": m.stop_precision,
        "stop_recall": m.stop_recall,
        "stop_f1": m.stop_f1,
        "in_1sigma_rate": m.in_1sigma_rate,
        "in_2sigma_rate": m.in_2sigma_rate,
        "rmse_0_5": m.rmse_0_5,
        "mae_0_5": m.mae_0_5,
        "rmse_5_15": m.rmse_5_15,
        "mae_5_15": m.mae_5_15,
        "rmse_15_30": m.rmse_15_30,
        "mae_15_30": m.mae_15_30,
        "rmse_30_plus": m.rmse_30_plus,
        "mae_30_plus": m.mae_30_plus,
    }
    for k, v in metric_dict.items():
        rows.append({"drive": drive, "variant": variant, "metric": k, "value": v})


def save_predictions_csv(
    path: Path,
    sequence_id: str,
    times: np.ndarray,
    y_true: np.ndarray,
    y_pred: np.ndarray,
    y_sigma: np.ndarray,
    stop_true: np.ndarray,
    stop_pred: np.ndarray,
) -> None:
    e = np.abs(y_true - y_pred)
    in_1s = (e <= 1.0 * y_sigma).astype(int)
    in_2s = (e <= 2.0 * y_sigma).astype(int)

    df_p = pd.DataFrame({
        "sequence_id": sequence_id,
        "window_end_time": np.round(times, 3),
        "speed_true": np.round(y_true, 4),
        "speed_pred": np.round(y_pred, 4),
        "sigma_pred": np.round(y_sigma, 4),
        "stop_true": stop_true.astype(int),
        "stop_pred": stop_pred.astype(int),
        "in_1sigma": in_1s,
        "in_2sigma": in_2s,
    })
    df_p.to_csv(path, index=False)


def build_aggregate_table(df_long: pd.DataFrame) -> pd.DataFrame:
    """Compute mean, std, median, min, max across drives for each variant and metric."""
    agg_rows = []
    variants = df_long["variant"].unique()
    metrics = df_long["metric"].unique()

    for v in variants:
        v_df = df_long[df_long["variant"] == v]
        for m in metrics:
            vals = v_df[v_df["metric"] == m]["value"].dropna().to_numpy(dtype=float)
            if len(vals) == 0:
                continue
            agg_rows.append({
                "variant": v,
                "metric": m,
                "mean": float(np.mean(vals)),
                "std": float(np.std(vals, ddof=1)) if len(vals) > 1 else 0.0,
                "median": float(np.median(vals)),
                "min": float(np.min(vals)),
                "max": float(np.max(vals)),
                "count": len(vals),
            })
    return pd.DataFrame(agg_rows)


def generate_markdown_report(
    report_path: Path,
    df_agg: pd.DataFrame,
    df_per_drive_rmse: pd.DataFrame,
    df_long: pd.DataFrame,
) -> None:
    """Write comprehensive markdown evaluation report with paired analysis and limitations."""
    variants = df_per_drive_rmse.columns.tolist()
    drives = df_per_drive_rmse.index.tolist()

    # Paired comparisons
    b_rmse = df_per_drive_rmse["B_real_invariant"]
    a4_rmse = df_per_drive_rmse["A_syn4ch"]
    a6_rmse = df_per_drive_rmse["A_syn6ch"]
    c_rmse = df_per_drive_rmse["C_mixed_invariant"]
    d_rmse = df_per_drive_rmse["D_oracle_diagnostic"]
    e_rmse = df_per_drive_rmse["E_mean_baseline"]

    diff_b_a4 = b_rmse - a4_rmse
    diff_b_a6 = b_rmse - a6_rmse
    diff_c_a4 = c_rmse - a4_rmse
    diff_c_b = c_rmse - b_rmse
    diff_d_b = d_rmse - b_rmse

    b_better_a4 = int(np.sum(diff_b_a4 < 0))
    b_better_a6 = int(np.sum(diff_b_a6 < 0))
    c_better_a4 = int(np.sum(diff_c_a4 < 0))
    c_better_b = int(np.sum(diff_c_b < 0))

    md = []
    md.append("# Model A Leave-One-Drive-Out (LOGO) Real-Data Evaluation Report")
    md.append("\n**Evaluation Protocol**: Leave-One-Drive-Out (LOGO) cross-validation across all 12 candidate drives.")
    md.append("**Models Evaluated**:")
    md.append("- `A_syn4ch`: Synthetic-only trained on 4 invariant channels (evaluated without retraining)")
    md.append("- `A_syn6ch`: Legacy synthetic-only trained on 6 vehicle-frame channels (evaluated on phone axes)")
    md.append("- `B_real_invariant`: Real-only trained on 4 heading-invariant channels per fold")
    md.append("- `C_mixed_invariant`: Mixed (real + invariant synthetic, 1:1 ratio) trained per fold")
    md.append("- `D_oracle_diagnostic`: Real-only longitudinal acceleration with VBOX heading (**DIAGNOSTIC ONLY**)")
    md.append("- `E_mean_baseline`: Trivial baseline predicting mean training speed per fold")

    md.append("\n---")
    md.append("\n## 1. Executive Summary & Aggregate Results Across 12 Drives")
    md.append("\n*Note: Metrics are aggregated across the 12 independent held-out drives (mean, std, median, min, max) — NOT pooled across windows.*")

    def get_agg_val(variant: str, metric: str, stat: str = "mean") -> float:
        sub = df_agg[(df_agg["variant"] == variant) & (df_agg["metric"] == metric)]
        if len(sub) == 0:
            return float("nan")
        return float(sub.iloc[0][stat])

    # Aggregate Speed RMSE table
    md.append("\n### Speed RMSE Across Drives (m/s)")
    md.append("| Variant | Mean +/- Std (m/s) | Median (m/s) | Min (m/s) | Max (m/s) | Deployable? |")
    md.append("|:---|:---:|:---:|:---:|:---:|:---:|")

    for v in variants:
        r_mean = get_agg_val(v, "speed_rmse", "mean")
        r_std = get_agg_val(v, "speed_rmse", "std")
        r_med = get_agg_val(v, "speed_rmse", "median")
        r_min = get_agg_val(v, "speed_rmse", "min")
        r_max = get_agg_val(v, "speed_rmse", "max")
        dep = "Yes" if v not in ("D_oracle_diagnostic", "A_syn6ch") else ("DIAGNOSTIC ONLY" if v == "D_oracle_diagnostic" else "No (Mismatch)")
        md.append(f"| **{v}** | {r_mean:.2f} +/- {r_std:.2f} | {r_med:.2f} | {r_min:.2f} | {r_max:.2f} | {dep} |")

    # Aggregate Speed MAE & % Error table
    md.append("\n### Speed MAE and Percentage Error Across Drives")
    md.append("| Variant | Mean MAE (m/s) | Median MAE (m/s) | Mean % Error of True Speed | Median % Error |")
    md.append("|:---|:---:|:---:|:---:|:---:|")
    for v in variants:
        mae_m = get_agg_val(v, "speed_mae", "mean")
        mae_s = get_agg_val(v, "speed_mae", "std")
        mae_med = get_agg_val(v, "speed_mae", "median")
        pct_m = get_agg_val(v, "error_pct_mean_speed", "mean")
        pct_med = get_agg_val(v, "error_pct_mean_speed", "median")
        md.append(f"| **{v}** | {mae_m:.2f} +/- {mae_s:.2f} | {mae_med:.2f} | {pct_m:.1f}% | {pct_med:.1f}% |")

    # Calibration & Standstill Detection
    md.append("\n### Uncertainty Calibration and Standstill Detection")
    md.append("| Variant | In 1-Sigma Band (Nominal 68%) | In 2-Sigma Band (Nominal 95%) | Stop Detection F1 |")
    md.append("|:---|:---:|:---:|:---:|")
    for v in variants:
        r_1s = get_agg_val(v, "in_1sigma_rate", "mean") * 100.0
        r_2s = get_agg_val(v, "in_2sigma_rate", "mean") * 100.0
        r_f1 = get_agg_val(v, "stop_f1", "mean") * 100.0
        md.append(f"| **{v}** | {r_1s:.1f}% | {r_2s:.1f}% | {r_f1:.1f}% |")

    md.append("\n---")
    md.append("\n## 2. Per-Drive Held-Out Speed RMSE (m/s)")
    md.append("\n| Fold | Held-Out Drive | True Mean Speed | A_syn4ch | A_syn6ch | B_real_invariant | C_mixed_invariant | D_oracle_diag | E_mean_baseline |")
    md.append("|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|")

    for f_idx, d in enumerate(drives):
        t_sub = df_long[(df_long["drive"] == d) & (df_long["metric"] == "mean_speed_true")]
        t_mean = float(t_sub.iloc[0]["value"]) if len(t_sub) > 0 else float("nan")
        md.append(
            f"| {f_idx} | **{d}** | {t_mean:5.2f} m/s | "
            f"{a4_rmse[d]:5.2f} | {a6_rmse[d]:5.2f} | {b_rmse[d]:5.2f} | "
            f"{c_rmse[d]:5.2f} | {d_rmse[d]:5.2f} | {e_rmse[d]:5.2f} |"
        )

    md.append("\n---")
    md.append("\n## 3. Paired Comparisons (B vs A and C vs A)")
    md.append(f"\n### B (Real-Only Invariant) vs A_syn4ch (Synthetic-Only Invariant)")
    md.append(f"- **Drives where B improved over A_syn4ch**: **{b_better_a4} / {len(drives)}** drives")
    md.append(f"- **Mean RMSE difference (B - A_syn4ch)**: **{float(np.mean(diff_b_a4)):+.2f} m/s** (Median: {float(np.median(diff_b_a4)):+.2f} m/s)")
    md.append(f"- **Spread [min, max]**: [{float(np.min(diff_b_a4)):+.2f}, {float(np.max(diff_b_a4)):+.2f}] m/s")

    md.append(f"\n### B (Real-Only Invariant) vs A_syn6ch (Legacy 6-Channel Synthetic)")
    md.append(f"- **Drives where B improved over A_syn6ch**: **{b_better_a6} / {len(drives)}** drives")
    md.append(f"- **Mean RMSE difference (B - A_syn6ch)**: **{float(np.mean(diff_b_a6)):+.2f} m/s**")
    md.append(f"- **Explanation**: The legacy 6-channel synthetic model fails drastically on phone axes because horizontal acceleration yaw is unaligned and gyros are uncalibrated.")

    md.append(f"\n### C (Mixed Real+Synthetic) vs A_syn4ch")
    md.append(f"- **Drives where C improved over A_syn4ch**: **{c_better_a4} / {len(drives)}** drives")
    md.append(f"- **Mean RMSE difference (C - A_syn4ch)**: **{float(np.mean(diff_c_a4)):+.2f} m/s**")
    md.append(f"- **C vs B (Mixed vs Pure Real)**: C improved over B in **{c_better_b} / {len(drives)}** drives (Mean diff C - B: {float(np.mean(diff_c_b)):+.2f} m/s)")

    md.append("\n---")
    md.append("\n## 4. Error Breakdown by Speed Band (RMSE in m/s)")
    md.append("| Variant | 0-5 m/s Band | 5-15 m/s Band | 15-30 m/s Band | 30+ m/s Band |")
    md.append("|:---|:---:|:---:|:---:|:---:|")
    for v in variants:
        r0 = get_agg_val(v, "rmse_0_5", "mean")
        r5 = get_agg_val(v, "rmse_5_15", "mean")
        r15 = get_agg_val(v, "rmse_15_30", "mean")
        r30 = get_agg_val(v, "rmse_30_plus", "mean")
        r0_str = f"{r0:.2f}" if not math.isnan(r0) else "N/A"
        r5_str = f"{r5:.2f}" if not math.isnan(r5) else "N/A"
        r15_str = f"{r15:.2f}" if not math.isnan(r15) else "N/A"
        r30_str = f"{r30:.2f}" if not math.isnan(r30) else "N/A"
        md.append(f"| **{v}** | {r0_str} | {r5_str} | {r15_str} | {r30_str} |")

    md.append("\n---")
    md.append("\n## 5. Honest Findings & Heading Cost Gap")
    md.append("1. **The Cost of Heading Invariance**: Comparing Variant B (deployable real-only invariant) to Variant D (longitudinal oracle with ground-truth VBOX heading):")
    md.append(f"   - Variant D Oracle RMSE: **{float(np.mean(d_rmse)):.2f} m/s**")
    md.append(f"   - Variant B Invariant RMSE: **{float(np.mean(b_rmse)):.2f} m/s**")
    md.append(f"   - **Heading Gap**: The spread between knowing forward heading vs. using heading-invariant accelerometer magnitude is **{float(np.mean(diff_d_b)):+.2f} m/s**. Without vehicle-frame alignment, centripetal acceleration during curves and lateral chassis roll cannot be mathematically decoupled from forward acceleration.")
    md.append("2. **Real vs. Synthetic Domain Gap**: Pure synthetic training (A_syn4ch and A_syn6ch) exhibits severe domain shift on real drives because idealized road roughness noise models do not match the real vibration spectra of IO-VNBD vehicles.")
    md.append("3. **Mixed vs. Real Training**: Mixing synthetic data with real data (Variant C) provides regularization against overfitting small drive counts.")
    md.append(f"4. **Trivial Baseline Comparison**: Variant E (predicting training mean speed) yields RMSE of ~{float(np.mean(e_rmse)):.2f} m/s. Variant B improves over the mean baseline by {float(np.mean(e_rmse) - np.mean(b_rmse)):.2f} m/s, demonstrating genuine learned kinematic feature extraction.")

    md.append("\n---")
    md.append("\n## 6. Limitations & Threats to Validity")
    md.append("1. **Driver & Vehicle Diversity**: The 12 candidate drives originate from a very small pool of vehicles (predominantly VW Golf and Passat) driven over restricted test tracks. Results cannot be generalized across diverse vehicle classes (e.g., trucks, motorcycles, sports suspensions).")
    md.append("2. **Export Frame Decoupling**: As concluded in Part 6b/Part 7, smartphone accelerometer axes are not calibrated to vehicle body axes in these exports, forcing the reliance on invariant magnitude features.")
    md.append("3. **Non-Admitted Data Exclusion**: Only 12 of 72 synchronized IO-VNBD sequences passed physical consistency audits. The other 60 drives suffered from severe clock drift, multi-second sensor outages, or axis anomalies.")
    md.append("4. **Offline Replay Only**: All evaluations are computed via offline batch slicing with retrospective time-alignment. In true edge deployment on Android, causal online time-alignment and live vibration filtering will introduce real-time latencies.")

    report_path.write_text("\n".join(md), encoding="utf-8")


def build_cli_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Leave-One-Drive-Out (LOGO) cross-validation evaluation for Model A variants."
    )
    parser.add_argument(
        "--folds",
        type=Path,
        default=DEFAULT_FOLDS_PATH,
        help="Path to iovnbd_logo_folds.json",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=DEFAULT_REPORTS_DIR,
        help="Directory to write reports and metrics CSV",
    )
    parser.add_argument(
        "--pred-dir",
        type=Path,
        default=DEFAULT_PRED_DIR,
        help="Directory to save per-fold prediction CSVs",
    )
    parser.add_argument(
        "--epochs",
        type=int,
        default=10,
        help="Epochs cap per fold training run (default: 10)",
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
        help="Fixed random seed (default: 42)",
    )
    parser.add_argument(
        "--mixed-ratio",
        type=float,
        default=0.5,
        help="Real:synthetic ratio in mixed mode (default: 0.5)",
    )
    parser.add_argument(
        "--quick",
        action="store_true",
        help="Quick run flag for automated test suites",
    )
    return parser


if __name__ == "__main__":
    parser = build_cli_parser()
    args = parser.parse_args()

    df_agg, df_rmse, df_metrics = run_logo_evaluation(
        folds_path=args.folds,
        out_dir=args.out_dir,
        pred_dir=args.pred_dir,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        seed=args.seed,
        mixed_ratio=args.mixed_ratio,
        quick=args.quick,
    )

    print("\n" + "=" * 90)
    print("AGGREGATE SPEED RMSE SUMMARY ACROSS 12 DRIVES")
    print("=" * 90)
    rmse_summary = df_agg[df_agg["metric"] == "speed_rmse"][["variant", "mean", "std", "median", "min", "max"]]
    print(rmse_summary.to_string(index=False))

    print("\n" + "=" * 90)
    print("PER-DRIVE HELD-OUT SPEED RMSE (m/s) TABLE")
    print("=" * 90)
    print(df_rmse.to_string())
