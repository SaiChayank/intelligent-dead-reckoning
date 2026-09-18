from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import pandas as pd

if __package__:
    from .common import add_dataset_argument, m_pair_from_args, sequence_paths, detect_encoding, safe_numeric
else:
    from common import add_dataset_argument, m_pair_from_args, sequence_paths, detect_encoding, safe_numeric


# =============================================================================
# CONFIG
# =============================================================================

DEFAULT_DURATION_S = 60.0
NOMINAL_HZ = 10.0
NOMINAL_DT_S = 1.0 / NOMINAL_HZ
ATTITUDE_AVG_SECONDS = 1.0
LAG_SCAN_SAMPLES = 20                 # +/- 2.0 s at 10 Hz
MIN_VALID_SAMPLES = 100
MIN_HEADING_DISPLACEMENT_M = 5.0
EARTH_RADIUS_M = 6_371_000.0

GYRO_KEYWORDS = {
    "X": ("gyroscope", "roll"),
    "Y": ("gyroscope", "pitch"),
    "Z": ("gyroscope", "yaw"),
}


# =============================================================================
# HELPERS
# =============================================================================

def find_exact(df: pd.DataFrame, name: str) -> str:
    target = name.strip().lower()
    matches = [
        col for col in df.columns
        if str(col).strip().lower() == target
    ]
    if len(matches) == 1:
        return matches[0]
    raise KeyError(
        f"Exact column not found: {name!r}\nAvailable columns:\n"
        + "\n".join(f"  {c!r}" for c in df.columns)
    )


def find_unique(df: pd.DataFrame, *keywords: str) -> str:
    keys = [k.strip().lower() for k in keywords]
    matches = [
        col for col in df.columns
        if all(k in str(col).strip().lower() for k in keys)
    ]
    if len(matches) != 1:
        raise KeyError(
            f"Expected exactly one column matching {keywords}, found {len(matches)}:\n"
            + "\n".join(f"  {c!r}" for c in matches)
        )
    return matches[0]


def resolve_gyro_columns(df: pd.DataFrame) -> dict[str, str]:
    """Keys are source labels, not physical body axes."""
    return {label: find_unique(df, "gyroscope", label.lower()) for label in ("Roll", "Pitch", "Yaw")}


def numeric(df: pd.DataFrame, col: str) -> np.ndarray:
    return safe_numeric(df[col])


def corr(a: np.ndarray, b: np.ndarray) -> float:
    mask = np.isfinite(a) & np.isfinite(b)
    if mask.sum() < MIN_VALID_SAMPLES:
        return float("nan")

    aa = a[mask]
    bb = b[mask]
    if np.std(aa) < 1e-12 or np.std(bb) < 1e-12:
        return float("nan")

    return float(np.corrcoef(aa, bb)[0, 1])


def regression_slope(x: np.ndarray, y: np.ndarray) -> float:
    """Least-squares slope y ~= slope*x, with intercept allowed."""
    mask = np.isfinite(x) & np.isfinite(y)
    if mask.sum() < MIN_VALID_SAMPLES:
        return float("nan")

    xx = x[mask]
    yy = y[mask]
    if np.std(xx) < 1e-12:
        return float("nan")

    return float(np.polyfit(xx, yy, 1)[0])


def wrap_deg(angle: np.ndarray | float) -> np.ndarray | float:
    return (np.asarray(angle) + 180.0) % 360.0 - 180.0


def angle_difference_deg(a: float, b: float) -> float:
    return float(abs(wrap_deg(a - b)))


