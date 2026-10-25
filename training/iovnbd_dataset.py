#!/usr/bin/env python3
"""IO-VNBD real-data window dataset builder for Model A forward speed estimation.

Constructs causal sliding windows from the 12 admitted IO-VNBD candidate sequences
using synchronized, time-aligned smartphone accelerometer and VBOX reference streams.

Feature modes:
  - 'invariant' (default, deployable):
      Uses only heading- and yaw-invariant channels derived from phone accelerometer.
      Never touches VBOX heading or ground truth. Gyroscope is excluded due to export
      coordinate decoupling.
      Channels (4):
        0: a_horiz         - Horizontal acceleration magnitude sqrt(ax^2 + ay^2)
        1: a_vert          - Vertical acceleration az (gravity + road pitch/bump)
        2: a_norm          - Full 3D acceleration vector norm sqrt(ax^2 + ay^2 + az^2)
        3: a_vert_bandpower - Causal short-time vertical vibration power (correlates with speed)
  - 'longitudinal_oracle' (DIAGNOSTIC ONLY, not deployable):
      Upper bound diagnostic mode where Channel 0 is longitudinal acceleration obtained
      using VBOX reference kinematics.

Targets:
  - forward_speed: VBOX Doppler speed (m/s) at the causal window end.
  - is_stop: Binary standstill label (1.0 if speed < 0.2 m/s else 0.0), matching synthetic.
  - sigma_target: Nominal target uncertainty based on longitudinal acceleration.

Fold integration:
  Loads 12-fold leave-one-drive-out cross-validation partitions from
  evaluation/iovnbd_logo_folds.json.
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np
import pandas as pd

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.phase0.audit import read_frame, time_array

DEFAULT_FOLDS_PATH = PROJECT_ROOT / "evaluation" / "iovnbd_logo_folds.json"
DEFAULT_ADMISSION_PATH = PROJECT_ROOT / "reports" / "iovnbd_admission.json"
DEFAULT_TIME_ALIGN_PATH = PROJECT_ROOT / "reports" / "iovnbd_time_alignment.csv"
DEFAULT_DATA_ROOT = PROJECT_ROOT / "data" / "raw" / "iovnbd"

WINDOW_SIZE_100HZ = 100  # 1.0 second at 100 Hz
NUM_INVARIANT_CHANNELS = 4
STATS_DIM = 10

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger("iovnbd_dataset")


@dataclass
class WindowBatch:
    """Container for sliding window arrays and ground truth targets."""
    x_seq: np.ndarray       # (N, C, L) float32
    x_stats: np.ndarray     # (N, stats_dim) float32
    y_speed: np.ndarray     # (N, 1) float32 in m/s
    y_stop: np.ndarray      # (N, 1) float32 in {0.0, 1.0}
    y_sigma: np.ndarray     # (N, 1) float32 in m/s
    sequence_names: list[str]
    window_end_times: np.ndarray  # (N,) float64 in seconds


def compute_causal_bandpower(
    signal: np.ndarray,
    short_window_len: int = 20,
) -> np.ndarray:
    """Compute causal short-time vibration power (variance around local rolling mean).

    Strictly causal: sample t depends only on samples [t - short_window_len + 1, t].
    """
    s = pd.Series(signal, dtype=np.float64)
    # Causal rolling mean
    rolling_mean = s.rolling(window=short_window_len, min_periods=1).mean()
    detrended = s - rolling_mean
    # Causal rolling variance / power
    bandpower = (detrended ** 2).rolling(window=short_window_len, min_periods=1).mean()
    # Fill initial boundary transients with first valid value
    bandpower_arr = bandpower.to_numpy(dtype=np.float32)
    bandpower_arr = np.nan_to_num(bandpower_arr, nan=0.0, posinf=0.0, neginf=0.0)
    return bandpower_arr


def build_invariant_channels(
    ax: np.ndarray,
    ay: np.ndarray,
    az: np.ndarray,
    sample_rate_hz: float = 100.0,
) -> np.ndarray:
    """Construct 4 heading-invariant sensor channels.

    Inputs must be phone accelerometer components (m/s^2).
    Returns array of shape (4, L).
    """
    # 0. Horizontal acceleration magnitude (yaw-invariant)
    a_horiz = np.sqrt(ax ** 2 + ay ** 2).astype(np.float32)
    # 1. Vertical acceleration (gravity + road perturbations along vehicle vertical)
    a_vert = az.astype(np.float32)
    # 2. Total 3D specific force norm (3D rotation-invariant)
    a_norm = np.sqrt(ax ** 2 + ay ** 2 + az ** 2).astype(np.float32)
    # 3. Short-time vertical vibration power (0.2 s causal window = 20 samples at 100 Hz)
    short_win = max(3, int(0.20 * sample_rate_hz))
    a_vert_bandpower = compute_causal_bandpower(az, short_window_len=short_win)

    return np.stack([a_horiz, a_vert, a_norm, a_vert_bandpower], axis=0)


def build_oracle_channels(
    ax: np.ndarray,
    ay: np.ndarray,
    az: np.ndarray,
    v_mps: np.ndarray,
    dt: float = 0.01,
    sample_rate_hz: float = 100.0,
) -> np.ndarray:
    """Construct 4 channels in longitudinal_oracle mode (DIAGNOSTIC ONLY).

    Channel 0 replaces a_horiz with forward longitudinal acceleration derived from VBOX.
    """
    a_long = np.gradient(v_mps, dt).astype(np.float32)
    a_vert = az.astype(np.float32)
    a_norm = np.sqrt(ax ** 2 + ay ** 2 + az ** 2).astype(np.float32)
    short_win = max(3, int(0.20 * sample_rate_hz))
    a_vert_bandpower = compute_causal_bandpower(az, short_window_len=short_win)

    return np.stack([a_long, a_vert, a_norm, a_vert_bandpower], axis=0)


def compute_invariant_window_statistics(window_4xL: np.ndarray, dt: float = 0.01) -> np.ndarray:
    """Extract deterministic 10-dimensional statistical summary features from (4, L) window.

    Window channels:
      0: a_horiz (or a_long in oracle mode)
      1: a_vert
      2: a_norm
      3: a_vert_bandpower

    Returns (10,) float32 vector:
      [mean_ch0, mean_a_vert, mean_a_norm, mean_bandpower,
       std_ch0,  std_a_vert,  std_a_norm,  std_bandpower,
       jerk_rms_ch0, dynamic_shock_rms]
    """
    ch0, a_vert, a_norm, bandpower = window_4xL[0], window_4xL[1], window_4xL[2], window_4xL[3]

    m0, m1, m2, m3 = float(np.mean(ch0)), float(np.mean(a_vert)), float(np.mean(a_norm)), float(np.mean(bandpower))
    s0, s1, s2, s3 = float(np.std(ch0)), float(np.std(a_vert)), float(np.std(a_norm)), float(np.std(bandpower))

    # Longitudinal / horizontal jerk RMS
    jerk = np.diff(ch0) / dt if len(ch0) > 1 else np.zeros(1)
    jerk_rms = float(np.sqrt(np.mean(jerk ** 2))) if len(jerk) > 0 else 0.0

    # RMS of dynamic specific force deviation from 1 g gravity (9.807 m/s^2)
    dynamic_shock_rms = float(np.sqrt(np.mean((a_norm - 9.807) ** 2)))

    return np.array([m0, m1, m2, m3, s0, s1, s2, s3, jerk_rms, dynamic_shock_rms], dtype=np.float32)


def load_candidate_filepaths(
    data_root: Path = DEFAULT_DATA_ROOT,
) -> tuple[dict[str, Path], dict[str, Path]]:
    """Locate VBOX and Smartphone files for synchronized categorized IO-VNBD dataset."""
    sync_dir = data_root / "Synchronised V abd S datasets" / "Categorised IOVNB Dataset"
    if not sync_dir.is_dir():
        sync_dir = data_root

    vbox_files: dict[str, Path] = {}
    phone_files: dict[str, Path] = {}

    for p in sync_dir.rglob("V-*.csv"):
        vbox_files[p.stem[2:].lower()] = p
    for p in sync_dir.rglob("S-*.csv"):
        phone_files[p.stem[2:].lower()] = p

    return vbox_files, phone_files


def load_time_alignment_offsets(
    csv_path: Path = DEFAULT_TIME_ALIGN_PATH,
) -> dict[str, float]:
    """Load verified time alignment offsets (seconds) from Part 4 report."""
    if not csv_path.is_file():
        logger.warning(f"Time alignment CSV missing at {csv_path}; using offset=0.0s")
        return {}
    df = pd.read_csv(csv_path, comment="#")
    return dict(zip(df["sequence"].str.lower(), df["offset_s"].astype(float)))


def process_sequence_windows(
    sequence_id: str,
    vbox_path: Path,
    phone_path: Path,
    time_offset_s: float,
    feature_mode: str = "invariant",
    window_size: int = WINDOW_SIZE_100HZ,
    stride: int = 10,
    target_sample_rate_hz: float = 100.0,
) -> WindowBatch:
    """Extract aligned, interpolated sliding windows from a single sequence pair."""
    df_p = read_frame(phone_path)
    df_v = read_frame(vbox_path)

    # 1. Timestamps: apply time alignment offset
    # offset_s > 0 means IMU lags odometry; so t_phone_aligned = t_phone - offset_s
    t_p = time_array(df_p, source="smartphone") - time_offset_s
    t_v = time_array(df_v, source="vbox")

    # Determine valid synchronized temporal overlap
    t_start = max(float(t_p[0]), float(t_v[0]))
    t_end = min(float(t_p[-1]), float(t_v[-1]))
    total_dur = t_end - t_start

    window_dur_s = window_size / target_sample_rate_hz
    if total_dur < window_dur_s:
        # Sequence shorter than one window
        empty_seq = np.empty((0, NUM_INVARIANT_CHANNELS, window_size), dtype=np.float32)
        empty_stats = np.empty((0, STATS_DIM), dtype=np.float32)
        empty_targets = np.empty((0, 1), dtype=np.float32)
        return WindowBatch(
            x_seq=empty_seq,
            x_stats=empty_stats,
            y_speed=empty_targets,
            y_stop=empty_targets,
            y_sigma=empty_targets,
            sequence_names=[],
            window_end_times=np.empty(0, dtype=np.float64),
        )

    # Common regular 100 Hz time grid
    dt = 1.0 / target_sample_rate_hz
    t_grid = np.arange(t_start, t_end, dt)

    # Interpolate phone accelerometer
    ax_interp = np.interp(t_grid, t_p, df_p["accel_x"].to_numpy(float))
    ay_interp = np.interp(t_grid, t_p, df_p["accel_y"].to_numpy(float))
    az_interp = np.interp(t_grid, t_p, df_p["accel_z"].to_numpy(float))

    # Interpolate reference speed (km/h -> m/s)
    ref_spd_mps = df_v["reference_speed"].to_numpy(float) / 3.6
    v_interp = np.interp(t_grid, t_v, ref_spd_mps)

    # Build 4 continuous channels
    if feature_mode == "invariant":
        channels_continuous = build_invariant_channels(
            ax_interp, ay_interp, az_interp, sample_rate_hz=target_sample_rate_hz
        )
    elif feature_mode == "longitudinal_oracle":
        channels_continuous = build_oracle_channels(
            ax_interp, ay_interp, az_interp, v_interp, dt=dt, sample_rate_hz=target_sample_rate_hz
        )
    else:
        raise ValueError(f"Unknown feature_mode '{feature_mode}'. Expected 'invariant' or 'longitudinal_oracle'.")

    # Sliding window extraction
    num_samples = len(t_grid)
    seq_list = []
    stats_list = []
    speeds = []
    stops = []
    sigmas = []
    end_times = []
    names = []

    for start_idx in range(0, num_samples - window_size + 1, stride):
        end_idx = start_idx + window_size
        win = channels_continuous[:, start_idx:end_idx]  # (4, window_size)

        # Ground truth target at causal window end
        curr_speed = float(v_interp[end_idx - 1])
        is_stop = 1.0 if curr_speed < 0.2 else 0.0

        # Acceleration magnitude at window end for dynamic uncertainty target
        curr_acc = abs(float(v_interp[end_idx - 1] - v_interp[end_idx - 2]) / dt) if end_idx > 1 else 0.0
        dyn_sigma = 0.35 + 0.05 * curr_acc

        stats = compute_invariant_window_statistics(win, dt=dt)

        seq_list.append(win)
        stats_list.append(stats)
        speeds.append(curr_speed)
        stops.append(is_stop)
        sigmas.append(dyn_sigma)
        end_times.append(t_grid[end_idx - 1])
        names.append(sequence_id)

    x_seq = np.array(seq_list, dtype=np.float32)
    x_stats = np.array(stats_list, dtype=np.float32)
    y_speed = np.array(speeds, dtype=np.float32).reshape(-1, 1)
    y_stop = np.array(stops, dtype=np.float32).reshape(-1, 1)
    y_sigma = np.array(sigmas, dtype=np.float32).reshape(-1, 1)

    return WindowBatch(
        x_seq=x_seq,
        x_stats=x_stats,
        y_speed=y_speed,
        y_stop=y_stop,
        y_sigma=y_sigma,
        sequence_names=names,
        window_end_times=np.array(end_times, dtype=np.float64),
    )


class IOVNBDDataset:
    """Cross-validation dataset provider for IO-VNBD Model A training & evaluation."""

    def __init__(
        self,
        folds_path: Path = DEFAULT_FOLDS_PATH,
        time_align_path: Path = DEFAULT_TIME_ALIGN_PATH,
        data_root: Path = DEFAULT_DATA_ROOT,
        feature_mode: str = "invariant",
        window_size: int = WINDOW_SIZE_100HZ,
        stride: int = 10,
        sample_rate_hz: float = 100.0,
    ):
        self.folds_path = folds_path
        self.feature_mode = feature_mode
        self.window_size = window_size
        self.stride = stride
        self.sample_rate_hz = sample_rate_hz

        with open(folds_path, "r", encoding="utf-8") as f:
            self.folds_metadata = json.load(f)

        self.vbox_files, self.phone_files = load_candidate_filepaths(data_root)
        self.offsets = load_time_alignment_offsets(time_align_path)

        # Cache extracted sequence batches
        self._seq_cache: dict[str, WindowBatch] = {}

    def get_sequence_batch(self, sequence_id: str) -> WindowBatch:
        """Get window batch for a single sequence (cached)."""
        clean_id = sequence_id.strip().lower()
        if clean_id in self._seq_cache:
            return self._seq_cache[clean_id]

        if clean_id not in self.vbox_files or clean_id not in self.phone_files:
            raise FileNotFoundError(f"Missing raw files for sequence '{clean_id}'")

        offset = self.offsets.get(clean_id, 0.0)
        batch = process_sequence_windows(
            sequence_id=clean_id,
            vbox_path=self.vbox_files[clean_id],
            phone_path=self.phone_files[clean_id],
            time_offset_s=offset,
            feature_mode=self.feature_mode,
            window_size=self.window_size,
            stride=self.stride,
            target_sample_rate_hz=self.sample_rate_hz,
        )
        self._seq_cache[clean_id] = batch
        return batch

    def get_fold_data(
        self,
        fold_idx: int,
    ) -> tuple[WindowBatch, WindowBatch]:
        """Return (train_batch, test_batch) for the specified fold (0 to 11)."""
        folds = self.folds_metadata["folds"]
        if fold_idx < 0 or fold_idx >= len(folds):
            raise IndexError(f"Fold index {fold_idx} out of range [0, {len(folds) - 1}]")

        fold_info = folds[fold_idx]
        test_seq_names = fold_info["test_sequences"]
        train_seq_names = fold_info["train_sequences"]

        train_batches = [self.get_sequence_batch(s) for s in train_seq_names]
        test_batches = [self.get_sequence_batch(s) for s in test_seq_names]

        def concat_batches(batches: list[WindowBatch]) -> WindowBatch:
            x_seq = np.concatenate([b.x_seq for b in batches], axis=0)
            x_stats = np.concatenate([b.x_stats for b in batches], axis=0)
            y_speed = np.concatenate([b.y_speed for b in batches], axis=0)
            y_stop = np.concatenate([b.y_stop for b in batches], axis=0)
            y_sigma = np.concatenate([b.y_sigma for b in batches], axis=0)
            names = [n for b in batches for n in b.sequence_names]
            end_times = np.concatenate([b.window_end_times for b in batches], axis=0)
            return WindowBatch(
                x_seq=x_seq,
                x_stats=x_stats,
                y_speed=y_speed,
                y_stop=y_stop,
                y_sigma=y_sigma,
                sequence_names=names,
                window_end_times=end_times,
            )

        return concat_batches(train_batches), concat_batches(test_batches)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Inspect and extract IO-VNBD Model A real-data sliding windows."
    )
    parser.add_argument(
        "--folds",
        type=Path,
        default=DEFAULT_FOLDS_PATH,
        help=f"Path to leave-one-drive-out folds JSON (default: {DEFAULT_FOLDS_PATH})",
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        default=DEFAULT_DATA_ROOT,
        help=f"Path to raw IO-VNBD dataset root (default: {DEFAULT_DATA_ROOT})",
    )
    parser.add_argument(
        "--feature-mode",
        type=str,
        default="invariant",
        choices=["invariant", "longitudinal_oracle"],
        help="Feature mode (default: 'invariant')",
    )
    parser.add_argument(
        "--window-size",
        type=int,
        default=WINDOW_SIZE_100HZ,
        help=f"Sliding window length in samples (default: {WINDOW_SIZE_100HZ})",
    )
    parser.add_argument(
        "--stride",
        type=int,
        default=10,
        help="Sliding window stride in samples (default: 10)",
    )

    args = parser.parse_args(argv)

    if args.feature_mode == "longitudinal_oracle":
        logger.warning(
            "*** DIAGNOSTIC ONLY ***: 'longitudinal_oracle' mode uses VBOX ground-truth kinematics. "
            "It is NOT deployable and exists only to benchmark the theoretical upper bound."
        )

    logger.info(f"Initializing IOVNBDDataset (mode='{args.feature_mode}', window_size={args.window_size}, stride={args.stride})...")
    ds = IOVNBDDataset(
        folds_path=args.folds,
        data_root=args.data_root,
        feature_mode=args.feature_mode,
        window_size=args.window_size,
        stride=args.stride,
    )

    num_folds = len(ds.folds_metadata["folds"])
    logger.info(f"Loaded {num_folds} folds. Computing window counts and speed statistics per fold...\n")

    print("=" * 105)
    print(f"IO-VNBD REAL-DATA DATASET SUMMARY (Mode: {args.feature_mode.upper()}, Window: {args.window_size} samples @ 100 Hz)")
    print("=" * 105)
    header = (
        f"{'Fold':<5} | {'Held-Out Drive':<15} | {'Test Wins':<10} | {'Train Wins':<10} | "
        f"{'Test Speed Mean+/-Std':<22} | {'Speed Range (m/s)':<18} | {'Stop%':<6}"
    )
    print(header)
    print("-" * 105)

    all_test_speeds = []

    for fold_idx in range(num_folds):
        train_b, test_b = ds.get_fold_data(fold_idx)
        held_out = ds.folds_metadata["folds"][fold_idx]["held_out_drive"]

        spd = test_b.y_speed.squeeze()
        all_test_speeds.extend(spd.tolist())

        spd_mean = float(np.mean(spd))
        spd_std = float(np.std(spd))
        spd_min = float(np.min(spd))
        spd_max = float(np.max(spd))
        stop_pct = float(np.mean(test_b.y_stop)) * 100.0

        mean_str = f"{spd_mean:5.2f} +/- {spd_std:4.2f} m/s"
        range_str = f"[{spd_min:4.1f}, {spd_max:4.1f}] m/s"

        row = (
            f"{fold_idx:<5} | {held_out:<15} | {len(test_b.x_seq):<10} | {len(train_b.x_seq):<10} | "
            f"{mean_str:<22} | {range_str:<18} | {stop_pct:4.1f}%"
        )
        print(row)

    all_spd = np.array(all_test_speeds)
    print("=" * 105)
    print("DATASET-WIDE SPEED DISTRIBUTION (Union of all held-out test sets = Full 12 Candidate Dataset):")
    print(f"  Total Windows : {len(all_spd)}")
    print(f"  Tensor Shapes : x_seq={test_b.x_seq.shape[1:]} (4 channels x 100 samples), x_stats={test_b.x_stats.shape[1:]} (10 features)")
    print(f"  Speed Mean    : {np.mean(all_spd):.2f} m/s ({np.mean(all_spd) * 3.6:.1f} km/h)")
    print(f"  Speed Std     : {np.std(all_spd):.2f} m/s")
    print(f"  Speed Min-Max : [{np.min(all_spd):.2f}, {np.max(all_spd):.2f}] m/s (0.0 to {np.max(all_spd) * 3.6:.1f} km/h)")
    print(f"  Speed Quartiles: 25%={np.percentile(all_spd, 25):.2f} m/s, 50% (Median)={np.median(all_spd):.2f} m/s, 75%={np.percentile(all_spd, 75):.2f} m/s")
    print(f"  Stillness (<0.2 m/s): {np.mean(all_spd < 0.2) * 100:.1f}% ({int(np.sum(all_spd < 0.2))} stationary windows)")
    print("=" * 105)

    return 0


if __name__ == "__main__":
    sys.exit(main())
