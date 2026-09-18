"""
training/diagnose_body_frame.py
============================================================

IDR Phase 1B — Final Phone/Vehicle Body-Frame Diagnostic

Purpose
-------
The first classical INS test produced a physically impossible trajectory.
The previous attitude diagnostic established:

    - accelerometer - gravity is the correct gravity-removal sign
    - phone +Z is approximately vertical
    - the CSV gyro labels Roll/Pitch/Yaw must not be assumed to be
      physical body X/Y/Z
    - smartphone GPS bearing and OS ORIENTATION yaw are not reliable
      substitutes for vehicle heading

This diagnostic therefore does NOT modify ins_mechanization.py.

It performs three complementary analyses:

A. GYRO AXIS SEARCH
   ----------------
   Exhaustively test all 48 signed axis permutations. For each candidate,
   map the raw gyro triad to vehicle [X,Y,Z] and compare the mapped vehicle
   Z rate with VBOX Yaw Rate.

   Primary ranking uses zero-lag normalized RMSE. Correlation, regression
   slope/intercept, and a small lag sweep are also reported.

B. ACCELERATION / MOUNTING-YAW SEARCH
   -----------------------------------
   Use:

       f_linear = accelerometer - gravity

   Then remove the component along the measured gravity direction so that
   the horizontal acceleration is independent of the phone's small
   roll/pitch mounting error.

   For each candidate signed permutation, solve the best continuous
   horizontal mounting yaw angle against VBOX longitudinal/lateral
   acceleration. The angle is estimated analytically from the centered
   2-D vectors, then refined with a small local search.

C. JOINT / CONSISTENCY ANALYSIS
   -----------------------------
   Compare:
       - best gyro-only mapping
       - best acceleration-only mapping
       - best joint candidates

   A single physical mounting should normally induce one rigid-frame
   relationship. If gyro and acceleration strongly disagree, the script
   flags that rather than silently forcing a single mapping.

Important
---------
This script does not "declare" the winner to be physically true. It
produces evidence for review before the INS is modified.

The diagnostic is row-for-row against the synchronized S-M/V-M pair.
No gap repair or interpolation is performed.

Run:
    python training\\diagnose_body_frame.py --duration 60

Optional:
    python training\\diagnose_body_frame.py --duration 120 --top 15
"""

from __future__ import annotations

import argparse
import itertools
from dataclasses import dataclass
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
NOMINAL_DT = 1.0 / NOMINAL_HZ

DEFAULT_DURATION_S = 60.0
DEFAULT_TOP = 10

G0 = 9.80665

# Secondary lag diagnostic only: +/- 0.5 s.
LAG_SAMPLES = range(-5, 6)

# Local refinement around the analytic horizontal yaw estimate.
YAW_REFINE_DEG = 2.0
YAW_REFINE_STEP_DEG = 0.05


# ============================================================
# Data structures
# ============================================================

@dataclass
class Candidate:
    matrix: np.ndarray
    permutation: tuple[int, int, int]
    signs: tuple[int, int, int]

    mapping: str

    # Gyro metrics
    gyro_nrmse: float
    gyro_rmse: float
    gyro_corr: float
    gyro_slope: float
    gyro_intercept: float
    gyro_best_lag: int
    gyro_best_lag_corr: float

    # Acceleration metrics
    accel_nrmse: float
    accel_rmse: float
    accel_corr_long: float
    accel_corr_lat: float
    accel_slope_long: float
    accel_slope_lat: float
    accel_intercept_long: float
    accel_intercept_lat: float
    best_yaw_deg: float

    # Gravity / handedness
    det: float
    gravity_alignment: float

    # Joint score
    joint_score: float


# ============================================================
# Column utilities
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
    column: str,
) -> np.ndarray:

    return safe_numeric(df[column])


# ============================================================
# Statistics
# ============================================================

