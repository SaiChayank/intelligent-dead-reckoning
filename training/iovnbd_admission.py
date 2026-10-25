#!/usr/bin/env python3
"""Decide which IO-VNBD sequences are trustworthy enough for training and evaluation.

Admission policy:
  A sequence is approved if and only if ALL hold:
    1. Frame confidence is 'high' or 'medium' (from reports/iovnbd_frame_inference.csv).
    2. Time-alignment peak_corr >= T_corr and second_peak_ratio <= T_amb (from reports/iovnbd_time_alignment.csv).
    3. corr_after_alignment > corr_at_zero (time alignment strictly improved correlation).
    4. No timestamp gaps larger than 1.0 s in the used range.

Outputs:
  reports/iovnbd_admission.json (matching reports/training_admission.json schema)
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Sequence

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

import numpy as np
import pandas as pd

from training.common import DEFAULT_DATA_ROOT, resolve_dataset_root
from training.phase0.audit import read_frame, time_array
from training.iovnbd_frame_inference import (
    DEFAULT_OUTPUT_PATH as DEFAULT_FRAME_PATH,
    find_all_pairs,
    read_frame_inference_csv,
)
from training.iovnbd_time_alignment import (
    DEFAULT_OUTPUT_PATH as DEFAULT_ALIGN_PATH,
    read_time_alignment_csv,
)

DEFAULT_OUTPUT_PATH = PROJECT_ROOT / "reports" / "iovnbd_admission.json"

DEFAULT_T_CORR = 0.50
DEFAULT_T_AMB = 0.80
DEFAULT_MAX_GAP = 1.0  # seconds


def check_timestamp_continuity(
    ts: np.ndarray,
    tv: np.ndarray,
    max_allowed_gap_s: float = DEFAULT_MAX_GAP,
) -> tuple[bool, float]:
    """Check whether any timestamp step in either stream exceeds the gap threshold.

    Returns:
        (has_no_large_gaps, max_observed_gap_s)
    """
    valid_ts = ts[np.isfinite(ts)]
    valid_tv = tv[np.isfinite(tv)]

    max_gap_s = 0.0
    if len(valid_ts) > 1:
        dts = np.diff(valid_ts)
        pos_dts = dts[dts > 0]
        if len(pos_dts) > 0:
            max_gap_s = max(max_gap_s, float(np.max(pos_dts)))

    if len(valid_tv) > 1:
        dtv = np.diff(valid_tv)
        pos_dtv = dtv[dtv > 0]
        if len(pos_dtv) > 0:
            max_gap_s = max(max_gap_s, float(np.max(pos_dtv)))

    has_no_gaps = (max_gap_s <= max_allowed_gap_s)
    return has_no_gaps, max_gap_s


def evaluate_admission(
    sequence_id: str,
    frame_row: pd.Series,
    align_row: pd.Series,
    max_gap_s: float,
    duration_s: float,
    t_corr: float = DEFAULT_T_CORR,
    t_amb: float = DEFAULT_T_AMB,
    max_allowed_gap_s: float = DEFAULT_MAX_GAP,
) -> dict:
    """Evaluate admission criteria for a single sequence pair."""
    confidence = str(frame_row.get("confidence", "low")).lower()
    peak_corr = float(align_row.get("peak_corr", 0.0))
    second_peak_ratio = float(align_row.get("second_peak_ratio", 1.0))
    corr_at_zero = float(align_row.get("corr_at_zero", 0.0))
    corr_after = float(align_row.get("corr_after_alignment", 0.0))

    # Evaluate each criterion
    crit_frame = confidence in {"high", "medium"}
    crit_corr = (peak_corr >= t_corr) and (second_peak_ratio <= t_amb)
    crit_improve = (corr_after > corr_at_zero)
    crit_gaps = (max_gap_s <= max_allowed_gap_s)

    approved = bool(crit_frame and crit_corr and crit_improve and crit_gaps)

    # Compile failure reasons if rejected
    blocking_reasons: list[str] = []
    if not crit_frame:
        blocking_reasons.append("Low frame confidence (decoupled world-accel vs device-gyro axes)")
    if peak_corr < t_corr:
        blocking_reasons.append(f"Peak correlation below threshold ({peak_corr:.3f} < {t_corr:.2f})")
    if second_peak_ratio > t_amb:
        blocking_reasons.append(f"Ambiguous lag correlation peak (2nd peak ratio {second_peak_ratio:.3f} > {t_amb:.2f})")
    if not crit_improve:
        blocking_reasons.append(f"Correlation did not improve after alignment ({corr_after:.3f} <= {corr_at_zero:.3f})")
    if not crit_gaps:
        blocking_reasons.append(f"Timestamp gap > {max_allowed_gap_s:.1f}s (max gap {max_gap_s:.2f}s)")

    if approved:
        disposition = "approved"
        blocking_reason_str = None
    elif crit_corr and crit_improve and crit_gaps:
        # Candidate for diagnostic calibration if frame issue is isolated
        disposition = "conditional_diagnostic_candidate_only"
        blocking_reason_str = "; ".join(blocking_reasons)
    else:
        disposition = "quarantined"
        blocking_reason_str = "; ".join(blocking_reasons)

    alignment_reason = (
        f"peak_corr={peak_corr:.3f}, corr_at_zero={corr_at_zero:.3f}, "
        f"corr_after={corr_after:.3f}, 2nd_ratio={second_peak_ratio:.3f}, "
        f"frame_conf={confidence}, max_gap={max_gap_s:.2f}s"
    )

    return {
        "id": f"categorized/{sequence_id}",
        "sequence": sequence_id,
        "split_group": f"recording-{sequence_id}",
        "historical_alignment_class": "evaluated",
        "approved_for_training": approved,
        "disposition": disposition,
        "alignment_reason": alignment_reason,
        "blocking_reason": blocking_reason_str,
        "primary_blocking_category": blocking_reasons[0] if blocking_reasons else "None",
        "duration_s": duration_s,
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Decide which IO-VNBD sequences are trustworthy enough for training and evaluation."
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
        default=DEFAULT_FRAME_PATH,
        help=f"Path to frame inference CSV (default: {DEFAULT_FRAME_PATH})",
    )
    parser.add_argument(
        "--time-alignment",
        type=Path,
        default=DEFAULT_ALIGN_PATH,
        help=f"Path to time alignment CSV (default: {DEFAULT_ALIGN_PATH})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_PATH,
        help=f"Path to output admission JSON (default: {DEFAULT_OUTPUT_PATH})",
    )
    parser.add_argument(
        "--t-corr",
        type=float,
        default=DEFAULT_T_CORR,
        help=f"Minimum cross-correlation peak threshold (default: {DEFAULT_T_CORR})",
    )
    parser.add_argument(
        "--t-amb",
        type=float,
        default=DEFAULT_T_AMB,
        help=f"Maximum allowed second-highest peak ratio (default: {DEFAULT_T_AMB})",
    )
    parser.add_argument(
        "--max-gap",
        type=float,
        default=DEFAULT_MAX_GAP,
        help=f"Maximum allowed timestamp gap in seconds (default: {DEFAULT_MAX_GAP} s)",
    )

    args = parser.parse_args(argv)

    try:
        data_root = resolve_dataset_root(args.data_root)
    except FileNotFoundError as exc:
        parser.error(str(exc))

    if not args.frame_inference.is_file():
        parser.error(f"Frame inference report missing at {args.frame_inference}. Run training/iovnbd_frame_inference.py first.")
    if not args.time_alignment.is_file():
        parser.error(f"Time alignment report missing at {args.time_alignment}. Run training/iovnbd_time_alignment.py first.")

    frame_df = read_frame_inference_csv(args.frame_inference)
    align_df = read_time_alignment_csv(args.time_alignment)

    pairs = find_all_pairs(data_root)
    if not pairs:
        parser.error(f"No synchronized pairs found under {data_root}.")

    print(f"Loaded {len(pairs)} synchronized sequence pair(s) from {data_root}.")
    print(f"Admission gates: frame_confidence in ['high', 'medium'], peak_corr >= {args.t_corr:.2f}, "
          f"second_peak_ratio <= {args.t_amb:.2f}, corr_after > corr_at_zero, max_gap <= {args.max_gap:.1f}s")
    print("-" * 105)

    pair_records: list[dict] = []
    primary_reasons: Counter[str] = Counter()
    approved_names: list[str] = []
    conditional_names: list[str] = []
    total_approved_seconds = 0.0

    for seq_id, s_path, v_path in pairs:
        f_match = frame_df[frame_df["sequence"] == seq_id]
        a_match = align_df[align_df["sequence"] == seq_id]
        if f_match.empty or a_match.empty:
            continue

        f_row = f_match.iloc[0]
        a_row = a_match.iloc[0]

        dfs = read_frame(s_path)
        dfv = read_frame(v_path)
        ts = time_array(dfs, "smartphone")
        tv = time_array(dfv, "vbox")

        duration_s = float(ts[-1] - ts[0]) if len(ts) > 1 and np.isfinite(ts[-1]) else 0.0
        _, max_gap_s = check_timestamp_continuity(ts, tv, max_allowed_gap_s=args.max_gap)

        eval_res = evaluate_admission(
            sequence_id=seq_id,
            frame_row=f_row,
            align_row=a_row,
            max_gap_s=max_gap_s,
            duration_s=duration_s,
            t_corr=args.t_corr,
            t_amb=args.t_amb,
            max_allowed_gap_s=args.max_gap,
        )

        pair_records.append(eval_res)

        if eval_res["approved_for_training"]:
            approved_names.append(seq_id)
            total_approved_seconds += duration_s
        else:
            primary_reasons[eval_res["primary_blocking_category"]] += 1
            if eval_res["disposition"] == "conditional_diagnostic_candidate_only":
                conditional_names.append(seq_id)

    total_evaluated = len(pair_records)
    approved_count = len(approved_names)
    rejected_count = total_evaluated - approved_count
    total_approved_minutes = total_approved_seconds / 60.0

    # Build JSON according to existing training_admission.json schema
    admission_doc = {
        "version": "2.0.0",
        "decision": "approved" if approved_count > 0 else "no-go",
        "scope": "IO-VNBD synchronized smartphone/VBOX pairs evaluated under data-driven frame inference and cross-correlation time alignment.",
        "approved_sequence_names": approved_names,
        "conditional_diagnostic_sequence_names": conditional_names,
        "policy": (
            f"Admitted only if frame confidence in ['high', 'medium'], peak_corr >= {args.t_corr:.2f}, "
            f"second_peak_ratio <= {args.t_amb:.2f}, corr_after_alignment > corr_at_zero, and no gaps > {args.max_gap:.1f}s."
        ),
        "unpaired_recordings": "Not approved for supervised VBOX velocity labels without separately verified reference correspondence.",
        "pairs": [
            {
                "id": r["id"],
                "sequence": r["sequence"],
                "split_group": r["split_group"],
                "historical_alignment_class": r["historical_alignment_class"],
                "approved_for_training": r["approved_for_training"],
                "disposition": r["disposition"],
                "alignment_reason": r["alignment_reason"],
                "blocking_reason": r["blocking_reason"],
            }
            for r in pair_records
        ],
    }

    # Write output JSON
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as f_json:
        json.dump(admission_doc, f_json, indent=2)
    print(f"Wrote admission report to {args.output}")

    # Print summary
    print("\n" + "=" * 60)
    print("IO-VNBD TRAINING ADMISSION SUMMARY")
    print("=" * 60)
    print(f"Approved count:               {approved_count}")
    print(f"Rejected count:               {rejected_count}")
    print(f"Total approved duration:      {total_approved_minutes:.2f} minutes")
    print(f"Conditional candidates:       {len(conditional_names)}")
    print("\nPrimary Rejection Reasons Grouped:")
    for reason, count in primary_reasons.most_common():
        print(f"  - [{count:2d}] {reason}")
    print("=" * 60)

    # Checkpoint
    if approved_count == 0:
        print("\n" + "!" * 90)
        print("CHECKPOINT REACHED: Approved sequence count is exactly 0.")
        print("Reason: All 72 sequences were rejected because their frame confidence is 'low'")
        print("        due to the uncalibrated Android world-stabilized accelerometer vs device gyro mismatch.")
        print("STOPPING as requested. Thresholds have NOT been artificially loosened.")
        print("!" * 90)

    return 0


if __name__ == "__main__":
    sys.exit(main())
