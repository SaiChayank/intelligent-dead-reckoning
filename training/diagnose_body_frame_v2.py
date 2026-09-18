"""
training/diagnose_body_frame_v2.py
============================================================

IDR Phase 1C — Final Physics-Constrained Body-Frame Diagnostic

What this version fixes
-----------------------
Earlier body-frame diagnostics had three problems:

1. The unrestricted 48-way search allowed physically impossible mappings,
   including assigning vehicle vertical to a phone horizontal axis.

2. A single permutation matrix was incorrectly forced onto BOTH the
   accelerometer triad (X/Y/Z) and the gyro columns (Roll/Pitch/Yaw).
   The labels of those two datasets must not be assumed to share the
   same column-index convention.

3. Previous versions could accidentally select a local variable with the
   same name as a diagnostic function and had brittle column matching.

This version is standalone and read-only.

It uses the evidence already established from the IO-VNBD M sequence:

    - accelerometer - gravity is the correct gravity-removal sign
    - the measured gravity vector is strongly dominated by phone Z
    - S-M.csv and V-M.csv are row-aligned for the diagnostic window
    - the gyro yaw channel must be evaluated independently

Method
------
A. Establish the dominant vertical PHONE AXIS from measured gravity.

B. Search only proper right-handed ACCELEROMETER frame candidates:
       vehicle Z is constrained to the dominant phone vertical axis,
       while the two horizontal axes and their signs are enumerated.

   Both possible vertical directions are tested because the sign convention
   of a gravity-like column must not be inferred from the label alone.

C. For every candidate, estimate a continuous horizontal mounting yaw using
   centered 2-D Procrustes fitting against the VBOX longitudinal/lateral
   acceleration vector. This avoids assuming that the phone is mounted with
   its X/Y axes exactly aligned to the vehicle.

D. Independently search the three raw GYROSCOPE columns against VBOX yaw rate.
   Report correlation, slope, intercept and small diagnostic lag.

E. Test the strongest gyro yaw channel against the gravity-constrained
   acceleration frame using a turn-consistency relationship:

       lateral_force ~= speed * yaw_rate

   and report whether the integrated yaw is at least directionally coherent
   with the observed horizontal acceleration.

F. Do NOT guess gyro roll/pitch. There is no direct VBOX roll/pitch-rate
   ground truth in this dataset.

IMPORTANT
---------
This file does NOT modify:
    - raw data
    - processed data
    - ins_mechanization.py
    - models

Run:
    python training\\diagnose_body_frame_v2.py --duration 60

For a longer, more maneuver-rich window:
    python training\\diagnose_body_frame_v2.py --duration 600
"""

from __future__ import annotations

import argparse
import itertools
from pathlib import Path

import numpy as np
import pandas as pd

if __package__:
    from .common import add_dataset_argument, m_pair_from_args, sequence_paths, normalize_name, safe_numeric
else:
    from common import add_dataset_argument, m_pair_from_args, sequence_paths, normalize_name, safe_numeric


# ============================================================
# Configuration
# ============================================================

NOMINAL_HZ = 10.0
DEFAULT_DURATION_S = 60.0

G0 = 9.80665

# Small diagnostic lag window only.
MAX_LAG_SAMPLES = 20


# ============================================================
# Dataset path
# ============================================================

# ============================================================
# Robust columns
# ============================================================

def find_exact_column(
    df: pd.DataFrame,
    expected: str,
) -> str:

    target = normalize_name(expected)

    for column in df.columns:

        if normalize_name(column) == target:
            return column

    raise KeyError(
        f"Column not found: {expected!r}\n"
        f"Available columns:\n{list(df.columns)}"
    )


def numeric(
    df: pd.DataFrame,
    expected: str,
) -> np.ndarray:

    column = find_exact_column(
        df,
        expected,
    )

    return safe_numeric(df[column])


# ============================================================
# Statistics
# ============================================================

def finite_mask(
    a: np.ndarray,
    b: np.ndarray,
) -> np.ndarray:

    return (
        np.isfinite(a)
        & np.isfinite(b)
    )


