#!/usr/bin/env python3
"""Segment IO-VNBD sequences into stopped / moving / accelerating / braking.

Uses ground-truth odometry forward speed from VBOX recordings as reference.
Converts VBOX speed from km/h to m/s via division by 3.6.
Smooths speed lightly with a moving average and computes longitudinal acceleration
via numeric gradient with respect to timestamps.

Thresholds (configurable via CLI):
  - stopped: speed < 0.2 m/s continuously for >= 2.0 s
  - accelerating: acceleration > +0.5 m/s^2 for >= 1.0 s while speed > 0.5 m/s
  - braking: acceleration < -0.5 m/s^2 for >= 1.0 s while speed > 0.5 m/s
  - moving: everything else with speed >= 0.5 m/s
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

from training.common import DEFAULT_DATA_ROOT, resolve_dataset_root
from training.phase0.audit import read_frame, time_array

KMH_TO_MPS = 1.0 / 3.6
DEFAULT_OUTPUT_PATH = PROJECT_ROOT / "reports" / "iovnbd_segments.csv"


def extract_forward_speed_mps(df: pd.DataFrame) -> tuple[np.ndarray, str]:
    """Obtain ground-truth forward speed in m/s from VBOX odometry columns.

    Priority:
      1. reference_speed (Velocity km/hr) -> multiplied by 1/3.6 (km/h to m/s)
      2. vehicle_indicated_speed (km/hr) -> multiplied by 1/3.6
      3. Average wheel speed (rad/sec) -> multiplied by effective tire radius ~0.2765 m
    """
    if "reference_speed" in df.columns:
        raw = pd.to_numeric(df["reference_speed"], errors="coerce").to_numpy(dtype=float)
        if np.any(np.isfinite(raw)):
            speed_mps = raw * KMH_TO_MPS
            # Clean small negative values due to Doppler sensor noise at standstill
            speed_mps = np.where(np.isnan(speed_mps), np.nan, np.maximum(0.0, speed_mps))
            return speed_mps, "reference_speed (km/h) / 3.6 -> m/s"

    if "vehicle_indicated_speed" in df.columns:
        raw = pd.to_numeric(df["vehicle_indicated_speed"], errors="coerce").to_numpy(dtype=float)
        if np.any(np.isfinite(raw)):
            speed_mps = np.maximum(0.0, raw * KMH_TO_MPS)
            return speed_mps, "vehicle_indicated_speed (km/h) / 3.6 -> m/s"

    ws_cols = [c for c in df.columns if "wheel_speed" in c]
    if ws_cols:
        ws_arrays = [pd.to_numeric(df[c], errors="coerce").to_numpy(dtype=float) for c in ws_cols]
        ws_mean = np.nanmean(ws_arrays, axis=0)
        tire_radius_m = 0.276539
        speed_mps = np.maximum(0.0, ws_mean * tire_radius_m)
        return speed_mps, f"mean({ws_cols}) * {tire_radius_m:.4f}m -> m/s"

    raise ValueError("No valid forward speed or wheel speed column found in dataframe")


def smooth_speed_and_compute_acceleration(
    t: np.ndarray,
    speed_mps: np.ndarray,
    smoothing_window_s: float = 0.5,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Smooth speed lightly using a moving average and compute acceleration.

    Returns:
        (t_clean, smoothed_speed_mps, longitudinal_accel_mps2)
    """
    n = len(t)
    if n == 0:
        return t, speed_mps, np.array([], dtype=float)

    # Ensure strictly increasing timestamps for differentiation
    t_clean = np.array(t, dtype=float, copy=True)
    dt_raw = np.diff(t_clean)
    pos_dt = dt_raw[np.isfinite(dt_raw) & (dt_raw > 0)]
    med_dt = float(np.median(pos_dt)) if len(pos_dt) > 0 else 0.1

    for i in range(1, n):
        if t_clean[i] <= t_clean[i - 1]:
            t_clean[i] = t_clean[i - 1] + med_dt

    # Moving average window size
    window_samples = max(1, int(round(smoothing_window_s / med_dt)))
    smoothed_speed = (
        pd.Series(speed_mps)
        .rolling(window=window_samples, center=True, min_periods=1)
        .mean()
        .to_numpy(dtype=float)
    )

    # Longitudinal acceleration derivative: dv/dt
    longitudinal_accel = np.gradient(smoothed_speed, t_clean)
    return t_clean, smoothed_speed, longitudinal_accel


