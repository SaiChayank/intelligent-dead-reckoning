"""Tests for IO-VNBD data-driven IMU-to-vehicle frame inference."""

from pathlib import Path
import numpy as np
import pandas as pd
import pytest
from scipy.spatial.transform import Rotation

from training.common import DEFAULT_DATA_ROOT, resolve_dataset_root
from training.iovnbd_segments import DEFAULT_OUTPUT_PATH as DEFAULT_SEGMENTS_PATH
from training.iovnbd_frame_inference import (
    compute_gravity_vector,
    compute_forward_vector,
    construct_so3_rotation,
    compute_gyro_yaw_ratio,
    rate_confidence,
    infer_sequence_frame,
)


def test_synthetic_frame_inference():
    """Build synthetic IMU stream with a known 3D rotation, infer frame, and assert recovered rotation < 3 deg."""
    np.random.seed(42)

    # Known ground-truth rotation mapping IMU to vehicle frame: v_veh = R_true @ v_imu
    # Euler angles: roll = 12 deg, pitch = -8 deg, yaw = 35 deg
    R_true = Rotation.from_euler("xyz", [12.0, -8.0, 35.0], degrees=True).as_matrix()

    dt = 0.1
    t = np.arange(0.0, 50.0, dt)
    n = len(t)

    # Vehicle motion profile
    speed = np.zeros(n)
    a_long = np.zeros(n)
    w_yaw = np.zeros(n)

    # 1. Stop 1: 0 to 6 s
    # 2. Accel: 6 to 14 s (+1.2 m/s^2)
    acc_idx = (t >= 6.0) & (t < 14.0)
    a_long[acc_idx] = 1.2

    # 3. Cruise with yaw turn: 14 to 28 s
    turn_idx = (t >= 14.0) & (t < 28.0)
    w_yaw[turn_idx] = 0.20  # 0.2 rad/s turn around vehicle +Z (up)

    # 4. Braking: 28 to 36 s (-1.2 m/s^2)
    brake_idx = (t >= 28.0) & (t < 36.0)
    a_long[brake_idx] = -1.2

    # 5. Stop 2: 36 to 50 s

    # Integrate speed
    for i in range(1, n):
        speed[i] = max(0.0, speed[i - 1] + a_long[i] * dt)

    # Vehicle specific force: at rest, specific force points UP (+Z) to oppose gravity
    a_veh = np.zeros((n, 3))
    a_veh[:, 0] = a_long
    a_veh[:, 2] = 9.80665

    w_veh = np.zeros((n, 3))
    w_veh[:, 2] = w_yaw

    # Transform to IMU frame: v_imu = R_true^T @ v_veh => row vector: a_veh @ R_true
    noise_a = np.random.normal(0.0, 0.02, size=(n, 3))
    noise_w = np.random.normal(0.0, 0.002, size=(n, 3))
    a_imu = a_veh @ R_true + noise_a
    w_imu = w_veh @ R_true + noise_w

    # Create segment mock
    stops_df = pd.DataFrame([
        {"sequence": "synth", "start_s": 0.0, "end_s": 6.0, "label": "stopped", "mean_speed_mps": 0.0},
        {"sequence": "synth", "start_s": 36.0, "end_s": 50.0, "label": "stopped", "mean_speed_mps": 0.0},
    ])
    ab_df = pd.DataFrame([
        {"sequence": "synth", "start_s": 6.0, "end_s": 14.0, "label": "accelerating", "mean_speed_mps": 4.8},
        {"sequence": "synth", "start_s": 28.0, "end_s": 36.0, "label": "braking", "mean_speed_mps": 4.8},
    ])
    moving_df = pd.DataFrame([
        {"sequence": "synth", "start_s": 6.0, "end_s": 36.0, "label": "moving", "mean_speed_mps": 7.0},
    ])

    # 1. Recover gravity direction
    up_rec, g_src, g_mag, g_std = compute_gravity_vector(t, a_imu, stops_df, min_stop_margin=0.5)
    assert g_src == "stopped"
    assert g_mag == pytest.approx(9.80665, abs=0.1)
    assert g_std < 0.15

    # 2. Recover forward axis from regression onto reference acceleration
    f_rec, r2_fwd = compute_forward_vector(t, a_imu, up_rec, ab_df, a_ref_interp=a_long)
    assert r2_fwd > 0.90

    # 3. Construct SO(3) rotation
    R_rec, det, orth_err = construct_so3_rotation(f_rec, up_rec)
    assert det == pytest.approx(1.0, abs=1e-7)
    assert orth_err < 1e-12

    # 4. Check rotation angle difference
    # R_rec maps IMU -> vehicle, R_true maps IMU -> vehicle
    # Difference matrix: E = R_rec @ R_true.T
    E = R_rec @ R_true.T
    tr = float(np.clip((np.trace(E) - 1.0) / 2.0, -1.0, 1.0))
    angle_error_deg = float(np.degrees(np.arccos(tr)))

    print(f"Recovered synthetic rotation error: {angle_error_deg:.4f} degrees")
    assert angle_error_deg < 3.0, f"Recovered angle error {angle_error_deg:.4f} deg exceeds 3.0 deg threshold!"

    # 5. Check gyro ratio and confidence
    gyro_ratio = compute_gyro_yaw_ratio(t, w_imu, up_rec, moving_df)
    assert gyro_ratio > 10.0  # In synthetic data with turning strictly on up axis, ratio >> 0.7

    conf = rate_confidence(g_src, g_std, r2_fwd, gyro_ratio)
    assert conf == "high"