def rodrigues(w: np.ndarray, dt: float) -> np.ndarray:
    """SO(3) exponential map for a body-frame angular increment."""
    w = np.asarray(w, dtype=float)
    theta = float(np.linalg.norm(w) * dt)

    K = np.array([
        [0.0, -w[2], w[1]],
        [w[2], 0.0, -w[0]],
        [-w[1], w[0], 0.0],
    ])

    if theta < 1e-10:
        return np.eye(3) + K * dt

    axis = w / np.linalg.norm(w)
    K_axis = np.array([
        [0.0, -axis[2], axis[1]],
        [axis[2], 0.0, -axis[0]],
        [-axis[1], axis[0], 0.0],
    ])

    return (
        np.eye(3)
        + np.sin(theta) * K_axis
        + (1.0 - np.cos(theta)) * (K_axis @ K_axis)
    )


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    lat1 = np.radians(lat1)
    lon1 = np.radians(lon1)
    lat2 = np.radians(lat2)
    lon2 = np.radians(lon2)

    dlat = lat2 - lat1
    dlon = lon2 - lon1

    a = (
        np.sin(dlat / 2.0) ** 2
        + np.cos(lat1) * np.cos(lat2) * np.sin(dlon / 2.0) ** 2
    )
    a = np.clip(a, 0.0, 1.0)
    return float(2.0 * EARTH_RADIUS_M * np.arcsin(np.sqrt(a)))


def bearing_deg(lat1, lon1, lat2, lon2) -> float:
    lat1r = np.radians(lat1)
    lat2r = np.radians(lat2)
    dlon = np.radians(lon2 - lon1)

    y = np.sin(dlon) * np.cos(lat2r)
    x = (
        np.cos(lat1r) * np.sin(lat2r)
        - np.sin(lat1r) * np.cos(lat2r) * np.cos(dlon)
    )
    return float(np.degrees(np.arctan2(y, x)) % 360.0)


def parse_timestamp_series(s: pd.DataFrame) -> np.ndarray:
    date_col = find_exact(s, "DATE (YYYY-MO-DD HH-MI-SS_SSS)")
    ts = pd.to_datetime(
        s[date_col].astype(str).str.strip(),
        format="%Y-%m-%d %H:%M:%S:%f",
        errors="coerce",
    )
    if ts.isna().any():
        raise ValueError(
            f"{int(ts.isna().sum())} smartphone DATE values failed to parse."
        )
    return (ts - ts.iloc[0]).dt.total_seconds().to_numpy(dtype=float)


def triad_candidates(gravity_mean: np.ndarray, magnetic_mean: np.ndarray) -> dict[str, np.ndarray]:
    """
    Construct both physically relevant gravity-sign TRIAD candidates.

    Navigation frame is ENU: +X=East, +Y=North, +Z=Up.
    The Android gravity sensor normally reports the direction of gravity,
    so both +g and -g are tested rather than silently assuming a sign.

    Magnetic vector is treated as a north reference. For an Up vector u and
    magnetic/north vector m, East is m x u, then North is u x East.
    """
    g = np.asarray(gravity_mean, dtype=float)
    m = np.asarray(magnetic_mean, dtype=float)

    g_norm = np.linalg.norm(g)
    m_norm = np.linalg.norm(m)
    if g_norm < 1e-9 or m_norm < 1e-9:
        raise ValueError("Gravity or magnetic vector is too small for TRIAD.")

    m = m / m_norm
    candidates = {}

    for label, u in (
        ("+gravity-as-UP", g / g_norm),
        ("-gravity-as-UP", -g / g_norm),
    ):
        east = np.cross(m, u)
        east_norm = np.linalg.norm(east)
        if east_norm < 1e-9:
            continue
        east /= east_norm

        north = np.cross(u, east)
        north /= np.linalg.norm(north)

        # Columns are phone basis vectors expressed in ENU.
        # Therefore C maps phone vectors to ENU vectors.
        C = np.column_stack((east, north, u))
        candidates[label] = C

    return candidates


def axis_azimuth_elevation(vec: np.ndarray) -> tuple[float, float]:
    az = float(np.degrees(np.arctan2(vec[0], vec[1])) % 360.0)
    el = float(np.degrees(np.arcsin(np.clip(vec[2], -1.0, 1.0))))
    return az, el


def euler_zyx_matrix(yaw_deg: float, pitch_deg: float, roll_deg: float) -> np.ndarray:
    z, y, x = np.radians([yaw_deg, pitch_deg, roll_deg])
    cz, sz = np.cos(z), np.sin(z)
    cy, sy = np.cos(y), np.sin(y)
    cx, sx = np.cos(x), np.sin(x)

    Rz = np.array([[cz, -sz, 0.0], [sz, cz, 0.0], [0.0, 0.0, 1.0]])
    Ry = np.array([[cy, 0.0, sy], [0.0, 1.0, 0.0], [-sy, 0.0, cy]])
    Rx = np.array([[1.0, 0.0, 0.0], [0.0, cx, -sx], [0.0, sx, cx]])
    return Rz @ Ry @ Rx


