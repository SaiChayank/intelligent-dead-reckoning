"""Tests for IO-VNBD sequence splitting and leakage prevention.

Asserts:
  - Zero sequence and group overlap across train, val, and test partitions
  - Full coverage of admitted sequences
  - Determinism (same seed yields exact identical split)
  - Proper fallback to leave-one-group-out (LOGO) folds when fewer than 6 groups exist
  - Output JSON schema adherence
"""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from training.iovnbd_split import (
    DEFAULT_ADMISSION_PATH,
    DEFAULT_OUTPUT_PATH,
    create_split,
    extract_group_id,
    load_admitted_sequences,
    load_sequence_durations,
    main,
    partition_groups,
)


@pytest.fixture
def admitted_sequences() -> list[str]:
    seqs, _ = load_admitted_sequences(DEFAULT_ADMISSION_PATH, pool="auto")
    return seqs


@pytest.fixture
def sequence_durations(admitted_sequences: list[str]) -> dict[str, float]:
    return load_sequence_durations(admitted_sequences)


def test_split_determinism(admitted_sequences: list[str], sequence_durations: dict[str, float]) -> None:
    """Same seed must produce the exact same split and duration metrics."""
    split1 = create_split(admitted_sequences, sequence_durations, seed=42)
    split2 = create_split(admitted_sequences, sequence_durations, seed=42)

    assert split1["train"] == split2["train"], "Train split not deterministic!"
    assert split1["val"] == split2["val"], "Val split not deterministic!"
    assert split1["test"] == split2["test"], "Test split not deterministic!"
    assert split1["durations_s"] == split2["durations_s"], "Durations not deterministic!"
    assert split1["groups"] == split2["groups"], "Group assignments not deterministic!"


def test_zero_sequence_overlap(admitted_sequences: list[str], sequence_durations: dict[str, float]) -> None:
    """No sequence may appear in more than one partition."""
    split = create_split(admitted_sequences, sequence_durations, seed=42)
    train_s = set(split["train"])
    val_s = set(split["val"])
    test_s = set(split["test"])

    assert len(train_s & val_s) == 0, f"Overlap between train and val: {train_s & val_s}"
    assert len(train_s & test_s) == 0, f"Overlap between train and test: {train_s & test_s}"
    assert len(val_s & test_s) == 0, f"Overlap between val and test: {val_s & test_s}"


def test_zero_group_overlap(admitted_sequences: list[str], sequence_durations: dict[str, float]) -> None:
    """No group may appear in more than one partition."""
    split = create_split(admitted_sequences, sequence_durations, seed=42)
    train_g = set(split["groups"]["train"])
    val_g = set(split["groups"]["val"])
    test_g = set(split["groups"]["test"])

    assert len(train_g & val_g) == 0, f"Group overlap between train and val: {train_g & val_g}"
    assert len(train_g & test_g) == 0, f"Group overlap between train and test: {train_g & test_g}"
    assert len(val_g & test_g) == 0, f"Group overlap between val and test: {val_g & test_g}"


def test_continuous_partition_slices_grouped_together() -> None:
    """Assert continuous slices like vta1a and vta1b are mapped to the same drive group and partition."""
    # Test grouping rule extraction
    assert extract_group_id("vta1a", rule="drive_session") == "vta1"
    assert extract_group_id("vta1b", rule="drive_session") == "vta1"
    assert extract_group_id("vw16a", rule="drive_session") == "vw16"
    assert extract_group_id("vw16b", rule="drive_session") == "vw16"
    assert extract_group_id("s3a", rule="drive_session") == "s3"
    assert extract_group_id("s3b", rule="drive_session") == "s3"
    assert extract_group_id("s3c", rule="drive_session") == "s3"
    assert extract_group_id("vfa01", rule="drive_session") == "vfa01"

    # If both slices are in the dataset, they must land in the same partition
    mock_seqs = ["vta1a", "vta1b", "vta7", "vta12", "vta13", "vta19", "vta23", "vta24", "vw16a", "vw16b"]
    mock_durs = {s: 60.0 for s in mock_seqs}
    split = create_split(mock_seqs, mock_durs, seed=42)

    # vta1a and vta1b must be in the same partition
    for part in ["train", "val", "test"]:
        part_seqs = set(split[part])
        has_1a = "vta1a" in part_seqs
        has_1b = "vta1b" in part_seqs
        assert has_1a == has_1b, f"vta1a and vta1b were separated across splits in {part}!"

        has_16a = "vw16a" in part_seqs
        has_16b = "vw16b" in part_seqs
        assert has_16a == has_16b, f"vw16a and vw16b were separated across splits in {part}!"