def test_so3_rotation_properties():
    """Verify construct_so3_rotation preserves right-handedness, determinant +1, and zero orth error."""
    # Arbitrary non-orthogonal forward and up
    f = np.array([2.5, 1.1, -0.4])
    u = np.array([0.1, 0.3, 9.8])
    R, det, orth = construct_so3_rotation(f, u)

    assert det == pytest.approx(1.0, abs=1e-12)
    assert orth < 1e-14
    assert np.allclose(R @ R.T, np.eye(3), atol=1e-12)
    # Check that row 2 (up) is aligned with u
    u_norm = u / np.linalg.norm(u)
    assert np.dot(R[2], u_norm) == pytest.approx(1.0, abs=1e-10)


def test_real_sequence_inference_structure():
    """Run frame inference on real sequence 'Vta2' and verify structure and numerical validity."""
    data_root = resolve_dataset_root(DEFAULT_DATA_ROOT)
    from training.iovnbd_frame_inference import find_all_pairs
    pairs = find_all_pairs(data_root, sequence_filter="vta2")
    assert pairs, f"Could not find synchronized pair for vta2 in {data_root}"
    seq_id, s_path, v_path = pairs[0]

    segments_path = DEFAULT_SEGMENTS_PATH
    assert segments_path.is_file(), f"Segments file {segments_path} missing"
    segs_df = pd.read_csv(segments_path)

    res = infer_sequence_frame(
        smartphone_csv_path=s_path,
        vbox_csv_path=v_path,
        sequence_id="vta2",
        segments_df=segs_df,
    )

    required_keys = [
        "sequence", "gravity_source", "gravity_magnitude", "gravity_std",
        "forward_x", "forward_y", "forward_z", "up_x", "up_y", "up_z",
        "r2_forward", "gyro_yaw_ratio", "det", "orth_error", "confidence",
    ]
    for k in required_keys:
        assert k in res, f"Missing key {k} in inference result"

    assert res["gravity_source"] == "stopped"
    assert res["gravity_magnitude"] == pytest.approx(9.81, abs=0.2)
    assert res["gravity_std"] < 0.25
    assert res["det"] == pytest.approx(1.0, abs=1e-6)
    assert res["orth_error"] < 1e-6
    assert res["confidence"] in {"high", "medium", "low"}

    # Forward and up must be unit vectors orthogonal to each other
    f = np.array([res["forward_x"], res["forward_y"], res["forward_z"]])
    u = np.array([res["up_x"], res["up_y"], res["up_z"]])
    assert np.linalg.norm(f) == pytest.approx(1.0, abs=1e-5)
    assert np.linalg.norm(u) == pytest.approx(1.0, abs=1e-5)
    assert np.dot(f, u) == pytest.approx(0.0, abs=1e-5)
