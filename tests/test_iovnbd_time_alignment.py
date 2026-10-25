"""Tests for IO-VNBD IMU and odometry cross-correlation time offset alignment."""

from pathlib import Path
import numpy as np
import pytest
from scipy import signal

from training.common import DEFAULT_DATA_ROOT, resolve_dataset_root
from training.iovnbd_frame_inference import DEFAULT_OUTPUT_PATH as DEFAULT_FRAME_PATH, read_frame_inference_csv, find_all_pairs
from training.iovnbd_time_alignment import (
    estimate_cross_correlation_offset,
    align_sequence,
    evaluate_aligned_correlation,
)


def test_synthetic_time_shift_recovery():
    """Shift a synthetic signal by a known offset (0.37 s) plus noise and assert recovery within 0.02 s."""
    np.random.seed(42)
    f_common = 50.0  # 50 Hz test grid
    dt = 1.0 / f_common
    t = np.arange(0.0, 60.0, dt)
    true_offset = 0.37  # seconds

    # Generate band-limited synthetic reference acceleration
    y_ref = (
        1.2 * np.sin(0.7 * t)
        + 0.8 * np.cos(1.8 * t)
        + 0.5 * np.sin(2.9 * t)
        + 0.3 * np.cos(3.8 * t)
    )

    # IMU lags odometry by true_offset: y_imu(t) = y_ref(t - true_offset)
    t_shifted = t - true_offset
    y_imu = (
        1.2 * np.sin(0.7 * t_shifted)
        + 0.8 * np.cos(1.8 * t_shifted)
        + 0.5 * np.sin(2.9 * t_shifted)
        + 0.3 * np.cos(3.8 * t_shifted)
    )

    # Add realistic sensor noise
    y_ref += np.random.normal(0.0, 0.05, size=len(t))
    y_imu += np.random.normal(0.0, 0.05, size=len(t))

    # Band-limit to below 5 Hz using zero-phase filter
    nyquist = f_common / 2.0
    b_filt, a_filt = signal.butter(2, 4.0 / nyquist, btype="low")
    y_ref_f = signal.filtfilt(b_filt, a_filt, y_ref)
    y_imu_f = signal.filtfilt(b_filt, a_filt, y_imu)

    # Estimate offset
    offset_s, peak_corr, corr_at_zero, second_peak_ratio = estimate_cross_correlation_offset(
        y_ref=y_ref_f,
        y_imu=y_imu_f,
        f_common=f_common,
        max_lag_s=2.0,
    )

    print(f"Synthetic recovery: true={true_offset:.4f}s, estimated={offset_s:.4f}s, error={abs(offset_s - true_offset):.4f}s")
    assert abs(offset_s - true_offset) < 0.02, f"Offset error {abs(offset_s - true_offset):.4f} exceeds 0.02 s tolerance!"
    assert peak_corr > 0.90
    assert peak_corr > corr_at_zero
    assert second_peak_ratio < 0.5


def test_synthetic_negative_offset_and_sign_convention():
    """Verify sign convention: offset_s > 0 means IMU timestamps lag odometry; offset_s < 0 means IMU leads."""
    np.random.seed(123)
    f_common = 20.0
    dt = 1.0 / f_common
    t = np.arange(0.0, 50.0, dt)
    true_offset = -0.45  # IMU leads odometry by 0.45s

    y_ref = np.sin(0.8 * t) + 0.6 * np.cos(2.1 * t)
    t_shifted = t - true_offset  # y_imu is shifted by true_offset
    y_imu = np.sin(0.8 * t_shifted) + 0.6 * np.cos(2.1 * t_shifted)

    nyquist = f_common / 2.0
    b_filt, a_filt = signal.butter(2, 4.0 / nyquist, btype="low")
    y_ref_f = signal.filtfilt(b_filt, a_filt, y_ref)
    y_imu_f = signal.filtfilt(b_filt, a_filt, y_imu)

    offset_s, peak_corr, corr_at_zero, _ = estimate_cross_correlation_offset(
        y_ref=y_ref_f,
        y_imu=y_imu_f,
        f_common=f_common,
        max_lag_s=2.0,
    )

    assert abs(offset_s - true_offset) < 0.02
    assert offset_s < 0.0  # Confirms sign convention: negative offset for leading IMU


def test_real_sequence_time_alignment():
    """Run time alignment on real sequence 'Vta1b' and assert correlation improves after alignment."""
    data_root = resolve_dataset_root(DEFAULT_DATA_ROOT)
    pairs = find_all_pairs(data_root, sequence_filter="vta1b")
    assert pairs, "Could not find synchronized pair for vta1b"
    seq_id, s_path, v_path = pairs[0]

    frame_path = DEFAULT_FRAME_PATH
    assert frame_path.is_file(), f"Frame inference report missing at {frame_path}"
    frame_df = read_frame_inference_csv(frame_path)
    match = frame_df[frame_df["sequence"] == "vta1b"]
    assert not match.empty

    row = match.iloc[0]
    f_vec = np.array([row["forward_x"], row["forward_y"], row["forward_z"]])
    u_vec = np.array([row["up_x"], row["up_y"], row["up_z"]])

    res = align_sequence(
        smartphone_csv_path=s_path,
        vbox_csv_path=v_path,
        sequence_id="vta1b",
        forward_unit=f_vec,
        up_unit=u_vec,
        max_lag_s=2.0,
        cutoff_hz=4.0,
    )

    required_keys = ["sequence", "offset_s", "peak_corr", "corr_at_zero", "second_peak_ratio", "corr_after_alignment", "flag"]
    for k in required_keys:
        assert k in res

    assert abs(res["offset_s"]) <= 2.0
    # On vta1b, alignment should clearly improve correlation over zero lag
    assert res["corr_after_alignment"] >= res["corr_at_zero"]
    assert res["corr_after_alignment"] > 0.50
