"""
training/ins_mechanization.py
============================================================

IDR Phase 1 — Classical Strapdown INS Baseline
IO-VNBD synchronized sequence M (Driver B)

Purpose
-------
Produce a transparent classical inertial-navigation baseline from the
smartphone IMU:

    accelerometer
        ↓
    gravity removal
        ↓
    initial alignment
        ↓
    gyro-based attitude propagation
        ↓
    body → local ENU transformation
        ↓
    velocity integration
        ↓
    position integration

The resulting raw INS trajectory is compared against the VBOX/reference
trajectory from V-M.csv.

This is deliberately a PRE-AI baseline.

OUT OF SCOPE
------------
- EKF / UKF
- AI/ML correction
- map matching
- GNSS/INS fusion
- dynamic phone-to-vehicle calibration
- continuous recalibration
- magnetometer propagation
- Earth rotation / Coriolis modelling

IMPORTANT PHASE-1 INTERPRETATION
--------------------------------
The synchronized IO-VNBD smartphone stream contains 105,974 samples and
is nominally 10 Hz. The smartphone DATE field contains two longer gaps:

    1.158 s
    1.365 s

The excess duration of those two gaps is exactly:

    (1.158 - 0.1) + (1.365 - 0.1) = 2.323 s

which equals the difference between the smartphone and VBOX recording
durations.

Therefore this Phase-1 synchronized-sequence implementation:

1. preserves the original raw DATE field for diagnostics,
2. logs anomalous DATE gaps,
3. uses the synchronized nominal 10-Hz sample index as the mechanization
   clock.

This preserves the original row count and maintains the S-M <-> V-M
synchronized pairing without inventing new samples.

INITIAL ALIGNMENT
-----------------
A short initial alignment is required because gyro integration provides
only relative attitude.

For Phase 1:

- initial heading is obtained from the first smartphone GPS displacement
  large enough to give a meaningful course;
- initial leveling uses the dataset-supplied gravity vector;
- initial velocity is seeded from the VBOX row-0 speed and heading.

The VBOX initial velocity is explicitly an EVALUATION/BASELINE initialization
aid. It is not an input used by the final mobile system during navigation.

GYRO MODEL
----------
The smartphone gyro columns are mapped into body axes as:

    body X = GYROSCOPE Roll
    body Y = GYROSCOPE Pitch
    body Z = GYROSCOPE Yaw

The rates are propagated through a true 3-D rotation representation using
SciPy Rotation, rather than integrating Euler angles independently.

GRAVITY
-------
The dataset supplies GRAVITY X/Y/Z. These are subtracted directly from the
raw accelerometer values before body→navigation transformation.

The resulting quantity is treated as linear acceleration in the phone
body frame.

GPS USAGE
---------
GPS is NOT used inside the INS propagation loop.

GPS is used only for:

- local reference-frame construction,
- initial heading alignment,
- VBOX/reference trajectory comparison.

Outputs
-------
reports/position_plots/M_raw_ins_vs_vbox.png
reports/phase1_metrics.md
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

if __package__:
    from .common import add_dataset_argument, m_pair_from_args, sequence_paths
else:
    from common import add_dataset_argument, m_pair_from_args, sequence_paths
from scipy.spatial.transform import Rotation


# ============================================================
# Configuration
# ============================================================

REPORT_DIR = Path("reports")
PLOT_DIR = REPORT_DIR / "position_plots"

PLOT_FILE = (
    PLOT_DIR
    / "M_raw_ins_vs_vbox.png"
)

METRICS_FILE = (
    REPORT_DIR
    / "phase1_metrics.md"
)


# Nominal smartphone sensing rate.
NOMINAL_HZ = 10.0
NOMINAL_DT = 1.0 / NOMINAL_HZ

# Initial attitude averaging window.
INITIAL_ATTITUDE_SECONDS = 1.0


# DATE gaps greater than this are logged.
DATE_GAP_THRESHOLD = 0.25

# Stationary detection thresholds.
STATIONARY_ACCEL_THRESHOLD = 0.20       # m/s²
STATIONARY_GYRO_THRESHOLD = 0.035       # rad/s
STATIONARY_MIN_SECONDS = 1.0

# Minimum GPS displacement needed for initial course.
MIN_HEADING_DISPLACEMENT_M = 5.0

EARTH_RADIUS_M = 6_371_000.0

# ============================================================
# Dataclasses
# ============================================================

@dataclass
class SmartphoneData:
    timestamps: pd.Series
    t_nominal: np.ndarray
    date_gaps: list[tuple[int, float]]

    acceleration: np.ndarray
    gravity: np.ndarray
    gyro_body: np.ndarray

    gps_lat: np.ndarray
    gps_lon: np.ndarray
    gps_speed_kmh: np.ndarray


@dataclass
class ReferenceData:
    latitude: np.ndarray
    longitude: np.ndarray
    velocity_kmh: np.ndarray
    heading_deg: np.ndarray
    position_enu: np.ndarray


@dataclass
class INSResult:
    position_enu: np.ndarray
    velocity_enu: np.ndarray
    acceleration_enu: np.ndarray
    rotations: list[Rotation]


# ============================================================
# Column resolution
# ============================================================

def find_column(
    df: pd.DataFrame,
    keywords: tuple[str, ...],
    *,
    exact: str | None = None,
) -> str:
    """
    Resolve a dataset column robustly.

    Exact matching is preferred. Otherwise all keywords must appear
    in the normalized column name.
    """

    normalized_columns = {
        str(column).strip().lower(): column
        for column in df.columns
    }

    if exact is not None:
        exact_key = exact.strip().lower()

        if exact_key in normalized_columns:
            return normalized_columns[exact_key]

        raise KeyError(
            f"Exact column not found: {exact!r}\n"
            f"Available columns:\n{list(df.columns)}"
        )

    matches = []

    for column in df.columns:
        normalized = str(column).strip().lower()

        if all(
            keyword.strip().lower() in normalized
            for keyword in keywords
        ):
            matches.append(column)

    if not matches:
        raise KeyError(
            f"No column matching {keywords}.\n"
            f"Available columns:\n{list(df.columns)}"
        )

    if len(matches) > 1:
        raise KeyError(
            f"Ambiguous column match for {keywords}:\n"
            + "\n".join(
                f"  {repr(column)}"
                for column in matches
            )
        )

    return matches[0]

# ============================================================
# Loading
# ============================================================

def load_raw_files(data_root: str | Path | None = None) -> tuple[pd.DataFrame, pd.DataFrame]:
    _, SMARTPHONE_FILE, REFERENCE_FILE = sequence_paths(data_root)

    if not SMARTPHONE_FILE.exists():
        raise FileNotFoundError(
            f"Smartphone file not found:\n{SMARTPHONE_FILE}"
        )

    if not REFERENCE_FILE.exists():
        raise FileNotFoundError(
            f"Reference file not found:\n{REFERENCE_FILE}"
        )

    smartphone = pd.read_csv(
        SMARTPHONE_FILE,
        encoding="cp1252",
    )

    reference = pd.read_csv(
        REFERENCE_FILE,
        encoding="utf-8",
    )

    if len(smartphone) != len(reference):
        raise ValueError(
            "S-M.csv and V-M.csv do not contain the same number of rows."
        )

    return smartphone, reference


# ============================================================
# Local ENU projection
# ============================================================

def latlon_to_enu(
    latitude: np.ndarray,
    longitude: np.ndarray,
    latitude0: float,
    longitude0: float,
) -> np.ndarray:
    """
    Flat local tangent-plane approximation.

    Output:
        columns = [east, north, up] in meters
    """

    latitude = np.asarray(latitude, dtype=float)
    longitude = np.asarray(longitude, dtype=float)

    lat0_rad = np.radians(latitude0)

    north = (
        np.radians(latitude - latitude0)
        * EARTH_RADIUS_M
    )

    east = (
        np.radians(longitude - longitude0)
        * EARTH_RADIUS_M
        * np.cos(lat0_rad)
    )

    up = np.zeros_like(east)

    return np.column_stack(
        (east, north, up)
    )


# ============================================================
# Haversine distance
# ============================================================

def haversine_m(
    lat1: float,
    lon1: float,
    lat2: float,
    lon2: float,
) -> float:

    lat1_rad = np.radians(lat1)
    lon1_rad = np.radians(lon1)

    lat2_rad = np.radians(lat2)
    lon2_rad = np.radians(lon2)

    dlat = lat2_rad - lat1_rad
    dlon = lon2_rad - lon1_rad

    a = (
        np.sin(dlat / 2.0) ** 2
        + np.cos(lat1_rad)
        * np.cos(lat2_rad)
        * np.sin(dlon / 2.0) ** 2
    )

    return float(
        2.0
        * EARTH_RADIUS_M
        * np.arcsin(np.sqrt(a))
    )


# ============================================================
# Smartphone loading
# ============================================================

def load_smartphone(
    df: pd.DataFrame,
) -> SmartphoneData:

    date_col = find_column(
        df,
        ("date",),
    )

    acc_x = find_column(
        df,
        ("accelerometer", "x"),
        exact="ACCELEROMETER X (m/s²)",
    )

    acc_y = find_column(
        df,
        ("accelerometer", "y"),
        exact="ACCELEROMETER Y (m/s²)",
    )

    acc_z = find_column(
        df,
        ("accelerometer", "z"),
        exact="ACCELEROMETER Z (m/s²)",
    )

    gravity_x = find_column(
        df,
        ("gravity", "x"),
        exact="GRAVITY X (m/s²)",
    )

    gravity_y = find_column(
        df,
        ("gravity", "y"),
        exact="GRAVITY Y (m/s²)",
    )

    gravity_z = find_column(
        df,
        ("gravity", "z"),
        exact="GRAVITY Z (m/s²)",
    )

    gyro_yaw = find_column(
        df,
        ("gyroscope", "yaw"),
        exact="GYROSCOPE Yaw (rad/s)",
    )

    gyro_pitch = find_column(
        df,
        ("gyroscope", "pitch"),
        exact="GYROSCOPE Pitch (rad/s)",
    )

    gyro_roll = find_column(
        df,
        ("gyroscope", "roll"),
        exact="GYROSCOPE Roll (rad/s)",
    )

    gps_lat = find_column(
        df,
        ("gps", "latitude"),
        exact="GPS LATITUDE (degrees)",
    )

    gps_lon = find_column(
        df,
        ("gps", "longitude"),
        exact="GPS LONGITUDE (degrees)",
    )

    gps_speed = find_column(
        df,
        ("gps", "speed"),
        exact="GPS SPEED (Kmh)",
    )

    # --------------------------------------------------------
    # Parse DATE
    # --------------------------------------------------------

    timestamps = pd.to_datetime(
        df[date_col]
        .astype(str)
        .str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )

    if timestamps.isna().any():

        bad_count = int(
            timestamps.isna().sum()
        )

        raise ValueError(
            f"{bad_count} smartphone timestamps failed to parse."
        )

    raw_elapsed = (
        timestamps
        - timestamps.iloc[0]
    ).dt.total_seconds().to_numpy()

    raw_dt = np.diff(raw_elapsed)

    date_gaps: list[tuple[int, float]] = []

    for index, dt_value in enumerate(raw_dt):

        if dt_value > DATE_GAP_THRESHOLD:

            date_gaps.append(
                (
                    index,
                    float(dt_value),
                )
            )

    # --------------------------------------------------------
    # IMPORTANT:
    #
    # Use the synchronized nominal 10-Hz row clock.
    #
    # This gives:
    # 105,974 samples
    # (105,974 - 1) * 0.1 = 10,597.3 seconds
    #
    # exactly matching the VBOX sequence duration.
    # --------------------------------------------------------

    n = len(df)

    t_nominal = (
        np.arange(n, dtype=float)
        * NOMINAL_DT
    )

    # --------------------------------------------------------
    # Sensor arrays
    # --------------------------------------------------------

    acceleration = df[
        [acc_x, acc_y, acc_z]
    ].to_numpy(dtype=float)

    gravity = df[
        [gravity_x, gravity_y, gravity_z]
    ].to_numpy(dtype=float)

    # Reorder raw gyro into body-axis order:
    #
    # body X = roll
    # body Y = pitch
    # body Z = yaw
    #
    gyro_body = df[
        [gyro_roll, gyro_pitch, gyro_yaw]
    ].to_numpy(dtype=float)

    latitude = pd.to_numeric(
        df[gps_lat],
        errors="coerce",
    ).to_numpy()

    longitude = pd.to_numeric(
        df[gps_lon],
        errors="coerce",
    ).to_numpy()

    speed_kmh = pd.to_numeric(
        df[gps_speed],
        errors="coerce",
    ).to_numpy()

    return SmartphoneData(
        timestamps=timestamps,
        t_nominal=t_nominal,
        date_gaps=date_gaps,
        acceleration=acceleration,
        gravity=gravity,
        gyro_body=gyro_body,
        gps_lat=latitude,
        gps_lon=longitude,
        gps_speed_kmh=speed_kmh,
    )


# ============================================================
# Reference loading
# ============================================================

def load_reference(
    df: pd.DataFrame,
    origin_lat: float,
    origin_lon: float,
) -> ReferenceData:

    latitude_col = find_column(
        df,
        ("latitude",),
        exact="Latitude (degrees)",
    )

    longitude_col = find_column(
        df,
        ("longitude",),
        exact="Longitude (degrees)",
    )

    velocity_col = find_column(
        df,
        ("velocity",),
        exact="Velocity (km/hr)",
    )

    heading_col = find_column(
        df,
        ("heading",),
        exact="Heading (degrees)",
    )

    latitude = pd.to_numeric(
        df[latitude_col],
        errors="coerce",
    ).to_numpy()

    longitude = pd.to_numeric(
        df[longitude_col],
        errors="coerce",
    ).to_numpy()

    velocity_kmh = pd.to_numeric(
        df[velocity_col],
        errors="coerce",
    ).to_numpy()

    heading_deg = pd.to_numeric(
        df[heading_col],
        errors="coerce",
    ).to_numpy()

    position_enu = latlon_to_enu(
        latitude,
        longitude,
        origin_lat,
        origin_lon,
    )

    return ReferenceData(
        latitude=latitude,
        longitude=longitude,
        velocity_kmh=velocity_kmh,
        heading_deg=heading_deg,
        position_enu=position_enu,
    )


# ============================================================
# Initial heading
# ============================================================

def estimate_initial_heading(
    latitude: np.ndarray,
    longitude: np.ndarray,
    *, allow_future: bool = False,
) -> float:
    """
    Estimate initial course from the first meaningful smartphone
    GPS displacement.

    Returns:
        heading in degrees clockwise from North.
    """

    lat0 = latitude[0]
    lon0 = longitude[0]

    if not allow_future:
        raise ValueError("Future GPS displacement cannot initialize time zero. Use CausalCalibration; legacy replay requires allow_future=True.")

    for i in range(1, len(latitude)):

        if not (
            np.isfinite(latitude[i])
            and np.isfinite(longitude[i])
        ):
            continue

        displacement = haversine_m(
            lat0,
            lon0,
            latitude[i],
            longitude[i],
        )

        if displacement >= MIN_HEADING_DISPLACEMENT_M:

            enu = latlon_to_enu(
                np.array(
                    [latitude[i]]
                ),
                np.array(
                    [longitude[i]]
                ),
                lat0,
                lon0,
            )[0]

            east = enu[0]
            north = enu[1]

            heading = np.degrees(
                np.arctan2(
                    east,
                    north,
                )
            )

            return float(
                heading % 360.0
            )

    raise RuntimeError(
        "Unable to obtain a meaningful initial GPS course."
    )


# ============================================================
# Initial attitude
# ============================================================

def build_initial_rotation(
    gravity_phone: np.ndarray,
    heading_deg: float,
) -> Rotation:
    """
    Build phone-body -> local ENU initial attitude.

    Assumption for Phase 1:
        phone +X = right
        phone +Y = forward
        phone +Z = up

    Gravity establishes leveling.

    GPS-derived course establishes horizontal heading.

    The measured phone forward axis is projected onto the horizontal
    plane defined by gravity so that small mounting pitch/roll does not
    contaminate the horizontal heading alignment.
    """

    gravity_norm = np.linalg.norm(
        gravity_phone
    )

    if gravity_norm < 1e-6:
        raise ValueError(
            "Gravity vector magnitude is too small "
            "for initial alignment."
        )

    # Gravity physically points downward.
    up_body = (
        -gravity_phone
        / gravity_norm
    )

    # Nominal phone forward axis.
    nominal_forward_body = np.array(
        [0.0, 1.0, 0.0]
    )

    # Project nominal forward axis onto plane perpendicular to up.
    forward_body = (
        nominal_forward_body
        - np.dot(
            nominal_forward_body,
            up_body,
        )
        * up_body
    )

    forward_norm = np.linalg.norm(
        forward_body
    )

    if forward_norm < 1e-6:
        raise ValueError(
            "Phone forward axis is nearly parallel "
            "to gravity; initial alignment failed."
        )

    forward_body /= forward_norm

    # Right-handed body basis.
    right_body = np.cross(
        forward_body,
        up_body,
    )

    right_body /= np.linalg.norm(
        right_body
    )

    # Re-orthogonalize forward.
    forward_body = np.cross(
        up_body,
        right_body,
    )

    # Desired ENU navigation basis.
    heading_rad = np.radians(
        heading_deg
    )

    forward_nav = np.array(
        [
            np.sin(heading_rad),
            np.cos(heading_rad),
            0.0,
        ]
    )

    up_nav = np.array(
        [
            0.0,
            0.0,
            1.0,
        ]
    )

    right_nav = np.cross(
        forward_nav,
        up_nav,
    )

    right_nav /= np.linalg.norm(
        right_nav
    )

    forward_nav = np.cross(
        up_nav,
        right_nav,
    )

    # Columns are body/nav basis vectors.
    body_basis = np.column_stack(
        [
            right_body,
            forward_body,
            up_body,
        ]
    )

    nav_basis = np.column_stack(
        [
            right_nav,
            forward_nav,
            up_nav,
        ]
    )

    # Body -> navigation.
    dcm_body_to_nav = (
        nav_basis
        @ body_basis.T
    )

    # Sanity checks.
    det = np.linalg.det(
        dcm_body_to_nav
    )

    if not np.isclose(
        det,
        1.0,
        atol=1e-5,
    ):
        raise ValueError(
            f"Initial rotation determinant is {det:.6f}, "
            "expected approximately +1."
        )

    return Rotation.from_matrix(
        dcm_body_to_nav
    )


# ============================================================
# Stationary detection and gyro bias
# ============================================================

def estimate_gyro_bias(
    acceleration: np.ndarray,
    gravity: np.ndarray,
    gyro: np.ndarray,
    t: np.ndarray,
) -> tuple[np.ndarray, int]:
    """
    Estimate gyro bias from IMU-only stationary periods.

    A sample is considered stationary when:

        ||accelerometer - gravity|| < threshold

    AND

        ||gyro|| < threshold

    Contiguous runs must last at least STATIONARY_MIN_SECONDS.
    """

    linear_acc = (
        acceleration
        - gravity
    )

    linear_acc_norm = np.linalg.norm(
        linear_acc,
        axis=1,
    )

    gyro_norm = np.linalg.norm(
        gyro,
        axis=1,
    )

    stationary = (
        linear_acc_norm
        < STATIONARY_ACCEL_THRESHOLD
    ) & (
        gyro_norm
        < STATIONARY_GYRO_THRESHOLD
    )

    min_samples = max(
        1,
        int(
            np.ceil(
                STATIONARY_MIN_SECONDS
                * NOMINAL_HZ
            )
        ),
    )

    stationary_samples: list[np.ndarray] = []

    run_start: int | None = None

    for index, is_stationary in enumerate(
        stationary
    ):

        if is_stationary and run_start is None:

            run_start = index

        elif (
            not is_stationary
            and run_start is not None
        ):

            run_length = (
                index
                - run_start
            )

            if run_length >= min_samples:

                stationary_samples.append(
                    gyro[
                        run_start:index
                    ]
                )

            run_start = None

    if run_start is not None:

        run_length = (
            len(stationary)
            - run_start
        )

        if run_length >= min_samples:

            stationary_samples.append(
                gyro[run_start:]
            )

    if not stationary_samples:

        print(
            "[BIAS] No stationary IMU windows found."
        )

        return np.zeros(3), 0

    bias_samples = np.vstack(
        stationary_samples
    )

    bias = np.mean(
        bias_samples,
        axis=0,
    )

    return (
        bias,
        len(bias_samples),
    )


# ============================================================
# Strapdown INS mechanization
# ============================================================

def run_ins(
    smartphone: SmartphoneData,
    initial_rotation: Rotation,
    initial_velocity_enu: np.ndarray,
    gyro_bias: np.ndarray,
) -> INSResult:
    """
    Classical strapdown INS propagation.

    The orientation is represented as:

        body -> navigation

    The gyro increment is applied as an incremental body-frame
    rotation.

    Gravity has already been removed from the accelerometer signal.
    """

    n = len(
        smartphone.t_nominal
    )

    position = np.zeros(
        (n, 3),
        dtype=float,
    )

    velocity = np.zeros(
        (n, 3),
        dtype=float,
    )

    acceleration_nav = np.zeros(
        (n, 3),
        dtype=float,
    )

    rotations: list[Rotation] = [
        initial_rotation
    ]

    velocity[0] = (
        initial_velocity_enu
    )

    rotation = initial_rotation

    linear_acc_body = (
        smartphone.acceleration
        - smartphone.gravity
    )

    for i in range(1, n):

        dt = (
            smartphone.t_nominal[i]
            - smartphone.t_nominal[i - 1]
        )

        # --------------------------------------------
        # Attitude propagation
        # --------------------------------------------

        omega_body = (
            smartphone.gyro_body[i - 1]
            - gyro_bias
        )

        delta_rotation = (
            Rotation.from_rotvec(
                omega_body * dt
            )
        )

        rotation = (
            rotation
            * delta_rotation
        )

        rotations.append(
            rotation
        )

        # --------------------------------------------
        # Body acceleration -> navigation
        # --------------------------------------------

        a_nav = rotation.apply(
            linear_acc_body[i]
        )

        acceleration_nav[i] = (
            a_nav
        )

        # --------------------------------------------
        # Velocity integration
        # --------------------------------------------

        velocity[i] = (
            velocity[i - 1]
            + a_nav * dt
        )

        # --------------------------------------------
        # Position integration
        # --------------------------------------------

        position[i] = (
            position[i - 1]
            + velocity[i - 1] * dt
            + 0.5 * a_nav * dt * dt
        )

    return INSResult(
        position_enu=position,
        velocity_enu=velocity,
        acceleration_enu=acceleration_nav,
        rotations=rotations,
    )


# ============================================================
# Metrics
# ============================================================

def compute_metrics(
    ins: INSResult,
    reference: ReferenceData,
) -> dict[str, float]:

    position_error = (
        ins.position_enu[:, :2]
        - reference.position_enu[:, :2]
    )

    position_error_norm = np.linalg.norm(
        position_error,
        axis=1,
    )

    reference_steps = np.linalg.norm(
        np.diff(
            reference.position_enu[:, :2],
            axis=0,
        ),
        axis=1,
    )

    total_distance = float(
        np.sum(reference_steps)
    )

    final_error = float(
        position_error_norm[-1]
    )

    drift_percentage = (
        100.0
        * final_error
        / total_distance
        if total_distance > 0
        else float("nan")
    )

    ins_speed_kmh = (
        np.linalg.norm(
            ins.velocity_enu[:, :2],
            axis=1,
        )
        * 3.6
    )

    speed_error = (
        ins_speed_kmh
        - reference.velocity_kmh
    )

    return {
        "samples": float(
            len(position_error_norm)
        ),
        "duration_s": float(
            (len(position_error_norm) - 1)
            * NOMINAL_DT
        ),
        "total_distance_m": total_distance,
        "final_error_m": final_error,
        "mean_error_m": float(
            np.mean(
                position_error_norm
            )
        ),
        "median_error_m": float(
            np.median(
                position_error_norm
            )
        ),
        "rmse_error_m": float(
            np.sqrt(
                np.mean(
                    position_error_norm ** 2
                )
            )
        ),
        "max_error_m": float(
            np.max(
                position_error_norm
            )
        ),
        "drift_percentage": drift_percentage,
        "velocity_rmse_kmh": float(
            np.sqrt(
                np.mean(
                    speed_error ** 2
                )
            )
        ),
        "velocity_mae_kmh": float(
            np.mean(
                np.abs(speed_error)
            )
        ),
    }


# ============================================================
# Plot
# ============================================================

def plot_trajectory(
    ins: INSResult,
    reference: ReferenceData,
) -> None:

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    PLOT_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    plt.figure(
        figsize=(10, 8)
    )

    plt.plot(
        reference.position_enu[:, 0],
        reference.position_enu[:, 1],
        label="VBOX reference",
        linewidth=2.0,
    )

    plt.plot(
        ins.position_enu[:, 0],
        ins.position_enu[:, 1],
        label="Raw INS",
        linewidth=1.2,
    )

    plt.scatter(
        [0.0],
        [0.0],
        label="Start",
        s=50,
    )

    plt.scatter(
        reference.position_enu[-1, 0],
        reference.position_enu[-1, 1],
        marker="x",
        s=60,
        label="VBOX end",
    )

    plt.scatter(
        ins.position_enu[-1, 0],
        ins.position_enu[-1, 1],
        marker="x",
        s=60,
        label="INS end",
    )

    plt.xlabel(
        "East (m)"
    )

    plt.ylabel(
        "North (m)"
    )

    plt.title(
        "IDR Phase 1 — Raw INS vs VBOX\n"
        "IO-VNBD M (Driver B)"
    )

    plt.axis("equal")
    plt.grid(True, alpha=0.3)
    plt.legend()

    plt.tight_layout()

    plt.savefig(
        PLOT_FILE,
        dpi=180,
    )

    plt.close()


# ============================================================
# Metrics report
# ============================================================

def write_metrics_report(
    metrics: dict[str, float],
    smartphone: SmartphoneData,
    gyro_bias: np.ndarray,
    bias_samples: int,
    initial_heading_deg: float,
) -> None:

    REPORT_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    gap_lines = []

    for row_index, gap in (
        smartphone.date_gaps
    ):
        gap_lines.append(
            f"- After row {row_index}: "
            f"{gap:.3f} s"
        )

    if not gap_lines:
        gap_lines.append(
            "- None"
        )

    lines = [
        "# Phase 1 — Raw INS Baseline",
        "",
        "## Sequence",
        "",
        "| Item | Value |",
        "|---|---|",
        "| Sequence | M (Driver B) |",
        f"| Samples | {int(metrics['samples']):,} |",
        f"| Duration | {metrics['duration_s']:.3f} s |",
        "| Nominal sampling rate | 10 Hz |",
        "",
        "## Timestamp diagnostics",
        "",
        "The smartphone DATE field was retained for diagnostic "
        "purposes. Mechanization uses the synchronized nominal "
        "10-Hz row clock because the two DATE gaps account exactly "
        "for the 2.323 s duration discrepancy with the VBOX stream.",
        "",
        "Detected DATE gaps:",
        "",
        *gap_lines,
        "",
        "## Initialization",
        "",
        f"- Initial GPS heading: {initial_heading_deg:.3f} deg",
        f"- Initial velocity: VBOX row-0 speed/heading "
        f"(evaluation baseline only)",
        f"- Gyro bias samples: {bias_samples:,}",
        (
            "- Estimated gyro bias: "
            f"[{gyro_bias[0]:+.6f}, "
            f"{gyro_bias[1]:+.6f}, "
            f"{gyro_bias[2]:+.6f}] rad/s"
        ),
        "",
        "## Metrics",
        "",
        "| Metric | Value |",
        "|---|---:|",
        f"| Total reference distance | {metrics['total_distance_m']:.3f} m |",
        f"| Final position error | {metrics['final_error_m']:.3f} m |",
        f"| Mean position error | {metrics['mean_error_m']:.3f} m |",
        f"| Median position error | {metrics['median_error_m']:.3f} m |",
        f"| Position RMSE | {metrics['rmse_error_m']:.3f} m |",
        f"| Maximum position error | {metrics['max_error_m']:.3f} m |",
        f"| Drift / distance | {metrics['drift_percentage']:.4f}% |",
        f"| Velocity RMSE | {metrics['velocity_rmse_kmh']:.3f} km/h |",
        f"| Velocity MAE | {metrics['velocity_mae_kmh']:.3f} km/h |",
        "",
        "## Propagation policy",
        "",
        "- Accelerometer and gravity are processed in phone body coordinates.",
        "- Linear acceleration = accelerometer - supplied gravity.",
        "- Raw gyroscope measurements are integrated using 3-D rotations.",
        "- Linear acceleration is rotated into local ENU coordinates.",
        "- Velocity is integrated from acceleration.",
        "- Position is integrated from velocity.",
        "- No EKF.",
        "- No AI.",
        "- No map matching.",
        "- No GNSS measurement updates.",
        "- GPS is used only for initial heading/reference comparison.",
        "",
        "## Interpretation",
        "",
        "This is the classical pre-AI dead-reckoning baseline. "
        "Significant drift is expected. Phase 2 should be evaluated "
        "by measuring whether AI-derived corrections reduce this drift.",
        "",
    ]

    METRICS_FILE.write_text(
        "\n".join(lines),
        encoding="utf-8",
    )


# ============================================================
# Main
# ============================================================

def main() -> None:

    parser = argparse.ArgumentParser(
        description=(
            "Run the IDR Phase 1 classical INS baseline "
            "on IO-VNBD sequence M."
        )
    )

    parser.add_argument(
        "--duration",
        type=float,
        default=None,
        help=(
            "Optional test duration in seconds. "
            "Example: --duration 60"
        ),
    )

    add_dataset_argument(parser)
    parser.add_argument("--no-write", action="store_true", help="Print results without writing reports or plots.")
    parser.add_argument("--offline-baseline", action="store_true", help="Explicitly allow historical future-GPS/VBOX-assisted initialization; never real-time calibration.")
    args = parser.parse_args()
    if not args.offline_baseline:
        parser.error("Initialization is not causal and full gyro axes are unresolved. Use training.frame_audit; --offline-baseline permits historical replay only.")
    root, s_file, v_file = m_pair_from_args(parser, args)

    print("=" * 80)
    print(
        "IDR PHASE 1 — CLASSICAL INS BASELINE"
    )
    print("=" * 80)

    # --------------------------------------------------------
    # Load raw files
    # --------------------------------------------------------

    smartphone_df, reference_df = (
        load_raw_files(root)
    )

    print(
        f"\nLoaded synchronized pair:"
        f"\n  S-M.csv: {len(smartphone_df):,} rows"
        f"\n  V-M.csv: {len(reference_df):,} rows"
    )

    # --------------------------------------------------------
    # Load smartphone
    # --------------------------------------------------------

    smartphone = load_smartphone(
        smartphone_df
    )

    # --------------------------------------------------------
    # Optional short-run test
    # --------------------------------------------------------

    if args.duration is not None:

        max_samples = int(
            np.floor(
                args.duration
                / NOMINAL_DT
            )
        ) + 1

        max_samples = min(
            max_samples,
            len(smartphone_df),
        )

        smartphone_df = (
            smartphone_df.iloc[
                :max_samples
            ]
            .reset_index(drop=True)
        )

        reference_df = (
            reference_df.iloc[
                :max_samples
            ]
            .reset_index(drop=True)
        )

        smartphone = load_smartphone(
            smartphone_df
        )

        print(
            f"\nShort test selected:"
            f" {len(smartphone_df):,} samples"
        )

    # --------------------------------------------------------
    # Diagnostic timing report
    # --------------------------------------------------------

    print("\nTIMING")
    print("-" * 80)

    print(
        f"DATE start: "
        f"{smartphone.timestamps.iloc[0]}"
    )

    print(
        f"DATE end:   "
        f"{smartphone.timestamps.iloc[-1]}"
    )

    print(
        f"Nominal duration: "
        f"{smartphone.t_nominal[-1]:.3f} s"
    )

    print(
        f"DATE gaps detected: "
        f"{len(smartphone.date_gaps)}"
    )

    for row_index, gap in smartphone.date_gaps:

        print(
            f"  after row {row_index}: "
            f"{gap:.3f} s"
        )

    # --------------------------------------------------------
    # Initial heading
    # --------------------------------------------------------

    initial_heading_deg = (
        estimate_initial_heading(
            smartphone.gps_lat,
            smartphone.gps_lon,
            allow_future=True,
        )
    )

    print("\nINITIAL ALIGNMENT")
    print("-" * 80)

    print(
        f"Initial GPS heading: "
        f"{initial_heading_deg:.3f} deg"
    )

    # --------------------------------------------------------
    # Initial gravity
    # --------------------------------------------------------

    initial_window_samples = min(
    int(INITIAL_ATTITUDE_SECONDS * NOMINAL_HZ),
    len(smartphone.gravity),
    )

    gravity_initial = np.mean(
        smartphone.gravity[
            :initial_window_samples
        ],
        axis=0,
    )

    print(
        f"Mean initial gravity vector: "
        f"{gravity_initial}"
    )

    print(
        f"Gravity magnitude: "
        f"{np.linalg.norm(gravity_initial):.6f} m/s²"
    )

    # --------------------------------------------------------
    # Initial rotation
    # --------------------------------------------------------

    rotation0 = build_initial_rotation(
        gravity_initial,
        initial_heading_deg,
    )

    # --------------------------------------------------------
    # Initial velocity
    #
    # Explicitly use VBOX row 0 to isolate inertial drift from
    # initial velocity estimation error.
    # --------------------------------------------------------

    reference = load_reference(
        reference_df,
        smartphone.gps_lat[0],
        smartphone.gps_lon[0],
    )

    reference_initial_speed = (
        reference.velocity_kmh[0]
        / 3.6
    )

    reference_initial_heading = np.radians(
        reference.heading_deg[0]
    )

    initial_velocity = np.array(
        [
            reference_initial_speed
            * np.sin(
                reference_initial_heading
            ),
            reference_initial_speed
            * np.cos(
                reference_initial_heading
            ),
            0.0,
        ]
    )

    print(
        f"Initial VBOX velocity: "
        f"{reference_initial_speed:.6f} m/s"
    )

    print(
        f"Initial VBOX heading: "
        f"{reference.heading_deg[0]:.3f} deg"
    )

    # --------------------------------------------------------
    # Gyro bias
    # --------------------------------------------------------

    gyro_bias, bias_samples = (
        estimate_gyro_bias(
            smartphone.acceleration,
            smartphone.gravity,
            smartphone.gyro_body,
            smartphone.t_nominal,
        )
    )

    print("\nGYRO BIAS")
    print("-" * 80)

    print(
        f"Stationary samples: "
        f"{bias_samples:,}"
    )

    print(
        "Bias [rad/s]: "
        f"{gyro_bias}"
    )

    # --------------------------------------------------------
    # Run INS
    # --------------------------------------------------------

    print("\nRUNNING INS")
    print("-" * 80)

    ins = run_ins(
        smartphone=smartphone,
        initial_rotation=rotation0,
        initial_velocity_enu=initial_velocity,
        gyro_bias=gyro_bias,
    )

    # --------------------------------------------------------
    # Metrics
    # --------------------------------------------------------

    metrics = compute_metrics(
        ins,
        reference,
    )

    print("\nMETRICS")
    print("-" * 80)

    for key, value in metrics.items():

        if key == "samples":
            print(
                f"{key}: {int(value):,}"
            )
        else:
            print(
                f"{key}: {value:.6f}"
            )

    # --------------------------------------------------------
    # Plot
    # --------------------------------------------------------

    if not args.no_write:
        plot_trajectory(
            ins,
            reference,
        )

        print(
            f"\nPlot saved to:\n{PLOT_FILE}"
        )

        # --------------------------------------------------------
        # Report
        # --------------------------------------------------------

        write_metrics_report(
            metrics,
            smartphone,
            gyro_bias,
            bias_samples,
            initial_heading_deg,
        )

        print(
            f"Metrics saved to:\n{METRICS_FILE}"
        )

    print("\n" + "=" * 80)
    print(
        "PHASE 1 BASELINE COMPLETE"
    )
    print("=" * 80)


if __name__ == "__main__":
    main()
