"""IMU noise of a stationary phone recording: white-noise density, Allan deviation and gyro bias.

    python tools/phone_imu_noise.py <recording-dir> [--out reports/phone_imu_<device>.json]

Reads the app's `measurements.jsonl` (contract 1.0.0). Only the accelerometer and gyroscope are used; no position is read or written.
It describes a phone lying still, which UNDERSTATES the noise the sensors see in a moving vehicle (engine and road vibration), so the
numbers are a floor for the process-noise settings, not a replacement for them.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

TAUS_S = (0.1, 1, 5, 10, 20)


def load(recording: Path) -> dict[str, np.ndarray]:
    series: dict[str, list] = {"accelerometer": [], "gyroscope": []}
    with open(recording / "measurements.jsonl", encoding="utf-8") as handle:
        for line in handle:
            event = json.loads(line)["event"]
            if event["type"] != "imu":
                continue
            data = event["data"]
            if data["sensor"] in series:
                series[data["sensor"]].append([int(event["t_ns"]) * 1e-9, *data["xyz"]])
    return {name: np.array(rows) for name, rows in series.items() if rows}


def allan_deviation(values: np.ndarray, dt: float, tau_s: float) -> list[float] | None:
    m = int(round(tau_s / dt))
    if m < 1 or 2 * m >= len(values):
        return None
    cumulative = np.concatenate([np.zeros((1, values.shape[1])), np.cumsum(values, axis=0)])
    cluster = (cumulative[m:] - cumulative[:-m]) / m
    diff = cluster[m:] - cluster[:-m]
    return np.sqrt(0.5 * (diff ** 2).mean(axis=0)).round(6).tolist()


def characterise(series: dict[str, np.ndarray]) -> dict:
    out: dict = {}
    for name, key in (("accelerometer", "accel"), ("gyroscope", "gyro")):
        rows = series[name]
        t, v = rows[:, 0] - rows[0, 0], rows[:, 1:]
        dt = float(np.median(np.diff(t)))
        std = v.std(axis=0)
        allan = {str(tau): a for tau in TAUS_S if (a := allan_deviation(v, dt, tau)) is not None}
        out[key] = {"samples": int(len(v)), "span_s": round(float(t[-1]), 2), "rate_hz": round(1 / dt, 2),
                    "mean": v.mean(axis=0).round(5).tolist(), "std": std.round(6).tolist(),
                    "white_noise_density_per_sqrt_hz": (std * np.sqrt(dt)).round(7).tolist(),
                    "allan_deviation": allan}
    out["gyro_bias_deg_per_min"] = (np.degrees(series["gyroscope"][:, 1:].mean(axis=0)) * 60).round(3).tolist()
    return out


def main(argv=None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("recording", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args(argv)
    result = characterise(load(args.recording))
    text = json.dumps(result, indent=1) + "\n"
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