def rotation_angle_deg(R: np.ndarray) -> float:
    c = np.clip((np.trace(R) - 1.0) / 2.0, -1.0, 1.0)
    return float(np.degrees(np.arccos(c)))


# =============================================================================
# CHECK 0 — GRAVITY CANCELLATION
# =============================================================================

def check_gravity_cancellation(accel: np.ndarray, gravity: np.ndarray) -> None:
    print("\n" + "=" * 100)
    print("CHECK 0 — ACCELEROMETER / GRAVITY CANCELLATION")
    print("=" * 100)

    residual = accel - gravity
    residual_plus = accel + gravity

    mag_minus = np.linalg.norm(residual, axis=1)
    mag_plus = np.linalg.norm(residual_plus, axis=1)

    print("\n|accel - gravity|:")
    print(f"  mean   : {np.nanmean(mag_minus):.4f} m/s^2")
    print(f"  median : {np.nanmedian(mag_minus):.4f} m/s^2")
    print(f"  min    : {np.nanmin(mag_minus):.4f} m/s^2")
    print(f"  max    : {np.nanmax(mag_minus):.4f} m/s^2")

    print("\n|accel + gravity|:")
    print(f"  mean   : {np.nanmean(mag_plus):.4f} m/s^2")
    print(f"  median : {np.nanmedian(mag_plus):.4f} m/s^2")
    print(f"  min    : {np.nanmin(mag_plus):.4f} m/s^2")
    print(f"  max    : {np.nanmax(mag_plus):.4f} m/s^2")

    mean_minus = float(np.nanmean(mag_minus))
    mean_plus = float(np.nanmean(mag_plus))

    print("\nVERDICT:")
    if mean_minus < mean_plus:
        print("  accel - gravity is the smaller residual -> current sign is supported.")
    elif mean_plus < mean_minus:
        print("  accel + gravity is the smaller residual -> gravity sign is likely flipped.")
    else:
        print("  Neither gravity sign clearly wins.")

    if mean_minus > 6.0 and mean_plus > 6.0:
        print("  *** SUSPECT: neither subtraction nor addition cancels gravity well.")
        print("      Check row alignment, sensor definitions, units, and dataset columns.")
    elif mean_minus > 6.0:
        print("  *** SUSPECT: accel - gravity remains very large.")
    else:
        print("  The residual magnitude is not, by itself, evidence of a gravity failure.")


# =============================================================================
# CHECK 1 — TRIAD VALIDITY AND GRAVITY SIGN
# =============================================================================

def check_triad(candidates: dict[str, np.ndarray], gravity_mean: np.ndarray, magnetic_mean: np.ndarray) -> None:
    print("\n" + "=" * 100)
    print("CHECK 1 — INITIAL TRIAD ROTATION VALIDITY")
    print("=" * 100)
    print(f"Mean gravity vector : {gravity_mean}")
    print(f"Mean magnetic vector: {magnetic_mean}")

    for name, C in candidates.items():
        det = float(np.linalg.det(C))
        ortho = float(np.max(np.abs(C @ C.T - np.eye(3))))
        print(f"\n{name}")
        print(f"  det(C)                  = {det:+.9f}")
        print(f"  max |C C^T - I|        = {ortho:.3e}")

        labels = ["phone +X (right)", "phone +Y (top)", "phone +Z (screen)"]
        for j, label in enumerate(labels):
            az, el = axis_azimuth_elevation(C[:, j])
            print(
                f"  {label:20s}: az={az:7.2f} deg, el={el:+7.2f} deg "
                f"(E={C[0,j]:+.3f}, N={C[1,j]:+.3f}, U={C[2,j]:+.3f})"
            )

        if abs(det - 1.0) < 1e-3 and ortho < 1e-3:
            print("  VALID proper rotation.")
        else:
            print("  *** SUSPECT: not a valid proper rotation.")