def segment_speed_profile(
    t: np.ndarray,
    speed_mps: np.ndarray,
    accel_mps2: np.ndarray,
    stopped_speed: float = 0.2,
    moving_speed: float = 0.5,
    accel_threshold: float = 0.5,
    brake_threshold: float = 0.5,
    min_stopped_duration: float = 2.0,
    min_accel_duration: float = 1.0,
    min_brake_duration: float = 1.0,
    min_moving_duration: float = 0.0,
) -> list[dict]:
    """Segment speed and acceleration profile into labeled intervals.

    Labels:
      - stopped: speed < stopped_speed continuously for >= min_stopped_duration
      - accelerating: accel > accel_threshold for >= min_accel_duration while speed > moving_speed
      - braking: accel < -brake_threshold for >= min_brake_duration while speed > moving_speed
      - moving: everything else with speed >= moving_speed (or fallback)
    """
    n = len(t)
    if n == 0:
        return []

    # 1. Pointwise candidate classification
    raw_labels = np.array(["moving"] * n, dtype=object)

    # Stopped candidates
    raw_labels[speed_mps < stopped_speed] = "stopped"
    # Accelerating candidates
    raw_labels[(speed_mps > moving_speed) & (accel_mps2 > accel_threshold)] = "accelerating"
    # Braking candidates
    raw_labels[(speed_mps > moving_speed) & (accel_mps2 < -brake_threshold)] = "braking"

    min_dur_map = {
        "stopped": min_stopped_duration,
        "accelerating": min_accel_duration,
        "braking": min_brake_duration,
        "moving": min_moving_duration,
    }

    # Vectorized run extraction helper
    def get_run_bounds(arr: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        ch = np.where(arr[:-1] != arr[1:])[0]
        st = np.r_[0, ch + 1]
        en = np.r_[ch, len(arr) - 1]
        return st, en

    # 2. Filter candidate runs that fail minimum duration (revert to "moving")
    starts, ends = get_run_bounds(raw_labels)
    for s_i, e_i in zip(starts, ends):
        lbl = raw_labels[s_i]
        dur = t[e_i] - t[s_i]
        req = min_dur_map.get(lbl, 0.0)
        if dur < req and lbl != "moving":
            raw_labels[s_i : e_i + 1] = "moving"

    # 3. Form final contiguous runs after merging adjacent identical labels
    starts, ends = get_run_bounds(raw_labels)

    # 4. Convert runs into segments with contiguous start_s and end_s
    segments: list[dict] = []
    for i in range(len(starts)):
        s_idx = int(starts[i])
        e_idx = int(ends[i])
        start_s = float(t[s_idx])
        # Contiguous non-overlapping end boundary
        if i + 1 < len(starts):
            end_s = float(t[starts[i + 1]])
        else:
            end_s = float(t[e_idx])

        duration_s = float(end_s - start_s)
        mean_speed = float(np.mean(speed_mps[s_idx : e_idx + 1]))

        if duration_s > 0 or len(starts) == 1:
            segments.append({
                "start_s": start_s,
                "end_s": end_s,
                "duration_s": duration_s,
                "label": raw_labels[s_idx],
                "mean_speed_mps": mean_speed,
            })

    # Drop segments shorter than their label's minimum duration
    filtered_segments: list[dict] = []
    for s in segments:
        req = min_dur_map.get(s["label"], 0.0)
        if s["duration_s"] >= req:
            filtered_segments.append(s)

    return filtered_segments


def process_sequence(
    vbox_csv_path: Path,
    sequence_id: str,
    stopped_speed: float = 0.2,
    moving_speed: float = 0.5,
    accel_threshold: float = 0.5,
    brake_threshold: float = 0.5,
    min_stopped_duration: float = 2.0,
    min_accel_duration: float = 1.0,
    min_brake_duration: float = 1.0,
    min_moving_duration: float = 0.0,
    smoothing_window: float = 0.5,
) -> tuple[list[dict], dict[str, float]]:
    """Process a single VBOX recording into labeled segments."""
    df = read_frame(vbox_csv_path)
    t = time_array(df, "vbox")
    speed_mps, _ = extract_forward_speed_mps(df)

    t_clean, smoothed_speed, accel_mps2 = smooth_speed_and_compute_acceleration(
        t, speed_mps, smoothing_window_s=smoothing_window
    )

    raw_segments = segment_speed_profile(
        t_clean,
        smoothed_speed,
        accel_mps2,
        stopped_speed=stopped_speed,
        moving_speed=moving_speed,
        accel_threshold=accel_threshold,
        brake_threshold=brake_threshold,
        min_stopped_duration=min_stopped_duration,
        min_accel_duration=min_accel_duration,
        min_brake_duration=min_brake_duration,
        min_moving_duration=min_moving_duration,
    )

    segments: list[dict] = []
    totals: dict[str, float] = {"stopped": 0.0, "moving": 0.0, "accelerating": 0.0, "braking": 0.0}

    for s in raw_segments:
        entry = {
            "sequence": sequence_id,
            "start_s": s["start_s"],
            "end_s": s["end_s"],
            "label": s["label"],
            "mean_speed_mps": s["mean_speed_mps"],
        }
        segments.append(entry)
        totals[s["label"]] = totals.get(s["label"], 0.0) + (s["end_s"] - s["start_s"])

    return segments, totals


def find_all_vbox_files(data_root: Path, sequence_filter: str | None = None) -> list[tuple[str, Path]]:
    """Find all synchronized VBOX sequences."""
    sync_dir = data_root / "Synchronised V abd S datasets" / "Categorised IOVNB Dataset"
    if not sync_dir.is_dir():
        sync_dir = data_root

    vbox_files = sorted(sync_dir.rglob("V-*.csv"))
    results: list[tuple[str, Path]] = []
    for p in vbox_files:
        seq = p.stem[2:].lower()
        if sequence_filter is not None and seq != sequence_filter.lower():
            continue
        results.append((seq, p))
    return results


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Label IO-VNBD sequences into stopped / moving / accelerating / braking segments."
    )
    parser.add_argument(
        "--data-root",
        type=Path,
        default=None,
        help=f"Path to extracted IO-VNBD root (default: {DEFAULT_DATA_ROOT})",
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
        "--stopped-speed",
        type=float,
        default=0.2,
        help="Maximum speed threshold (m/s) for stopped segments (default: 0.2 m/s)",
    )
    parser.add_argument(
        "--moving-speed",
        type=float,
        default=0.5,
        help="Minimum speed threshold (m/s) for moving, accelerating, and braking segments (default: 0.5 m/s)",
    )
    parser.add_argument(
        "--accel-threshold",
        type=float,
        default=0.5,
        help="Minimum longitudinal acceleration (m/s^2) for accelerating segments (default: +0.5 m/s^2)",
    )
    parser.add_argument(
        "--brake-threshold",
        type=float,
        default=0.5,
        help="Minimum deceleration magnitude (m/s^2) for braking segments (default: -0.5 m/s^2)",
    )
    parser.add_argument(
        "--min-stopped-duration",
        type=float,
        default=2.0,
        help="Minimum continuous duration (seconds) for a stopped segment (default: 2.0 s)",
    )
    parser.add_argument(
        "--min-accel-duration",
        type=float,
        default=1.0,
        help="Minimum continuous duration (seconds) for an accelerating segment (default: 1.0 s)",
    )
    parser.add_argument(
        "--min-brake-duration",
        type=float,
        default=1.0,
        help="Minimum continuous duration (seconds) for a braking segment (default: 1.0 s)",
    )
    parser.add_argument(
        "--min-moving-duration",
        type=float,
        default=0.0,
        help="Minimum continuous duration (seconds) for a moving segment (default: 0.0 s)",
    )
    parser.add_argument(
        "--smoothing-window",
        type=float,
        default=0.5,
        help="Moving average smoothing window (seconds) applied to speed (default: 0.5 s)",
    )

    args = parser.parse_args(argv)

    try:
        data_root = resolve_dataset_root(args.data_root)
    except FileNotFoundError as exc:
        parser.error(str(exc))

    vbox_list = find_all_vbox_files(data_root, sequence_filter=args.sequence)
    if not vbox_list:
        parser.error(f"No VBOX files found under {data_root}" + (f" for sequence {args.sequence}" if args.sequence else ""))

    print(f"Loaded {len(vbox_list)} VBOX sequence(s) from {data_root}.")
    print(f"Ground-truth speed unit conversion: VBOX Velocity (km/hr) * (1/3.6) -> m/s.")
    print(f"Thresholds: stopped < {args.stopped_speed} m/s (>= {args.min_stopped_duration}s), "
          f"accel > +{args.accel_threshold} m/s^2 (>= {args.min_accel_duration}s, speed > {args.moving_speed}), "
          f"brake < -{args.brake_threshold} m/s^2 (>= {args.min_brake_duration}s, speed > {args.moving_speed}), "
          f"moving >= {args.moving_speed} m/s.")
    print("-" * 90)
    print(f"{'Sequence':<10} {'Duration':<10} {'Stopped':<12} {'Moving':<12} {'Accel':<12} {'Braking':<12} {'Status'}")
    print("-" * 90)

    all_segments: list[dict] = []
    flagged_no_stops: list[str] = []

    for seq_id, path in vbox_list:
        segments, totals = process_sequence(
            path,
            seq_id,
            stopped_speed=args.stopped_speed,
            moving_speed=args.moving_speed,
            accel_threshold=args.accel_threshold,
            brake_threshold=args.brake_threshold,
            min_stopped_duration=args.min_stopped_duration,
            min_accel_duration=args.min_accel_duration,
            min_brake_duration=args.min_brake_duration,
            min_moving_duration=args.min_moving_duration,
            smoothing_window=args.smoothing_window,
        )
        all_segments.extend(segments)

        total_dur = sum(totals.values())
        stop_s = totals.get("stopped", 0.0)
        mov_s = totals.get("moving", 0.0)
        acc_s = totals.get("accelerating", 0.0)
        brk_s = totals.get("braking", 0.0)

        if stop_s < args.min_stopped_duration:
            status = "[FLAG: NO STOPS]"
            flagged_no_stops.append(seq_id)
        else:
            status = "[OK: has stops]"

        print(
            f"{seq_id:<10} {total_dur:>8.1f}s {stop_s:>10.1f}s {mov_s:>10.1f}s {acc_s:>10.1f}s {brk_s:>10.1f}s  {status}",
            flush=True,
        )

    print("-" * 90)
    print(f"Total sequences processed: {len(vbox_list)}")
    print(f"Total segments produced: {len(all_segments)}")
    print(f"Sequences WITH valid stops (>= {args.min_stopped_duration}s): {len(vbox_list) - len(flagged_no_stops)}")
    print(f"Sequences FLAGGED WITH NO STOPS: {len(flagged_no_stops)} -> {flagged_no_stops}")

    # Write output CSV
    args.output.parent.mkdir(parents=True, exist_ok=True)
    df_out = pd.DataFrame(all_segments, columns=["sequence", "start_s", "end_s", "label", "mean_speed_mps"])
    # Format floats cleanly
    df_out["start_s"] = df_out["start_s"].round(3)
    df_out["end_s"] = df_out["end_s"].round(3)
    df_out["mean_speed_mps"] = df_out["mean_speed_mps"].round(3)
    df_out.to_csv(args.output, index=False)
    print(f"\nWrote segment labels to {args.output} ({len(df_out)} rows).")

    return 0


if __name__ == "__main__":
    sys.exit(main())