def finite_pairs(
    a: np.ndarray,
    b: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:

    mask = (
        np.isfinite(a)
        & np.isfinite(b)
    )

    return a[mask], b[mask]


def correlation(
    a: np.ndarray,
    b: np.ndarray,
) -> float:

    a, b = finite_pairs(a, b)

    if len(a) < 100:
        return float("nan")

    if (
        np.std(a) < 1e-12
        or np.std(b) < 1e-12
    ):
        return float("nan")

    return float(
        np.corrcoef(a, b)[0, 1]
    )


def regression(
    reference: np.ndarray,
    candidate: np.ndarray,
) -> tuple[float, float]:

    reference, candidate = finite_pairs(
        reference,
        candidate,
    )

    if len(reference) < 100:
        return float("nan"), float("nan")

    x = reference - np.mean(reference)
    y = candidate - np.mean(candidate)

    denominator = np.dot(x, x)

    if denominator < 1e-12:
        return float("nan"), float("nan")

    slope = float(
        np.dot(x, y) / denominator
    )

    intercept = float(
        np.mean(candidate)
        - slope * np.mean(reference)
    )

    return slope, intercept


def rmse(
    a: np.ndarray,
    b: np.ndarray,
) -> float:

    a, b = finite_pairs(a, b)

    if len(a) == 0:
        return float("nan")

    return float(
        np.sqrt(
            np.mean(
                (a - b) ** 2
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

    reference, _ = finite_pairs(
        reference,
        candidate,
    )

    if len(reference) == 0:
        return float("nan")

    scale = float(
        np.std(reference)
    )

    if scale < 1e-12:
        return float("nan")

    return value / scale


# ============================================================
# Lag search
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


def best_lag_corr(
    candidate: np.ndarray,
    reference: np.ndarray,
) -> tuple[int, float]:

    best_lag = 0
    best_corr = float("nan")
    best_abs = -1.0

    for lag in LAG_SAMPLES:

        a, b = shifted_pair(
            candidate,
            reference,
            lag,
        )

        c = correlation(
            a,
            b,
        )

        if not np.isfinite(c):
            continue

        if abs(c) > best_abs:

            best_abs = abs(c)
            best_corr = c
            best_lag = lag

    return best_lag, best_corr


# ============================================================
# Candidate mappings
# ============================================================

def all_signed_permutations():
    """
    The 24 proper signed permutation matrices; reflections are excluded.

    M maps raw phone-frame vector -> candidate vehicle-frame vector:

        v_vehicle = M @ v_phone
    """

    for permutation in itertools.permutations(
        range(3)
    ):

        for signs in itertools.product(
            (-1, 1),
            repeat=3,
        ):

            matrix = np.zeros(
                (3, 3),
                dtype=float,
            )

            for row, (column, sign) in enumerate(
                zip(permutation, signs)
            ):

                matrix[row, column] = sign

            if np.linalg.det(matrix) < 0:
                continue
            yield (
                matrix,
                permutation,
                signs,
            )


def axis_name(
    axis: int,
) -> str:

    return (
        ["X", "Y", "Z"][axis]
    )


def describe_mapping(
    permutation: tuple[int, int, int],
    signs: tuple[int, int, int],
) -> str:

    vehicle_names = [
        "Vx(forward)",
        "Vy(lateral)",
        "Vz(up)",
    ]

    parts = []

    for vehicle_axis in range(3):

        sign = (
            "+"
            if signs[vehicle_axis] > 0
            else "-"
        )

        phone_axis = axis_name(
            permutation[vehicle_axis]
        )

        parts.append(
            f"{vehicle_names[vehicle_axis]}={sign}{phone_axis}"
        )

    return " | ".join(parts)


# ============================================================
# Horizontal acceleration rotation
# ============================================================

def best_horizontal_yaw(
    horizontal_phone: np.ndarray,
    reference_vehicle: np.ndarray,
) -> tuple[float, float, float, float, float, float, float]:
    """
    Estimate the horizontal mounting yaw angle.

    horizontal_phone:
        N x 2 array [x, y]

    reference_vehicle:
        N x 2 array [longitudinal, lateral]

    The vectors are centered first, so constant offsets do not dominate
    the orientation estimate.

    Returns:
        yaw_deg,
        rmse,
        corr_long,
        corr_lat,
        slope_long,
        slope_lat,
        combined_nrmse
    """

    p = np.asarray(
        horizontal_phone,
        dtype=float,
    )

    r = np.asarray(
        reference_vehicle,
        dtype=float,
    )

    mask = np.isfinite(
        p
    ).all(axis=1) & np.isfinite(
        r
    ).all(axis=1)

    p = p[mask]
    r = r[mask]

    if len(p) < 100:
        return (
            float("nan"),
            float("nan"),
            float("nan"),
            float("nan"),
            float("nan"),
            float("nan"),
            float("nan"),
        )

    # Remove means before orientation estimation.
    p0 = p - np.mean(
        p,
        axis=0,
        keepdims=True,
    )

    r0 = r - np.mean(
        r,
        axis=0,
        keepdims=True,
    )

    # Complex representation:
    # p = x + i*y
    # Multiplying by exp(i*theta) performs a 2-D rotation.
    z_phone = (
        p0[:, 0]
        + 1j * p0[:, 1]
    )

    z_ref = (
        r0[:, 0]
        + 1j * r0[:, 1]
    )

    phase = np.angle(
        np.sum(
            z_ref
            * np.conjugate(
                z_phone
            )
        )
    )

    yaw_deg = float(
        np.degrees(phase)
    )

    # Refine analytically derived angle around a small interval.
    candidates = np.arange(
        yaw_deg - YAW_REFINE_DEG,
        yaw_deg + YAW_REFINE_DEG + YAW_REFINE_STEP_DEG / 2.0,
        YAW_REFINE_STEP_DEG,
    )

    best = None

    for candidate_deg in candidates:

        theta = np.radians(
            candidate_deg
        )

        c = np.cos(theta)
        s = np.sin(theta)

        rotated = np.column_stack(
            (
                c * p[:, 0]
                - s * p[:, 1],

                s * p[:, 0]
                + c * p[:, 1],
            )
        )

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

        # Allow independent scalar gains when evaluating axis agreement.
        # This prevents sensor scale differences from hiding a good axis.
        long_pred = rotated[:, 0]
        lat_pred = rotated[:, 1]

        rmse_long = rmse(
            long_pred,
            r[:, 0],
        )

        rmse_lat = rmse(
            lat_pred,
            r[:, 1],
        )

        ref_long_std = np.std(
            r[:, 0]
        )
        ref_lat_std = np.std(
            r[:, 1]
        )

        nrmse_long = (
            rmse_long / ref_long_std
            if ref_long_std > 1e-12
            else np.inf
        )

        nrmse_lat = (
            rmse_lat / ref_lat_std
            if ref_lat_std > 1e-12
            else np.inf
        )

        score = (
            nrmse_long
            + nrmse_lat
        )

        if (
            best is None
            or score < best[0]
        ):

            best = (
                score,
                candidate_deg,
                np.sqrt(
                    np.mean(
                        (
                            rotated
                            - r
                        ) ** 2
                    )
                ),
                corr_long,
                corr_lat,
                slope_long,
                slope_lat,
            )

    assert best is not None

    (
        combined_nrmse,
        yaw_deg,
        accel_rmse,
        corr_long,
        corr_lat,
        slope_long,
        slope_lat,
    ) = best

    return (
        float(yaw_deg),
        float(accel_rmse),
        float(corr_long),
        float(corr_lat),
        float(slope_long),
        float(slope_lat),
        float(combined_nrmse),
    )


# ============================================================
# Signal preparation
# ============================================================

def load_data(
    duration_s: float,
    data_root: str | Path | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame]:

    _, SMARTPHONE_FILE, VBOX_FILE = sequence_paths(data_root)

    if not SMARTPHONE_FILE.exists():

        raise FileNotFoundError(
            f"Smartphone file not found:\n"
            f"{SMARTPHONE_FILE}"
        )

    if not VBOX_FILE.exists():

        raise FileNotFoundError(
            f"VBOX file not found:\n"
            f"{VBOX_FILE}"
        )

    s = pd.read_csv(
        SMARTPHONE_FILE,
        encoding="cp1252",
    )

    v = pd.read_csv(
        VBOX_FILE,
        encoding="utf-8",
    )

    if len(s) != len(v):

        raise ValueError(
            "S-M.csv and V-M.csv must have equal row counts."
        )

    date_col = find_exact_column(
        s,
        "DATE (YYYY-MO-DD HH-MI-SS_SSS)",
    )

    timestamps = pd.to_datetime(
        s[date_col]
        .astype(str)
        .str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    if timestamps.isna().any():

        raise ValueError(
            f"{timestamps.isna().sum()} DATE values "
            "failed to parse."
        )

    elapsed = (
        timestamps
        - timestamps.iloc[0]
    ).dt.total_seconds()

    mask = (
        elapsed <= duration_s
    )

    s = s.loc[
        mask
    ].reset_index(drop=True)

    v = v.iloc[
        :len(s)
    ].reset_index(drop=True)

    return s, v


def extract_signals(
    s: pd.DataFrame,
    v: pd.DataFrame,
) -> dict[str, np.ndarray]:

    # Raw phone accelerometer.
    accel = np.column_stack(
        [
            numeric(
                s,
                find_exact_column(
                    s,
                    "ACCELEROMETER X (m/s²)",
                ),
            ),
            numeric(
                s,
                find_exact_column(
                    s,
                    "ACCELEROMETER Y (m/s²)",
                ),
            ),
            numeric(
                s,
                find_exact_column(
                    s,
                    "ACCELEROMETER Z (m/s²)",
                ),
            ),
        ]
    )

    # Supplied phone gravity.
    gravity = np.column_stack(
        [
            numeric(
                s,
                find_exact_column(
                    s,
                    "GRAVITY X (m/s²)",
                ),
            ),
            numeric(
                s,
                find_exact_column(
                    s,
                    "GRAVITY Y (m/s²)",
                ),
            ),
            numeric(
                s,
                find_exact_column(
                    s,
                    "GRAVITY Z (m/s²)",
                ),
            ),
        ]
    )

    # IMPORTANT:
    # This preserves the physical CSV order exactly as stored.
    # No Roll/Pitch/Yaw meaning is imposed here.
    gyro = np.column_stack(
        [
            numeric(
                s,
                find_exact_column(
                    s,
                    "GYROSCOPE Roll (rad/s)",
                ),
            ),
            numeric(
                s,
                find_exact_column(
                    s,
                    "GYROSCOPE Pitch (rad/s)",
                ),
            ),
            numeric(
                s,
                find_exact_column(
                    s,
                    "GYROSCOPE Yaw (rad/s)",
                ),
            ),
        ]
    )

    vbox_yaw = numeric(
        v,
        find_exact_column(
            v,
            "Yaw Rate (deg/sec)",
        ),
    )

    vbox_long = (
        numeric(
            v,
            find_exact_column(
                v,
                "Indicated Longitudinal Acceleration (g)",
            ),
        )
        * G0
    )

    vbox_lat = (
        numeric(
            v,
            find_exact_column(
                v,
                "Indicated Lateral Acceleration (g)",
            ),
        )
        * G0
    )

    return {
        "accel": accel,
        "gravity": gravity,
        "linear_accel": (
            accel - gravity
        ),
        "gyro": gyro,
        "vbox_yaw": vbox_yaw,
        "vbox_long": vbox_long,
        "vbox_lat": vbox_lat,
    }


# ============================================================
# Gravity / timing diagnostics
# ============================================================

def print_basic_diagnostics(
    s: pd.DataFrame,
    signals: dict[str, np.ndarray],
) -> np.ndarray:

    date_col = find_exact_column(
        s,
        "DATE (YYYY-MO-DD HH-MI-SS_SSS)",
    )

    timestamps = pd.to_datetime(
        s[date_col].astype(str).str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    dt = (
        timestamps
        .diff()
        .dt.total_seconds()
        .to_numpy()
    )[1:]

    unusual = dt[
        dt > 0.25
    ]

    accel = signals["accel"]
    gravity = signals["gravity"]

    minus = np.linalg.norm(
        accel - gravity,
        axis=1,
    )

    plus = np.linalg.norm(
        accel + gravity,
        axis=1,
    )

    g0 = np.mean(
        gravity[: min(10, len(gravity))],
        axis=0,
    )

    gnorm = np.linalg.norm(
        g0
    )

    print("\n" + "=" * 100)
    print("TIMING / GRAVITY SANITY")
    print("=" * 100)

    print(
        f"DATE mean interval   : {np.mean(dt):.6f} s"
    )

    print(
        f"DATE median interval : {np.median(dt):.6f} s"
    )

    print(
        f"DATE min interval    : {np.min(dt):.6f} s"
    )

    print(
        f"DATE max interval    : {np.max(dt):.6f} s"
    )

    print(
        f"DATE gaps > 0.25 s   : {len(unusual)}"
    )

    for index, value in enumerate(
        dt
    ):

        if value > 0.25:

            print(
                f"  after row {index}: "
                f"{value:.3f} s"
            )

    print(
        f"\nInitial gravity vector: {g0}"
    )

    print(
        f"Initial gravity magnitude: "
        f"{gnorm:.6f} m/s²"
    )

    print(
        f"|accel - gravity| mean: "
        f"{np.mean(minus):.4f} m/s²"
    )

    print(
        f"|accel + gravity| mean: "
        f"{np.mean(plus):.4f} m/s²"
    )

    return (
        g0 / gnorm
    )


# ============================================================
# Candidate scoring
# ============================================================

def evaluate_candidate(
    matrix: np.ndarray,
    permutation: tuple[int, int, int],
    signs: tuple[int, int, int],
    signals: dict[str, np.ndarray],
    gravity_hat: np.ndarray,
) -> Candidate:
    raise RuntimeError("A single permutation cannot identify both acceleration axes and Euler-labelled gyro channels. Use training.frame_audit.")

    gyro = (
        matrix
        @ signals["gyro"].T
    ).T

    accel = (
        matrix
        @ signals["linear_accel"].T
    ).T

    # --------------------------------------------------------
    # Gyro yaw
    # --------------------------------------------------------

    yaw_candidate = np.degrees(
        gyro[:, 2]
    )

    gyro_corr = correlation(
        yaw_candidate,
        signals["vbox_yaw"],
    )

    gyro_slope, gyro_intercept = regression(
        signals["vbox_yaw"],
        yaw_candidate,
    )

    gyro_rmse = rmse(
        yaw_candidate,
        signals["vbox_yaw"],
    )

    gyro_nrmse = normalized_rmse(
        yaw_candidate,
        signals["vbox_yaw"],
    )

    best_lag, best_lag_corr_value = (
        best_lag_corr(
            yaw_candidate,
            signals["vbox_yaw"],
        )
    )

    # --------------------------------------------------------
    # Acceleration:
    #
    # matrix already defines candidate vehicle X/Y/Z.
    # Because yaw mounting is still unknown, solve an additional
    # continuous rotation in the horizontal vehicle plane.
    # --------------------------------------------------------

    reference = np.column_stack(
        [
            signals["vbox_long"],
            signals["vbox_lat"],
        ]
    )

    (
        yaw_deg,
        accel_rmse,
        corr_long,
        corr_lat,
        slope_long,
        slope_lat,
        accel_nrmse,
    ) = best_horizontal_yaw(
        accel[:, :2],
        reference,
    )

    # Intercepts at the final chosen yaw.
    if np.isfinite(yaw_deg):

        theta = np.radians(
            yaw_deg
        )

        c = np.cos(theta)
        ss = np.sin(theta)

        rotated = np.column_stack(
            [
                c * accel[:, 0]
                - ss * accel[:, 1],

                ss * accel[:, 0]
                + c * accel[:, 1],
            ]
        )

        _, intercept_long = regression(
            signals["vbox_long"],
            rotated[:, 0],
        )

        _, intercept_lat = regression(
            signals["vbox_lat"],
            rotated[:, 1],
        )

    else:

        intercept_long = float("nan")
        intercept_lat = float("nan")

    # --------------------------------------------------------
    # Gravity alignment
    # --------------------------------------------------------

    gravity_vehicle = (
        matrix
        @ (
            np.mean(
                signals["gravity"][: min(10, len(signals["gravity"]))],
                axis=0,
            )
        )
    )

    gravity_norm = np.linalg.norm(
        gravity_vehicle
    )

    if gravity_norm > 1e-12:

        gravity_alignment = float(
            np.dot(
                gravity_vehicle / gravity_norm,
                np.array(
                    [0.0, 0.0, -1.0]
                ),
            )
        )

    else:

        gravity_alignment = float("nan")

    det = float(
        np.linalg.det(matrix)
    )

    # --------------------------------------------------------
    # Joint score
    #
    # Correlation agreement is primary; normalized RMSE is a
    # secondary error measure. Gravity is a physical validity
    # constraint. This score is dimensionless.
    # --------------------------------------------------------

    corr_terms = [
        abs(gyro_corr)
        if np.isfinite(gyro_corr)
        else 0.0,

        abs(corr_long)
        if np.isfinite(corr_long)
        else 0.0,

        abs(corr_lat)
        if np.isfinite(corr_lat)
        else 0.0,
    ]

    correlation_score = np.mean(
        corr_terms
    )

    nrmse_terms = [
        gyro_nrmse
        if np.isfinite(gyro_nrmse)
        else 10.0,

        accel_nrmse
        if np.isfinite(accel_nrmse)
        else 10.0,
    ]

    nrmse_penalty = np.mean(
        np.clip(
            nrmse_terms,
            0.0,
            10.0,
        )
    )

    gravity_penalty = (
        0.5
        * (
            1.0
            - gravity_alignment
        )
        if np.isfinite(gravity_alignment)
        else 1.0
    )

    joint_score = float(
        correlation_score
        - 0.10 * nrmse_penalty
        - 0.15 * gravity_penalty
    )

    return Candidate(
        matrix=matrix,
        permutation=permutation,
        signs=signs,
        mapping=describe_mapping(
            permutation,
            signs,
        ),
        gyro_nrmse=float(gyro_nrmse),
        gyro_rmse=float(gyro_rmse),
        gyro_corr=float(gyro_corr),
        gyro_slope=float(gyro_slope),
        gyro_intercept=float(gyro_intercept),
        gyro_best_lag=int(best_lag),
        gyro_best_lag_corr=float(best_lag_corr_value),
        accel_nrmse=float(accel_nrmse),
        accel_rmse=float(accel_rmse),
        accel_corr_long=float(corr_long),
        accel_corr_lat=float(corr_lat),
        accel_slope_long=float(slope_long),
        accel_slope_lat=float(slope_lat),
        accel_intercept_long=float(intercept_long),
        accel_intercept_lat=float(intercept_lat),
        best_yaw_deg=float(yaw_deg),
        det=det,
        gravity_alignment=float(
            gravity_alignment
        ),
        joint_score=joint_score,
    )


# ============================================================
# Independent rankings
# ============================================================

def gyro_only_score(
    candidate: Candidate,
) -> float:

    if not np.isfinite(
        candidate.gyro_corr
    ):

        return -np.inf

    return (
        candidate.gyro_corr
        - 0.05
        * min(
            candidate.gyro_nrmse,
            10.0,
        )
    )


def accel_only_score(
    candidate: Candidate,
) -> float:

    values = [
        candidate.accel_corr_long,
        candidate.accel_corr_lat,
    ]

    values = [
        value
        for value in values
        if np.isfinite(value)
    ]

    if not values:
        return -np.inf

    return (
        np.mean(values)
        - 0.05
        * min(
            candidate.accel_nrmse,
            10.0,
        )
    )


# ============================================================
# Printing
# ============================================================

def print_ranked(
    title: str,
    candidates: list[Candidate],
    top: int,
    score_function,
) -> list[Candidate]:

    ranked = sorted(
        candidates,
        key=score_function,
        reverse=True,
    )

    print("\n" + "=" * 100)
    print(title)
    print("=" * 100)

    for rank, candidate in enumerate(
        ranked[:top],
        start=1,
    ):

        score = score_function(
            candidate
        )

        print(
            f"\n#{rank}  score={score:.5f}  "
            f"det={candidate.det:+.0f}  "
            f"gravity={candidate.gravity_alignment:+.4f}"
        )

        print(
            f"    {candidate.mapping}"
        )

        print(
            f"    gyro: "
            f"corr={candidate.gyro_corr:+.4f}, "
            f"slope={candidate.gyro_slope:+.4f}, "
            f"nRMSE={candidate.gyro_nrmse:.4f}, "
            f"best lag={candidate.gyro_best_lag:+d} "
            f"({candidate.gyro_best_lag_corr:+.4f})"
        )

        print(
            f"    accel: "
            f"yaw*={candidate.best_yaw_deg:+.3f} deg, "
            f"corr_long={candidate.accel_corr_long:+.4f}, "
            f"corr_lat={candidate.accel_corr_lat:+.4f}, "
            f"nRMSE={candidate.accel_nrmse:.4f}"
        )

    return ranked


# ============================================================
# Report
# ============================================================

def write_report(
    candidates: list[Candidate],
    gyro_ranked: list[Candidate],
    accel_ranked: list[Candidate],
    joint_ranked: list[Candidate],
    duration_s: float,
    row_count: int,
) -> Path:

    report_path = Path(
        "reports/diagnose_body_frame_report.md"
    )

    report_path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    best_joint = joint_ranked[0]

    gyro_best = gyro_ranked[0]
    accel_best = accel_ranked[0]

    lines = [
        "# IDR Phase 1B — Body-Frame Diagnostic",
        "",
        f"Window: {duration_s:.3f} s",
        f"Rows: {row_count:,}",
        "",
        "## Best joint candidate",
        "",
        f"- Mapping: `{best_joint.mapping}`",
        f"- Joint score: `{best_joint.joint_score:.6f}`",
        f"- det(M): `{best_joint.det:+.0f}`",
        f"- Gravity alignment: `{best_joint.gravity_alignment:+.6f}`",
        f"- Gyro yaw correlation: `{best_joint.gyro_corr:+.6f}`",
        f"- Gyro yaw slope: `{best_joint.gyro_slope:+.6f}`",
        f"- Gyro yaw normalized RMSE: `{best_joint.gyro_nrmse:.6f}`",
        f"- Best diagnostic lag: `{best_joint.gyro_best_lag:+d}` samples",
        f"- Best horizontal yaw: `{best_joint.best_yaw_deg:+.6f}` deg",
        f"- Longitudinal correlation: `{best_joint.accel_corr_long:+.6f}`",
        f"- Lateral correlation: `{best_joint.accel_corr_lat:+.6f}`",
        f"- Acceleration normalized RMSE: `{best_joint.accel_nrmse:.6f}`",
        "",
        "## Independent searches",
        "",
        f"- Best gyro-only: `{gyro_best.mapping}`",
        f"- Best acceleration-only: `{accel_best.mapping}`",
        "",
        "The independent results must be reviewed before forcing one shared",
        "mapping into the INS.",
        "",
        "## Top joint candidates",
        "",
        "| Rank | Joint | det | Gravity | Gyro corr | Long corr | Lat corr | Yaw* (deg) | Mapping |",
        "|---:|---:|---:|---:|---:|---:|---:|---:|---|",
    ]

    for rank, candidate in enumerate(
        joint_ranked[:10],
        start=1,
    ):

        lines.append(
            "| "
            f"{rank} | "
            f"{candidate.joint_score:.5f} | "
            f"{candidate.det:+.0f} | "
            f"{candidate.gravity_alignment:+.4f} | "
            f"{candidate.gyro_corr:+.4f} | "
            f"{candidate.accel_corr_long:+.4f} | "
            f"{candidate.accel_corr_lat:+.4f} | "
            f"{candidate.best_yaw_deg:+.2f} | "
            f"{candidate.mapping} |"
        )

    lines += [
        "",
        "## Interpretation rules",
        "",
        "- A strong candidate should score well on gyro yaw agreement AND",
        "  horizontal acceleration agreement, not just one signal.",
        "- det(M)=+1 is a proper rotation; det(M)=-1 is a reflection.",
        "- Gravity alignment near +1 means transformed physical gravity points",
        "  approximately downward under the vehicle +Z=Up convention.",
        "- The reported lag is diagnostic only. It is NOT applied to the data.",
        "- The reported yaw* is a mounting-orientation estimate, not a command",
        "  to modify the synchronization timeline.",
        "",
        "## Important caution",
        "",
        "The numerical winner is evidence, not automatic physical truth.",
        "The final mapping must also be consistent with the dataset's physical",
        "mounting geometry and with the subsequent attitude-propagation test.",
    ]

    report_path.write_text(
        "\n".join(lines),
        encoding="utf-8",
    )

    return report_path


# ============================================================
# Main
# ============================================================

def legacy_main() -> None:
    raise RuntimeError("Historical diagnostic retired; use main() or training.frame_audit for validated methodology.")

    parser = argparse.ArgumentParser(
        description=(
            "Exhaustively diagnose IO-VNBD phone->vehicle body-frame "
            "axis/sign mappings."
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
        default=DEFAULT_TOP,
        help="Number of candidates to print.",
    )

    add_dataset_argument(parser)
    parser.add_argument("--no-write", action="store_true", help="Print results without writing reports or plots.")
    args = parser.parse_args()
    root, s_file, v_file = m_pair_from_args(parser, args)

    if args.duration <= 0:
        raise ValueError(
            "--duration must be greater than zero."
        )

    if args.top <= 0:
        raise ValueError(
            "--top must be greater than zero."
        )

    print("=" * 100)
    print(
        "IDR PHASE 1B — FINAL EXHAUSTIVE BODY-FRAME DIAGNOSTIC"
    )
    print(
        "READ-ONLY — S-M/V-M are compared row-for-row; "
        "no gap repair or INS changes"
    )
    print("=" * 100)

    print(
        f"\nDataset root: {root}"
    )

    s, v = load_data(
        args.duration,
        data_root=root,
    )

    print(
        f"\nRows loaded:"
        f"\n  Smartphone: {len(s):,}"
        f"\n  VBOX:       {len(v):,}"
    )

    print(
        f"Window: "
        f"{(len(s) - 1) * NOMINAL_DT:.3f} s"
    )

    signals = extract_signals(
        s,
        v,
    )

    gravity_hat = (
        print_basic_diagnostics(
            s,
            signals,
        )
    )

    candidates = []

    matrices = list(
        all_signed_permutations()
    )

    if len(matrices) != 48:
        raise RuntimeError(
            f"Expected 48 mappings; got {len(matrices)}."
        )

    print("\n" + "=" * 100)
    print("EVALUATING ALL 48 SIGNED AXIS MAPPINGS")
    print("=" * 100)

    for index, (
        matrix,
        permutation,
        signs,
    ) in enumerate(
        matrices,
        start=1,
    ):

        candidates.append(
            evaluate_candidate(
                matrix,
                permutation,
                signs,
                signals,
                gravity_hat,
            )
        )

        print(
            f"\rEvaluated {index}/48",
            end="",
            flush=True,
        )

    print()

    gyro_ranked = print_ranked(
        "GYRO-ONLY RANKING",
        candidates,
        args.top,
        gyro_only_score,
    )

    accel_ranked = print_ranked(
        "ACCELERATION-ONLY RANKING",
        candidates,
        args.top,
        accel_only_score,
    )

    joint_ranked = print_ranked(
        "JOINT RANKING",
        candidates,
        args.top,
        lambda c: c.joint_score,
    )

    print("\n" + "=" * 100)
    print("CONSISTENCY CHECK")
    print("=" * 100)

    gyro_best = gyro_ranked[0]
    accel_best = accel_ranked[0]
    joint_best = joint_ranked[0]

    same_gyro = np.allclose(
        gyro_best.matrix,
        joint_best.matrix,
    )

    same_accel = np.allclose(
        accel_best.matrix,
        joint_best.matrix,
    )

    print(
        f"Best gyro-only == best joint: "
        f"{'YES' if same_gyro else 'NO'}"
    )

    print(
        f"Best accel-only == best joint: "
        f"{'YES' if same_accel else 'NO'}"
    )

    if (
        not same_gyro
        or not same_accel
    ):

        print(
            "\nWARNING:"
        )

        print(
            "Independent gyro and acceleration searches disagree "
            "with the joint winner."
        )

        print(
            "Do NOT blindly force one shared mapping into the INS."
        )

        print(
            "Review the top candidates and the dataset's frame conventions."
        )

    else:

        print(
            "\nThe strongest independent and joint candidates agree."
        )

    if not args.no_write:
        report = write_report(
            candidates,
            gyro_ranked,
            accel_ranked,
            joint_ranked,
            args.duration,
            len(s),
        )

        print(
            f"\nReport saved to:\n{report}"
        )

    print("\n" + "=" * 100)
    print("DIAGNOSTIC COMPLETE")
    print("=" * 100)
    print(
        "No dataset files or mechanization code were modified."
    )
    print(
        "Do not change ins_mechanization.py until the results are reviewed."
    )


def main() -> None:
    # Keep direct-file invocation compatible while sharing one diagnostic method.
    if not __package__:
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from training.frame_audit import main as validated_main
    validated_main(default_sequences=["m"])



if __name__ == "__main__":
    main()