# =============================================================================
# CHECK 2 — GYRO CHANNEL VS VBOX YAW RATE
# =============================================================================

def check_gyro_vs_vbox(s: pd.DataFrame, v: pd.DataFrame, n: int, gyro_columns: dict[str, str]) -> None:
    if __package__:
        from .frame_math import gyro_candidates
    else:
        from frame_math import gyro_candidates
    yaw = np.radians(numeric(v, find_exact(v, "Yaw Rate (deg/sec)"))[:n])
    channels = {label: numeric(s, col)[:n] for label, col in gyro_columns.items()}
    for row in gyro_candidates(channels, yaw):
        print(row)
    print("Source labels are not XYZ. Lag results are diagnostic only; no lag is applied.")


# =============================================================================
# CHECK 3 — GYRO CHANNELS VS RECORDED ORIENTATION DERIVATIVES
# =============================================================================

def check_gyro_vs_orientation(t: np.ndarray, s: pd.DataFrame, gyro_columns: dict[str, str]) -> None:
    print("\n" + "=" * 100)
    print("CHECK 3 — GYRO CHANNELS vs RECORDED ORIENTATION RATES")
    print("=" * 100)
    print("This is diagnostic only. Recorded ORIENTATION is never fed into the INS.")
    print("Because Euler-angle rates are not identical to body rates, treat this as")
    print("an axis/sign consistency check, not as a final mechanization mapping.")

    names = ["Yaw", "Pitch", "Roll"]
    cols = [
        find_unique(s, "orientation", "yaw"),
        find_unique(s, "orientation", "pitch"),
        find_unique(s, "orientation", "roll"),
    ]

    recorded = np.column_stack([numeric(s, c) for c in cols])
    recorded_unwrapped = np.degrees(np.unwrap(np.radians(recorded), axis=0))

    rates = np.zeros_like(recorded_unwrapped)
    for j in range(3):
        rates[:, j] = np.gradient(recorded_unwrapped[:, j], t)

    for target_idx, target_name in enumerate(names):
        results = []
        print(f"\n  Recorded {target_name} rate:")
        for gyro_name, gyro_col in gyro_columns.items():
            gyro_deg_s = np.degrees(numeric(s, gyro_col))
            best = None

            for lag in range(-LAG_SCAN_SAMPLES, LAG_SCAN_SAMPLES + 1):
                if lag >= 0:
                    a = gyro_deg_s[lag:]
                    b = rates[: len(rates) - lag, target_idx]
                else:
                    a = gyro_deg_s[: len(gyro_deg_s) + lag]
                    b = rates[-lag:, target_idx]

                m = min(len(a), len(b))
                if m < MIN_VALID_SAMPLES:
                    continue

                a = a[:m]
                b = b[:m]
                c = corr(a, b)
                if not np.isfinite(c):
                    continue

                if best is None or abs(c) > abs(best[1]):
                    best = (lag, c, regression_slope(b, a))

            if best is None:
                continue

            lag, c, slope = best
            results.append((gyro_name, lag, c, slope))
            print(
                f"    gyro {gyro_name}: corr={c:+.4f}, slope={slope:+.4f}, "
                f"lag={lag:+d} ({lag * NOMINAL_DT_S:+.2f}s)"
            )

        if results:
            best = max(results, key=lambda x: abs(x[2]))
            print(
                f"    BEST: gyro {best[0]} -> recorded {target_name}; "
                f"corr={best[2]:+.4f}, slope={best[3]:+.4f}"
            )


# =============================================================================
# CHECK 4 — TRIAD HEADING VS GPS / DEVICE HEADING
# =============================================================================

