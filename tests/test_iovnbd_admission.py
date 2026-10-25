"""Tests for IO-VNBD training and evaluation admission pipeline."""

import json
from pathlib import Path
import pandas as pd
import pytest

from training.iovnbd_admission import (
    evaluate_admission,
    check_timestamp_continuity,
    DEFAULT_OUTPUT_PATH,
)


def test_admission_approve_path():
    """Verify that a sequence meeting all 4 criteria is approved."""
    frame_row = pd.Series({
        "sequence": "synth_good",
        "confidence": "high",
        "gravity_source": "stopped",
        "gravity_std": 0.08,
    })
    align_row = pd.Series({
        "sequence": "synth_good",
        "offset_s": -0.25,
        "peak_corr": 0.78,
        "corr_at_zero": 0.65,
        "second_peak_ratio": 0.15,
        "corr_after_alignment": 0.82,
        "flag": "OK",
    })

    res = evaluate_admission(
        sequence_id="synth_good",
        frame_row=frame_row,
        align_row=align_row,
        max_gap_s=0.10,
        duration_s=600.0,
        t_corr=0.50,
        t_amb=0.80,
        max_allowed_gap_s=1.0,
    )

    assert res["approved_for_training"] is True
    assert res["disposition"] == "approved"
    assert res["blocking_reason"] is None


def test_admission_reject_on_low_confidence():
    """Verify rejection when frame confidence is low despite strong time alignment."""
    frame_row = pd.Series({"confidence": "low"})
    align_row = pd.Series({
        "peak_corr": 0.85,
        "second_peak_ratio": 0.10,
        "corr_at_zero": 0.70,
        "corr_after_alignment": 0.88,
    })

    res = evaluate_admission(
        sequence_id="synth_low_conf",
        frame_row=frame_row,
        align_row=align_row,
        max_gap_s=0.10,
        duration_s=500.0,
        t_corr=0.50,
        t_amb=0.80,
    )

    assert res["approved_for_training"] is False
    assert "Low frame confidence" in res["blocking_reason"]
    # Qualifies for conditional diagnostic candidate if all other gates pass
    assert res["disposition"] == "conditional_diagnostic_candidate_only"


def test_admission_reject_on_low_correlation():
    """Verify rejection when peak correlation is below threshold."""
    frame_row = pd.Series({"confidence": "medium"})
    align_row = pd.Series({
        "peak_corr": 0.32,  # < 0.50
        "second_peak_ratio": 0.20,
        "corr_at_zero": 0.25,
        "corr_after_alignment": 0.35,
    })

    res = evaluate_admission(
        sequence_id="synth_low_corr",
        frame_row=frame_row,
        align_row=align_row,
        max_gap_s=0.10,
        duration_s=300.0,
        t_corr=0.50,
        t_amb=0.80,
    )

    assert res["approved_for_training"] is False
    assert "Peak correlation below threshold" in res["blocking_reason"]
    assert res["disposition"] == "quarantined"


def test_admission_reject_on_ambiguous_peak():
    """Verify rejection when second-highest peak ratio exceeds ambiguity threshold."""
    frame_row = pd.Series({"confidence": "high"})
    align_row = pd.Series({
        "peak_corr": 0.72,
        "second_peak_ratio": 0.95,  # > 0.80
        "corr_at_zero": 0.60,
        "corr_after_alignment": 0.75,
    })

    res = evaluate_admission(
        sequence_id="synth_ambiguous",
        frame_row=frame_row,
        align_row=align_row,
        max_gap_s=0.10,
        duration_s=400.0,
        t_corr=0.50,
        t_amb=0.80,
    )

    assert res["approved_for_training"] is False
    assert "Ambiguous lag correlation peak" in res["blocking_reason"]
    assert res["disposition"] == "quarantined"


def test_admission_reject_on_timestamp_gap():
    """Verify rejection when timestamp step exceeds maximum allowed gap."""
    frame_row = pd.Series({"confidence": "high"})
    align_row = pd.Series({
        "peak_corr": 0.70,
        "second_peak_ratio": 0.30,
        "corr_at_zero": 0.55,
        "corr_after_alignment": 0.72,
    })

    res = evaluate_admission(
        sequence_id="synth_gap",
        frame_row=frame_row,
        align_row=align_row,
        max_gap_s=2.45,  # > 1.0 s
        duration_s=800.0,
        t_corr=0.50,
        t_amb=0.80,
        max_allowed_gap_s=1.0,
    )

    assert res["approved_for_training"] is False
    assert "Timestamp gap > 1.0s" in res["blocking_reason"]
    assert res["disposition"] == "quarantined"


def test_admission_json_schema():
    """Verify reports/iovnbd_admission.json exists and strictly matches the admission schema."""
    output_path = DEFAULT_OUTPUT_PATH
    assert output_path.is_file(), f"Expected admission report at {output_path}"

    with output_path.open("r", encoding="utf-8") as f:
        doc = json.load(f)

    required_top_keys = [
        "version", "decision", "scope", "approved_sequence_names",
        "conditional_diagnostic_sequence_names", "policy",
        "unpaired_recordings", "pairs",
    ]
    for k in required_top_keys:
        assert k in doc, f"Missing top-level key {k}"

    assert isinstance(doc["pairs"], list)
    assert len(doc["pairs"]) == 72

    pair_keys = [
        "id", "sequence", "split_group", "historical_alignment_class",
        "approved_for_training", "disposition", "alignment_reason", "blocking_reason",
    ]
    for p in doc["pairs"]:
        for pk in pair_keys:
            assert pk in p, f"Missing key {pk} in pair {p.get('sequence')}"
        assert isinstance(p["approved_for_training"], bool)