def correlation(
    a: np.ndarray,
    b: np.ndarray,
) -> float:

    mask = finite_mask(a, b)

    if mask.sum() < 100:
        return float("nan")

    aa = a[mask]
    bb = b[mask]

    if (
        np.std(aa) < 1e-12
        or np.std(bb) < 1e-12
    ):
        return float("nan")

    return float(
        np.corrcoef(
            aa,
            bb,
        )[0, 1]
    )


def regression(
    reference: np.ndarray,
    candidate: np.ndarray,
) -> tuple[float, float]:

    mask = finite_mask(
        reference,
        candidate,
    )

    if mask.sum() < 100:
        return float("nan"), float("nan")

    x = reference[mask]
    y = candidate[mask]

    x0 = x - np.mean(x)
    y0 = y - np.mean(y)

    denom = np.dot(
        x0,
        x0,
    )

    if denom < 1e-12:
        return float("nan"), float("nan")

    slope = float(
        np.dot(
            x0,
            y0,
        )
        / denom
    )

    intercept = float(
        np.mean(y)
        - slope * np.mean(x)
    )

    return slope, intercept


def rmse(
    a: np.ndarray,
    b: np.ndarray,
) -> float:

    mask = finite_mask(a, b)

    if not mask.any():
        return float("nan")

    return float(
        np.sqrt(
            np.mean(
                (
                    a[mask]
                    - b[mask]
                ) ** 2
            )
        )
    )


def normalized_rmse(
    candidate: np.ndarray,
    reference: np.ndarray,
) -> float:

    value = rmse(
        candidate,
        reference,
    )

    mask = finite_mask(
        candidate,
        reference,
    )

    if not mask.any():
        return float("nan")

    scale = np.std(
        reference[mask]
    )

    if scale < 1e-12:
        return float("nan")

    return float(
        value / scale
    )


# ============================================================
# Lag diagnostic
# ============================================================

def shifted_pair(
    candidate: np.ndarray,
    reference: np.ndarray,
    lag: int,
) -> tuple[np.ndarray, np.ndarray]:

    n = min(
        len(candidate),
        len(reference),
    )

    candidate = candidate[:n]
    reference = reference[:n]

    if lag > 0:
        return (
            candidate[lag:],
            reference[:-lag],
        )

    if lag < 0:
        return (
            candidate[:lag],
            reference[-lag:],
        )

    return candidate, reference


def best_lag(candidate: np.ndarray, reference: np.ndarray) -> tuple[int, float]:
    if __package__:
        from .frame_math import lag_metrics
    else:
        from frame_math import lag_metrics
    result = lag_metrics(candidate, reference, max_lag=10)
    correlation = result["metrics"]["correlation"]
    return result["lag_samples"], float(correlation) if correlation is not None else float("nan")


# ============================================================
# Loading
# ============================================================