def check_headings(
    s: pd.DataFrame,
    v: pd.DataFrame,
    triads: dict[str, np.ndarray],
) -> None:
    print("\n" + "=" * 100)
    print("CHECK 4 — INITIAL HEADING CROSS-CHECK")
    print("=" * 100)

    lat_col = find_exact(s, "GPS LATITUDE (degrees)")
    lon_col = find_exact(s, "GPS LONGITUDE (degrees)")
    lat = numeric(s, lat_col)
    lon = numeric(s, lon_col)

    v_head_col = find_exact(v, "Heading (degrees)")
    v_heading = numeric(v, v_head_col)

    orient_yaw_col = find_unique(s, "orientation", "yaw")
    device_yaw = numeric(s, orient_yaw_col)

    gps_heading = None
    for i in range(1, len(lat)):
        if not (
            np.isfinite(lat[0])
            and np.isfinite(lon[0])
            and np.isfinite(lat[i])
            and np.isfinite(lon[i])
        ):
            continue
        if haversine_m(lat[0], lon[0], lat[i], lon[i]) >= MIN_HEADING_DISPLACEMENT_M:
            gps_heading = bearing_deg(lat[0], lon[0], lat[i], lon[i])
            break

    print(f"VBOX heading at t0       : {v_heading[0]:.3f} deg")
    print(f"Device ORIENTATION yaw   : {device_yaw[0]:.3f} deg")
    if gps_heading is not None:
        print(f"Smartphone GPS bearing   : {gps_heading:.3f} deg")
    else:
        print("Smartphone GPS bearing   : unavailable within displacement threshold")

    for name, C in triads.items():
        print(f"\n{name}:")
        for j, axis in enumerate(("+X", "+Y", "+Z")):
            az, el = axis_azimuth_elevation(C[:, j])
            print(f"  phone {axis} azimuth={az:.3f} deg, elevation={el:+.3f} deg")

        # Both horizontal phone axes are reported because the mounting may not
        # have phone +Y pointing along the vehicle forward direction.
        x_az = axis_azimuth_elevation(C[:, 0])[0]
        y_az = axis_azimuth_elevation(C[:, 1])[0]
        print(f"  |phone +X az - VBOX| = {angle_difference_deg(x_az, v_heading[0]):.3f} deg")
        print(f"  |phone +Y az - VBOX| = {angle_difference_deg(y_az, v_heading[0]):.3f} deg")
        print(f"  |device yaw - VBOX|   = {angle_difference_deg(device_yaw[0], v_heading[0]):.3f} deg")
        if gps_heading is not None:
            print(f"  |GPS bearing - VBOX|  = {angle_difference_deg(gps_heading, v_heading[0]):.3f} deg")
            print(f"  |phone +X az - GPS|   = {angle_difference_deg(x_az, gps_heading):.3f} deg")
            print(f"  |phone +Y az - GPS|   = {angle_difference_deg(y_az, gps_heading):.3f} deg")


# =============================================================================
# CHECK 5 — SHORT GYRO PROPAGATION INTERNAL CONSISTENCY
# =============================================================================

def check_relative_gyro_propagation(t: np.ndarray, s: pd.DataFrame, gyro_columns: dict[str, str]) -> None:
    raise ValueError("Full gyro XYZ mapping is unresolved; do not propagate Roll/Pitch/Yaw labels as a body vector.")


# =============================================================================
# MAIN
# =============================================================================

