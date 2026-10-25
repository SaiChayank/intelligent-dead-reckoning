#!/usr/bin/env python3
"""Create a leakage-free split of admitted IO-VNBD sequences.

Groups sequences by drive unit (e.g. grouping continuous partition slices like
vta1a/vta1b into vta1, vw16a/vw16b into vw16) to ensure no continuous trip
leaks across train, val, and test splits.

Splits groups into train / val / test of roughly 70/15/15 by total duration
using a fixed random seed (default 42). If fewer than 6 groups are approved,
it produces a leave-one-group-out (LOGO) fold list and marks the dataset as
too small for a fixed split.

Output:
  evaluation/iovnbd_split.json
"""

from __future__ import annotations

import argparse
import json
import logging
import random
import re
import sys
from pathlib import Path
from typing import Any, Mapping, Sequence

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

DEFAULT_ADMISSION_PATH = PROJECT_ROOT / "reports" / "iovnbd_admission.json"
DEFAULT_OUTPUT_PATH = PROJECT_ROOT / "evaluation" / "iovnbd_split.json"
DEFAULT_SEGMENTS_PATH = PROJECT_ROOT / "reports" / "iovnbd_segments.csv"

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger("iovnbd_split")


def extract_group_id(sequence_id: str, rule: str = "drive_session") -> str:
    """Extract grouping unit from sequence identifier.

    Rules:
      - 'drive_session': Strips trailing alphabetic partition letters following numbers.
        Slices of the same physical continuous recording (e.g. 'vta1a' and 'vta1b',
        'vw16a' and 'vw16b', 's3a', 's3b', 's3c') map to the same base drive ID.
      - 'route': Extracts leading alphabetic letters representing route/environment
        (e.g. 'vta', 'vtb', 'vw', 's', 'm', 'y').
      - 'driver': Maps sequence prefix to driver identifier (e.g. 'driver_e', 'driver_a').
    """
    clean_id = sequence_id.strip().lower()
    if rule == "drive_session":
        # Match digit followed by trailing lowercase letters, strip trailing letters
        return re.sub(r"([0-9]+)[a-z]+$", r"\1", clean_id)
    elif rule == "route":
        m = re.match(r"^[a-z]+", clean_id)
        return m.group(0) if m else clean_id
    elif rule in ("driver", "vehicle"):
        if clean_id.startswith(("vta", "vtb", "vw", "vfa", "vfb")):
            return "driver_e"
        elif clean_id.startswith("s"):
            return "driver_a"
        elif clean_id.startswith("m"):
            return "driver_b"
        elif clean_id.startswith("y"):
            return "driver_d"
        elif clean_id.startswith("t"):
            return "driver_f"
        elif clean_id.startswith("st"):
            return "driver_c"
        elif clean_id.startswith("a"):
            return "driver_h"
        elif clean_id.startswith("i"):
            return "driver_g"
        return "driver_unknown"
    else:
        raise ValueError(f"Unknown grouping rule '{rule}'. Expected 'drive_session', 'route', or 'driver'.")


def load_sequence_durations(
    sequence_ids: Sequence[str],
    segments_path: Path | None = None,
) -> dict[str, float]:
    """Obtain duration in seconds for each sequence.

    Reads segment intervals from iovnbd_segments.csv if available.
    Falls back to raw data frame count if necessary.
    """
    durations: dict[str, float] = {}
    remaining = set(s.lower() for s in sequence_ids)

    # Attempt reading from segments CSV
    if segments_path and segments_path.is_file():
        import pandas as pd

        df_seg = pd.read_csv(segments_path)
        if "sequence" in df_seg.columns and "start_s" in df_seg.columns and "end_s" in df_seg.columns:
            for s in list(remaining):
                sub = df_seg[df_seg["sequence"].str.lower() == s]
                if not sub.empty:
                    dur = float((sub["end_s"] - sub["start_s"]).sum())
                    durations[s] = round(dur, 2)
                    remaining.remove(s)

    # For any remaining, check raw files via audit loader
    if remaining:
        from training.iovnbd_segments import find_all_vbox_files
        from training.phase0.audit import read_frame, time_array

        vbox_files = dict(find_all_vbox_files(PROJECT_ROOT / "data" / "raw" / "iovnbd"))
        for s in list(remaining):
            p = vbox_files.get(s)
            if p and p.is_file():
                df = read_frame(p)
                t = time_array(df, source="vbox")
                dur = float(t[-1] - t[0]) if len(t) > 1 else len(df) * 0.1
                durations[s] = round(dur, 2)
                remaining.remove(s)
            else:
                # Default fallback nominal 10 Hz estimate
                durations[s] = 100.0

    return durations


