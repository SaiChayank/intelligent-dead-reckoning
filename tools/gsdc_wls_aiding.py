"""Phone-grade GNSS aiding from the raw measurements in a GSDC trip (`device_gnss.csv`): a position, a speed and a course per epoch.

    python tools/gsdc_wls_aiding.py <trip dir holding device_gnss.csv> [--reference <trip dir holding ground_truth.csv>]

The dataset withholds the phone's own GNSS position, but ships a weighted-least-squares position computed from the same raw pseudoranges a phone sees
(`WlsPosition*EcefMeters`). A phone's speed comes from the Doppler (pseudorange rate), so this module solves for velocity the same way: per epoch,
with the satellites' positions and velocities, `rate - u.v_sat = -u.v_user + b` is a linear problem in the user velocity and the receiver clock drift
(weighted by the reported rate uncertainty, an elevation mask, one round of outlier rejection, and a conditioning gate). The result is much noisier than
the 0.1 m reference the earlier evaluation used as aiding, which is the point: it is what the engine is fed in `tools/gsdc_to_recording.py --aiding wls`.
The reference is never used here except by `quality`, to measure how good this aiding is.

CC BY-NC-SA 3.0 IGO data; see tools/gsdc_to_recording.py for the citations.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

A = 6_378_137.0
F = 1 / 298.257223563
E2 = F * (2 - F)

MIN_ELEVATION_DEG = 10.0
MIN_ROWS = 6
MIN_SATELLITES = 5
MAX_CONDITION = 200.0
MAX_RESIDUAL_RMS_M_S = 2.0
MIN_RATE_SIGMA = 0.05

COLUMNS = ["utcTimeMillis", "Svid", "ConstellationType", "Cn0DbHz", "PseudorangeRateMetersPerSecond", "PseudorangeRateUncertaintyMetersPerSecond",
           "SvElevationDegrees", "SvPositionXEcefMeters", "SvPositionYEcefMeters", "SvPositionZEcefMeters", "SvVelocityXEcefMetersPerSecond",
           "SvVelocityYEcefMetersPerSecond", "SvVelocityZEcefMetersPerSecond", "WlsPositionXEcefMeters", "WlsPositionYEcefMeters", "WlsPositionZEcefMeters"]


def ecef_to_geodetic(x: np.ndarray, y: np.ndarray, z: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """WGS84 latitude and longitude in degrees and ellipsoidal height in metres (iterative, converges to well under a millimetre)."""
    lon = np.arctan2(y, x)
    p = np.hypot(x, y)
    lat = np.arctan2(z, p * (1 - E2))
    h = np.zeros_like(lat)
    for _ in range(6):
        n = A / np.sqrt(1 - E2 * np.sin(lat) ** 2)
        h = p / np.cos(lat) - n
        lat = np.arctan2(z, p * (1 - E2 * n / (n + h)))
    return np.degrees(lat), np.degrees(lon), h


def enu_matrix(lat_deg: float, lon_deg: float) -> np.ndarray:
    la, lo = math.radians(lat_deg), math.radians(lon_deg)
    return np.array([[-math.sin(lo), math.cos(lo), 0.0],
                     [-math.sin(la) * math.cos(lo), -math.sin(la) * math.sin(lo), math.cos(la)],
                     [math.cos(la) * math.cos(lo), math.cos(la) * math.sin(lo), math.sin(la)]])


def solve_velocity(user: np.ndarray, sv_pos: np.ndarray, sv_vel: np.ndarray, rate: np.ndarray, sigma: np.ndarray) -> dict | None:
    """User ECEF velocity and clock drift from one epoch of Doppler, or None when the epoch is not good enough."""
    los = sv_pos - user
    u = los / np.linalg.norm(los, axis=1, keepdims=True)
    y = rate - np.einsum("ij,ij->i", u, sv_vel)
    design = np.column_stack([-u, np.ones(len(u))])
    keep = np.ones(len(y), dtype=bool)
    solution = None
    for _ in range(2):                                   # second pass drops gross outliers (reflections, bad Doppler)
        if keep.sum() < MIN_ROWS:
            return None
        w = 1.0 / sigma[keep]
        solution, *_ = np.linalg.lstsq(design[keep] * w[:, None], y[keep] * w, rcond=None)
        residual = y - design @ solution
        spread = 1.4826 * np.median(np.abs(residual[keep] - np.median(residual[keep])))
        new_keep = np.abs(residual) <= max(3.0 * spread, 0.3)
        if new_keep.sum() == keep.sum() or new_keep.sum() < MIN_ROWS:
            break
        keep = new_keep
    weighted = design[keep] / sigma[keep][:, None]
    if np.linalg.cond(weighted) > MAX_CONDITION:
        return None
    rms = float(np.sqrt(np.mean((y[keep] - design[keep] @ solution) ** 2)))
    if rms > MAX_RESIDUAL_RMS_M_S:
        return None
    return {"v_ecef": solution[:3], "drift": float(solution[3]), "rms": rms, "rows": int(keep.sum())}


def load_epochs(trip: Path) -> dict[str, np.ndarray]:
    """One entry per epoch with a position: time (UTC s), lat, lon, alt, speed, course (NaN when the Doppler solution is not good enough), satellites."""
    df = pd.read_csv(trip / "device_gnss.csv", usecols=COLUMNS)
    df = df[np.isfinite(df.WlsPositionXEcefMeters) & np.isfinite(df.SvPositionXEcefMeters) & np.isfinite(df.utcTimeMillis)]
    t, lat, lon, alt, speed, course, sats, rms = [], [], [], [], [], [], [], []
    for utc, group in df.groupby("utcTimeMillis", sort=True):
        user = group[["WlsPositionXEcefMeters", "WlsPositionYEcefMeters", "WlsPositionZEcefMeters"]].iloc[0].to_numpy(float)
        la, lo, h = ecef_to_geodetic(*(user[i:i + 1] for i in range(3)))
        usable = group[np.isfinite(group.PseudorangeRateMetersPerSecond) & np.isfinite(group.SvVelocityXEcefMetersPerSecond)
                       & (group.SvElevationDegrees >= MIN_ELEVATION_DEG)]
        sat_count = int(group.drop_duplicates(["ConstellationType", "Svid"]).shape[0])
        v_enu = None
        if usable.shape[0] >= MIN_ROWS and usable.drop_duplicates(["ConstellationType", "Svid"]).shape[0] >= MIN_SATELLITES:
            sol = solve_velocity(
                user, usable[["SvPositionXEcefMeters", "SvPositionYEcefMeters", "SvPositionZEcefMeters"]].to_numpy(float),
                usable[["SvVelocityXEcefMetersPerSecond", "SvVelocityYEcefMetersPerSecond", "SvVelocityZEcefMetersPerSecond"]].to_numpy(float),
                usable.PseudorangeRateMetersPerSecond.to_numpy(float),
                np.maximum(usable.PseudorangeRateUncertaintyMetersPerSecond.to_numpy(float), MIN_RATE_SIGMA))
            if sol is not None:
                v_enu = enu_matrix(float(la[0]), float(lo[0])) @ sol["v_ecef"]
                rms.append(sol["rms"])
        t.append(utc / 1000.0)
        lat.append(float(la[0])); lon.append(float(lo[0])); alt.append(float(h[0]))
        speed.append(float(np.hypot(v_enu[0], v_enu[1])) if v_enu is not None else np.nan)
        course.append(float(np.degrees(np.arctan2(v_enu[0], v_enu[1])) % 360.0) if v_enu is not None else np.nan)
        sats.append(sat_count)
    return {"t": np.array(t), "lat": np.array(lat), "lon": np.array(lon), "alt": np.array(alt), "speed": np.array(speed), "course": np.array(course),
            "satellites": np.array(sats)}


def quality(epochs: dict[str, np.ndarray], reference: dict[str, np.ndarray]) -> dict:
    """How far the aiding is from the reference: horizontal position error, speed error and course error (only where the reference moves)."""
    t = epochs["t"]
    inside = (t >= reference["t"][0]) & (t <= reference["t"][-1])
    interp = lambda key: np.interp(t[inside], reference["t"], reference[key])
    err = np.hypot(np.radians(epochs["lat"][inside] - interp("lat")) * 6_371_000.0,
                   np.radians(epochs["lon"][inside] - interp("lon")) * 6_371_000.0 * np.cos(np.radians(interp("lat"))))
    speed_err = epochs["speed"][inside] - interp("speed")
    moving = interp("speed") > 3.0
    dc = (epochs["course"][inside] - interp("bearing") + 540.0) % 360.0 - 180.0
    ok = np.isfinite(speed_err)
    okc = np.isfinite(dc) & moving
    q = lambda a, p: round(float(np.percentile(a, p)), 2)
    return {"epochs": int(inside.sum()), "doppler_solutions": int(np.isfinite(epochs["speed"]).sum()),
            "position_error_m": {"median": q(err, 50), "p68": q(err, 68), "p95": q(err, 95)},
            "speed_error_m_s": {"median_abs": q(np.abs(speed_err[ok]), 50), "p95_abs": q(np.abs(speed_err[ok]), 95)} if ok.any() else None,
            "course_error_deg_moving": {"median_abs": q(np.abs(dc[okc]), 50), "p95_abs": q(np.abs(dc[okc]), 95)} if okc.any() else None,
            "satellites_median": float(np.median(epochs["satellites"][inside]))}


def load_reference(trip: Path) -> dict[str, np.ndarray]:
    truth = pd.read_csv(trip / "ground_truth.csv")
    return {"t": truth.UnixTimeMillis.to_numpy(float) / 1000.0, "lat": truth.LatitudeDegrees.to_numpy(float), "lon": truth.LongitudeDegrees.to_numpy(float),
            "speed": truth.SpeedMps.to_numpy(float), "bearing": truth.BearingDegrees.to_numpy(float)}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("trip", type=Path)
    parser.add_argument("--reference", type=Path, help="a trip folder holding ground_truth.csv, to measure the aiding's quality")
    args = parser.parse_args(argv)
    epochs = load_epochs(args.trip)
    print(f"{len(epochs['t'])} epochs, {int(np.isfinite(epochs['speed']).sum())} with a Doppler velocity")
    if args.reference:
        print(json.dumps(quality(epochs, load_reference(args.reference)), indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