def legacy_main() -> None:
    raise RuntimeError("Historical diagnostic retired; use main() or training.frame_audit for validated methodology.")
    parser = argparse.ArgumentParser(
        description="Read-only IDR Phase 1 attitude/gyro diagnostic."
    )
    parser.add_argument(
        "--duration",
        type=float,
        default=DEFAULT_DURATION_S,
        help="Seconds of the synchronized M (Driver B) pair to inspect (default: 60).",
    )
    add_dataset_argument(parser)
    args = parser.parse_args()
    root, s_file, v_file = m_pair_from_args(parser, args)

    if args.duration <= 0:
        raise ValueError("--duration must be greater than zero.")


    print("=" * 100)
    print("IDR PHASE 1 — FINAL ATTITUDE / GYRO DIAGNOSTIC")
    print("READ-ONLY: no dataset, model, report, or mechanization files are modified")
    print("=" * 100)
    print(f"\nDataset root: {root}")
    print(f"Smartphone : {s_file}")
    print(f"VBOX       : {v_file}")

    s_full = pd.read_csv(s_file, encoding=detect_encoding(s_file))
    v_full = pd.read_csv(v_file, encoding=detect_encoding(v_file))

    # Resolve gyro headers from the actual CSV instead of assuming the exact
    # spelling/spacing of the column names. This also prevents a hard-coded
    # header mismatch from stopping the diagnostic before Check 2.
    gyro_columns = resolve_gyro_columns(s_full)
    print("\nResolved gyro columns:")
    for axis, col in gyro_columns.items():
        print(f"  {axis}: {col!r}")

    t_full = parse_timestamp_series(s_full)
    n = min(
        len(s_full),
        len(v_full),
        int(np.floor(args.duration / NOMINAL_DT_S)) + 1,
    )

    s = s_full.iloc[:n].reset_index(drop=True)
    v = v_full.iloc[:n].reset_index(drop=True)
    t = t_full[:n]

    print(f"\nRows loaded: smartphone={len(s_full):,}, VBOX={len(v_full):,}")
    print(f"Diagnostic window: {len(s):,} samples, {t[-1]:.6f} s by smartphone DATE")

    # -------------------------------------------------------------------------
    # Resolve all required columns explicitly. Ambiguous matches fail loudly.
    # -------------------------------------------------------------------------
    accel_cols = [
        find_exact(s, "ACCELEROMETER X (m/s²)"),
        find_exact(s, "ACCELEROMETER Y (m/s²)"),
        find_exact(s, "ACCELEROMETER Z (m/s²)"),
    ]
    gravity_cols = [
        find_exact(s, "GRAVITY X (m/s²)"),
        find_exact(s, "GRAVITY Y (m/s²)"),
        find_exact(s, "GRAVITY Z (m/s²)"),
    ]
    magnetic_cols = [
        find_unique(s, "magnetic", "x"),
        find_unique(s, "magnetic", "y"),
        find_unique(s, "magnetic", "z"),
    ]

    accel = np.column_stack([numeric(s, c) for c in accel_cols])
    gravity = np.column_stack([numeric(s, c) for c in gravity_cols])
    magnetic = np.column_stack([numeric(s, c) for c in magnetic_cols])

    # -------------------------------------------------------------------------
    # Timing sanity
    # -------------------------------------------------------------------------
    dt = np.diff(t)
    print("\n" + "=" * 100)
    print("TIMING SANITY")
    print("=" * 100)
    print(f"Mean DATE interval   : {np.nanmean(dt):.6f} s")
    print(f"Median DATE interval : {np.nanmedian(dt):.6f} s")
    print(f"Min DATE interval    : {np.nanmin(dt):.6f} s")
    print(f"Max DATE interval    : {np.nanmax(dt):.6f} s")
    bad = np.where((dt <= 0.0) | (dt > 0.25))[0]
    print(f"Intervals <=0 or >0.25 s: {len(bad)}")
    for i in bad[:20]:
        print(f"  after row {i}: {dt[i]:.6f} s")
    if len(bad) > 20:
        print(f"  ... {len(bad) - 20} more")

    # -------------------------------------------------------------------------
    # Initial averages
    # -------------------------------------------------------------------------
    n_avg = max(1, min(len(s), int(round(ATTITUDE_AVG_SECONDS / NOMINAL_DT_S))))
    gravity_mean = np.nanmean(gravity[:n_avg], axis=0)
    magnetic_mean = np.nanmean(magnetic[:n_avg], axis=0)

    check_gravity_cancellation(accel, gravity)

    triads = triad_candidates(gravity_mean, magnetic_mean)
    check_triad(triads, gravity_mean, magnetic_mean)

    check_gyro_vs_vbox(s, v, n, gyro_columns)
    check_gyro_vs_orientation(t, s, gyro_columns)
    check_headings(s, v, triads)
    check_relative_gyro_propagation(t, s, gyro_columns)

    print("\n" + "=" * 100)
    print("DIAGNOSTIC COMPLETE")
    print("=" * 100)
    print("No dataset files were modified.")
    print("Do NOT change ins_mechanization.py until these results are reviewed.")


def main() -> None:
    # Keep direct-file invocation compatible while sharing one diagnostic method.
    if not __package__:
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from training.frame_audit import main as validated_main
    validated_main(default_sequences=["m"])



if __name__ == "__main__":
    main()
 