def test_full_coverage(admitted_sequences: list[str], sequence_durations: dict[str, float]) -> None:
    """The union of train, val, and test must equal the full admitted sequence set."""
    split = create_split(admitted_sequences, sequence_durations, seed=42)
    all_split = set(split["train"]) | set(split["val"]) | set(split["test"])
    expected = set(admitted_sequences)

    assert all_split == expected, f"Missing sequences: {expected - all_split}, extra: {all_split - expected}"
    assert len(split["train"]) + len(split["val"]) + len(split["test"]) == len(admitted_sequences)


def test_duration_proportions_roughly_70_15_15(
    admitted_sequences: list[str], sequence_durations: dict[str, float]
) -> None:
    """Duration proportions must be roughly 70/15/15."""
    split = create_split(admitted_sequences, sequence_durations, seed=42)
    durations = split["durations_s"]
    total = durations["train"] + durations["val"] + durations["test"]

    p_train = durations["train"] / total
    p_val = durations["val"] / total
    p_test = durations["test"] / total

    # Tolerance window around 70/15/15 given discrete group sizes
    assert 0.60 <= p_train <= 0.80, f"Train proportion {p_train:.3f} outside [0.60, 0.80]"
    assert 0.08 <= p_val <= 0.22, f"Val proportion {p_val:.3f} outside [0.08, 0.22]"
    assert 0.08 <= p_test <= 0.22, f"Test proportion {p_test:.3f} outside [0.08, 0.22]"


def test_fewer_than_6_groups_fallback() -> None:
    """When fewer than 6 groups are approved, generate leave-one-group-out folds instead."""
    small_seqs = ["vta7", "vta12", "vtb7", "vtb10", "vw9"]  # 5 groups
    small_durs = {s: 50.0 for s in small_seqs}

    split = create_split(small_seqs, small_durs, grouping_rule="drive_session", seed=42)

    assert split.get("status") == "dataset_too_small_for_fixed_split"
    assert "leave_one_group_out_folds" in split
    folds = split["leave_one_group_out_folds"]
    assert len(folds) == 5, f"Expected 5 LOGO folds for 5 groups, got {len(folds)}"

    for fold in folds:
        test_seqs = set(fold["test_sequences"])
        train_seqs = set(fold["train_sequences"])
        assert len(test_seqs & train_seqs) == 0, f"Overlap in fold {fold['fold']}"
        assert test_seqs | train_seqs == set(small_seqs), f"Incomplete coverage in fold {fold['fold']}"


def test_route_grouping_fewer_than_6_groups(admitted_sequences: list[str], sequence_durations: dict[str, float]) -> None:
    """Grouping by route yields 3 groups ('vta', 'vtb', 'vw'), triggering LOGO fallback."""
    split = create_split(admitted_sequences, sequence_durations, grouping_rule="route", seed=42)

    assert split.get("status") == "dataset_too_small_for_fixed_split"
    assert split["num_groups"] == 3
    assert len(split["leave_one_group_out_folds"]) == 3


def test_output_json_artifact_schema() -> None:
    """Output JSON file must exist and adhere to the contract schema."""
    assert DEFAULT_OUTPUT_PATH.is_file(), f"Output file missing: {DEFAULT_OUTPUT_PATH}"

    with open(DEFAULT_OUTPUT_PATH, "r", encoding="utf-8") as f:
        data = json.load(f)

    # Required top-level keys specified in task
    required_keys = ["seed", "grouping_rule", "train", "val", "test", "durations_s"]
    for k in required_keys:
        assert k in data, f"Required key '{k}' missing from {DEFAULT_OUTPUT_PATH}"

    assert isinstance(data["seed"], int)
    assert isinstance(data["grouping_rule"], str)
    assert isinstance(data["train"], list)
    assert isinstance(data["val"], list)
    assert isinstance(data["test"], list)
    assert isinstance(data["durations_s"], dict)
    assert "train" in data["durations_s"]
    assert "val" in data["durations_s"]
    assert "test" in data["durations_s"]


def test_cli_execution(tmp_path: Path) -> None:
    """CLI must run cleanly via python training/iovnbd_split.py."""
    out_file = tmp_path / "cli_split.json"
    cmd = [
        sys.executable,
        str(PROJECT_ROOT / "training" / "iovnbd_split.py"),
        "--seed",
        "42",
        "--output",
        str(out_file),
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    assert res.returncode == 0, f"CLI failed: {res.stderr}"
    assert out_file.is_file(), f"CLI did not create {out_file}"

    with open(out_file, "r", encoding="utf-8") as f:
        data = json.load(f)
    assert len(data["train"]) > 0
    assert len(data["val"]) > 0
    assert len(data["test"]) > 0
