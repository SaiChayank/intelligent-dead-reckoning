"""Offline 3D mount calibration for a phone recording: which way is up, which way is forward, and what the gyro reads at rest.

    python tools/gsdc_batch_calibration.py <recording-dir> [--out calibration.json]

A stand-in for the app's in-drive calibrator, which does not lock on real phone-in-car drives (reports/gsdc_calibrator_probe_development.json). It reads
only what the engine itself receives, the phone IMU and the GNSS aiding fixes of the first minutes of a drive, never the reference track, and it
is released to the engine at the end of the window it was estimated from, so nothing earlier can use it.

Vehicle frame: x forward, y left, z up. With `u` the unit up vector in device coordinates and (e1, e2) an arbitrary horizontal basis,
the mount is a rotation about u by theta: (vx, vy) = e^{i theta} (a.e1 + i a.e2) for horizontal specific force a.
  up           from the accelerometer mean while the aiding speed says the vehicle is stopped (else the mean over the window)
  theta        from the horizontal specific force against the aiding speed's rate of change (forward) and speed times yaw rate (left)
  gyro bias    from the gyro mean while stopped; zero when there is no stop
Quality is reported (longitudinal and lateral correlation, number of moving seconds) and a calibration that does not reach
CALIBRATION_MIN_R_LONG is returned as not valid.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np

ORIGIN_NS = 1_000_000_000
WINDOWS_S = (120.0, 240.0, 480.0)
STOPPED_SPEED_M_S = 0.3
MIN_STOPPED_S = 4.0
MOVING_SPEED_M_S = 3.0
MIN_MOVING_S = 30
MIN_EXCITATION_M_S2 = 0.08
CALIBRATION_MIN_R_LONG = 0.5


def load_streams(recording: Path) -> dict[str, np.ndarray]:
    """accelerometer and gyroscope as [t_s, x, y, z] and the aiding fixes as [t_s, speed]; times on the session clock."""
    accel, gyro, fixes = [], [], []
    with open(recording / "measurements.jsonl", encoding="utf-8") as handle:
        for line in handle:
            if '"imu"' in line:
                event = json.loads(line)["event"]
                data = event["data"]
                row = [(int(event["t_ns"]) - ORIGIN_NS) * 1e-9, *data["xyz"]]
                (accel if data["sensor"] == "accelerometer" else gyro).append(row)
            elif '"gnss"' in line:
                event = json.loads(line)["event"]
                data = event["data"]
                if data.get("provider") == "gps" and data.get("speed_m_s") is not None:
                    fixes.append([(int(event["t_ns"]) - ORIGIN_NS) * 1e-9, data["speed_m_s"]])
    return {"accelerometer": np.array(accel), "gyroscope": np.array(gyro), "fixes": np.array(fixes)}


def _interval_means(t: np.ndarray, values: np.ndarray, lo: np.ndarray, hi: np.ndarray) -> np.ndarray:
    csum = np.vstack([np.zeros((1, values.shape[1])), np.cumsum(values, axis=0)])
    a, b = np.searchsorted(t, lo), np.searchsorted(t, hi)
    n = (b - a).astype(float)
    out = np.full((len(lo), values.shape[1]), np.nan)
    ok = n > 0
    out[ok] = (csum[b[ok]] - csum[a[ok]]) / n[ok, None]
    return out


def quaternion_from_matrix(r: np.ndarray) -> list[float]:
    """Unit quaternion (w, x, y, z) of a rotation matrix, with w >= 0."""
    t = np.trace(r)
    if t > 0:
        s = math.sqrt(t + 1.0) * 2
        q = [0.25 * s, (r[2, 1] - r[1, 2]) / s, (r[0, 2] - r[2, 0]) / s, (r[1, 0] - r[0, 1]) / s]
    elif r[0, 0] > r[1, 1] and r[0, 0] > r[2, 2]:
        s = math.sqrt(1.0 + r[0, 0] - r[1, 1] - r[2, 2]) * 2
        q = [(r[2, 1] - r[1, 2]) / s, 0.25 * s, (r[0, 1] + r[1, 0]) / s, (r[0, 2] + r[2, 0]) / s]
    elif r[1, 1] > r[2, 2]:
        s = math.sqrt(1.0 + r[1, 1] - r[0, 0] - r[2, 2]) * 2
        q = [(r[0, 2] - r[2, 0]) / s, (r[0, 1] + r[1, 0]) / s, 0.25 * s, (r[1, 2] + r[2, 1]) / s]
    else:
        s = math.sqrt(1.0 + r[2, 2] - r[0, 0] - r[1, 1]) * 2
        q = [(r[1, 0] - r[0, 1]) / s, (r[0, 2] + r[2, 0]) / s, (r[1, 2] + r[2, 1]) / s, 0.25 * s]
    q = np.array(q) / np.linalg.norm(q)
    return (q if q[0] >= 0 else -q).tolist()


def calibrate_window(streams: dict[str, np.ndarray], window_s: float) -> dict:
    a, g, f = streams["accelerometer"], streams["gyroscope"], streams["fixes"]
    in_a, in_g, in_f = a[a[:, 0] <= window_s], g[g[:, 0] <= window_s], f[f[:, 0] <= window_s]
    if len(in_a) < 100 or len(in_g) < 100 or len(in_f) < 5:
        return {"status": "invalid", "reason": f"{window_s:.0f} s: too little data"}
    stopped_fix = in_f[in_f[:, 1] < STOPPED_SPEED_M_S, 0]
    mask_a = np.zeros(len(in_a), dtype=bool)
    mask_g = np.zeros(len(in_g), dtype=bool)
    for t in stopped_fix:
        mask_a |= (in_a[:, 0] >= t - 0.5) & (in_a[:, 0] <= t + 0.5)
        mask_g |= (in_g[:, 0] >= t - 0.5) & (in_g[:, 0] <= t + 0.5)
    stopped_s = len(stopped_fix)
    if stopped_s >= MIN_STOPPED_S and mask_a.sum() >= 50:
        up_vec, bias = in_a[mask_a, 1:].mean(axis=0), in_g[mask_g, 1:].mean(axis=0)
        up_method = f"accelerometer mean while stopped ({stopped_s} stopped fixes)"
    else:
        up_vec, bias = in_a[:, 1:].mean(axis=0), np.zeros(3)
        up_method = "accelerometer mean over the window (no stop), gyro bias assumed 0"
    u = up_vec / np.linalg.norm(up_vec)
    helper = np.array([1.0, 0.0, 0.0]) if abs(u[0]) < 0.9 else np.array([0.0, 1.0, 0.0])
    e1 = np.cross(helper, u)
    e1 /= np.linalg.norm(e1)
    e2 = np.cross(u, e1)

    t_f, v = in_f[:, 0], in_f[:, 1]
    inner = np.arange(1, len(t_f) - 1)
    dt2 = t_f[inner + 1] - t_f[inner - 1]
    a_long = (v[inner + 1] - v[inner - 1]) / dt2
    lo, hi = t_f[inner] - 0.5, t_f[inner] + 0.5
    acc = _interval_means(in_a[:, 0], in_a[:, 1:], lo, hi)
    gyr = _interval_means(in_g[:, 0], in_g[:, 1:], lo, hi) - bias
    h1, h2, w_up = acc @ e1, acc @ e2, gyr @ u
    a_lat = v[inner] * w_up
    moving = (v[inner] >= MOVING_SPEED_M_S) & np.isfinite(a_long) & np.isfinite(h1) & np.isfinite(w_up) & (dt2 > 0) & (dt2 <= 3.0)
    if moving.sum() < MIN_MOVING_S:
        return {"status": "invalid", "reason": f"{window_s:.0f} s: {int(moving.sum())} moving seconds"}
    dev = (h1[moving] - h1[moving].mean()) + 1j * (h2[moving] - h2[moving].mean())
    veh = (a_long[moving] - a_long[moving].mean()) + 1j * (a_lat[moving] - a_lat[moving].mean())
    if float(np.std(a_long[moving])) < MIN_EXCITATION_M_S2:
        return {"status": "invalid", "reason": f"{window_s:.0f} s: longitudinal excitation {np.std(a_long[moving]):.3f} m/s^2"}
    theta = float(np.angle(np.sum(veh * np.conj(dev))))
    rotated = np.exp(1j * theta) * dev
    r_long = float(np.corrcoef(rotated.real, veh.real)[0, 1])
    r_lat = float(np.corrcoef(rotated.imag, veh.imag)[0, 1])
    if not r_long >= CALIBRATION_MIN_R_LONG:
        return {"status": "invalid", "reason": f"{window_s:.0f} s: longitudinal r={r_long:.2f}", "r_long": round(r_long, 3)}
    x_v = math.cos(theta) * e1 - math.sin(theta) * e2
    y_v = math.sin(theta) * e1 + math.cos(theta) * e2
    rot = np.vstack([x_v, y_v, u])                       # rows are the vehicle axes in device coordinates: v_vehicle = rot @ v_device
    return {"status": "valid", "available_s": float(window_s), "q_vehicle_from_device_wxyz": [round(c, 9) for c in quaternion_from_matrix(rot)],
            "gyro_bias_rad_s": [round(float(c), 9) for c in bias], "up_method": up_method,
            "r_long": round(r_long, 3), "r_lat": round(r_lat, 3), "moving_seconds": int(moving.sum()), "theta_deg": round(math.degrees(theta), 2)}


def calibrate(recording: Path) -> dict:
    streams = load_streams(recording)
    reasons = []
    for window in WINDOWS_S:
        result = calibrate_window(streams, window)
        if result["status"] == "valid":
            return {**result, "id": f"batch-{recording.name}", "available_ns": str(ORIGIN_NS + int(result["available_s"] * 1e9)),
                    "windows_tried": len(reasons) + 1}
        reasons.append(result["reason"])
    return {"status": "invalid", "reasons": reasons}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("recording", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    result = calibrate(args.recording)
    text = json.dumps(result, indent=1) + "\n"
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    print(text)
    return 0 if result["status"] == "valid" else 1


if __name__ == "__main__":
    raise SystemExit(main())
