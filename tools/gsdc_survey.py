"""Survey of the Google Smartphone Decimeter Challenge 2023-2024 training trips: what the phone IMU and the reference really contain.

    python tools/gsdc_survey.py [--root data/raw/gsdc2023] [--out reports/gsdc_survey.json]

Reads `<root>/train/<drive>/<phone>/device_imu.csv` and `ground_truth.csv` (never the raw GNSS files). Writes only aggregate numbers per trip (no
positions) so the result can be committed. The data is CC BY-NC-SA 3.0 IGO; cite Fu, Khider, Gulati, van Diggelen, "Google Smartphone Decimeter
Challenge" (2023), https://kaggle.com/competitions/smartphone-decimeter-2023/data, and Fu, Khider, van Diggelen, ION GNSS+ 2020,
https://doi.org/10.33012/2020.17628. The raw files stay out of the repository.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
EARTH_R = 6_371_000.0


def trips(root: Path) -> list[tuple[str, str, Path]]:
    out = []
    for drive in sorted((root / "train").iterdir()):
        for phone in sorted(drive.iterdir()):
            if (phone / "device_imu.csv").is_file() and (phone / "ground_truth.csv").is_file():
                out.append((drive.name, phone.name, phone))
    return out


def rate(times_ms: np.ndarray) -> dict:
    if len(times_ms) < 3:
        return {"samples": int(len(times_ms)), "rate_hz": None}
    t = np.sort(times_ms.astype(np.int64))
    dt = np.diff(t) / 1000.0
    span = (t[-1] - t[0]) / 1000.0
    return {"samples": int(len(t)), "rate_hz": round(len(t) / span, 1) if span > 0 else None,
            "median_dt_ms": round(float(np.median(dt)) * 1000, 2), "max_gap_s": round(float(dt.max()), 2),
            "duplicate_timestamps": int((dt == 0).sum())}


def survey_trip(phone_dir: Path) -> dict:
    imu = pd.read_csv(phone_dir / "device_imu.csv", usecols=["MessageType", "utcTimeMillis", "elapsedRealtimeNanos",
                                                             "MeasurementX", "MeasurementY", "MeasurementZ"])
    truth = pd.read_csv(phone_dir / "ground_truth.csv", usecols=["LatitudeDegrees", "LongitudeDegrees", "SpeedMps", "AccuracyMeters",
                                                                "UnixTimeMillis"])
    accel = imu[imu.MessageType == "UncalAccel"]
    gyro = imu[imu.MessageType == "UncalGyro"]
    lat, lon = truth.LatitudeDegrees.to_numpy(), truth.LongitudeDegrees.to_numpy()
    step = np.hypot(np.radians(np.diff(lat)) * EARTH_R, np.radians(np.diff(lon)) * EARTH_R * np.cos(np.radians(lat[:-1])))
    t_truth = truth.UnixTimeMillis.to_numpy() / 1000.0
    speed = truth.SpeedMps.to_numpy()
    g = np.linalg.norm(accel[["MeasurementX", "MeasurementY", "MeasurementZ"]].to_numpy(), axis=1)
    overlap_s = max(0.0, min(accel.utcTimeMillis.max(), truth.UnixTimeMillis.max()) / 1000 - max(accel.utcTimeMillis.min(), truth.UnixTimeMillis.min()) / 1000)
    return {
        "accelerometer": rate(accel.utcTimeMillis.to_numpy()),
        "gyroscope": rate(gyro.utcTimeMillis.to_numpy()),
        "elapsed_realtime_present": bool(imu.elapsedRealtimeNanos.notna().any()),
        "accel_norm_median_m_s2": round(float(np.median(g)), 3),
        "truth_fixes": int(len(truth)),
        "truth_duration_s": round(float(t_truth[-1] - t_truth[0]), 1),
        "imu_truth_overlap_s": round(overlap_s, 1),
        "truth_max_gap_s": round(float(np.diff(t_truth).max()), 2),
        "truth_accuracy_median_m": round(float(truth.AccuracyMeters.median()), 3),
        "distance_km": round(float(step.sum()) / 1000, 2),
        "median_speed_m_s": round(float(np.median(speed)), 2),
        "fraction_above_5_m_s": round(float(np.mean(speed > 5.0)), 3),
        "max_speed_m_s": round(float(np.nanmax(speed)), 1),
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=ROOT / "data" / "raw" / "gsdc2023")
    parser.add_argument("--out", type=Path, default=ROOT / "reports" / "gsdc_survey.json")
    args = parser.parse_args(argv)
    rows = []
    for drive, phone, directory in trips(args.root):
        rows.append({"drive": drive, "phone": phone, **survey_trip(directory)})
        print(f"{drive}/{phone}: {rows[-1]['truth_duration_s']} s, {rows[-1]['distance_km']} km, accel {rows[-1]['accelerometer']['rate_hz']} Hz, "
              f"gyro {rows[-1]['gyroscope']['rate_hz']} Hz", flush=True)
    args.out.write_text(json.dumps({"dataset": "Google Smartphone Decimeter Challenge 2023-2024, train split", "license": "CC BY-NC-SA 3.0 IGO",
                                    "trips": rows}, indent=1) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