def load_admitted_sequences(
    admission_path: Path,
    pool: str = "auto",
) -> tuple[list[str], str]:
    """Load sequences to be split from admission report.

    Pool options:
      - 'auto': Uses 'approved_sequence_names' if non-empty; otherwise falls back
        to 'conditional_diagnostic_sequence_names'.
      - 'approved': Strictly uses 'approved_sequence_names'.
      - 'conditional': Strictly uses 'conditional_diagnostic_sequence_names'.
    """
    if not admission_path.is_file():
        raise FileNotFoundError(f"Admission report not found: {admission_path}")

    with open(admission_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    approved = [s.strip().lower() for s in data.get("approved_sequence_names", [])]
    conditional = [s.strip().lower() for s in data.get("conditional_diagnostic_sequence_names", [])]

    if pool == "approved":
        return approved, "approved"
    elif pool == "conditional":
        return conditional, "conditional_diagnostic_candidates"
    elif pool == "auto":
        if approved:
            return approved, "approved"
        elif conditional:
            logger.info(
                "No sequences in 'approved_sequence_names' (blocked by frame confidence). "
                "Splitting 12 'conditional_diagnostic_sequence_names' that passed time alignment."
            )
            return conditional, "conditional_diagnostic_candidates"
        else:
            return [], "empty"
    else:
        raise ValueError(f"Unknown pool mode '{pool}'. Expected 'auto', 'approved', or 'conditional'.")


def partition_groups(
    group_durations: Mapping[str, float],
    target_ratios: Mapping[str, float] = {"train": 0.70, "val": 0.15, "test": 0.15},
    seed: int = 42,
    num_restarts: int = 50,
) -> dict[str, list[str]]:
    """Partition groups into train/val/test sets balancing duration towards target ratios.

    Uses a deterministic multi-start randomized local hill-climbing search seeded by `seed`.
    """
    groups = sorted(group_durations.keys())
    total_dur = sum(group_durations.values())
    if total_dur <= 0.0:
        return {"train": list(groups), "val": [], "test": []}

    targets = {k: ratio * total_dur for k, ratio in target_ratios.items()}

    def compute_loss(assignment: dict[str, str]) -> float:
        durs = {"train": 0.0, "val": 0.0, "test": 0.0}
        for g, set_name in assignment.items():
            durs[set_name] += group_durations[g]
        # Heavily penalize empty sets if there are at least 3 groups
        if len(groups) >= 3 and any(durs[k] == 0.0 for k in ["train", "val", "test"]):
            return float("inf")
        return sum(((durs[k] - targets[k]) / total_dur) ** 2 for k in ["train", "val", "test"])

    rng = random.Random(seed)
    best_loss = float("inf")
    best_assignment: dict[str, str] = {g: "train" for g in groups}

    for _ in range(num_restarts):
        # Seeded random initial assignment
        assignment: dict[str, str] = {g: rng.choice(["train", "val", "test"]) for g in groups}
        if len(groups) >= 3:
            for needed in ["train", "val", "test"]:
                if not any(v == needed for v in assignment.values()):
                    assignment[rng.choice(groups)] = needed

        # Local hill climbing
        improved = True
        while improved:
            improved = False
            cur_loss = compute_loss(assignment)
            shuffled_g = list(groups)
            rng.shuffle(shuffled_g)
            for g in shuffled_g:
                cur_set = assignment[g]
                for next_set in ["train", "val", "test"]:
                    if next_set == cur_set:
                        continue
                    cand = dict(assignment)
                    cand[g] = next_set
                    cand_loss = compute_loss(cand)
                    if cand_loss < cur_loss - 1e-7:
                        assignment = cand
                        cur_loss = cand_loss
                        improved = True
                        break
                if improved:
                    break

        loss = compute_loss(assignment)
        if loss < best_loss:
            best_loss = loss
            best_assignment = assignment

    result: dict[str, list[str]] = {"train": [], "val": [], "test": []}
    for g in sorted(groups):
        result[best_assignment[g]].append(g)

    return result


def create_split(
    sequence_ids: Sequence[str],
    durations: Mapping[str, float],
    grouping_rule: str = "drive_session",
    target_ratios: Mapping[str, float] = {"train": 0.70, "val": 0.15, "test": 0.15},
    seed: int = 42,
) -> dict[str, Any]:
    """Create leakage-free split or leave-one-group-out folds if groups < 6."""
    # 1. Group sequences
    groups_to_seqs: dict[str, list[str]] = {}
    for seq in sorted(sequence_ids):
        g = extract_group_id(seq, rule=grouping_rule)
        groups_to_seqs.setdefault(g, []).append(seq)

    group_durations: dict[str, float] = {
        g: sum(durations.get(s, 0.0) for s in seqs) for g, seqs in groups_to_seqs.items()
    }
    total_duration = sum(group_durations.values())
    num_groups = len(groups_to_seqs)

    # 2. Check group count threshold (< 6)
    if num_groups < 6:
        folds = []
        for fold_idx, test_group in enumerate(sorted(groups_to_seqs.keys())):
            test_seqs = groups_to_seqs[test_group]
            train_seqs = [s for g, s_list in groups_to_seqs.items() if g != test_group for s in s_list]
            test_dur = group_durations[test_group]
            train_dur = sum(group_durations[g] for g in groups_to_seqs if g != test_group)
            folds.append(
                {
                    "fold": fold_idx,
                    "test_group": test_group,
                    "test_sequences": sorted(test_seqs),
                    "train_sequences": sorted(train_seqs),
                    "test_duration_s": round(test_dur, 2),
                    "train_duration_s": round(train_dur, 2),
                }
            )

        return {
            "seed": seed,
            "grouping_rule": grouping_rule,
            "status": "dataset_too_small_for_fixed_split",
            "message": (
                f"Dataset has fewer than 6 approved groups ({num_groups} found: {sorted(groups_to_seqs.keys())}); "
                f"the dataset is too small for a fixed 70/15/15 split. Generated leave-one-group-out folds instead."
            ),
            "num_groups": num_groups,
            "train": [],
            "val": [],
            "test": [],
            "durations_s": {
                "train": 0.0,
                "val": 0.0,
                "test": 0.0,
            },
            "leave_one_group_out_folds": folds,
        }

    # 3. Partition groups >= 6
    group_split = partition_groups(group_durations, target_ratios=target_ratios, seed=seed)

    train_seqs = sorted(s for g in group_split["train"] for s in groups_to_seqs[g])
    val_seqs = sorted(s for g in group_split["val"] for s in groups_to_seqs[g])
    test_seqs = sorted(s for g in group_split["test"] for s in groups_to_seqs[g])

    dur_train = sum(durations.get(s, 0.0) for s in train_seqs)
    dur_val = sum(durations.get(s, 0.0) for s in val_seqs)
    dur_test = sum(durations.get(s, 0.0) for s in test_seqs)

    durations_dict = {
        "train": round(dur_train, 2),
        "val": round(dur_val, 2),
        "test": round(dur_test, 2),
    }

    # Verification: zero overlap
    train_set, val_set, test_set = set(train_seqs), set(val_seqs), set(test_seqs)
    assert not (train_set & val_set), f"Leakage: sequences in train and val: {train_set & val_set}"
    assert not (train_set & test_set), f"Leakage: sequences in train and test: {train_set & test_set}"
    assert not (val_set & test_set), f"Leakage: sequences in val and test: {val_set & test_set}"

    train_g, val_g, test_g = set(group_split["train"]), set(group_split["val"]), set(group_split["test"])
    assert not (train_g & val_g), f"Group leakage: groups in train and val: {train_g & val_g}"
    assert not (train_g & test_g), f"Group leakage: groups in train and test: {train_g & test_g}"
    assert not (val_g & test_g), f"Group leakage: groups in val and test: {val_g & test_g}"

    # Full coverage
    all_assigned = train_set | val_set | test_set
    assert all_assigned == set(sequence_ids), "Not all admitted sequences were assigned to a partition!"

    return {
        "seed": seed,
        "grouping_rule": grouping_rule,
        "num_groups": num_groups,
        "train": train_seqs,
        "val": val_seqs,
        "test": test_seqs,
        "durations_s": durations_dict,
        "groups": {
            "train": sorted(group_split["train"]),
            "val": sorted(group_split["val"]),
            "test": sorted(group_split["test"]),
        },
        "sequence_counts": {
            "train": len(train_seqs),
            "val": len(val_seqs),
            "test": len(test_seqs),
        },
        "proportions": {
            "train": round(dur_train / total_duration, 4) if total_duration > 0 else 0.0,
            "val": round(dur_val / total_duration, 4) if total_duration > 0 else 0.0,
            "test": round(dur_test / total_duration, 4) if total_duration > 0 else 0.0,
        },
    }


def format_summary_table(split_data: Mapping[str, Any]) -> str:
    """Format per-set summary table as human-readable markdown text."""
    rule = split_data.get("grouping_rule", "unknown")
    seed = split_data.get("seed", 42)
    num_groups = split_data.get("num_groups", 0)

    if split_data.get("status") == "dataset_too_small_for_fixed_split":
        folds = split_data.get("leave_one_group_out_folds", [])
        lines = [
            "=" * 78,
            f"IO-VNBD SPLIT WARNING: DATASET TOO SMALL FOR FIXED SPLIT (Seed: {seed}, Rule: {rule})",
            "=" * 78,
            split_data.get("message", ""),
            f"Total Groups: {num_groups}. Generated {len(folds)} Leave-One-Group-Out Folds:",
            "-" * 78,
            f"{'Fold':<6} {'Test Group':<12} {'Test Seqs':<12} {'Test Dur (s)':<14} {'Train Seqs':<12} {'Train Dur (s)':<14}",
            "-" * 78,
        ]
        for f in folds:
            lines.append(
                f"{f['fold']:<6} {f['test_group']:<12} {len(f['test_sequences']):<12} "
                f"{f['test_duration_s']:<14.1f} {len(f['train_sequences']):<12} {f['train_duration_s']:<14.1f}"
            )
        lines.append("=" * 78)
        return "\n".join(lines)

    train_seqs = split_data["train"]
    val_seqs = split_data["val"]
    test_seqs = split_data["test"]
    durations = split_data["durations_s"]
    props = split_data.get("proportions", {})
    groups = split_data.get("groups", {})

    total_seqs = len(train_seqs) + len(val_seqs) + len(test_seqs)
    total_dur = durations["train"] + durations["val"] + durations["test"]

    lines = [
        "=" * 84,
        f"IO-VNBD LEAKAGE-FREE SPLIT SUMMARY (Seed: {seed}, Grouping Rule: {rule})",
        "=" * 84,
        f"{'Partition':<10} {'Sequences':<11} {'Groups':<8} {'Duration (s)':<14} {'Duration (min)':<16} {'Pct':<8} {'Target':<8}",
        "-" * 84,
        f"{'train':<10} {len(train_seqs):<11} {len(groups.get('train', [])):<8} {durations['train']:<14.2f} {durations['train']/60.0:<16.2f} {props.get('train', 0.0)*100:<7.1f}% 70.0%",
        f"{'val':<10} {len(val_seqs):<11} {len(groups.get('val', [])):<8} {durations['val']:<14.2f} {durations['val']/60.0:<16.2f} {props.get('val', 0.0)*100:<7.1f}% 15.0%",
        f"{'test':<10} {len(test_seqs):<11} {len(groups.get('test', [])):<8} {durations['test']:<14.2f} {durations['test']/60.0:<16.2f} {props.get('test', 0.0)*100:<7.1f}% 15.0%",
        "-" * 84,
        f"{'Total':<10} {total_seqs:<11} {num_groups:<8} {total_dur:<14.2f} {total_dur/60.0:<16.2f} 100.0%   100.0%",
        "=" * 84,
        "Leakage-Free Verification:",
        f"  - Train Groups ({len(groups.get('train', []))}): {groups.get('train', [])}",
        f"  - Val Groups   ({len(groups.get('val', []))}): {groups.get('val', [])}",
        f"  - Test Groups  ({len(groups.get('test', []))}): {groups.get('test', [])}",
        "  - Group Overlap: ZERO across all sets (Verified)",
        "  - Sequence Overlap: ZERO across all sets (Verified)",
        "=" * 84,
    ]
    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Create a leakage-free split of approved IO-VNBD sequences into train/val/test."
    )
    parser.add_argument(
        "--admission",
        type=Path,
        default=DEFAULT_ADMISSION_PATH,
        help=f"Path to admission report JSON (default: {DEFAULT_ADMISSION_PATH})",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_PATH,
        help=f"Path to output split JSON (default: {DEFAULT_OUTPUT_PATH})",
    )
    parser.add_argument(
        "--segments",
        type=Path,
        default=DEFAULT_SEGMENTS_PATH,
        help=f"Path to segments CSV for sequence durations (default: {DEFAULT_SEGMENTS_PATH})",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=42,
        help="Fixed random seed for deterministic splitting (default: 42)",
    )
    parser.add_argument(
        "--train-ratio",
        type=float,
        default=0.70,
        help="Target duration ratio for training set (default: 0.70)",
    )
    parser.add_argument(
        "--val-ratio",
        type=float,
        default=0.15,
        help="Target duration ratio for validation set (default: 0.15)",
    )
    parser.add_argument(
        "--test-ratio",
        type=float,
        default=0.15,
        help="Target duration ratio for testing set (default: 0.15)",
    )
    parser.add_argument(
        "--group-by",
        type=str,
        default="drive_session",
        choices=["drive_session", "route", "driver"],
        help="Grouping rule for leakage prevention (default: 'drive_session')",
    )
    parser.add_argument(
        "--pool",
        type=str,
        default="auto",
        choices=["auto", "approved", "conditional"],
        help="Candidate sequence pool selection (default: 'auto')",
    )

    args = parser.parse_args(argv)

    seq_ids, pool_type = load_admitted_sequences(args.admission, pool=args.pool)
    logger.info(f"Loaded {len(seq_ids)} sequences from pool '{pool_type}'.")

    durations = load_sequence_durations(seq_ids, segments_path=args.segments)

    target_ratios = {
        "train": args.train_ratio,
        "val": args.val_ratio,
        "test": args.test_ratio,
    }

    split_result = create_split(
        seq_ids,
        durations,
        grouping_rule=args.group_by,
        target_ratios=target_ratios,
        seed=args.seed,
    )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(split_result, f, indent=2)
    logger.info(f"Wrote split results to {args.output}")

    summary_table = format_summary_table(split_result)
    print("\n" + summary_table + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
