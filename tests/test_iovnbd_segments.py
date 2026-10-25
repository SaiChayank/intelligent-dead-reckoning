"""Tests for IO-VNBD sequence segmentation into stopped / moving / accelerating / braking."""

from pathlib import Path
import numpy as np
import pytest

from training.common import PROJECT_ROOT, DEFAULT_DATA_ROOT, resolve_dataset_root
from training.iovnbd_segments import (
    extract_forward_speed_mps,
    smooth_speed_and_compute_acceleration,
    segment_speed_profile,
    process_sequence,
)


def test_synthetic_speed_profile():
    """(a) Test synthetic speed profile that has a known stop, acceleration, and braking."""
    dt = 0.1
    # 1. Stopped: 0 to 4 s (speed 0 m/s)
    t1 = np.arange(0.0, 4.0, dt)
    s1 = np.zeros_like(t1)

    # 2. Accelerating: 4 to 9 s (starts from 1.0 m/s to 8.5 m/s with constant a = +1.5 m/s^2)
    t2 = np.arange(4.0, 9.0, dt)
    s2 = 1.0 + 1.5 * (t2 - 4.0)

    # 3. Moving (cruise): 9 to 15 s (constant speed 8.5 m/s, a = 0 m/s^2)
    t3 = np.arange(9.0, 15.0, dt)
    s3 = np.full_like(t3, 8.5)

    # 4. Braking: 15 to 19 s (speed decreases from 8.5 to 2.5 m/s with a = -1.5 m/s^2)
    t4 = np.arange(15.0, 19.0, dt)
    s4 = 8.5 - 1.5 * (t4 - 15.0)

    # 5. Stopped: 19 to 24 s (speed 0 m/s)
    t5 = np.arange(19.0, 24.0 + dt / 2, dt)
    s5 = np.zeros_like(t5)

    t = np.concatenate([t1, t2, t3, t4, t5])
    spd = np.concatenate([s1, s2, s3, s4, s5])

    # Pre-computed acceleration directly or with lightweight smoothing
    t_clean, v_smooth, accel = smooth_speed_and_compute_acceleration(t, spd, smoothing_window_s=0.2)
    segments = segment_speed_profile(
        t_clean,
        v_smooth,
        accel,
        stopped_speed=0.2,
        moving_speed=0.5,
        accel_threshold=0.5,
        brake_threshold=0.5,
        min_stopped_duration=2.0,
        min_accel_duration=1.0,
        min_brake_duration=1.0,
    )

    labels = [s["label"] for s in segments]
    # Check that known states are present
    assert "stopped" in labels, "Expected stopped segment in synthetic profile"
    assert "accelerating" in labels, "Expected accelerating segment in synthetic profile"
    assert "moving" in labels, "Expected moving segment in synthetic profile"
    assert "braking" in labels, "Expected braking segment in synthetic profile"

    stopped_segs = [s for s in segments if s["label"] == "stopped"]
    accel_segs = [s for s in segments if s["label"] == "accelerating"]
    moving_segs = [s for s in segments if s["label"] == "moving"]
    brake_segs = [s for s in segments if s["label"] == "braking"]

    assert len(stopped_segs) >= 2, "Expected at least 2 stopped segments"
    assert len(accel_segs) >= 1, "Expected at least 1 accelerating segment"
    assert len(moving_segs) >= 1, "Expected at least 1 moving segment"
    assert len(brake_segs) >= 1, "Expected at least 1 braking segment"

    # Stopped segments have speed ~ 0 and duration >= 2s
    for s in stopped_segs:
        assert s["duration_s"] >= 2.0
        assert s["mean_speed_mps"] == pytest.approx(0.0, abs=0.05)

    # Accelerating segments have speed > 0.5 and duration >= 1s
    for s in accel_segs:
        assert s["duration_s"] >= 1.0
        assert s["mean_speed_mps"] > 0.5

    # Braking segments have speed > 0.5 and duration >= 1s
    for s in brake_segs:
        assert s["duration_s"] >= 1.0
        assert s["mean_speed_mps"] > 0.5

    # Check non-overlap and contiguity
    for i in range(len(segments) - 1):
        assert segments[i]["end_s"] == pytest.approx(segments[i + 1]["start_s"])


def test_short_real_sequence_coverage_and_no_overlap():
    """(b) Test one short real sequence (Vta9) checking labels cover time range without overlap."""
    data_root = resolve_dataset_root(DEFAULT_DATA_ROOT)
    vbox_candidates = list(data_root.rglob("V-Vta9.csv")) or list(data_root.rglob("V-Vta09.csv"))
    assert vbox_candidates, f"Could not find V-Vta9.csv in {data_root}"
    vbox_path = vbox_candidates[0]

    segments, totals = process_sequence(vbox_path, "vta9")
    assert len(segments) > 0, "Expected at least one segment for Vta9"

    total_time = sum(totals.values())
    assert total_time > 10.0, "Expected Vta9 duration to be > 10 s"

    # 1. Labels cover the time range: starts at 0.0 and ends at total_time
    assert segments[0]["start_s"] == pytest.approx(0.0, abs=1e-3)
    assert segments[-1]["end_s"] == pytest.approx(total_time, abs=1e-3)

    # 2. Non-overlap and contiguous boundaries
    for i in range(len(segments) - 1):
        assert segments[i]["end_s"] == pytest.approx(
            segments[i + 1]["start_s"], abs=1e-3
        ), f"Overlap or gap detected between segments {i} and {i+1}"

    # 3. Sum of durations equals total time range
    sum_durations = sum(s["end_s"] - s["start_s"] for s in segments)
    assert sum_durations == pytest.approx(total_time, abs=1e-3)

    # 4. Valid labels and positive duration
    valid_labels = {"stopped", "moving", "accelerating", "braking"}
    for s in segments:
        assert s["label"] in valid_labels
        assert s["end_s"] > s["start_s"]
        assert s["mean_speed_mps"] >= 0.0


def test_unit_conversion():
    """Verify km/h to m/s conversion factor."""
    import pandas as pd
    df = pd.DataFrame({"reference_speed": [0.0, 36.0, 72.0, 108.0]})
    speed_mps, desc = extract_forward_speed_mps(df)
    assert np.allclose(speed_mps, [0.0, 10.0, 20.0, 30.0])
    assert "km/h" in desc and "m/s" in desc
