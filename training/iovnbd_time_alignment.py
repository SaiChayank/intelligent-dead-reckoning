#!/usr/bin/env python3
"""Estimate time offset between smartphone IMU and VBOX odometry streams per sequence.

Sign convention:
  offset_s > 0 means IMU timestamps lag odometry by offset_s.
  To align streams, evaluate IMU at t + offset_s (or subtract offset_s from IMU clock).

Method:
  1. Build forward acceleration from smartphone IMU by projecting gravity-removed
     acceleration onto the inferred forward axis from reports/iovnbd_frame_inference.csv.
  2. Build reference longitudinal acceleration from derivative of smoothed odometry speed.
  3. Resample both to a common sampling rate (lower of the two streams, or 50 Hz if unknown).
  4. Band-limit both signals to below 5 Hz using a zero-phase lowpass filter to suppress vibration.
  5. Compute normalized cross-correlation over lags of +-2.0 s and refine the peak to sub-sample
     precision via 3-point parabolic interpolation.
  6. Compute second-highest local peak ratio (ambiguity indicator) and zero-lag correlation.
  7. Apply the estimated offset, recompute correlation, and flag any sequence where correlation
     does not improve, offset exceeds 1.0 s, or second-peak ratio exceeds 0.8.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import numpy as np
import pandas as pd
from scipy import signal

from training.common import DEFAULT_DATA_ROOT, resolve_dataset_root
from training.phase0.audit import read_frame, time_array
from training.iovnbd_segments import (
    extract_forward_speed_mps,
    smooth_speed_and_compute_acceleration,
)
from training.iovnbd_frame_inference import (
    DEFAULT_OUTPUT_PATH as DEFAULT_FRAME_INFERENCE_PATH,
    find_all_pairs,
    read_frame_inference_csv,
)

DEFAULT_OUTPUT_PATH = PROJECT_ROOT / "reports" / "iovnbd_time_alignment.csv"


def estimate_cross_correlation_offset(
    y_ref: np.ndarray,
    y_imu: np.ndarray,
    f_common: float,
    max_lag_s: float = 2.0,
    peak_exclusion_s: float = 0.3,
) -> tuple[float, float, float, float]:
    """Compute normalized cross-correlation, parabolic peak refinement, and second peak ratio.

    Sign convention:
      lags > 0 evaluates correlation with y_imu shifted forward in time (lagging ref).
      offset_s > 0 means IMU timestamps lag odometry by offset_s.

    Returns:
        (offset_s, peak_corr, corr_at_zero, second_peak_ratio)
    """
    max_lag_idx = int(round(max_lag_s * f_common))
    lags = np.arange(-max_lag_idx, max_lag_idx + 1)

    s_ref = y_ref - np.mean(y_ref)
    s_imu = y_imu - np.mean(y_imu)
    norm_ref = float(np.linalg.norm(s_ref))
    norm_imu = float(np.linalg.norm(s_imu))

    if norm_ref < 1e-9 or norm_imu < 1e-9:
        return 0.0, 0.0, 0.0, 0.0

    corrs: list[float] = []
    for l in lags:
        if l < 0:
            c = float(np.dot(s_ref[-l:], s_imu[:l]) / (np.linalg.norm(s_ref[-l:]) * np.linalg.norm(s_imu[:l]) + 1e-12))
        elif l > 0:
            c = float(np.dot(s_ref[:-l], s_imu[l:]) / (np.linalg.norm(s_ref[:-l]) * np.linalg.norm(s_imu[l:]) + 1e-12))
        else:
            c = float(np.dot(s_ref, s_imu) / (norm_ref * norm_imu + 1e-12))
        corrs.append(c)

    corrs_arr = np.array(corrs)
    best_idx = int(np.argmax(corrs_arr))
    corr_at_zero = float(corrs_arr[max_lag_idx])

    # Parabolic sub-sample refinement: delta = (y_-1 - y_+1) / (2 * (y_-1 - 2*y_0 + y_+1))
    if 0 < best_idx < len(corrs_arr) - 1:
        y_m1 = corrs_arr[best_idx - 1]
        y_0 = corrs_arr[best_idx]
        y_p1 = corrs_arr[best_idx + 1]
        denom = float(y_m1 - 2.0 * y_0 + y_p1)
        if denom < -1e-12:
            delta = float(np.clip((y_m1 - y_p1) / (2.0 * denom), -0.5, 0.5))
            peak_corr = float(y_0 - ((y_m1 - y_p1) ** 2) / (8.0 * denom))
        else:
            delta = 0.0
            peak_corr = float(y_0)
    else:
        delta = 0.0
        peak_corr = float(corrs_arr[best_idx])

    offset_s = float((lags[best_idx] + delta) / f_common)

    # Find second highest local peak
    exclusion_samples = max(2, int(round(peak_exclusion_s * f_common)))
    peak_indices, _ = signal.find_peaks(corrs_arr)
    other_peaks = [
        p for p in peak_indices
        if abs(p - best_idx) >= exclusion_samples and corrs_arr[p] > 0.0
    ]

    if other_peaks and peak_corr > 1e-6:
        second_peak_val = float(max(corrs_arr[p] for p in other_peaks))
        second_peak_ratio = float(min(1.0, second_peak_val / peak_corr))
    else:
        second_peak_ratio = 0.0

    return offset_s, peak_corr, corr_at_zero, second_peak_ratio


def evaluate_aligned_correlation(
    t_grid: np.ndarray,
    ts: np.ndarray,
    a_imu_fwd: np.ndarray,
    y_ref_f: np.ndarray,
    offset_s: float,
    b_filt: np.ndarray,
    a_filt: np.ndarray,
) -> float:
    """Evaluate correlation after applying time offset shift to IMU signal."""
    # offset_s > 0 means IMU lags odometry, so IMU event occurs at t + offset_s
    t_eval = t_grid + offset_s
    valid = (t_eval >= ts[0]) & (t_eval <= ts[-1])
    if np.sum(valid) < 20:
        return 0.0

    y_imu_aligned = np.interp(t_eval[valid], ts, a_imu_fwd)
    y_imu_aligned_f = signal.filtfilt(b_filt, a_filt, y_imu_aligned)

    s_al = y_imu_aligned_f - np.mean(y_imu_aligned_f)
    s_rf = y_ref_f[valid] - np.mean(y_ref_f[valid])
    norm_al = float(np.linalg.norm(s_al))
    norm_rf = float(np.linalg.norm(s_rf))

    if norm_al < 1e-9 or norm_rf < 1e-9:
        return 0.0

    return float(np.dot(s_rf, s_al) / (norm_rf * norm_al))


def align_sequence(
    smartphone_csv_path: Path,
    vbox_csv_path: Path,
    sequence_id: str,
    forward_unit: np.ndarray,
    up_unit: np.ndarray,
    max_lag_s: float = 2.0,
    cutoff_hz: float = 4.0,
    common_rate_override: float | None = None,
    large_offset_thresh: float = 1.0,
    ambiguity_thresh: float = 0.8,
) -> dict:
    """Compute time alignment metrics between IMU forward acceleration and odometry acceleration."""
    dfs = read_frame(smartphone_csv_path)
    dfv = read_frame(vbox_csv_path)
    ts = time_array(dfs, "smartphone")
    tv = time_array(dfv, "vbox")

    # 1. IMU forward acceleration
    accel = dfs[["accel_x", "accel_y", "accel_z"]].to_numpy(float)
    proj_up = np.outer(accel @ up_unit, up_unit)
    a_horiz = accel - proj_up
    a_imu_fwd = a_horiz @ forward_unit

    # 2. Reference acceleration from odometry
    v_mps, _ = extract_forward_speed_mps(dfv)
    tv_clean, _, a_ref = smooth_speed_and_compute_acceleration(
        tv, v_mps, smoothing_window_s=0.5
    )

    # 3. Determine common sampling rate
    dt_s_arr = np.diff(ts[np.isfinite(ts)])
    dt_v_arr = np.diff(tv_clean[np.isfinite(tv_clean)])
    med_dt_s = float(np.median(dt_s_arr[dt_s_arr > 0])) if len(dt_s_arr) else 0.1
    med_dt_v = float(np.median(dt_v_arr[dt_v_arr > 0])) if len(dt_v_arr) else 0.1

    fs = 1.0 / med_dt_s if med_dt_s > 0 else 10.0
    fv = 1.0 / med_dt_v if med_dt_v > 0 else 10.0

    if common_rate_override is not None:
        f_common = float(common_rate_override)
    else:
        f_common = min(fs, fv) if (fs > 0 and fv > 0) else 50.0

    t_start = max(ts[0], tv_clean[0])
    t_end = min(ts[-1], tv_clean[-1])
    if t_end <= t_start + 1.0:
        return {
            "sequence": sequence_id,
            "offset_s": 0.0,
            "peak_corr": 0.0,
            "corr_at_zero": 0.0,
            "second_peak_ratio": 0.0,
            "corr_after_alignment": 0.0,
            "flag": "INSUFFICIENT_DATA",
        }

    t_grid = np.arange(t_start, t_end, 1.0 / f_common)
    y_imu = np.interp(t_grid, ts, a_imu_fwd)
    y_ref = np.interp(t_grid, tv_clean, a_ref)

    # 4. Band-limit below 5 Hz using zero-phase Butterworth filter
    nyquist = f_common / 2.0
    actual_cutoff = min(cutoff_hz, 0.45 * f_common)
    b_filt, a_filt = signal.butter(2, actual_cutoff / nyquist, btype="low")
    y_imu_f = signal.filtfilt(b_filt, a_filt, y_imu)
    y_ref_f = signal.filtfilt(b_filt, a_filt, y_ref)

    # 5. Cross-correlation over +- max_lag_s
    offset_s, peak_corr, corr_at_zero, second_peak_ratio = estimate_cross_correlation_offset(
        y_ref=y_ref_f,
        y_imu=y_imu_f,
        f_common=f_common,
        max_lag_s=max_lag_s,
    )

    # 6. Apply offset and recompute correlation
    corr_after = evaluate_aligned_correlation(
        t_grid=t_grid,
        ts=ts,
        a_imu_fwd=a_imu_fwd,
        y_ref_f=y_ref_f,
        offset_s=offset_s,
        b_filt=b_filt,
        a_filt=a_filt,
    )

    # 7. Flag anomalies
    flags: list[str] = []
    if corr_after < corr_at_zero - 1e-4:
        flags.append("NO_IMPROVEMENT")
    if abs(offset_s) > large_offset_thresh:
        flags.append("LARGE_OFFSET")
    if second_peak_ratio > ambiguity_thresh:
        flags.append("AMBIGUOUS_PEAK")

    flag_str = ";".join(flags) if flags else "OK"

    return {
        "sequence": sequence_id,
        "offset_s": offset_s,
        "peak_corr": peak_corr,
        "corr_at_zero": corr_at_zero,
        "second_peak_ratio": second_peak_ratio,
        "corr_after_alignment": corr_after,
        "flag": flag_str,
    }


def read_time_alignment_csv(path: Path | str) -> pd.DataFrame:
    """Helper to load time alignment CSV, ignoring comment lines."""
    return pd.read_csv(path, comment="#")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Estimate time offset between smartphone IMU and VBOX odometry streams per sequence."
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        default=None,
        help=f"Path to extracted IO-VNBD root (default: {DEFAULT_DATA_ROOT})",
    )
    parser.add_argument(
        "--frame-inference",
        type=Path,
        default=DEFAULT_FRAME_INFERENCE_PATH,
        help=f"Path to frame inference CSV (default: {DEFAULT_FRAME_INFERENCE_PATH})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_PATH,
        help=f"Path to output CSV (default: {DEFAULT_OUTPUT_PATH})",
    )
    parser.add_argument(
        "--sequence",
        type=str,
        default=None,
        help="Optional sequence identifier to process (e.g. 'M', 'Vta2'). If omitted, runs all.",
    )
    parser.add_argument(
        "--max-lag",
        type=float,
        default=2.0,
        help="Maximum lag search window in seconds (default: +-2.0 s)",
    )
    parser.add_argument(
        "--cutoff-hz",
        type=float,
        default=4.0,
        help="Lowpass filter cutoff frequency in Hz (default: 4.0 Hz, must be < 5.0 Hz)",
    )
    parser.add_argument(
        "--common-rate",
        type=float,
        default=None,
        help="Common resampling rate in Hz (default: lower of stream rates, or 50 Hz if unknown)",
    )
    parser.add_argument(
        "--large-offset-threshold",
        type=float,
        default=1.0,
        help="Flag threshold for suspicious large offset in seconds (default: 1.0 s)",
    )
    parser.add_argument(
        "--ambiguity-threshold",
        type=float,
        default=0.8,
        help="Flag threshold for suspicious second-highest peak ratio (default: 0.8)",
    )

    args = parser.parse_args(argv)

    try:
        data_root = resolve_dataset_root(args.data_root)
    except FileNotFoundError as exc:
        parser.error(str(exc))

    if not args.frame_inference.is_file():
        parser.error(f"Frame inference CSV not found at {args.frame_inference}. Run training/iovnbd_frame_inference.py first.")

    frame_df = read_frame_inference_csv(args.frame_inference)
    pairs = find_all_pairs(data_root, sequence_filter=args.sequence)
    if not pairs:
        parser.error(f"No synchronized S/V pairs found under {data_root}" + (f" for sequence {args.sequence}" if args.sequence else ""))

    print(f"Loaded {len(pairs)} synchronized sequence pair(s) from {data_root}.")
    print("Sign convention: offset_s > 0 means IMU timestamps lag odometry by offset_s.")
    print(f"Lag window: +- {args.max_lag:.1f} s, Lowpass cutoff: {args.cutoff_hz:.1f} Hz")
    print(f"Suspicious thresholds: |offset_s| > {args.large_offset_threshold:.1f} s, second_peak_ratio > {args.ambiguity_threshold:.2f}")
    print("-" * 95)
    print(f"{'Sequence':<10} {'Offset (s)':<12} {'Peak Corr':<11} {'Zero Corr':<11} {'2nd Ratio':<11} {'After Corr':<12} {'Flag'}")
    print("-" * 95)

    results: list[dict] = []
    suspicious_count = 0

    for seq_id, s_path, v_path in pairs:
        frame_match = frame_df[frame_df["sequence"] == seq_id]
        if frame_match.empty:
            print(f"Skipping {seq_id}: missing from frame inference report.")
            continue
        row = frame_match.iloc[0]
        f_vec = np.array([row["forward_x"], row["forward_y"], row["forward_z"]])
        u_vec = np.array([row["up_x"], row["up_y"], row["up_z"]])

        res = align_sequence(
            smartphone_csv_path=s_path,
            vbox_csv_path=v_path,
            sequence_id=seq_id,
            forward_unit=f_vec,
            up_unit=u_vec,
            max_lag_s=args.max_lag,
            cutoff_hz=args.cutoff_hz,
            common_rate_override=args.common_rate,
            large_offset_thresh=args.large_offset_threshold,
            ambiguity_thresh=args.ambiguity_threshold,
        )
        results.append(res)

        is_suspicious = (res["flag"] != "OK")
        if is_suspicious:
            suspicious_count += 1
            status_tag = f"[{res['flag']}]"
        else:
            status_tag = "[OK]"

        print(
            f"{res['sequence']:<10} {res['offset_s']:>10.3f}s {res['peak_corr']:>10.3f} "
            f"{res['corr_at_zero']:>10.3f} {res['second_peak_ratio']:>10.3f} "
            f"{res['corr_after_alignment']:>11.3f}  {status_tag}",
            flush=True,
        )

    print("-" * 95)
    print(f"Total sequences processed: {len(results)}")
    print(f"Clean sequences [OK]: {len(results) - suspicious_count}")
    print(f"Suspicious sequences [FLAGGED]: {suspicious_count}")

    # Write output CSV with explicit sign convention in comment header
    args.output.parent.mkdir(parents=True, exist_ok=True)
    cols = ["sequence", "offset_s", "peak_corr", "corr_at_zero", "second_peak_ratio", "corr_after_alignment", "flag"]
    df_out = pd.DataFrame(results)[cols]

    for c in ["offset_s", "peak_corr", "corr_at_zero", "second_peak_ratio", "corr_after_alignment"]:
        df_out[c] = df_out[c].round(4)

    header_comment = (
        "# Sign convention: offset_s > 0 means IMU timestamps lag odometry by offset_s\n"
        f"# Parameters: max_lag={args.max_lag:.1f}s, cutoff_hz={args.cutoff_hz:.1f}Hz, "
        f"large_offset_thresh={args.large_offset_threshold:.1f}s, ambiguity_thresh={args.ambiguity_threshold:.2f}\n"
    )
    with args.output.open("w", encoding="utf-8") as f_out:
        f_out.write(header_comment)
        df_out.to_csv(f_out, index=False)

    print(f"\nWrote time alignment results to {args.output} ({len(df_out)} rows).")

    return 0


if __name__ == "__main__":
    sys.exit(main())
