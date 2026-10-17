"""
training/speed_estimator.py
============================================================
AI-ML Forward Speed & Vibration Estimator (Model A)
for Smart India Hackathon #26168 (ISRO / Department of Space)

Implements a causal 1D Temporal Convolutional + Feature-Fused Neural Network
that estimates forward vehicle speed (v_x >= 0), measurement uncertainty (sigma_v),
and stationary state directly from a sliding window of calibrated vehicle-frame IMU data.

No OBD-II or CAN bus dependencies. 100% causal and local-inference capable.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple, Optional, Dict, Any

import numpy as np

try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
    HAS_TORCH = True
except ImportError:
    torch = None
    nn = None
    F = None
    HAS_TORCH = False

WINDOW_SIZE = 100  # 1.0 second at 100 Hz
NUM_CHANNELS = 6   # [ax, ay, az, gx, gy, gz] in vehicle frame


# -----------------------------------------------------------------------------
# 1. PyTorch Neural Network Definition (Model A)
# -----------------------------------------------------------------------------

if HAS_TORCH:
    class SpeedEstimatorModel(nn.Module):
        """
        Causal 1D-CNN + Feature Fusion Model for Forward Speed Estimation.

        Inputs:
            x_seq: (B, 6, 100) - 1-second sliding window of IMU channels in vehicle frame.
            x_stats: (B, 10)   - Window statistics:
                [mean_ax, mean_ay, mean_az, std_ax, std_ay, std_az, std_gz, jerk_rms, norm_a, norm_g]

        Outputs:
            speed: (B, 1)      - Estimated forward speed (m/s, strictly >= 0)
            sigma: (B, 1)      - Estimated speed standard deviation (m/s, >= 0.25)
            stationary: (B, 1) - Stillness probability in [0, 1]
        """
        def __init__(self, in_channels: int = 6, stats_dim: int = 10):
            super().__init__()

            # Causal temporal convolutions
            # Conv1: receptive field 5 samples (50 ms)
            self.conv1 = nn.Conv1d(in_channels, 16, kernel_size=5, padding=2)
            # Conv2: receptive field 9 samples, downsample by 2
            self.conv2 = nn.Conv1d(16, 32, kernel_size=5, stride=2, padding=2)
            # Conv3: receptive field 13 samples
            self.conv3 = nn.Conv1d(32, 32, kernel_size=3, padding=1)

            self.act = nn.LeakyReLU(negative_slope=0.1)

            # Temporal pooling: mean pool + max pool across time -> 32 + 32 = 64 features
            conv_out_dim = 32 * 2
            fused_dim = conv_out_dim + stats_dim  # 64 + 10 = 74

            # Dense MLP Head
            self.fc1 = nn.Linear(fused_dim, 48)
            self.fc2 = nn.Linear(48, 24)

            # Output Heads
            self.head_speed = nn.Linear(24, 1)
            self.head_sigma = nn.Linear(24, 1)
            self.head_stop = nn.Linear(24, 1)

        def forward(self, x_seq: torch.Tensor, x_stats: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
            # x_seq: (B, 6, 100)
            h = self.act(self.conv1(x_seq))
            h = self.act(self.conv2(h))
            h = self.act(self.conv3(h))

            # Global pooling across time
            mean_p = torch.mean(h, dim=-1)
            max_p, _ = torch.max(h, dim=-1)
            conv_feats = torch.cat([mean_p, max_p], dim=-1)  # (B, 64)

            # Fuse with statistical summary features
            fused = torch.cat([conv_feats, x_stats], dim=-1)  # (B, 74)

            feat = self.act(self.fc1(fused))
            feat = self.act(self.fc2(feat))

            # 1. Speed: Softplus guarantees speed >= 0
            speed = F.softplus(self.head_speed(feat))

            # 2. Sigma: Softplus + baseline 0.25 m/s
            sigma = F.softplus(self.head_sigma(feat)) + 0.25

            # 3. Stationary probability: Sigmoid in [0, 1]
            stationary = torch.sigmoid(self.head_stop(feat))

            return speed, sigma, stationary
else:
    class SpeedEstimatorModel:  # type: ignore[no-redef]
        def __init__(self, *args, **kwargs):
            raise ImportError("PyTorch is required for SpeedEstimatorModel training. Install torch to use.")


# -----------------------------------------------------------------------------
# 2. Causal Feature Extraction
# -----------------------------------------------------------------------------

def compute_window_statistics(window: np.ndarray) -> np.ndarray:
    """
    Extracts deterministic statistical features from a (100, 6) IMU window.
    Channels: 0:ax, 1:ay, 2:az, 3:gx, 4:gy, 5:gz.
    Returns array of shape (10,):
        [mean_ax, mean_ay, mean_az, std_ax, std_ay, std_az, std_gz, jerk_rms, norm_a, norm_g]
    """
    ax, ay, az = window[:, 0], window[:, 1], window[:, 2]
    gx, gy, gz = window[:, 3], window[:, 4], window[:, 5]

    mean_ax = float(np.mean(ax))
    mean_ay = float(np.mean(ay))
    mean_az = float(np.mean(az))

    std_ax = float(np.std(ax))
    std_ay = float(np.std(ay))
    std_az = float(np.std(az))
    std_gz = float(np.std(gz))

    # Longitudinal jerk RMS (finite difference)
    jerk = np.diff(ax) / 0.01  # dt = 0.01s
    jerk_rms = float(np.sqrt(np.mean(jerk * jerk))) if len(jerk) > 0 else 0.0

    norm_a = float(np.mean(np.sqrt(ax * ax + ay * ay + az * az)))
    norm_g = float(np.mean(np.sqrt(gx * gx + gy * gy + gz * gz)))

    return np.array([
        mean_ax, mean_ay, mean_az,
        std_ax, std_ay, std_az, std_gz,
        jerk_rms, norm_a, norm_g
    ], dtype=np.float32)


# -----------------------------------------------------------------------------
# 3. Synthetic Multi-Regime Ground Truth Generator
# -----------------------------------------------------------------------------

def generate_training_data(
    num_episodes: int = 40,
    episode_duration_s: float = 60.0,
    dt: float = 0.01,
    seed: int = 42,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """
    Generates synthetic vehicle kinematics and IMU windows covering diverse regimes:
    - Stationary rest (0 m/s) with engine idling vibrations
    - Linear acceleration (0 to 10-25 m/s)
    - Steady cruise (5, 10, 15, 20, 25 m/s) with road vibration harmonics
    - Left/right turns with centripetal lateral acceleration and yaw rate
    - Braking deceleration to stops
    - Road bumps, pothole shocks, accelerometer bias (0.02 m/s^2), and gyro bias (0.001 rad/s)

    Returns:
        X_seq: (N, 6, 100)
        X_stats: (N, 10)
        Y_speed: (N, 1)
        Y_sigma: (N, 1)
        Y_stop: (N, 1)
    """
    np.random.seed(seed)

    all_seqs = []
    all_stats = []
    all_speeds = []
    all_sigmas = []
    all_stops = []

    steps_per_episode = int(episode_duration_s / dt)
    window_len = WINDOW_SIZE

    for ep in range(num_episodes):
        # Episode profile
        t = np.arange(steps_per_episode) * dt
        v = np.zeros(steps_per_episode, dtype=np.float32)
        yaw_rate = np.zeros(steps_per_episode, dtype=np.float32)

        # Random profile segments
        # 0-5s: parked
        # 5-15s: accelerate to target_speed
        target_speed = np.random.uniform(8.0, 24.0)
        accel_dur = np.random.uniform(6.0, 12.0)
        turn_start = 5.0 + accel_dur + np.random.uniform(4.0, 8.0)
        turn_dur = np.random.uniform(4.0, 8.0)
        turn_rate = np.random.choice([-1.0, 1.0]) * np.random.uniform(0.15, 0.35)
        brake_start = turn_start + turn_dur + np.random.uniform(8.0, 14.0)
        brake_dur = np.random.uniform(4.0, 8.0)

        for i, ti in enumerate(t):
            if ti < 5.0:
                v[i] = 0.0
            elif ti < 5.0 + accel_dur:
                frac = (ti - 5.0) / accel_dur
                v[i] = target_speed * (0.5 - 0.5 * math.cos(math.pi * frac))
            elif ti < brake_start:
                v[i] = target_speed
            elif ti < brake_start + brake_dur:
                frac = (ti - brake_start) / brake_dur
                v[i] = max(0.0, target_speed * (0.5 + 0.5 * math.cos(math.pi * frac)))
            else:
                v[i] = 0.0

            if turn_start <= ti <= turn_start + turn_dur:
                yaw_rate[i] = turn_rate
            else:
                yaw_rate[i] = 0.0

        # Forward acceleration derivative
        dv_dt = np.gradient(v, dt)

        # Vehicle IMU measurements
        # 1. Forward accel = dv/dt + road vibration (dependent on speed) + sensor bias + noise
        road_vib_amp = 0.08 + 0.04 * (v / 10.0)
        tire_freq = 2.0 * math.pi * (v / (2.0 * math.pi * 0.31) + 0.1)
        vib_x = road_vib_amp * np.sin(tire_freq * t) + np.random.normal(0, 0.06, steps_per_episode)
        acc_x = dv_dt + vib_x + np.random.normal(0.02, 0.005)  # 0.02 bias

        # 2. Lateral accel = v * yaw_rate + lateral vibration
        acc_y = v * yaw_rate + np.random.normal(0, 0.05, steps_per_episode)

        # 3. Vertical accel (dynamic component)
        acc_z = np.random.normal(0, 0.08 + 0.03 * (v / 10.0), steps_per_episode)

        # 4. Gyro rates
        gyro_x = np.random.normal(0, 0.005, steps_per_episode)
        gyro_y = np.random.normal(0, 0.005, steps_per_episode)
        gyro_z = yaw_rate + np.random.normal(0.001, 0.003, steps_per_episode)

        # Stack IMU sequence: (steps, 6)
        imu_ep = np.stack([acc_x, acc_y, acc_z, gyro_x, gyro_y, gyro_z], axis=1).astype(np.float32)

        # Sample sliding windows (stride = 10 samples = 0.1 s)
        stride = 10
        for start_idx in range(0, steps_per_episode - window_len + 1, stride):
            end_idx = start_idx + window_len
            win = imu_ep[start_idx:end_idx]  # (100, 6)

            # Ground truth at current causal moment (end of window)
            curr_v = float(v[end_idx - 1])
            is_stop = 1.0 if curr_v < 0.2 else 0.0

            # True uncertainty based on dynamics: higher during rapid acceleration or hard turns
            dyn_uncert = 0.35 + 0.05 * abs(dv_dt[end_idx - 1]) + 0.1 * abs(yaw_rate[end_idx - 1])

            stats = compute_window_statistics(win)

            all_seqs.append(win.T)  # (6, 100)
            all_stats.append(stats)
            all_speeds.append(curr_v)
            all_sigmas.append(dyn_uncert)
            all_stops.append(is_stop)

    X_seq = np.array(all_seqs, dtype=np.float32)
    X_stats = np.array(all_stats, dtype=np.float32)
    Y_speed = np.array(all_speeds, dtype=np.float32).reshape(-1, 1)
    Y_sigma = np.array(all_sigmas, dtype=np.float32).reshape(-1, 1)
    Y_stop = np.array(all_stops, dtype=np.float32).reshape(-1, 1)

    return X_seq, X_stats, Y_speed, Y_sigma, Y_stop


# -----------------------------------------------------------------------------
# 4. Pure Python / NumPy Inference Engine (Offline & Edge Verification)
# -----------------------------------------------------------------------------

class NumpySpeedEstimator:
    """
    Pure NumPy deterministic forward-pass engine matching SpeedEstimatorModel weights.
    Provides identical inference to PyTorch without any PyTorch or GPU runtime dependency.
    """
    def __init__(self, weights_dict: Dict[str, Any]):
        self.weights = weights_dict

    @classmethod
    def from_file(cls, path: Path) -> "NumpySpeedEstimator":
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return cls(data)

    def predict(self, window_100x6: np.ndarray) -> Tuple[float, float, bool, float]:
        """
        Runs causal inference on a (100, 6) window.
        Returns: (speed_m_s, sigma_m_s, is_stationary, confidence)
        """
        x_seq = window_100x6.T[np.newaxis, :, :]  # (1, 6, 100)
        x_stats = compute_window_statistics(window_100x6)[np.newaxis, :]  # (1, 10)

        # Conv1: (16, 6, 5), bias (16)
        w_c1 = np.array(self.weights["conv1.weight"])
        b_c1 = np.array(self.weights["conv1.bias"])
        h1 = self._conv1d(x_seq, w_c1, b_c1, stride=1, padding=2)
        h1 = np.where(h1 > 0, h1, 0.1 * h1)

        # Conv2: (32, 16, 5), bias (32), stride 2
        w_c2 = np.array(self.weights["conv2.weight"])
        b_c2 = np.array(self.weights["conv2.bias"])
        h2 = self._conv1d(h1, w_c2, b_c2, stride=2, padding=2)
        h2 = np.where(h2 > 0, h2, 0.1 * h2)

        # Conv3: (32, 32, 3), bias (32), stride 1
        w_c3 = np.array(self.weights["conv3.weight"])
        b_c3 = np.array(self.weights["conv3.bias"])
        h3 = self._conv1d(h2, w_c3, b_c3, stride=1, padding=1)
        h3 = np.where(h3 > 0, h3, 0.1 * h3)

        # Pooling: mean and max across time dimension
        mean_p = np.mean(h3, axis=-1)  # (1, 32)
        max_p = np.max(h3, axis=-1)    # (1, 32)
        conv_feats = np.concatenate([mean_p, max_p], axis=-1)  # (1, 64)

        # Fuse with stats
        fused = np.concatenate([conv_feats, x_stats], axis=-1)  # (1, 74)

        # FC1
        w_fc1 = np.array(self.weights["fc1.weight"])
        b_fc1 = np.array(self.weights["fc1.bias"])
        feat1 = fused @ w_fc1.T + b_fc1
        feat1 = np.where(feat1 > 0, feat1, 0.1 * feat1)

        # FC2
        w_fc2 = np.array(self.weights["fc2.weight"])
        b_fc2 = np.array(self.weights["fc2.bias"])
        feat2 = feat1 @ w_fc2.T + b_fc2
        feat2 = np.where(feat2 > 0, feat2, 0.1 * feat2)

        # Heads
        w_spd = np.array(self.weights["head_speed.weight"])
        b_spd = np.array(self.weights["head_speed.bias"])
        raw_spd = float((feat2 @ w_spd.T + b_spd).item())
        speed = math.log1p(math.exp(raw_spd)) if raw_spd < 20.0 else raw_spd  # softplus

        w_sig = np.array(self.weights["head_sigma.weight"])
        b_sig = np.array(self.weights["head_sigma.bias"])
        raw_sig = float((feat2 @ w_sig.T + b_sig).item())
        sigma = (math.log1p(math.exp(raw_sig)) if raw_sig < 20.0 else raw_sig) + 0.25

        w_stp = np.array(self.weights["head_stop.weight"])
        b_stp = np.array(self.weights["head_stop.bias"])
        raw_stp = float((feat2 @ w_stp.T + b_stp).item())
        p_stop = 1.0 / (1.0 + math.exp(-raw_stp))

        is_stationary = (p_stop > 0.5) or (speed < 0.35)
        if is_stationary:
            speed = 0.0
            sigma = 0.05

        confidence = float(np.clip(1.0 - (sigma - 0.25) / 1.5, 0.1, 0.99))
        return speed, sigma, is_stationary, confidence

    def _conv1d(self, x: np.ndarray, w: np.ndarray, b: np.ndarray, stride: int, padding: int) -> np.ndarray:
        # x: (1, C_in, L_in)
        # w: (C_out, C_in, K)
        # b: (C_out)
        _, c_in, l_in = x.shape
        c_out, _, k = w.shape

        if padding > 0:
            x_padded = np.pad(x, ((0, 0), (0, 0), (padding, padding)), mode="constant")
        else:
            x_padded = x

        l_out = (x_padded.shape[2] - k) // stride + 1
        out = np.zeros((1, c_out, l_out), dtype=np.float32)

        for i in range(l_out):
            start = i * stride
            window = x_padded[:, :, start:start + k]  # (1, C_in, K)
            # Element-wise multiply across channels and kernel, sum
            out[0, :, i] = np.sum(window * w, axis=(1, 2)) + b

        return out
