"""Tests for IO-VNBD leave-one-group-out (LOGO) cross-validation folds.

Asserts:
  - evaluation/iovnbd_logo_folds.json exists and adheres to schema
  - Exactly 12 folds generated for the 12 candidate drive sessions
  - Zero leakage in every fold (held-out drive/sequences never appear in training set)
  - Full sequence coverage in every fold
  - Every candidate drive is evaluated as the held-out test set exactly once
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[1]
FOLDS_PATH = PROJECT_ROOT / "evaluation" / "iovnbd_logo_folds.json"
ADMISSION_PATH = PROJECT_ROOT / "reports" / "iovnbd_admission.json"


@pytest.fixture
def folds_data() -> dict:
    assert FOLDS_PATH.is_file(), f"Folds artifact missing: {FOLDS_PATH}"
    with open(FOLDS_PATH, "r", encoding="utf-8") as f:
        return json.load(f)


@pytest.fixture
def candidate_sequences() -> list[str]:
    with open(ADMISSION_PATH, "r", encoding="utf-8") as f:
        adm = json.load(f)
    return sorted(adm.get("conditional_diagnostic_sequence_names", []))


def test_folds_schema(folds_data: dict) -> None:
    """Validate top-level keys and structure of iovnbd_logo_folds.json."""
    required_keys = ["grouping_rule", "num_folds", "num_drives", "num_sequences", "total_duration_s", "drives", "folds"]
    for k in required_keys:
        assert k in folds_data, f"Missing required top-level key '{k}'"

    assert folds_data["grouping_rule"] == "drive_session"
    assert folds_data["num_folds"] == 12
    assert folds_data["num_drives"] == 12
    assert folds_data["num_sequences"] == 12
    assert folds_data["total_duration_s"] == 815.0
    assert len(folds_data["drives"]) == 12
    assert len(folds_data["folds"]) == 12


def test_no_leakage_in_any_fold(folds_data: dict) -> None:
    """Assert zero drive and zero sequence overlap across test and train sets for every fold."""
    for fold in folds_data["folds"]:
        fold_id = fold["fold"]
        held_out_drive = fold["held_out_drive"]
        test_seqs = set(fold["test_sequences"])
        train_drives = set(fold["train_drives"])
        train_seqs = set(fold["train_sequences"])

        # 1. Drive-level leakage check
        assert held_out_drive not in train_drives, (
            f"Drive leakage in fold {fold_id}: held-out drive '{held_out_drive}' present in train_drives"
        )

        # 2. Sequence-level leakage check
        overlap = test_seqs & train_seqs
        assert len(overlap) == 0, (
            f"Sequence leakage in fold {fold_id}: overlapping sequences {overlap}"
        )


def test_full_coverage_in_every_fold(folds_data: dict, candidate_sequences: list[str]) -> None:
    """Every fold must partition the exact full candidate sequence set."""
    expected_seqs = set(candidate_sequences)
    expected_drives = set(folds_data["drives"])

    for fold in folds_data["folds"]:
        fold_id = fold["fold"]
        test_seqs = set(fold["test_sequences"])
        train_seqs = set(fold["train_sequences"])
        combined_seqs = test_seqs | train_seqs

        assert combined_seqs == expected_seqs, (
            f"Incomplete sequence coverage in fold {fold_id}: missing {expected_seqs - combined_seqs}"
        )
        assert len(test_seqs) + len(train_seqs) == len(expected_seqs)

        # Drives coverage
        combined_drives = {fold["held_out_drive"]} | set(fold["train_drives"])
        assert combined_drives == expected_drives

        # Duration sum
        dur_sum = round(fold["test_duration_s"] + fold["train_duration_s"], 2)
        assert dur_sum == 815.0, f"Duration sum mismatch in fold {fold_id}: {dur_sum} != 815.0"


def test_every_drive_tested_exactly_once(folds_data: dict) -> None:
    """Each drive must be held out as the test drive in exactly one fold."""
    all_drives = folds_data["drives"]
    held_out_list = [f["held_out_drive"] for f in folds_data["folds"]]

    assert len(held_out_list) == len(all_drives)
    assert sorted(held_out_list) == sorted(all_drives)
    assert len(set(held_out_list)) == len(all_drives)
