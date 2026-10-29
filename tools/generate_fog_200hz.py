"""Generate a LABELLED-SYNTHETIC 200 Hz drive with tactical (fibre-optic-gyro) grade inertial noise.

    python tools/generate_fog_200hz.py out.csv [--seconds 70] [--seed 3]

There is no real FOG dataset in this repository, so this stands in for an external IMU when exercising the edge
engine's 200 Hz path (PS #26168 asks for higher rates on the edge with FOG IMUs). The data is scripted: a
straight cruise at 12 m/s with one turn and a GNSS outage from t = 30 s to 50 s, in the vehicle frame
(x forward, y left, z up). Noise follows a stated tactical-grade profile (constants below). It says nothing
about real FOG hardware; it exists to measure throughput, latency and drops at 200 Hz.

Output columns match tools/external_imu_to_contract.py (units: g and deg/s).
"""
from __future__ import annotations

import argparse
import csv
import math
from pathlib import Path

import numpy as np

HZ = 200.0
SPEED_M_S = 12.0
TURN_START_S, TURN_END_S, YAW_RATE_RAD_S = 20.0, 28.0, 0.2
OUTAGE_FROM_S, OUTAGE_TO_S = 30.0, 50.0
ANCHOR_LAT, ANCHOR_LON = 17.4350, 78.4450
G = 9.80665
# Stated tactical-grade profile (illustrative, not a datasheet).
ACCEL_NOISE_G = 50e-6 * math.sqrt(HZ)        # 50 micro-g/sqrt(Hz) white noise per sample
ACCEL_BIAS_G = 100e-6
GYRO_NOISE_DEG_S = (0.003 / 60.0) * math.sqrt(HZ)   # angle random walk 0.003 deg/sqrt(h), per-sample sigma in deg/s
GYRO_BIAS_DEG_S = 10 * 0.01 / 3600.0         # ten times a 0.01 deg/h bias instability, in deg/s
M_PER_DEG_LAT = 110_660.0
M_PER_DEG_LON = 106_200.0


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("out", type=Path)
    p.add_argument("--seconds", type=float, default=70.0)
    p.add_argument("--seed", type=int, default=3)
    a = p.parse_args()
    rng = np.random.default_rng(a.seed)
    n = int(a.seconds * HZ)
    t = np.arange(n) / HZ
    yaw_rate = np.where((t >= TURN_START_S) & (t < TURN_END_S), YAW_RATE_RAD_S, 0.0)       # CCW positive
    heading = np.cumsum(yaw_rate) / HZ                                                      # rad, CCW from the start axis
    east = np.cumsum(SPEED_M_S * np.cos(heading)) / HZ                                      # start heading: +x east
    north = np.cumsum(SPEED_M_S * np.sin(heading)) / HZ
    course = (90.0 - np.degrees(heading)) % 360.0                                           # clockwise from north
    a_lat_left = SPEED_M_S * yaw_rate                                                       # specific force, y = left
    accel_g = np.column_stack([np.zeros(n), a_lat_left / G, np.ones(n)])
    accel_g += ACCEL_BIAS_G + rng.normal(0.0, ACCEL_NOISE_G, accel_g.shape)
    gyro_dps = np.column_stack([np.zeros(n), np.zeros(n), np.degrees(yaw_rate)])
    gyro_dps += GYRO_BIAS_DEG_S + rng.normal(0.0, GYRO_NOISE_DEG_S, gyro_dps.shape)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, lineterminator="\n")
        w.writerow(["t_s", "ax", "ay", "az", "gx", "gy", "gz", "lat", "lon", "speed_m_s", "course_deg", "accuracy_m", "satellites"])
        for i in range(n):
            fix = (i % int(HZ) == 0) and not (OUTAGE_FROM_S <= t[i] < OUTAGE_TO_S)
            row = [f"{t[i]:.5f}", *(f"{v:.9f}" for v in accel_g[i]), *(f"{v:.9f}" for v in gyro_dps[i])]
            if fix:
                row += [f"{ANCHOR_LAT + north[i] / M_PER_DEG_LAT:.8f}", f"{ANCHOR_LON + east[i] / M_PER_DEG_LON:.8f}",
                        f"{SPEED_M_S:.3f}", f"{course[i]:.2f}", "2.5", "12"]
            else:
                row += ["", "", "", "", "", ""]
            w.writerow(row)
    print(f"wrote {n} samples ({a.seconds:.0f} s at {HZ:.0f} Hz, synthetic) to {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