def load_data(
    duration_s: float,
    data_root: str | Path | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame]:

    root, s_path, v_path = sequence_paths(data_root)

    smartphone = pd.read_csv(
        s_path,
        encoding="cp1252",
    )

    vbox = pd.read_csv(
        v_path,
        encoding="utf-8",
    )

    if len(smartphone) != len(vbox):

        raise ValueError(
            "S-M.csv and V-M.csv do not have equal row counts:\n"
            f"smartphone={len(smartphone):,}\n"
            f"vbox={len(vbox):,}"
        )

    date_col = find_exact_column(
        smartphone,
        "DATE (YYYY-MO-DD HH-MI-SS_SSS)",
    )

    timestamps = pd.to_datetime(
        smartphone[date_col]
        .astype(str)
        .str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    if timestamps.isna().any():

        raise ValueError(
            f"{int(timestamps.isna().sum())} DATE values "
            "could not be parsed."
        )

    elapsed = (
        timestamps
        - timestamps.iloc[0]
    ).dt.total_seconds()

    mask = (
        elapsed <= duration_s
    )

    smartphone = (
        smartphone.loc[mask]
        .reset_index(drop=True)
    )

    vbox = (
        vbox.iloc[:len(smartphone)]
        .reset_index(drop=True)
    )

    print(
        f"Dataset root : {root}"
    )

    print(
        f"Smartphone   : {s_path}"
    )

    print(
        f"VBOX         : {v_path}"
    )

    return smartphone, vbox


# ============================================================
# Signal extraction
# ============================================================

def extract_signals(
    smartphone: pd.DataFrame,
    vbox: pd.DataFrame,
) -> dict[str, np.ndarray]:

    accel = np.column_stack(
        [
            numeric(
                smartphone,
                "ACCELEROMETER X (m/s²)",
            ),
            numeric(
                smartphone,
                "ACCELEROMETER Y (m/s²)",
            ),
            numeric(
                smartphone,
                "ACCELEROMETER Z (m/s²)",
            ),
        ]
    )

    gravity = np.column_stack(
        [
            numeric(
                smartphone,
                "GRAVITY X (m/s²)",
            ),
            numeric(
                smartphone,
                "GRAVITY Y (m/s²)",
            ),
            numeric(
                smartphone,
                "GRAVITY Z (m/s²)",
            ),
        ]
    )

    # IMPORTANT:
    # Keep the CSV gyro order explicit. The Roll/Pitch/Yaw labels are
    # diagnostic labels here, NOT an assumed physical XYZ mapping.
    gyro = np.column_stack(
        [
            numeric(
                smartphone,
                "GYROSCOPE Roll (rad/s)",
            ),
            numeric(
                smartphone,
                "GYROSCOPE Pitch (rad/s)",
            ),
            numeric(
                smartphone,
                "GYROSCOPE Yaw (rad/s)",
            ),
        ]
    )

    return {
        "accel": accel,
        "gravity": gravity,
        "linear_accel": accel - gravity,

        "gyro": gyro,

        "vbox_yaw_rate": numeric(
            vbox,
            "Yaw Rate (deg/sec)",
        ),

        "vbox_speed": (
            numeric(
                vbox,
                "Velocity (km/hr)",
            )
            / 3.6
        ),

        "vbox_long": (
            numeric(
                vbox,
                "Indicated Longitudinal Acceleration (g)",
            )
            * G0
        ),

        "vbox_lat": (
            numeric(
                vbox,
                "Indicated Lateral Acceleration (g)",
            )
            * G0
        ),

        "vbox_heading_deg": numeric(
            vbox,
            "Heading (degrees)",
        ),

        "smartphone_lat": numeric(
            smartphone,
            "GPS LATITUDE (degrees)",
        ),

        "smartphone_lon": numeric(
            smartphone,
            "GPS LONGITUDE (degrees)",
        ),
    }


# ============================================================
# Timing and gravity
# ============================================================

def print_timing_and_gravity(
    smartphone: pd.DataFrame,
    signals: dict[str, np.ndarray],
) -> tuple[int, int, np.ndarray]:

    date_col = find_exact_column(
        smartphone,
        "DATE (YYYY-MO-DD HH-MI-SS_SSS)",
    )

    timestamps = pd.to_datetime(
        smartphone[date_col]
        .astype(str)
        .str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    dt = (
        timestamps
        .diff()
        .dt.total_seconds()
        .to_numpy()[1:]
    )

    gravity = signals["gravity"]
    accel = signals["accel"]
    linear = signals["linear_accel"]

    n_avg = min(
        10,
        len(gravity),
    )

    g0 = np.mean(
        gravity[:n_avg],
        axis=0,
    )

    g_norm = np.linalg.norm(
        g0
    )

    dominant_axis = int(
        np.argmax(
            np.abs(g0)
        )
    )

    dominant_sign = (
        1
        if g0[dominant_axis] >= 0
        else -1
    )

    alignment_fraction = (
        abs(g0[dominant_axis])
        / g_norm
        if g_norm > 1e-12
        else np.nan
    )

    print("\n" + "=" * 100)
    print("TIMING / GRAVITY SANITY")
    print("=" * 100)

    print(
        f"Mean DATE interval   : {np.mean(dt):.6f} s"
    )

    print(
        f"Median DATE interval : {np.median(dt):.6f} s"
    )

    print(
        f"Min DATE interval    : {np.min(dt):.6f} s"
    )

    print(
        f"Max DATE interval    : {np.max(dt):.6f} s"
    )

    print(
        f"DATE gaps > 0.25 s   : {int(np.sum(dt > 0.25))}"
    )

    print(
        f"\nInitial gravity vector : {g0}"
    )

    print(
        f"Gravity magnitude      : {g_norm:.6f} m/s²"
    )

    print(
        f"Dominant phone axis    : "
        f"{'XYZ'[dominant_axis]} "
        f"(sign {dominant_sign:+d})"
    )

    print(
        f"Single-axis alignment  : "
        f"{alignment_fraction:.6f}"
    )

    acc_minus = np.linalg.norm(
        accel - gravity,
        axis=1,
    )

    acc_plus = np.linalg.norm(
        accel + gravity,
        axis=1,
    )

    print(
        f"\n|accel - gravity| mean : "
        f"{np.mean(acc_minus):.4f} m/s²"
    )

    print(
        f"|accel + gravity| mean : "
        f"{np.mean(acc_plus):.4f} m/s²"
    )

    print(
        "\nInterpretation:"
    )

    print(
        "  The dominant axis identifies the phone's vertical sensor axis."
    )

    print(
        "  The SIGN of the vertical mapping is intentionally NOT assumed "
        "from the gravity label; both directions are tested."
    )

    print(
        "  accel - gravity is retained because that sign was already "
        "validated by the earlier diagnostic."
    )

    if alignment_fraction < 0.90:
        print(
            "\nWARNING: gravity is not strongly single-axis dominated."
        )

    # Return the dominant axis and sign only as diagnostic evidence.
    return (
        dominant_axis,
        dominant_sign,
        g0,
    )


# ============================================================
# Proper frame candidates
# ============================================================

def candidate_accel_frames(
    vertical_phone_axis: int,
):
    """
    Enumerate ALL proper right-handed signed mappings with vehicle Z fixed
    to the measured phone vertical axis.

    There are 8 candidates:

        2 choices for vehicle Z sign
        ×
        2 horizontal axis orders
        ×
        2 horizontal sign choices after det=+1 is enforced
        = 8
    """

    horizontal_phone_axes = [
        i
        for i in range(3)
        if i != vertical_phone_axis
    ]

    for z_sign in (-1.0, 1.0):

        for horizontal_order in (
            (
                horizontal_phone_axes[0],
                horizontal_phone_axes[1],
            ),
            (
                horizontal_phone_axes[1],
                horizontal_phone_axes[0],
            ),
        ):

            for sx in (-1.0, 1.0):

                # Determine sy from det(M)=+1.
                #
                # Construct both choices and select the proper rotation.
                for sy in (-1.0, 1.0):

                    M = np.zeros(
                        (3, 3),
                        dtype=float,
                    )

                    M[0, horizontal_order[0]] = sx
                    M[1, horizontal_order[1]] = sy
                    M[2, vertical_phone_axis] = z_sign

                    if np.linalg.det(M) > 0.5:
                        yield M


def describe_frame(
    M: np.ndarray,
) -> str:

    vehicle_names = (
        "Vx(forward)",
        "Vy(lateral)",
        "Vz(vertical)",
    )

    phone_names = (
        "phone X",
        "phone Y",
        "phone Z",
    )

    parts = []

    for row, vehicle_name in enumerate(
        vehicle_names
    ):

        phone_axis = int(
            np.argmax(
                np.abs(M[row])
            )
        )

        sign = (
            "+"
            if M[row, phone_axis] > 0
            else "-"
        )

        parts.append(
            f"{vehicle_name}={sign}{phone_names[phone_axis]}"
        )

    return " | ".join(parts)


# ============================================================
# 2D horizontal mounting rotation
# ============================================================

def rotate_xy(
    xy: np.ndarray,
    yaw_deg: float,
) -> np.ndarray:

    theta = np.radians(
        yaw_deg
    )

    c = np.cos(theta)
    s = np.sin(theta)

    return np.column_stack(
        [
            c * xy[:, 0]
            - s * xy[:, 1],

            s * xy[:, 0]
            + c * xy[:, 1],
        ]
    )


def fit_horizontal_rotation(
    phone_xy: np.ndarray,
    reference_xy: np.ndarray,
) -> dict[str, float]:

    mask = (
        np.isfinite(phone_xy).all(axis=1)
        & np.isfinite(reference_xy).all(axis=1)
    )

    p = phone_xy[mask]
    r = reference_xy[mask]

    if len(p) < 100:
        return {
            "yaw_deg": np.nan,
            "corr_long": np.nan,
            "corr_lat": np.nan,
            "slope_long": np.nan,
            "slope_lat": np.nan,
            "nrmse": np.nan,
        }

    p0 = (
        p
        - np.mean(
            p,
            axis=0,
            keepdims=True,
        )
    )

    r0 = (
        r
        - np.mean(
            r,
            axis=0,
            keepdims=True,
        )
    )

    z_p = (
        p0[:, 0]
        + 1j * p0[:, 1]
    )

    z_r = (
        r0[:, 0]
        + 1j * r0[:, 1]
    )

    initial_yaw = np.degrees(
        np.angle(
            np.sum(
                z_r
                * np.conjugate(z_p)
            )
        )
    )

    # Fine local refinement.
    grid = np.arange(
        initial_yaw - 2.0,
        initial_yaw + 2.0001,
        0.05,
    )

    best = None

    for yaw_deg in grid:

        rotated = rotate_xy(
            p,
            yaw_deg,
        )

        long_rmse = rmse(
            rotated[:, 0],
            r[:, 0],
        )

        lat_rmse = rmse(
            rotated[:, 1],
            r[:, 1],
        )

        long_std = np.std(
            r[:, 0]
        )

        lat_std = np.std(
            r[:, 1]
        )

        if (
            long_std < 1e-12
            or lat_std < 1e-12
        ):
            continue

        nrmse = 0.5 * (
            long_rmse / long_std
            + lat_rmse / lat_std
        )

        if (
            best is None
            or nrmse < best[0]
        ):

            corr_long = correlation(
                rotated[:, 0],
                r[:, 0],
            )

            corr_lat = correlation(
                rotated[:, 1],
                r[:, 1],
            )

            slope_long, _ = regression(
                r[:, 0],
                rotated[:, 0],
            )

            slope_lat, _ = regression(
                r[:, 1],
                rotated[:, 1],
            )

            best = (
                nrmse,
                yaw_deg,
                corr_long,
                corr_lat,
                slope_long,
                slope_lat,
            )

    if best is None:
        return {
            "yaw_deg": np.nan,
            "corr_long": np.nan,
            "corr_lat": np.nan,
            "slope_long": np.nan,
            "slope_lat": np.nan,
            "nrmse": np.nan,
        }

    (
        nrmse,
        yaw_deg,
        corr_long,
        corr_lat,
        slope_long,
        slope_lat,
    ) = best

    return {
        "yaw_deg": float(yaw_deg),
        "corr_long": float(corr_long),
        "corr_lat": float(corr_lat),
        "slope_long": float(slope_long),
        "slope_lat": float(slope_lat),
        "nrmse": float(nrmse),
    }


# ============================================================
# Acceleration search
# ============================================================

def search_accel_frames(signals: dict[str, np.ndarray], vertical_phone_axis: int) -> list[dict[str, object]]:
    if __package__:
        from .frame_math import fit_mounting, score
    else:
        from frame_math import fit_mounting, score
    reference = np.column_stack([signals["vbox_long"], signals["vbox_lat"]])
    fit = fit_mounting(signals["linear_accel"], np.mean(signals["gravity"], axis=0), reference)
    a, b = fit["longitudinal"], fit["lateral"]
    # Total proper rotation already contains mounting yaw; no second yaw application.
    return [dict(M=fit["matrix"], mapping="one gravity-leveled proper rotation; see total M",
        det=float(np.linalg.det(fit["matrix"])), vertical_fraction=1.,
        correlation_score=(score(a)+score(b))/2, corr_long=a["correlation"],
        corr_lat=b["correlation"], slope_long=a["slope"], slope_lat=b["slope"],
        nrmse=(a["nrmse"]+b["nrmse"])/2 if a["nrmse"] is not None and b["nrmse"] is not None else float("nan"),
        yaw_deg=0., total_mounting_yaw_deg=float(np.degrees(fit["yaw_rad"]))) ]


# ============================================================
# Gyro yaw search
# ============================================================

def search_gyro_yaw(signals: dict[str, np.ndarray]) -> list[dict[str, object]]:
    if __package__:
        from .frame_math import gyro_candidates
    else:
        from frame_math import gyro_candidates
    labels = ("GYROSCOPE Roll", "GYROSCOPE Pitch", "GYROSCOPE Yaw")
    channels = {label: signals["gyro"][:, i] for i, label in enumerate(labels)}
    ranked = gyro_candidates(channels, np.radians(signals["vbox_yaw_rate"]),
                             signals.get("vbox_speed"), signals.get("vbox_lat"))
    results = []
    for r in ranked:
        m = r["zero_lag"]; best = r["best_lag"]
        results.append(dict(label=r["channel"], index=labels.index(r["channel"]), sign=r["sign"],
            description=f"vehicle yaw rate = {r['sign']:+d} * {r['channel']} (raw rad/s)",
            corr=m["correlation"], slope=m["slope"], intercept=m["intercept"],
            rmse=m["rmse"], nrmse=m["nrmse"], lag=best["lag_samples"],
            lag_corr=best["metrics"]["correlation"], zero_lag=m, best_lag=best,
            ranking_score=r["ranking_score"]))
    return results


# ============================================================
# Turn consistency
# ============================================================

def turn_consistency(
    signals: dict[str, np.ndarray],
    accel_result: dict[str, object],
    gyro_result: dict[str, object],
) -> dict[str, float]:

    M = accel_result["M"]

    mapped_accel = (
        M
        @ signals["linear_accel"].T
    ).T

    yaw_deg = float(
        accel_result["yaw_deg"]
    )

    accel_vehicle = rotate_xy(
        mapped_accel[:, :2],
        yaw_deg,
    )

    gyro_index = int(
        gyro_result["index"]
    )

    gyro_sign = float(
        gyro_result["sign"]
    )

    omega = (
        gyro_sign
        * signals["gyro"][:, gyro_index]
    )

    speed = signals["vbox_speed"]

    # Predicted lateral centripetal acceleration.
    # This is deliberately used only as a direction/magnitude diagnostic.
    predicted_lat = (
        speed
        * omega
    )

    observed_lat = (
        accel_vehicle[:, 1]
    )

    mask = finite_mask(
        predicted_lat,
        observed_lat,
    ) & (
        np.abs(predicted_lat) > 0.05
    )

    if mask.sum() < 100:

        return {
            "corr": np.nan,
            "slope": np.nan,
            "nrmse": np.nan,
            "samples": float(mask.sum()),
        }

    c = correlation(
        predicted_lat[mask],
        observed_lat[mask],
    )

    sl, _ = regression(
        predicted_lat[mask],
        observed_lat[mask],
    )

    nrmse = normalized_rmse(
        predicted_lat[mask],
        observed_lat[mask],
    )

    return {
        "corr": float(c),
        "slope": float(sl),
        "nrmse": float(nrmse),
        "samples": float(mask.sum()),
    }


# ============================================================
# GPS heading cross-check
# ============================================================

def gps_heading_cross_check(
    signals: dict[str, np.ndarray],
) -> dict[str, float]:

    lat = signals["smartphone_lat"]
    lon = signals["smartphone_lon"]

    vbox_heading = signals[
        "vbox_heading_deg"
    ]

    lat0 = lat[0]
    lon0 = lon[0]

    earth_radius = 6_371_000.0

    heading = np.nan

    for i in range(
        1,
        len(lat),
    ):

        if not (
            np.isfinite(lat[i])
            and np.isfinite(lon[i])
        ):
            continue

        lat0r = np.radians(
            lat0
        )

        east = (
            np.radians(
                lon[i] - lon0
            )
            * earth_radius
            * np.cos(lat0r)
        )

        north = (
            np.radians(
                lat[i] - lat0
            )
            * earth_radius
        )

        displacement = np.hypot(
            east,
            north,
        )

        if displacement >= 5.0:

            heading = (
                np.degrees(
                    np.arctan2(
                        east,
                        north,
                    )
                )
                % 360.0
            )

            break

    if not np.isfinite(heading):

        return {
            "gps_heading": np.nan,
            "vbox_heading": float(
                vbox_heading[0]
            ),
            "difference": np.nan,
        }

    difference = (
        (
            heading
            - vbox_heading[0]
            + 180.0
        )
        % 360.0
    ) - 180.0

    return {
        "gps_heading": float(heading),
        "vbox_heading": float(
            vbox_heading[0]
        ),
        "difference": float(
            difference
        ),
    }


# ============================================================
# Main
# ============================================================

def legacy_main() -> None:
    raise RuntimeError("Historical diagnostic retired; use main() or training.frame_audit for validated methodology.")

    parser = argparse.ArgumentParser(
        description=(
            "Final physics-constrained IO-VNBD body-frame diagnostic."
        )
    )

    parser.add_argument(
        "--duration",
        type=float,
        default=DEFAULT_DURATION_S,
        help="Diagnostic window in seconds.",
    )

    parser.add_argument(
        "--top",
        type=int,
        default=8,
        help="Number of top acceleration candidates to show.",
    )

    add_dataset_argument(parser)
    args = parser.parse_args()
    root, s_file, v_file = m_pair_from_args(parser, args)

    if args.duration <= 0:
        raise ValueError(
            "--duration must be greater than zero."
        )

    print("=" * 100)
    print(
        "IDR PHASE 1C — FINAL PHYSICS-CONSTRAINED BODY-FRAME DIAGNOSTIC"
    )
    print(
        "READ-ONLY"
    )
    print("=" * 100)

    smartphone, vbox = load_data(
        args.duration,
        data_root=root,
    )

    print(
        f"\nRows used:"
        f"\n  Smartphone: {len(smartphone):,}"
        f"\n  VBOX:       {len(vbox):,}"
        f"\n  Duration:   {(len(smartphone)-1)/NOMINAL_HZ:.3f} s"
    )

    signals = extract_signals(
        smartphone,
        vbox,
    )

    (
        vertical_axis,
        vertical_sign,
        gravity0,
    ) = print_timing_and_gravity(
        smartphone,
        signals,
    )

    # --------------------------------------------------------
    # ACCELERATION FRAME
    # --------------------------------------------------------

    print("\n" + "=" * 100)
    print(
        "A — GRAVITY-CONSTRAINED ACCELERATION FRAME SEARCH"
    )
    print("=" * 100)

    accel_results = search_accel_frames(
        signals,
        vertical_axis,
    )

    for rank, result in enumerate(
        accel_results[:args.top],
        start=1,
    ):

        print(
            f"\n#{rank}  "
            f"score={result['correlation_score']:.4f}"
        )

        print(
            f"    {result['mapping']}"
        )

        print(
            f"    det(M)={result['det']:+.0f}"
        )

        print(
            f"    horizontal mounting yaw="
            f"{result['yaw_deg']:+.3f} deg"
        )

        print(
            f"    corr longitudinal="
            f"{result['corr_long']:+.4f}"
        )

        print(
            f"    corr lateral="
            f"{result['corr_lat']:+.4f}"
        )

        print(
            f"    slope longitudinal="
            f"{result['slope_long']:+.4f}"
        )

        print(
            f"    slope lateral="
            f"{result['slope_lat']:+.4f}"
        )

        print(
            f"    normalized acceleration RMSE="
            f"{result['nrmse']:.4f}"
        )

        print(
            f"    mapped gravity vertical fraction="
            f"{result['vertical_fraction']:.6f}"
        )

    best_accel = accel_results[0]

    print("\n" + "=" * 100)
    print(
        "BEST ACCELERATION CANDIDATE"
    )
    print("=" * 100)

    print(
        best_accel["mapping"]
    )

    # --------------------------------------------------------
    # GYRO
    # --------------------------------------------------------

    print("\n" + "=" * 100)
    print(
        "B — INDEPENDENT GYRO YAW-CHANNEL SEARCH"
    )
    print("=" * 100)

    gyro_results = search_gyro_yaw(
        signals
    )

    for rank, result in enumerate(
        gyro_results[:6],
        start=1,
    ):

        print(
            f"\n#{rank}  {result['description']}"
        )

        print(
            f"    zero-lag corr="
            f"{result['corr']:+.4f}"
        )

        print(
            f"    slope="
            f"{result['slope']:+.4f}"
        )

        print(
            f"    intercept="
            f"{result['intercept']:+.4f} deg/s"
        )

        print(
            f"    normalized RMSE="
            f"{result['nrmse']:.4f}"
        )

        print(
            f"    best lag="
            f"{result['lag']:+d} samples "
            f"({result['lag']/NOMINAL_HZ:+.2f} s)"
        )

        print(
            f"    best-lag corr="
            f"{result['lag_corr']:+.4f}"
        )

    best_gyro = gyro_results[0]

    # --------------------------------------------------------
    # Turn consistency
    # --------------------------------------------------------

    print("\n" + "=" * 100)
    print(
        "C — TURN-CONSISTENCY CHECK"
    )
    print("=" * 100)

    tc = turn_consistency(
        signals,
        best_accel,
        best_gyro,
    )

    print(
        "Uses the independently selected gyro yaw channel and"
    )

    print(
        "the gravity-constrained horizontal acceleration frame."
    )

    print(
        f"\nCorrelation:"
        f" {tc['corr']:+.4f}"
    )

    print(
        f"Slope observed_lateral ~= slope * (speed*yaw_rate):"
        f" {tc['slope']:+.4f}"
    )

    print(
        f"Normalized RMSE:"
        f" {tc['nrmse']:.4f}"
    )

    print(
        f"Usable samples:"
        f" {int(tc['samples'])}"
    )

    # --------------------------------------------------------
    # GPS heading
    # --------------------------------------------------------

    print("\n" + "=" * 100)
    print(
        "D — SMARTPHONE GPS / VBOX HEADING CROSS-CHECK"
    )
    print("=" * 100)

    heading = gps_heading_cross_check(
        signals
    )

    print(
        f"Smartphone GPS bearing:"
        f" {heading['gps_heading']:.3f} deg"
        if np.isfinite(
            heading["gps_heading"]
        )
        else "Smartphone GPS bearing: unavailable"
    )

    print(
        f"VBOX heading:"
        f" {heading['vbox_heading']:.3f} deg"
    )

    print(
        f"Difference:"
        f" {heading['difference']:+.3f} deg"
        if np.isfinite(
            heading["difference"]
        )
        else "Difference: unavailable"
    )

    # --------------------------------------------------------
    # Final interpretation
    # --------------------------------------------------------

    print("\n" + "=" * 100)
    print(
        "FINAL INTERPRETATION"
    )
    print("=" * 100)

    print(
        f"\nMeasured dominant phone vertical axis:"
        f" phone {'XYZ'[vertical_axis]}"
        f" (measured gravity sign {vertical_sign:+d})"
    )

    print(
        "\nRecommended acceleration-frame candidate:"
    )

    print(
        f"  {best_accel['mapping']}"
    )

    print(
        "\nBest independent gyro yaw candidate:"
    )

    print(
        f"  {best_gyro['description']}"
    )

    if (
        best_gyro["label"]
        != "GYROSCOPE Yaw"
    ):

        print(
            "\nIMPORTANT WARNING:"
        )

        print(
            "The strongest VBOX yaw relationship is NOT the"
        )

        print(
            "column named GYROSCOPE Yaw."
        )

        print(
            "Therefore the gyro column labels must NOT be treated"
        )

        print(
            "as ordinary physical XYZ axes without further evidence."
        )

    print(
        "\nGyro roll/pitch mapping is intentionally NOT guessed."
    )

    print(
        "There is no direct VBOX roll-rate/pitch-rate ground truth."
    )

    print(
        "\nDo NOT change ins_mechanization.py until these results"
    )

    print(
        "are reviewed together with the 60-second/600-second behavior."
    )

    print("\n" + "=" * 100)
    print(
        "DIAGNOSTIC COMPLETE — NO FILES MODIFIED"
    )
    print("=" * 100)


def main() -> None:
    # Keep direct-file invocation compatible while sharing one diagnostic method.
    if not __package__:
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from training.frame_audit import main as validated_main
    validated_main(default_sequences=["m"])



if __name__ == "__main__":
    main()
